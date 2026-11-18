package com.danidipp.sneakymail

/**
 * Claim statements that are running, and claims that committed but whose rewards wait for the next tick, so that
 * onDisable can hand those out and report the rest. Entries are the attempts' own objects (identity), not mail ids.
 * No Bukkit; statements complete on SneakyDB threads, everything else runs on the server thread.
 */
internal class PendingClaims<A : Any> {
    private val running = LinkedHashSet<A>()
    private val committed = ArrayList<A>()
    private var stopped = false

    @Synchronized
    fun started(attempt: A) {
        running += attempt
    }

    /** The statement ended without claiming, or with an error. */
    @Synchronized
    fun ended(attempt: A) {
        running -= attempt
    }

    /** The statement claimed the mail. False after [shutdown]: nobody will hand the rewards out any more. */
    @Synchronized
    fun committed(attempt: A): Boolean {
        running -= attempt
        if (stopped) return false
        committed += attempt
        return true
    }

    /** The hand-out tick takes its attempt; false when [shutdown] took it already. */
    @Synchronized
    fun take(attempt: A): Boolean = committed.remove(attempt)

    /** Statements still running and committed claims not handed out yet; afterwards [committed] refuses. */
    @Synchronized
    fun shutdown(): Pair<List<A>, List<A>> {
        stopped = true
        val result = running.toList() to committed.toList()
        running.clear()
        committed.clear()
        return result
    }
}
