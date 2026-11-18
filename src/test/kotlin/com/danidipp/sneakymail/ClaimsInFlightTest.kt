package com.danidipp.sneakymail

import com.danidipp.sneakymail.ClaimsInFlight.Outcome
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaimsInFlightTest {
    private val claims = ClaimsInFlight()
    private val player = UUID.fromString("11111111-1111-4111-8111-111111111111")
    private val rewards = listOf(MailReward.CommandReward("say {player}"))

    @Test
    fun `a claim that returned rewards is handed out once`() {
        val claim = claims.start(1, player, "Alice")!!
        assertEquals(Outcome.HAND_OUT, claims.finished(claim, rewards))
        assertEquals(rewards, claims.take(claim))
        assertNull(claims.take(claim), "only once")
        assertEquals(0, claims.size())
        assertEquals(emptyList<ClaimsInFlight.Claim>() to emptyList(), claims.shutdown())
    }

    @Test
    fun `a claim that found nothing settles`() {
        val claim = claims.start(1, player, "Alice")!!
        assertEquals(Outcome.NOTHING, claims.finished(claim, null))
        assertEquals(0, claims.size())
    }

    @Test
    fun `a failed statement settles`() {
        val claim = claims.start(1, player, "Alice")!!
        assertTrue(claims.failed(claim))
        assertEquals(0, claims.size())
    }

    @Test
    fun `shutdown takes known rewards whose hand-out has not run`() {
        val claim = claims.start(1, player, "Alice")!!
        claims.finished(claim, rewards)
        val (running, claimed) = claims.shutdown()
        assertEquals(emptyList(), running)
        assertEquals(listOf(claim), claimed)
        assertEquals(rewards, claimed.single().rewards)
        assertNull(claims.take(claim), "the cancelled hand-out task, if it still ran, gives nothing")
    }

    @Test
    fun `a rejoin can't start a second claim of a mail whose first claim is still running`() {
        // Claim A runs; the player reconnects, so the new session's cache has no claim guard, and clicks the mail again.
        val first = claims.start(1, player, "Alice")!!
        assertNull(claims.start(1, player, "Alice"), "the per-mail guard outlives the session")
        assertNull(claims.start(1, UUID.randomUUID(), "Bob"))
        // A won; its hand-out waits for the server thread when the plugin disables.
        assertEquals(Outcome.HAND_OUT, claims.finished(first, rewards))
        assertNull(claims.start(1, player, "Alice"), "still unsettled until handed out")
        val (running, claimed) = claims.shutdown()
        assertEquals(emptyList(), running)
        assertEquals(listOf(first), claimed, "shutdown still finds A and hands it out")
    }

    @Test
    fun `settling an attempt removes exactly that attempt`() {
        val first = claims.start(1, player, "Alice")!!
        assertEquals(Outcome.NOTHING, claims.finished(first, null))
        val second = claims.start(1, player, "Alice")!!
        assertEquals(Outcome.NOTHING, claims.finished(first, null), "a stale completion of the settled attempt")
        assertEquals(1, claims.size(), "does not remove the newer attempt")
        assertEquals(Outcome.HAND_OUT, claims.finished(second, rewards))
        assertEquals(listOf(second), claims.shutdown().second)
    }

    @Test
    fun `shutdown reports running statements, which give the mail back when they claim late`() {
        val late = claims.start(1, player, "Alice")!!
        val empty = claims.start(2, player, "Alice")!!
        val failing = claims.start(3, player, "Alice")!!
        val (running, claimed) = claims.shutdown()
        assertEquals(listOf(late, empty, failing), running)
        assertEquals(emptyList(), claimed)
        assertEquals(Outcome.LATE_CLAIMED, claims.finished(late, rewards))
        assertEquals(Outcome.LATE_NOTHING, claims.finished(empty, null))
        assertFalse(claims.failed(failing), "already reported by the shutdown")
        assertNull(claims.take(late))
    }
}
