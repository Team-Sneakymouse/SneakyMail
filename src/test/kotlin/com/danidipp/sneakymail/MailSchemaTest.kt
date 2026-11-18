package com.danidipp.sneakymail

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.postgresql.PGConnection
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * db/migrations/V001__mail.sql and the plugin's statements against a real PostgreSQL. Run with
 * `./gradlew postgresTest` and SNEAKYMAIL_TEST_PG_URL pointing at a throwaway database (see README).
 */
@Tag("postgres")
class MailSchemaTest {
    private val url = System.getenv("SNEAKYMAIL_TEST_PG_URL") ?: error("SNEAKYMAIL_TEST_PG_URL not set")
    private val db = JdbcSneakyDb(url)
    private val store = MailStore(db, Logger.getAnonymousLogger())
    private val alice = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val bob = UUID.fromString("22222222-2222-4222-8222-222222222222")
    private val command = """[{"type":"command","command":"say {player}"}]"""

    @BeforeEach
    fun setUp() = TestSchema.reset(url)

    @AfterEach
    fun tearDown() = db.close()

    private fun connect(): Connection = DriverManager.getConnection(url)

    private fun <T> sql(query: String, vararg params: Any?, read: (java.sql.ResultSet) -> T): T = connect().use { c ->
        c.prepareStatement(query).use { s ->
            params.forEachIndexed { i, p -> s.setObject(i + 1, p) }
            s.executeQuery().use { r -> r.next(); read(r) }
        }
    }

    private fun exec(statement: String, vararg params: Any?): Int = connect().use { c ->
        c.prepareStatement(statement).use { s ->
            params.forEachIndexed { i, p -> s.setObject(i + 1, p) }
            s.executeUpdate()
        }
    }

    private fun valid(rewards: String): Boolean? = sql("select sneakymail.rewards_valid(?::jsonb)", rewards) { r -> r.getObject(1) as Boolean? }

    private fun send(key: String?, recipient: UUID? = alice, sender: String? = "Sender", note: String? = "Hi", rewards: String? = command,
                     expires: OffsetDateTime? = null, connection: Connection? = null): Long {
        val run = { c: Connection ->
            c.prepareStatement("select sneakymail.send(?, ?, ?, ?, ?::jsonb, ?)").use { s ->
                listOf(key, recipient, sender, note, rewards, expires).forEachIndexed { i, p -> s.setObject(i + 1, p) }
                s.executeQuery().use { r -> r.next(); r.getLong(1) }
            }
        }
        return if (connection != null) run(connection) else connect().use(run)
    }

    private fun sqlState(block: () -> Unit): String? = try {
        block()
        null
    } catch (error: SQLException) {
        error.sqlState
    }

    private fun count(where: String = "true"): Long = sql("select count(*) from sneakymail.mail where $where") { it.getLong(1) }

    @Test
    fun `rewards_valid accepts the two reward shapes`() {
        for (ok in listOf(
            "[]",
            command,
            """[{"type":"item","item":"item-EXP","amount":1}]""",
            """[{"amount":2304,"item":"x","type":"item"},{"command":"a","type":"command"}]""",
            """[{"type":"item","item":"x","amount":1e2}]""",
        )) assertEquals(true, valid(ok), ok)
    }

    @Test
    fun `rewards_valid refuses everything else`() {
        for (bad in listOf(
            "{}", "null", "\"x\"", "1", "[1]", "[null]", "[[]]", "[{}]",
            """[{"type":"command"}]""",
            """[{"type":"command","command":""}]""",
            """[{"type":"command","command":5}]""",
            """[{"type":"command","command":"a","extra":1}]""",
            """[{"type":"item","item":"x"}]""",
            """[{"type":"item","item":"","amount":1}]""",
            """[{"type":"item","item":"x","amount":0}]""",
            """[{"type":"item","item":"x","amount":2305}]""",
            """[{"type":"item","item":"x","amount":-1}]""",
            """[{"type":"item","item":"x","amount":1.5}]""",
            """[{"type":"item","item":"x","amount":"1"}]""",
            """[{"type":"item","item":"x","amount":99999999999}]""",
            """[{"type":"item","item":5,"amount":1}]""",
            """[{"type":"item","command":"x","amount":1}]""",
            """[{"type":"money","command":"x"}]""",
            """[{"type":"command","command":"a"},{"type":"item"}]""",
        )) assertEquals(false, valid(bad), bad)
        assertEquals(false, sql("select sneakymail.rewards_valid(null)") { it.getObject(1) })
    }

