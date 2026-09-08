package com.danidipp.sneakymail

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecipientUuidTest {
    private val uuid = UUID.fromString("12345678-1234-5678-9abc-123456789abc")

    @Test
    fun `missing recipient UUIDs do not parse`() {
        assertNull(recipientUuidOrNull(null))
        assertNull(recipientUuidOrNull(""))
        assertNull(recipientUuidOrNull("   "))
        assertNull(recipientUuidOrNull("null"))
        assertNull(recipientUuidOrNull("NULL"))
    }

    @Test
    fun `malformed recipient UUIDs do not parse`() {
        assertNull(recipientUuidOrNull("not-a-uuid"))
        assertNull(recipientUuidOrNull("1-1-1-1-1"))
    }

    @Test
    fun `canonical recipient UUID parses`() {
        assertEquals(uuid, recipientUuidOrNull(uuid.toString()))
    }

    @Test
    fun `compact recipient UUID is normalized`() {
        assertEquals(uuid, recipientUuidOrNull("12345678123456789abc123456789abc"))
    }

    @Test
    fun `missing UUID resolves from recipient name`() {
        var lookedUpName: String? = null

        val result = resolveRecipientUuid(null, "  Dani  ") { name ->
            lookedUpName = name
            uuid
        }

        assertEquals(uuid, result)
        assertEquals("Dani", lookedUpName)
    }

    @Test
    fun `missing UUID stays unresolved when name lookup fails`() {
        assertNull(resolveRecipientUuid("null", "Dani") { null })
    }

    @Test
    fun `malformed UUID does not fall back to recipient name`() {
        var lookupCalled = false

        val result = resolveRecipientUuid("not-a-uuid", "Dani") {
            lookupCalled = true
            uuid
        }

        assertNull(result)
        assertFalse(lookupCalled)
    }

    @Test
    fun `missing UUID classification handles placeholders and whitespace`() {
        assertTrue((null as String?).isMissingRecipientUuid())
        assertTrue(" null ".isMissingRecipientUuid())
        assertTrue("   ".isMissingRecipientUuid())
        assertFalse("not-a-uuid".isMissingRecipientUuid())
    }
}
