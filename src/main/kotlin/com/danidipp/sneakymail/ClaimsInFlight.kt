package com.danidipp.sneakymail

import java.time.Instant
import java.util.UUID

/**
 * Claims between the claim statement and the hand-out of their rewards, so that a shutdown neither loses rewards nor
 * leaves a claim unexplained. No Bukkit. The claim statement completes on a SneakyDB thread; everything else runs on
 * the server thread.
 *
 * It lives as long as the plugin's enable, not a player's session: at most one attempt per mail is unsettled, also
 * across a reconnect, and an attempt leaves only when it settles itself.
 */
internal class ClaimsInFlight {
    enum class State { RUNNING, CLAIMED, SETTLED, ABANDONED }

    /** What the claim's statement returned means now. */
    enum class Outcome {
        /** The rewards are this claim's: hand them out on the server thread. */
        HAND_OUT,
        /** Nothing was claimed (already collected, deleted, someone else's). */
        NOTHING,
        /** The plugin stopped while the statement ran, and it did claim the mail: give it back. */
        LATE_CLAIMED,
        /** The plugin stopped while the statement ran, and it claimed nothing. */
        LATE_NOTHING,
    }

    class Claim(val id: Long, val player: UUID, val name: String, val startedAt: Instant) {
        var state = State.RUNNING
            internal set
        var rewards: List<MailReward>? = null
            internal set
    }

    private val claims = LinkedHashMap<Long, Claim>()

    /** A new attempt for mail [id], or null while an earlier attempt for it is unsettled (a double click, a rejoin). */
    @Synchronized
    fun start(id: Long, player: UUID, name: String, now: Instant = Instant.now()): Claim? {
        if (claims.containsKey(id)) return null
        return Claim(id, player, name, now).also { claims[id] = it }
    }

    /** The statement returned [rewards] (null: no row). */
    @Synchronized
    fun finished(claim: Claim, rewards: List<MailReward>?): Outcome {
        if (claim.state == State.ABANDONED) return if (rewards != null) Outcome.LATE_CLAIMED else Outcome.LATE_NOTHING
        if (rewards == null) {
            settle(claim)
            return Outcome.NOTHING
        }
        claim.rewards = rewards
        claim.state = State.CLAIMED
        return Outcome.HAND_OUT
    }

    /** The statement failed. False when the plugin stopped meanwhile (the shutdown already reported it). */
    @Synchronized
    fun failed(claim: Claim): Boolean {
        if (claim.state == State.ABANDONED) return false
        settle(claim)
        return true
    }

    /** The hand-out takes the rewards, once; null when the shutdown took them already. */
    @Synchronized
    fun take(claim: Claim): List<MailReward>? {
        if (claim.state != State.CLAIMED) return null
        settle(claim)
        return claim.rewards
    }

    /**
     * At shutdown: claims whose statement still runs become abandoned (reported, and given back when their statement
     * still claims), and claims whose rewards are known but not handed out are taken for the caller to settle.
     */
    @Synchronized
    fun shutdown(): Pair<List<Claim>, List<Claim>> {
        val running = claims.values.filter { it.state == State.RUNNING }
        val claimed = claims.values.filter { it.state == State.CLAIMED }
        running.forEach { it.state = State.ABANDONED }
        claimed.forEach { it.state = State.SETTLED }
        claims.clear()
        return running to claimed
    }

    @Synchronized
    fun size(): Int = claims.size

    private fun settle(claim: Claim) {
        claim.state = State.SETTLED
        claims.remove(claim.id, claim) // exactly this attempt
    }
}