    @Test
    fun `the table refuses malformed rewards inserted by hand and defaults the rest`() {
        assertEquals("23514", sqlState {
            exec("insert into sneakymail.mail (recipient_uuid, sender_name, rewards) values (?, 'Staff', ?::jsonb)", alice, """[{"type":"cmd","command":"x"}]""")
        })
        assertEquals("23514", sqlState {
            exec("insert into sneakymail.mail (recipient_uuid, sender_name, source_key) values (?, 'Staff', '')", alice)
        })
        exec("insert into sneakymail.mail (recipient_uuid, sender_name) values (?, 'Staff')", alice)
        assertEquals(listOf("", "[]", "true"), sql("select note, rewards::text, created_at is not null and claimed_at is null and expires_at is null from sneakymail.mail") {
            listOf(it.getString(1), it.getString(2), it.getBoolean(3).toString())
        })
    }

    @Test
    fun `V001 leaves the old journal table alone`() {
        assertEquals("sneakymail.command_requests", sql("select to_regclass('sneakymail.command_requests')::text") { it.getString(1) })
    }

    @Test
    fun `send without a source key inserts every time`() {
        val first = send(null)
        val second = send(null)
        assertNotEquals(first, second)
        assertEquals(2, count())
    }

    @Test
    fun `send with a source key returns the first mail for the same content`() {
        val expires = OffsetDateTime.parse("2030-01-01T00:00:00Z")
        val id = send("shop_1", expires = expires)
        assertEquals(id, send("shop_1", expires = expires))
        assertEquals(id, send("shop_1", rewards = """[{"command": "say {player}", "type": "command"}]""", expires = expires), "jsonb equality, not text")
        assertEquals(1, count())
        assertEquals("shop_1", sql("select source_key from sneakymail.mail where id = ?", id) { it.getString(1) })
    }

    @Test
    fun `a null note is an empty note`() {
        val id = send("k", note = null)
        assertEquals("", sql("select note from sneakymail.mail where id = ?", id) { it.getString(1) })
        assertEquals(id, send("k", note = ""))
        assertEquals(id, send("k", note = null))
    }

    @Test
    fun `send with a reused source key and different content raises 23505`() {
        send("k", expires = null)
        for (change in listOf<() -> Long>(
            { send("k", recipient = bob) },
            { send("k", sender = "Other") },
            { send("k", note = "Other") },
            { send("k", rewards = """[{"type":"command","command":"say other"}]""") },
            { send("k", expires = OffsetDateTime.parse("2030-01-01T00:00:00Z")) },
        )) {
            val error = assertFailsWith<SQLException> { change() }
            assertEquals("23505", error.sqlState)
            assertTrue("source key was reused with different content" in error.message!!, error.message)
        }
        assertEquals(1, count())
    }

    @Test
    fun `send refuses null recipient, sender and rewards, and invalid rewards`() {
        assertEquals("22004", sqlState { send(null, recipient = null) })
        assertEquals("22004", sqlState { send("k", sender = null) })
        assertEquals("22004", sqlState { send("k", rewards = null) })
        assertEquals("23514", sqlState { send("k", rewards = """[{"type":"item","item":"x","amount":0}]""") })
        assertEquals("23514", sqlState { send("k".repeat(513)) })
        assertEquals(0, count())
    }

    @Test
    fun `concurrent identical sends return the same mail`() {
        val callers = 8
        val barrier = CyclicBarrier(callers)
        val pool = Executors.newFixedThreadPool(callers)
        try {
            repeat(5) { round ->
                val futures = (1..callers).map {
                    pool.submit<Long> { connect().use { c -> barrier.await(10, TimeUnit.SECONDS); send("race-$round", connection = c) } }
                }
                val ids = futures.map { it.get(30, TimeUnit.SECONDS) }.toSet()
                assertEquals(1, ids.size, "round $round: $ids")
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(5, count())
    }

    @Test
    fun `a send waiting on an uncommitted identical send returns its mail, or inserts when it rolls back`() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            for (commit in listOf(true, false)) {
                connect().use { first ->
                    first.autoCommit = false
                    val id = send("held-$commit", connection = first)
                    val second = pool.submit<Long> { send("held-$commit") }
                    Thread.sleep(300)
                    assertFalse(second.isDone, "the second call waits for the first transaction")
                    if (commit) first.commit() else first.rollback()
                    val secondId = second.get(10, TimeUnit.SECONDS)
                    if (commit) assertEquals(id, secondId) else assertNotEquals(id, secondId)
                }
            }
        } finally {
            pool.shutdownNow()
        }
        assertEquals(2, count())
    }

