package com.danidipp.sneakymail

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

class MailCommand : Command("mail") {
    override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): MutableList<String> {
        return mutableListOf()
    }
    override fun execute(sender: CommandSender, commandLabel: String, args: Array<out String>): Boolean {
        if (!SneakyMail.getInstance().isEnabled) {
            sender.sendMessage(Component.text("Plugin is disabled", NamedTextColor.RED))
            return true
        }
        if (sender !is Player) {
            sender.sendMessage("You must be a player to use this command")
            return true
        }
        sender.openInventory(MailGUI(sender).inventory)
        return true
    }
}
