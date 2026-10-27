package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.PocketbaseProvider
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.ConcurrentHashMap

class SneakyMail : JavaPlugin() {
    val mailKey = NamespacedKey(this, "mail")
    private var mailRuntime: MailRuntime? = null
    lateinit var mailSender: MailSender

    override fun onLoad() {
        instance = this
        saveDefaultConfig()
    }

    override fun onEnable() {
        val pocketbase = PocketbaseProvider.getApi()
        mailSender = MailSender(logger, pocketbase)
        mailRuntime = MailRuntime(com.sneakydb.SneakyDBProvider.getApi(), pocketbase, logger)

        Bukkit.getServer().commandMap.registerAll(IDENTIFIER, listOf(
            MailCommand(),
            MailcheckCommand()
        ))

        Bukkit.getServer().pluginManager.registerEvents(NewMailEvent.listener, this)
        Bukkit.getServer().pluginManager.registerEvents(PocketbaseListener(logger, pocketbase), this)
        Bukkit.getServer().pluginManager.registerEvents(MailGUI.listener, this)


        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            Placeholders().register();
        }
    }

    override fun onDisable() {
        mailRuntime?.close()
        mailRuntime = null
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
