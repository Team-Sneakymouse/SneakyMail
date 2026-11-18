package com.danidipp.sneakymail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingClaimsTest {
    /** Like MailClaims' attempts: two attempts for one mail are different entries. */
    private class Attempt(val id: Long)

    private val pending = PendingClaims<Attempt>()

    @Test
    fun `a committed claim is handed out once by its tick`() {
        val attempt = Attempt(1)
        pending.started(attempt)
        assertTrue(pending.committed(attempt))
        assertTrue(pending.take(attempt))
        assertFalse(pending.take(attempt))
        assertEquals(emptyList<Attempt>() to emptyList(), pending.shutdown())
    }

    @Test
    fun `shutdown reports running statements and takes committed claims whose tick has not run`() {
        val running = Attempt(1)
        val committed = Attempt(2)
        val ended = Attempt(3)
        listOf(running, committed, ended).forEach(pending::started)
        pending.committed(committed)
        pending.ended(ended)
        assertEquals(listOf(running) to listOf(committed), pending.shutdown())
        assertFalse(pending.take(committed), "the cancelled tick, if it still ran, finds nothing")
        assertFalse(pending.committed(running), "a statement that claims after shutdown is reported by the caller")
    }

    @Test
    fun `two attempts for the same mail are separate entries`() {
        // A double click, or a click after a reconnect: both statements run; at most one claims.
        val first = Attempt(1)
        val second = Attempt(1)
        pending.started(first)
        pending.started(second)
        assertTrue(pending.committed(first))
        pending.ended(second) // got no row: "already collected"
        assertFalse(pending.take(second))
        assertEquals(emptyList<Attempt>() to listOf(first), pending.shutdown())
    }
}
