package com.danidipp.sneakymail

import me.clip.placeholderapi.expansion.PlaceholderExpansion
import org.bukkit.entity.Player

class Placeholders : PlaceholderExpansion() {
    override fun getIdentifier(): String {
        return SneakyMail.IDENTIFIER
    }

    override fun getAuthor(): String {
        return SneakyMail.AUTHORS
    }

    override fun getVersion(): String {
        return SneakyMail.VERSION
    }

    override fun persist(): Boolean {
        return true
    }

    override fun onPlaceholderRequest(player: Player, identifier: String): String? {
        return when (identifier) {
            "unread_count" -> (SneakyMail.getInstance().cache?.unreadCount(player.uniqueId) ?: 0).toString()
            else -> null
        }
    }
}
