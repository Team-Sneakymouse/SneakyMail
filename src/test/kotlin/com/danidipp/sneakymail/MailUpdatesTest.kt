package com.danidipp.sneakymail

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MailUpdatesTest {
    private val recipient = UUID.fromString("12345678-1234-5678-9abc-123456789abc")
    private fun record(available: Boolean) = MailRecord(
        id = "mail-id", sender_uuid = null, sender_name = "Sender",
        recipient_uuid = recipient.toString(), recipient_name = "Player",
        rewards = emptyList(), available = available, note = "Original"
    )

    @Test
    fun `claimed mail made available in database appears in player mail without restart`() {
        val claimed = record(false)
        val cache = mutableMapOf("mail-id" to claimed)
        val updated = record(true).copy(note = "Available again")

        val notification = updateCachedMail(cache, updated)

        val visibleMail = cache.values.filter {
            it.available && it.recipientUuidOrNull() == recipient
        }
        assertEquals(listOf(updated), visibleMail)
        assertSame(claimed, notification)
        assertTrue(notification!!.available)
        assertNull(updateCachedMail(cache, updated.copy()))
    }

    @Test
    fun `claim update removes mail from available mail without notification`() {
        val cache = mutableMapOf("mail-id" to record(true))
        val claimed = record(false)

        assertNull(updateCachedMail(cache, claimed))
        assertEquals(claimed, cache["mail-id"])
        assertTrue(cache.values.none { it.available })
    }
}
