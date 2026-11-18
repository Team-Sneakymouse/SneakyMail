package com.danidipp.sneakymail

import com.danidipp.sneakymail.api.SneakyMailApi
import com.sneakydb.SneakyDBApi
import com.sneakydb.SneakyDBTransaction
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MailApiServiceTest {
    class Database : SneakyDBApi {
        val result = CompletableFuture<List<Map<String, Any?>>>()
        var available = true
        var sql = ""
        var parameters: List<*> = emptyList<Any>()
        override fun isAvailable() = available
        override fun whenReady(): CompletableFuture<Void> = CompletableFuture.completedFuture(null)
        override fun query(sql: String, parameters: List<*>): CompletableFuture<List<Map<String, Any?>>> {
            this.sql = sql; this.parameters = parameters; return result
        }
        override fun execute(sql: String, parameters: List<*>): CompletableFuture<Int> = CompletableFuture.completedFuture(1)

        /** Statements the transaction's work ran before its query (the `set local` timeouts). */
        val settings = mutableListOf<String>()

        /**
         * Records the work's statements at once (its first query stops that pass), then runs it again with [result]'s
         * rows once a test completes [result].
         */
        override fun <T> transaction(work: java.util.function.Function<SneakyDBTransaction, T>): CompletableFuture<T> {
            try {
                work.apply(object : SneakyDBTransaction {
                    override fun query(sql: String, parameters: List<*>): List<Map<String, Any?>> {
                        this@Database.sql = sql; this@Database.parameters = parameters; throw Recorded()
                    }
                    override fun execute(sql: String, parameters: List<*>): Int { settings += sql; return 0 }
                })
            } catch (_: Recorded) {
            }
            return result.thenApply { rows ->
                work.apply(object : SneakyDBTransaction {
                    override fun query(sql: String, parameters: List<*>) = rows
                    override fun execute(sql: String, parameters: List<*>) = 0
                })
            }
        }

        private class Recorded : RuntimeException()
    }

    private val db = Database()
    private val service = MailApiService().also { it.ready(MailStore(db, Logger.getAnonymousLogger())) }
    private val recipient = UUID.fromString("11111111-1111-4111-8111-111111111111")

    private fun failure(future: CompletableFuture<*>): Throwable {
        assertTrue(future.isCompletedExceptionally)
        return try {
            future.join()
            error("completed normally")
        } catch (error: CompletionException) {
            error.cause!!
        }
    }

    @Test
    fun `Java null in each argument returns a failed future without throwing`() {
        val method = SneakyMailApi::class.java.getMethod("submitCommandMail", String::class.java, UUID::class.java, String::class.java, String::class.java, String::class.java)
        for (index in 0..4) {
            val args = arrayOf<Any?>("key", recipient, "sender", "note", "command")
            args[index] = null
            assertIs<IllegalArgumentException>(failure(method.invoke(service, *args) as CompletableFuture<*>), "argument $index")
        }
        assertEquals("", db.sql)
    }

    @Test
    fun `blank or long source key and blank command are rejected before SQL`() {
        assertIs<IllegalArgumentException>(failure(service.submitCommandMail(" ", recipient, "Phone", "note", "command")))
        assertIs<IllegalArgumentException>(failure(service.submitCommandMail("k".repeat(513), recipient, "Phone", "note", "command")))
        assertIs<IllegalArgumentException>(failure(service.submitCommandMail("key", recipient, "Phone", "note", " ")))
        assertEquals("", db.sql)
    }

    @Test
    fun `accepts with the mail id once sneakymail send returned it`() {
        val future = service.submitCommandMail("phone:one", recipient, "Phone", "note", "reward {player}")
        assertFalse(future.isDone)
        assertEquals(MailStore.SEND, db.sql)
        assertEquals(listOf(MailStore.LOCK_TIMEOUT, MailStore.STATEMENT_TIMEOUT), db.settings, "send runs with the timeouts")
        assertEquals(listOf("phone:one", recipient, "Phone", "note", """[{"type":"command","command":"reward {player}"}]"""), db.parameters)
        db.result.complete(listOf(mapOf("id" to 42L)))
        assertEquals("42", future.join().requestId())
    }

    @Test
    fun `a reused source key with different content fails like before`() {
        val future = service.submitCommandMail("phone:one", recipient, "Phone", "note", "reward")
        db.result.completeExceptionally(SQLException("sneakymail.send: source key was reused with different content", "23505"))
        val error = failure(future)
        assertIs<IllegalArgumentException>(error)
        assertEquals("Source key already used with different payload", error.message)
    }

    @Test
    fun `other database failures pass through`() {
        val future = service.submitCommandMail("phone:one", recipient, "Phone", "note", "reward")
        val cause = SQLException("connection lost", "08006")
        db.result.completeExceptionally(cause)
        assertSame(cause, failure(future))
    }

    @Test
    fun `unavailable before ready, while the database is down, and after close`() {
        val unready = MailApiService()
        assertFalse(unready.isAvailable())
        assertIs<IllegalStateException>(failure(unready.submitCommandMail("key", recipient, "Phone", "note", "command")))

        assertTrue(service.isAvailable())
        db.available = false
        assertFalse(service.isAvailable())
        assertIs<IllegalStateException>(failure(service.submitCommandMail("key", recipient, "Phone", "note", "command")))
        db.available = true

        val pending = (1..3).map { service.submitCommandMail("key:$it", recipient, "Phone", "note", "command") }
        service.close()
        assertFalse(service.isAvailable())
        assertTrue(pending.all { it.isCompletedExceptionally })
        assertTrue(service.submitCommandMail("late", recipient, "Phone", "note", "command").isCompletedExceptionally)
        db.result.complete(listOf(mapOf("id" to 1L)))
        assertTrue(pending.all { it.isCompletedExceptionally })
    }
}
