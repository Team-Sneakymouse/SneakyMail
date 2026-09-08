package com.danidipp.sneakymail

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender

class MailcheckCommand : Command("mailcheck") {
    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
        return mutableListOf()
    }
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        if (!SneakyMail.getInstance().isEnabled) {
            sender.sendMessage(Component.text("Plugin is disabled", NamedTextColor.RED))
            return true
        }
        val id = args.getOrNull(0)
        if (id != null){
            val mail = SneakyMail.mail[id]
            sender.sendMessage(mail.toString())
            return true
        }

        // sets of 3
        for (mail in SneakyMail.mail.values.chunked(3)){
            sender.sendMessage(mail.joinToString(", ") { "${it.id}: ${it.available}" })
        }
        return true
    }
}
