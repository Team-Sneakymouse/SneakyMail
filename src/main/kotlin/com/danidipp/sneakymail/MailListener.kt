package com.danidipp.sneakymail

import com.sneakyutils.util.ToastUtil
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

/** Keeps the cache to the online players and tells them about new mail. */
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
        Bukkit.getPlayer(event.record.recipientUuid)?.let { MailNotices.notify(it, cache.unreadCount(it.uniqueId)) }
    }
}

/** What the cache reports, turned into Bukkit events and notices to the player (server thread). */
object MailNotices : MailCache.Events {
    private val mini = MiniMessage.miniMessage()
    private val ICON_MODEL = NamespacedKey("lom", "icons/mailcarrier")

    override fun newMail(record: MailRecord) {
        Bukkit.getPluginManager().callEvent(NewMailEvent(record))
    }

    override fun unreadAtJoin(player: UUID, count: Int) {
        Bukkit.getPlayer(player)?.let { notify(it, count) }
    }

    /** A clickable chat line with the unread count, and a toast. Replaces MagicSpells' old `purchase-newmail`. */
    fun notify(player: Player, unread: Int) {
        player.sendMessage(mini.deserialize(
            "<hover:show_text:'<gold><<Click to open your mail!>>'><click:run_command:/mail>" +
                "<gold>You have <aqua><count></aqua> unread mail!",
            Placeholder.unparsed("count", unread.toString())
        ))
        val icon = ItemStack(Material.PAPER).apply { editMeta { it.itemModel = ICON_MODEL } }
        // The spaces push "/mail to access it!" onto the toast's second line.
        ToastUtil.show(player, icon, mini.deserialize("<gray>You got <gold>Mail!</gold>             /mail to access it!"))
    }
}
