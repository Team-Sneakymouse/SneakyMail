package com.danidipp.sneakymail

import com.danidipp.sneakymail.api.SneakyMailProvider
import com.sneakydb.SneakyDBApi
import com.sneakydb.SneakyDBProvider
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor

class SneakyMail : JavaPlugin() {
    val mailKey = NamespacedKey(this, "mail")

    /** Set on the server thread once the tables are ready; null before, after a failed migration and after disable. */
    @Volatile var cache: MailCache? = null
        private set
    @Volatile var claims: MailClaims? = null
        private set

    /** Changes on every enable and disable, so work started by an earlier enable can tell it is stale. */
    @Volatile private var generation = 0L
    private var api: MailApiService? = null
    private var subscription: AutoCloseable? = null
    private var placeholders: Placeholders? = null

    override fun onLoad() {
        instance = this
        saveDefaultConfig()
    }

    override fun onEnable() {
        val generation = ++generation
        Bukkit.getServer().commandMap.registerAll(IDENTIFIER, listOf(MailCommand(), MailcheckCommand()))
        server.pluginManager.registerEvents(MailGUI.listener, this)

        // Installed now so callers find it during their enable; it refuses mail until the tables are ready.
        val api = MailApiService()
        this.api = api
        SneakyMailProvider.install(api)

        val db = try {
            SneakyDBProvider.getApi()
        } catch (error: IllegalStateException) {
            logger.severe("SneakyDB is not enabled; mail is not available.")
            return
        }
        // SneakyDB runs this jar's db/migrations scripts once every plugin has enabled, before players can connect.
        // Nothing touches the tables until the future completes; when it fails the plugin stays inert.
        val migrated = try {
            db.migrate(IDENTIFIER, javaClass)
        } catch (error: RuntimeException) {
            CompletableFuture.failedFuture(error)
        }
        migrated.whenComplete { version, failure ->
            if (generation != this.generation) return@whenComplete
            if (failure != null) {
                logger.severe("SneakyMail's tables are not usable, so mail is not available: ${failure.message}")
                return@whenComplete
            }
            // SneakyDB's thread: the rest starts on the server thread, which runs this once startup continues.
            try {
                server.scheduler.runTask(this, Runnable {
                    if (isEnabled && generation == this.generation) start(db, api, version)
                })
            } catch (_: IllegalPluginAccessException) {
                // Disabled before this completion could schedule.
            }
        }
    }

    private fun start(db: SneakyDBApi, api: MailApiService, version: Int) {
        val generation = generation
        val main = Executor { task ->
            try {
                if (isEnabled && generation == this.generation) server.scheduler.runTask(this, task)
            } catch (_: IllegalPluginAccessException) {
                // Disabled meanwhile: the cache is gone with it.
            }
        }
        val store = MailStore(db, logger)
        val cache = MailCache(store::unclaimedFor, main, MailNotices, logger)
        this.cache = cache
        claims = MailClaims(this, store, cache, logger)
        server.pluginManager.registerEvents(MailListener(cache), this)
        server.onlinePlayers.forEach { cache.track(it.uniqueId, announceUnread = false) }

        // Callbacks run on SneakyDB's notification thread. onConnected also runs after every reconnect, when
        // notifications may have been lost meanwhile.
        subscription = try {
            db.listen(IDENTIFIER, { payload ->
                val notification = MailNotification.parse(payload)
                if (notification == null) {
                    logger.warning("Ignoring an unknown notification on channel $IDENTIFIER")
                    return@listen
                }
                main.execute {
                    when (notification.event) {
                        MailNotification.Event.NEW -> cache.created(notification.id, notification.recipient)
                        MailNotification.Event.CLAIMED, MailNotification.Event.DELETED -> cache.gone(notification.id, notification.recipient)
                    }
                }
            }) { main.execute { cache.reloadAll() } }
        } catch (error: RuntimeException) {
            logger.severe("Could not listen for mail notifications (${error.javaClass.simpleName}); new mail shows only after a rejoin")
            null
        }

        api.ready(store)
        if (server.pluginManager.isPluginEnabled("PlaceholderAPI")) {
            placeholders = Placeholders().also { it.register() }
        }
        logger.info("SneakyMail ready (schema version $version).")
    }

    override fun onDisable() {
        ++generation
        // Players are still online here: committed claims get their rewards now.
        claims?.shutdown()
        subscription?.close()
        subscription = null
        placeholders?.unregister()
        placeholders = null
        api?.let {
            SneakyMailProvider.uninstall(it)
            it.close()
        }
        api = null
        cache = null
        claims = null

        // Close open GUIs
        MailGUI.openInventories.toList().forEach { it.close() }
    }

    companion object {
        const val IDENTIFIER = "sneakymail"
        const val AUTHORS = "Team Sneakymouse"
        const val VERSION = "1.0"
        private lateinit var instance: SneakyMail

        fun getInstance(): SneakyMail {
            return instance
        }
    }
}
