package com.danidipp.sneakymail

import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.logging.Logger

/**
 * Unclaimed mail of the players who are online, loaded on join and dropped on quit. No Bukkit.
 *
 * Every method except [unreadCount] runs on the server thread, and [main] runs load results there. [unreadCount]
 * may be called from any thread (PlaceholderAPI does).
 *
 * The database decides: every change notification reloads the recipient. Loads for one player never overlap (a reload
 * asked for while one runs follows it), so results apply in order. A mail that was claimed or deleted while a load was
 * running stays out of that load's result, which may have been read before the claim; the load after it, read after
 * the claim committed, decides again, so a mail that was given back (unclaimed) shows up again.
 */
class MailCache(
    private val load: (UUID) -> CompletableFuture<List<MailRecord>>,
    private val main: Executor,
    private val events: Events,
    private val logger: Logger,
) {
    interface Events {
        /** A mail sent while its recipient is online has arrived. */
        fun newMail(record: MailRecord)

        /** The first load after [player] joined found [count] unclaimed mails (only when it announced no new mail). */
        fun unreadAtJoin(player: UUID, count: Int)
    }

    private class Player(val announceAtJoin: Boolean) {
        @Volatile var mail: Map<Long, MailRecord> = emptyMap()
        var loaded = false
        var loading = false
        var reloadAfter = false
        /** Ids of "new" notifications whose mail the next load should announce. */
        val announce = mutableSetOf<Long>()
        /** Ids claimed or deleted since the running load started: that load's result may still hold them. */
        val goneDuringLoad = mutableSetOf<Long>()
        /** Ids whose claim is running. */
        val claiming = mutableSetOf<Long>()
    }

    private val players = ConcurrentHashMap<UUID, Player>()

    /** Starts caching [player]'s mail. [announceUnread]: tell [Events.unreadAtJoin] after the first load. */
    fun track(player: UUID, announceUnread: Boolean) {
        if (players.containsKey(player)) return
        val state = Player(announceUnread)
        players[player] = state
        requestLoad(player, state)
    }

    fun untrack(player: UUID) {
        players.remove(player)
    }

    fun tracked(): Set<UUID> = players.keys.toSet()

    /** Reads every tracked player's mail again (after the notification connection was down). */
    fun reloadAll() {
        players.forEach { (player, state) -> requestLoad(player, state) }
    }

    /** Reads [player]'s mail again, e.g. after an "unclaimed" notification. */
    fun reload(player: UUID) {
        val state = players[player] ?: return
        requestLoad(player, state)
    }

    /** A "new" notification: mail [id] was inserted for [recipient]. */
    fun created(id: Long, recipient: UUID) {
        val state = players[recipient] ?: return
        state.announce += id
        requestLoad(recipient, state)
    }

    /**
     * A "claimed" or "deleted" notification, or this server's own claim: mail [id] is no longer claimable. It leaves
     * the cache at once and the recipient is read again.
     */
    fun gone(id: Long, recipient: UUID) {
        val state = players[recipient] ?: return
        state.announce -= id
        if (state.loading) state.goneDuringLoad += id
        if (id in state.mail) state.mail = state.mail - id
        requestLoad(recipient, state)
    }

    /** False when a claim of [id] is already running (a double click). */
    fun beginClaim(player: UUID, id: Long): Boolean = players[player]?.claiming?.add(id) ?: false

    fun endClaim(player: UUID, id: Long) {
        players[player]?.claiming?.remove(id)
    }

    /** [player]'s unclaimed mail, oldest first, without mail whose claim is running. */
    fun unclaimed(player: UUID): List<MailRecord> {
        val state = players[player] ?: return emptyList()
        val claiming = state.claiming.toSet()
        return state.mail.values.filter { it.id !in claiming }.sortedBy { it.id }
    }

    fun unreadCount(player: UUID): Int = players[player]?.mail?.size ?: 0

    fun find(id: Long): MailRecord? = players.values.firstNotNullOfOrNull { it.mail[id] }

    /** Every tracked player's unclaimed mail, for /mailcheck. */
    fun snapshot(): Map<UUID, List<MailRecord>> = players.mapValues { (_, state) -> state.mail.values.sortedBy { it.id } }

    private fun requestLoad(player: UUID, state: Player) {
        if (state.loading) {
            state.reloadAfter = true
            return
        }
        state.loading = true
        state.goneDuringLoad.clear()
        val future = try {
            load(player)
        } catch (error: Exception) {
            CompletableFuture.failedFuture(error)
        }
        future.whenComplete { rows, error -> main.execute { applyLoad(player, state, rows, error) } }
    }

    private fun applyLoad(player: UUID, state: Player, rows: List<MailRecord>?, error: Throwable?) {
        state.loading = false
        if (players[player] !== state) return // quit meanwhile (and maybe joined again with a new state)
        if (error != null || rows == null) {
            logger.warning("Could not load mail of $player: ${error?.javaClass?.simpleName}")
        } else {
            // A mail gone during this load is followed by another load (gone() asked for it), which decides for good.
            state.mail = rows.filter { it.id !in state.goneDuringLoad }.associateBy { it.id }
            var announced = 0
            for (id in state.announce.toList()) {
                val record = state.mail[id] ?: continue
                state.announce -= id
                announced++
                events.newMail(record)
            }
            if (!state.loaded) {
                state.loaded = true
                if (state.announceAtJoin && announced == 0 && state.mail.isNotEmpty()) events.unreadAtJoin(player, state.mail.size)
            }
            // A "new" notification arrives after its insert committed and asks for a load, so the last load in a row
            // has seen every announced mail: whatever it didn't find was claimed or deleted already.
            if (!state.reloadAfter) state.announce.clear()
        }
        state.goneDuringLoad.clear()
        if (state.reloadAfter) {
            state.reloadAfter = false
            requestLoad(player, state)
        }
    }
}
