package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.SneakyPocketbase
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

class SneakyMail : JavaPlugin() {
    val mailKey = NamespacedKey(this, "mail")
    lateinit var mailSender: MailSender

    override fun onLoad() {
        instance = this
        saveDefaultConfig()
    }

    override fun onEnable() {
        mailSender = MailSender(logger, SneakyPocketbase.getInstance())

        Bukkit.getServer().commandMap.registerAll(IDENTIFIER, listOf(
            MailCommand(),
            MailcheckCommand()
        ))

        Bukkit.getServer().pluginManager.registerEvents(NewMailEvent.listener, this)
        Bukkit.getServer().pluginManager.registerEvents(PocketbaseListener(logger, SneakyPocketbase.getInstance()), this)
        Bukkit.getServer().pluginManager.registerEvents(MailGUI.listener, this)


        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            Placeholders().register();
        }
    }

    override fun onDisable() {
        logger.warning("Disabling SneakyMail")

        // Close open GUIs
        MailGUI.openInventories.forEach { it.close() }
    }

    companion object {
        const val IDENTIFIER = "sneakymail"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0"
        private lateinit var instance: SneakyMail
        val mail: ConcurrentHashMap<String, MailRecord> = ConcurrentHashMap()

        fun getInstance(): SneakyMail {
            return instance
        }
    }
}