    @Test
    fun `the plugin's send gives up on an uncommitted insert of the same key instead of holding a worker`() {
        val api = MailApiService().also { it.ready(store) }
        connect().use { holder ->
            holder.autoCommit = false
            send("held", connection = holder, sender = "Phone", note = "Note", rewards = """[{"type":"command","command":"reward {player}"}]""")
            val started = System.nanoTime()
            val error = assertFailsWith<java.util.concurrent.ExecutionException> {
                store.send("held", alice, "Phone", "Note", listOf(MailReward.CommandReward("reward {player}"))).get(30, TimeUnit.SECONDS)
            }
            val waited = (System.nanoTime() - started) / 1_000_000
            assertEquals("55P03", (error.cause as SQLException).sqlState)
            assertTrue(waited in 4_000..14_000, "gave up after the 5 s lock timeout: $waited ms")
            val apiError = assertFailsWith<java.util.concurrent.ExecutionException> {
                api.submitCommandMail("held", alice, "Phone", "Note", "reward {player}").get(30, TimeUnit.SECONDS)
            }
            assertIs<SQLException>(apiError.cause, "a timeout is not a reused key: the caller retries with the same key")
            holder.rollback()
        }
        val id = store.send("held", alice, "Phone", "Note", listOf(MailReward.CommandReward("reward {player}"))).get(10, TimeUnit.SECONDS)
        assertEquals(id.toString(), api.submitCommandMail("held", alice, "Phone", "Note", "reward {player}").get(10, TimeUnit.SECONDS).requestId(),
            "the retry with the same key works once the other insert is gone")
        api.close()
    }

    @Test
    fun `send runs as its owner and only for roles granted EXECUTE`() {
        val password = UUID.randomUUID().toString()
        exec("drop role if exists sneakymail_test_caller")
        exec("create role sneakymail_test_caller login password '$password'")
        try {
            exec("grant usage on schema sneakymail to sneakymail_test_caller")
            val callerUrl = Regex("""([?&])user=[^&]*""").replace(Regex("""([?&])password=[^&]*""").replace(url, "$1password=$password"), "$1user=sneakymail_test_caller")
            val call = { DriverManager.getConnection(callerUrl).use { c -> send("granted", connection = c) } }
            assertEquals("42501", sqlState { call() }, "PUBLIC has no EXECUTE")
            exec("grant execute on function sneakymail.send(text, uuid, text, text, jsonb, timestamptz) to sneakymail_test_caller")
            call()
            assertEquals(1, count("source_key = 'granted'"), "inserted without privileges on the table")
            assertEquals("42501", sqlState { DriverManager.getConnection(callerUrl).use { c -> c.createStatement().use { it.executeQuery("select * from sneakymail.mail") } } })
        } finally {
            exec("drop owned by sneakymail_test_caller")
            exec("drop role sneakymail_test_caller")
        }
    }

    @Test
    fun `the plugin's claim hands out the rewards once and only to the recipient`() {
        val id = send(null, rewards = """[{"type":"item","item":"item-EXP","amount":3},{"type":"command","command":"say {player}"}]""")
        assertNull(store.claim(id, bob).join(), "someone else's mail")
        assertEquals(listOf(MailReward.ItemReward("item-EXP", 3), MailReward.CommandReward("say {player}")), store.claim(id, alice).join())
        assertNull(store.claim(id, alice).join(), "already claimed")
        assertEquals(1, count("claimed_at is not null"))
        assertNull(store.claim(id + 100, alice).join(), "no such mail")
    }

    @Test
    fun `a claim waiting on a row locked elsewhere times out and claims nothing`() {
        val id = send(null)
        connect().use { holder ->
            holder.autoCommit = false
            holder.createStatement().use { it.executeQuery("select * from sneakymail.mail where id = $id for update").close() }
            val started = System.nanoTime()
            val error = assertFailsWith<java.util.concurrent.ExecutionException> { store.claim(id, alice).get(30, TimeUnit.SECONDS) }
            val waited = (System.nanoTime() - started) / 1_000_000
            val timedOut = assertIs<MailStore.ClaimTimedOut>(error.cause)
            assertEquals("55P03", (timedOut.cause as SQLException).sqlState)
            assertTrue(waited in 4_000..14_000, "gave up after the 5 s lock timeout, not the statement timeout: $waited ms")
            assertEquals(0, count("claimed_at is not null"), "rolled back")
            holder.rollback()
        }
        assertEquals(listOf(MailReward.CommandReward("say {player}")), store.claim(id, alice).get(10, TimeUnit.SECONDS),
            "the next try works")
    }

    @Test
    fun `concurrent claims hand out the rewards once`() {
        val id = send(null)
        val results = (1..8).map { store.claim(id, alice) }.map { it.get(10, TimeUnit.SECONDS) }
        assertEquals(1, results.count { it != null })
    }

