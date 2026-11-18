package com.danidipp.sneakymail

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MailCacheTest {
    private val alice = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val loads = mutableListOf<Pair<UUID, CompletableFuture<List<MailRecord>>>>()
    private val newMail = mutableListOf<Long>()
    private val unreadAtJoin = mutableListOf<Pair<UUID, Int>>()
    private val cache = MailCache(
        load = { player -> CompletableFuture<List<MailRecord>>().also { loads += player to it } },
        main = Executor { it.run() },
        events = object : MailCache.Events {
            override fun newMail(record: MailRecord) { newMail += record.id }
            override fun unreadAtJoin(player: UUID, count: Int) { unreadAtJoin += player to count }
        },
        logger = Logger.getAnonymousLogger(),
    )

    private fun mail(id: Long, recipient: UUID = alice) =
        MailRecord(id, recipient, "Sender", "", listOf(MailReward.CommandReward("say {player}")))

    private fun ids(player: UUID = alice) = cache.unclaimed(player).map { it.id }

    @Test
    fun `join loads the player's mail and announces unread mail once`() {
        cache.track(alice, announceUnread = true)
        assertEquals(1, loads.size)
        loads.single().second.complete(listOf(mail(2), mail(1)))
        assertEquals(listOf(1L, 2L), ids())
        assertEquals(2, cache.unreadCount(alice))
        assertEquals(listOf(alice to 2), unreadAtJoin)

        cache.reloadAll()
        loads.last().second.complete(listOf(mail(1)))
        assertEquals(listOf(1L), ids())
        assertEquals(1, unreadAtJoin.size, "only the first load after a join announces")
    }

    @Test
    fun `players already online at enable are loaded without an announcement`() {
        cache.track(alice, announceUnread = false)
        loads.single().second.complete(listOf(mail(1)))
        assertEquals(listOf(1L), ids())
        assertTrue(unreadAtJoin.isEmpty())
    }

    @Test
    fun `tracking twice loads once`() {
        cache.track(alice, announceUnread = true)
        cache.track(alice, announceUnread = true)
        assertEquals(1, loads.size)
    }

    @Test
    fun `a new mail is loaded and announced as new mail, not as unread at join`() {
        cache.track(alice, announceUnread = true)
        loads[0].second.complete(emptyList())
        cache.created(5, alice)
        assertEquals(2, loads.size)
        loads[1].second.complete(listOf(mail(5)))
        assertEquals(listOf(5L), newMail)
        assertEquals(listOf(5L), ids())
        assertTrue(unreadAtJoin.isEmpty())
    }

    @Test
    fun `a new mail during the first load is announced once`() {
        cache.track(alice, announceUnread = true)
        cache.created(5, alice)
        loads[0].second.complete(listOf(mail(5)))
        loads[1].second.complete(listOf(mail(5)))
        assertEquals(listOf(5L), newMail)
        assertTrue(unreadAtJoin.isEmpty())
    }

    @Test
    fun `new mail for a player who is not online is ignored`() {
        cache.created(5, alice)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `loads of one player never overlap and the latest result wins`() {
        cache.track(alice, announceUnread = false)
        cache.created(5, alice)
        cache.created(6, alice)
        assertEquals(1, loads.size, "reloads wait for the running load")
        loads[0].second.complete(listOf(mail(5)))
        assertEquals(listOf(5L), newMail)
        assertEquals(2, loads.size, "one more load follows")
        loads[1].second.complete(listOf(mail(5), mail(6)))
        assertEquals(listOf(5L, 6L), newMail)
        assertEquals(listOf(5L, 6L), ids())
        assertEquals(2, loads.size)
    }

    @Test
    fun `a new mail deleted before the load is not announced later`() {
        cache.track(alice, announceUnread = false)
        loads[0].second.complete(emptyList())
        cache.created(5, alice)
        loads[1].second.complete(emptyList())
        cache.reloadAll()
        loads[2].second.complete(listOf(mail(5)))
        assertTrue(newMail.isEmpty())
    }

    @Test
    fun `a load that ran while a mail was claimed can't bring it back, the next load decides`() {
        cache.track(alice, announceUnread = false)
        loads[0].second.complete(listOf(mail(1), mail(2)))
        cache.reloadAll()
        cache.gone(1, alice)
        assertEquals(listOf(2L), ids())
        loads[1].second.complete(listOf(mail(1), mail(2))) // read before the claim committed
        assertEquals(listOf(2L), ids())
        assertEquals(3, loads.size, "the claim asked for a load read after it")
        loads[2].second.complete(listOf(mail(2)))
        assertEquals(listOf(2L), ids())
        assertEquals(3, loads.size)
    }

    @Test
    fun `a claim while no load runs removes the mail and reads the player again`() {
        cache.track(alice, announceUnread = false)
        loads[0].second.complete(listOf(mail(1), mail(2)))
        cache.gone(1, alice)
        assertEquals(listOf(2L), ids())
        assertEquals(2, loads.size)
        loads[1].second.complete(listOf(mail(2)))
        assertEquals(listOf(2L), ids())
    }

    @Test
    fun `a notification for a player who is not online does nothing`() {
        cache.gone(1, alice)
        cache.created(1, alice)
        assertTrue(loads.isEmpty())
    }

    @Test
    fun `quitting drops the mail and a late result of the old session`() {
        cache.track(alice, announceUnread = true)
        cache.untrack(alice)
        loads[0].second.complete(listOf(mail(1)))
        assertEquals(0, cache.unreadCount(alice))
        assertTrue(unreadAtJoin.isEmpty())
        assertTrue(cache.tracked().isEmpty())

        cache.track(alice, announceUnread = true)
        loads[1].second.complete(listOf(mail(1)))
        assertEquals(listOf(1L), ids())
    }

    @Test
    fun `a failed load keeps the cache and the next load works`() {
        cache.track(alice, announceUnread = true)
        loads[0].second.completeExceptionally(IllegalStateException("database down"))
        assertEquals(0, cache.unreadCount(alice))
        cache.reloadAll()
        loads[1].second.complete(listOf(mail(1)))
        assertEquals(listOf(1L), ids())
        assertEquals(listOf(alice to 1), unreadAtJoin, "the first successful load after the join announces")
    }

    @Test
    fun `a loader that throws counts as a failed load`() {
        val throwing = MailCache({ throw IllegalStateException("no") }, Executor { it.run() }, object : MailCache.Events {
            override fun newMail(record: MailRecord) = Unit
            override fun unreadAtJoin(player: UUID, count: Int) = Unit
        }, Logger.getAnonymousLogger())
        throwing.track(alice, announceUnread = true)
        assertEquals(0, throwing.unreadCount(alice))
        assertEquals(setOf(alice), throwing.tracked())
    }

    @Test
    fun `find and snapshot read the cached mail`() {
        cache.track(alice, announceUnread = false)
        loads[0].second.complete(listOf(mail(1)))
        assertEquals(mail(1), cache.find(1))
        assertEquals(null, cache.find(9))
        assertEquals(mapOf(alice to listOf(mail(1))), cache.snapshot())
    }
}
