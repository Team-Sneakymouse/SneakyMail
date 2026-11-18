package com.danidipp.sneakymail

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.CommandSender

/** Admin view of the cache: `/mailcheck` lists online players' unclaimed mail ids, `/mailcheck <id>` shows one mail. */
class MailcheckCommand : Command("mailcheck") {
    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
        return mutableListOf()
    }
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        val cache = SneakyMail.getInstance().cache
        if (!SneakyMail.getInstance().isEnabled || cache == null) {
            sender.sendMessage(Component.text("Mail is not available right now", NamedTextColor.RED))
            return true
        }
        val id = args.getOrNull(0)
        if (id != null) {
            val mail = id.toLongOrNull()?.let(cache::find)
            sender.sendMessage(mail?.toString() ?: "No cached unclaimed mail $id (only online players' mail is cached)")
            return true
        }

        val snapshot = cache.snapshot()
        sender.sendMessage("${snapshot.size} players cached, ${snapshot.values.sumOf { it.size }} unclaimed mails")
        for ((player, mail) in snapshot) {
            if (mail.isEmpty()) continue
            val name = Bukkit.getPlayer(player)?.name ?: player.toString()
            sender.sendMessage("$name: ${mail.joinToString(", ") { it.id.toString() }}")
        }
        return true
    }
}
