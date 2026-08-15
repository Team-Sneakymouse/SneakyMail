package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.AsyncPocketbaseEvent
import com.danidipp.sneakypocketbase.PocketbaseApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import java.util.logging.Logger

class PocketbaseListener(private val logger: Logger, pocketbase: PocketbaseApi) : Listener {
    init {
        logger.warning("Registering callback for when Pocketbase is loaded")
        pocketbase.whenReady {
            logger.warning("Pocketbase is loaded. Now subscribing to mail events")
            pocketbase.subscribe("lom2_mail")

            logger.warning("Fetching current mail records")
            Bukkit.getScheduler().runTaskAsynchronously(SneakyMail.getInstance(), Runnable {

                val mailList = pocketbase.getFullList(
                    "lom2_mail",
                    500,
                    "",
                    "available=true"
                ).join().map {
                    Json { ignoreUnknownKeys = true; coerceInputValues = true }.decodeFromString<MailRecord>(it)
                }
                mailList.forEach {
                    it.checkUuid()
                    SneakyMail.mail[it.id!!] = it
                }
                logger.warning("Fetched ${mailList.size} mail records")
            })
        }
    }

    @EventHandler
    fun onMailEvent(event: AsyncPocketbaseEvent) {
        if (event.collectionName != "lom2_mail") return
        logger.warning("Received mail event: ${event.action}")
        val mailRecord = Json { ignoreUnknownKeys = true; coerceInputValues = true }
            .decodeFromString<MailRecord>(event.recordJson)

        when (event.action) {
            AsyncPocketbaseEvent.Action.CREATE -> {
                logger.info("${mailRecord.recipient_name} has received a new mail")
                mailRecord.checkUuid()
                SneakyMail.mail[mailRecord.id!!] = mailRecord
                Bukkit.getScheduler().runTask(SneakyMail.getInstance(), Runnable {
                    val mailEvent = NewMailEvent(mailRecord)
                    Bukkit.getPluginManager().callEvent(mailEvent)
                })
            }
            AsyncPocketbaseEvent.Action.UPDATE -> {
                mailRecord.checkUuid()
                if (!SneakyMail.mail.containsKey(mailRecord.id)) {
                    handleCreated(mailRecord)
                    return
                }

                val oldRecord = SneakyMail.mail[mailRecord.id]!!
                if (!oldRecord.available && mailRecord.available) {
                    // Mail has been marked as available again
                    oldRecord.apply {
                        sender_uuid = mailRecord.sender_uuid
                        sender_name = mailRecord.sender_name
                        recipient_uuid = mailRecord.recipient_uuid
                        recipient_name = mailRecord.recipient_name
                        rewards = mailRecord.rewards
                        note = mailRecord.note
                    }
                    Bukkit.getScheduler().runTask(SneakyMail.getInstance(), Runnable {
                        val mailEvent = NewMailEvent(oldRecord)
                        Bukkit.getPluginManager().callEvent(mailEvent)
                    })
                } else {
                    SneakyMail.mail[mailRecord.id!!] = mailRecord
                }
            }
            AsyncPocketbaseEvent.Action.DELETE -> { //TODO: Properly handle deletion
                SneakyMail.mail[mailRecord.id]?.available = false
                SneakyMail.mail.remove(mailRecord.id)
            }
            else -> {
                throw IllegalStateException("${event.action} can never occur. (${event.recordJson}")
            }
        }

    }

    private fun handleCreated(mailRecord: MailRecord) {
        logger.info("${mailRecord.recipient_name} has received a new mail")
        mailRecord.checkUuid()
        SneakyMail.mail[mailRecord.id!!] = mailRecord
        Bukkit.getScheduler().runTask(SneakyMail.getInstance(), Runnable {
            Bukkit.getPluginManager().callEvent(NewMailEvent(mailRecord))
        })
    }
}
