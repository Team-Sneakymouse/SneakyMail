package com.danidipp.sneakymail

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.util.UUID

/** A notification on channel `sneakymail`, sent by the trigger in db/migrations/V001__mail.sql. */
data class MailNotification(val event: Event, val id: Long, val recipient: UUID) {
    enum class Event { NEW, CLAIMED, UNCLAIMED, DELETED }

    companion object {
        /** Null for a payload that isn't one of the trigger's. */
        fun parse(payload: String): MailNotification? = try {
            val json = Json.parseToJsonElement(payload).jsonObject
            val event = when (json.getValue("event").jsonPrimitive.content) {
                "new" -> Event.NEW
                "claimed" -> Event.CLAIMED
                "unclaimed" -> Event.UNCLAIMED
                "deleted" -> Event.DELETED
                else -> null
            }
            event?.let {
                MailNotification(it, json.getValue("id").jsonPrimitive.long, UUID.fromString(json.getValue("recipient").jsonPrimitive.content))
            }
        } catch (_: Exception) {
            null
        }
    }
}
