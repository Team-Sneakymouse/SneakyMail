package com.danidipp.sneakymail

import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import java.util.UUID

/** Keeps the cache to the online players and casts MagicSpells' `purchase-newmail` for new mail. */
class MailListener(private val cache: MailCache) : Listener {
    @EventHandler
    fun onJoin(event: PlayerJoinEvent) {
        cache.track(event.player.uniqueId, announceUnread = true)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        cache.untrack(event.player.uniqueId)
    }

    @EventHandler
    fun onNewMail(event: NewMailEvent) {
        Bukkit.getPlayer(event.record.recipientUuid)?.let(MailNotices::castNewMail)
    }
}

/** What the cache reports, turned into Bukkit events and spell casts (server thread). */
object MailNotices : MailCache.Events {
    override fun newMail(record: MailRecord) {
        Bukkit.getPluginManager().callEvent(NewMailEvent(record))
    }

    override fun unreadAtJoin(player: UUID, count: Int) {
        Bukkit.getPlayer(player)?.let(::castNewMail)
    }

    fun castNewMail(player: Player) {
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ms cast as ${player.name} purchase-newmail")
    }
}
