package com.danidipp.sneakymail

import org.bukkit.event.Event
import org.bukkit.event.HandlerList

/** A mail arrived for an online player (fired on the server thread). [record] is the unclaimed mail. */
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
    }
}
