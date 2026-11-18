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
        val claim = claims.start(1, player, "Alice")
        assertEquals(Outcome.HAND_OUT, claims.finished(claim, rewards))
        assertEquals(rewards, claims.take(claim))
        assertNull(claims.take(claim), "only once")
        assertEquals(0, claims.size())
        assertEquals(emptyList<ClaimsInFlight.Claim>() to emptyList(), claims.shutdown())
    }

    @Test
    fun `a claim that found nothing settles`() {
        val claim = claims.start(1, player, "Alice")
        assertEquals(Outcome.NOTHING, claims.finished(claim, null))
        assertEquals(0, claims.size())
    }

    @Test
    fun `a failed statement settles`() {
        val claim = claims.start(1, player, "Alice")
        assertTrue(claims.failed(claim))
        assertEquals(0, claims.size())
    }

    @Test
    fun `shutdown takes known rewards whose hand-out has not run`() {
        val claim = claims.start(1, player, "Alice")
        claims.finished(claim, rewards)
        val (running, claimed) = claims.shutdown()
        assertEquals(emptyList(), running)
        assertEquals(listOf(claim), claimed)
        assertEquals(rewards, claimed.single().rewards)
        assertNull(claims.take(claim), "the cancelled hand-out task, if it still ran, gives nothing")
    }

    @Test
    fun `shutdown reports running statements, which give the mail back when they claim late`() {
        val late = claims.start(1, player, "Alice")
        val empty = claims.start(2, player, "Alice")
        val failing = claims.start(3, player, "Alice")
        val (running, claimed) = claims.shutdown()
        assertEquals(listOf(late, empty, failing), running)
        assertEquals(emptyList(), claimed)
        assertEquals(Outcome.LATE_CLAIMED, claims.finished(late, rewards))
        assertEquals(Outcome.LATE_NOTHING, claims.finished(empty, null))
        assertFalse(claims.failed(failing), "already reported by the shutdown")
        assertNull(claims.take(late))
    }
}