    @Test
    fun `unclaimedFor reads one player's unclaimed mail, oldest first`() {
        val first = send(null, note = "first\nline")
        val second = send(null, sender = "<gold>Mother Mouse</gold>", rewards = "[]")
        send(null, recipient = bob)
        val claimed = send(null)
        store.claim(claimed, alice).join()
        assertEquals(
            listOf(
                MailRecord(first, alice, "Sender", "first\nline", listOf(MailReward.CommandReward("say {player}"))),
                MailRecord(second, alice, "<gold>Mother Mouse</gold>", "Hi", emptyList()),
            ),
            store.unclaimedFor(alice).join(),
        )
    }

    @Test
    fun `MailStore send and the public API go through sneakymail send`() {
        val id = store.send("api", alice, "Phone", "Reward", listOf(MailReward.CommandReward("reward {player}"))).join()
        assertEquals(id, send("api", sender = "Phone", note = "Reward", rewards = """[{"type":"command","command":"reward {player}"}]"""))

        val api = MailApiService().also { it.ready(store) }
        val accepted = api.submitCommandMail("phone:1", alice, "Phone", "Note", "reward {player}").get(10, TimeUnit.SECONDS)
        assertEquals(accepted, api.submitCommandMail("phone:1", alice, "Phone", "Note", "reward {player}").get(10, TimeUnit.SECONDS))
        assertEquals(sql("select id from sneakymail.mail where source_key = 'phone:1'") { it.getLong(1).toString() }, accepted.requestId())
        val error = assertFailsWith<CompletionException> { api.submitCommandMail("phone:1", alice, "Phone", "Changed", "reward {player}").join() }
        assertIs<IllegalArgumentException>(error.cause)
        api.close()
    }

    @Test
    fun `delete_expired deletes only unclaimed mail whose expiry has passed`() {
        val past = OffsetDateTime.now().minusMinutes(1)
        val expired = send(null, expires = past)
        val claimedExpired = send(null, expires = past)
        store.claim(claimedExpired, alice).join()
        send(null, expires = OffsetDateTime.now().plusDays(1))
        send(null)
        assertEquals(1, sql("select sneakymail.delete_expired()") { it.getInt(1) })
        assertEquals(0, count("id = $expired"))
        assertEquals(3, count())
        assertEquals(0, sql("select sneakymail.delete_expired()") { it.getInt(1) })
    }

    @Test
    fun `the trigger notifies new, claimed and deleted mail`() {
        connect().use { listener ->
            listener.createStatement().use { it.execute("listen sneakymail") }
            val pg = listener.unwrap(PGConnection::class.java)
            fun received(): List<String> {
                listener.createStatement().use { it.execute("select 1") }
                return pg.getNotifications(2000)?.filter { it.name == "sneakymail" }?.map { it.parameter } ?: emptyList()
            }

            val id = send(null)
            assertEquals(listOf("""{"event":"new","id":$id,"recipient":"$alice"}"""), received())

            exec("update sneakymail.mail set note = 'edited' where id = ?", id)
            assertEquals(emptyList(), received(), "other updates send nothing")

            store.claim(id, alice).join()
            assertEquals(listOf("""{"event":"claimed","id":$id,"recipient":"$alice"}"""), received())

            exec("update sneakymail.mail set claimed_at = now() where id = ?", id)
            exec("update sneakymail.mail set claimed_at = null where id = ?", id)
            assertEquals(emptyList(), received(), "claimed again or reset by hand: nothing")

            exec("delete from sneakymail.mail where id = ?", id)
            assertEquals(listOf("""{"event":"deleted","id":$id,"recipient":"$alice"}"""), received())

            exec("insert into sneakymail.mail (recipient_uuid, sender_name, claimed_at) values (?, 'Import', now())", bob)
            val imported = sql("select max(id) from sneakymail.mail") { it.getLong(1) }
            assertEquals(listOf("""{"event":"new","id":$imported,"recipient":"$bob"}"""), received(), "every insert is new, claimed or not")

            val expiring = send(null, expires = OffsetDateTime.now().minusMinutes(1))
            received()
            sql("select sneakymail.delete_expired()") { it.getInt(1) }
            assertEquals(listOf("""{"event":"deleted","id":$expiring,"recipient":"$alice"}"""), received())
        }
    }

    @Test
    fun `the parsed notification matches what the trigger sends`() {
        connect().use { listener ->
            listener.createStatement().use { it.execute("listen sneakymail") }
            val id = send(null)
            listener.createStatement().use { it.execute("select 1") }
            val payload = listener.unwrap(PGConnection::class.java).getNotifications(2000)!!.single().parameter
            assertEquals(MailNotification(MailNotification.Event.NEW, id, alice), MailNotification.parse(payload))
        }
    }
}
