package com.danidipp.sneakymail

import org.bukkit.Bukkit
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent

class NewMailEvent(val record: MailRecord) : Event() {
    override fun getHandlers(): HandlerList {
        return HANDLERS
    }

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList {
            return HANDLERS
        }

        val listener = object : Listener {
            @EventHandler
            fun onNewMail(event: NewMailEvent) {
                val record = event.record
                val recipientUuid = record.recipientUuidOrNull() ?: return
                val recipient = Bukkit.getPlayer(recipientUuid) ?: return
                val command = "ms cast as ${recipient.name} purchase-newmail"
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            }

            @EventHandler
            fun onLogin(event: PlayerJoinEvent) {
                val player = event.player
                val mails = SneakyMail.mail.filter {
                    it.value.available &&
                    it.value.recipientUuidOrNull() == player.uniqueId
                }
                if (mails.isNotEmpty()) {
                    val command = "ms cast as ${player.name} purchase-newmail"
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                }
            }
        }
    }
}
