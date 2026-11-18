package com.danidipp.sneakymail

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MailNotificationTest {
    private val recipient = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @Test
    fun `parses the trigger's three events`() {
        assertEquals(MailNotification(MailNotification.Event.NEW, 7, recipient),
            MailNotification.parse("""{"event":"new","id":7,"recipient":"$recipient"}"""))
        assertEquals(MailNotification(MailNotification.Event.CLAIMED, 7, recipient),
            MailNotification.parse("""{"event":"claimed","id":7,"recipient":"$recipient"}"""))
        assertEquals(MailNotification(MailNotification.Event.DELETED, 9007199254740993, recipient),
            MailNotification.parse("""{"recipient" : "$recipient", "id" : 9007199254740993, "event" : "deleted"}"""))
    }

    @Test
    fun `anything else is null`() {
        for (payload in listOf("", "not json", "[]", """{"event":"updated","id":1,"recipient":"$recipient"}""",
            """{"event":"unclaimed","id":1,"recipient":"$recipient"}""",
            """{"event":"new","id":"x","recipient":"$recipient"}""", """{"event":"new","id":1,"recipient":"nope"}""",
            """{"event":"new","recipient":"$recipient"}""")) {
            assertNull(MailNotification.parse(payload), payload)
        }
    }
}
