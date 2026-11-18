package com.danidipp.sneakymail

import com.sneakyitems.api.SneakyItemsAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin
import java.util.logging.Logger

/**
 * A player collecting a mail from the /mail GUI: checks on the server thread, the claim in the database, then the
 * rewards on the server thread. Rewards are handed out only when this server's claim statement returned them.
 *
 * Every claim logs a line before its statement and one after its redemption, so a crash in between can be found
 * in the log and reconciled against `claimed_at`. [shutdown] settles the claims still in flight.
 */
class MailClaims(
    private val plugin: Plugin,
    private val store: MailStore,
    private val cache: MailCache,
    private val logger: Logger,
) {
    private val inFlight = ClaimsInFlight()

    /**
     * Starts collecting mail [id] for [player] (server thread). False when the mail stays where it is: unknown, a claim
     * of it already running, an item reward that SneakyItems doesn't know, or not enough room for the items.
     */
    fun collect(player: Player, id: Long): Boolean {
        val uuid = player.uniqueId
        val mail = cache.unclaimed(uuid).firstOrNull { it.id == id }
        if (mail == null) {
            player.sendMessage(Component.text("You have already collected this mail", NamedTextColor.RED))
            return false
        }
        val missing = RewardItems.missing(mail.rewards) { SneakyItemsAPI.getItemStack(it) != null }
        if (missing.isNotEmpty()) {
            logger.warning("Mail $id for ${player.name} holds items SneakyItems doesn't know: ${missing.joinToString()}")
            player.sendMessage(Component.text("This mail holds an item that doesn't exist. Please tell staff", NamedTextColor.RED))
            return false
        }
        if (!hasRoom(player, templates(mail.rewards))) {
            player.sendMessage(Component.text("Make room in your inventory to collect this mail", NamedTextColor.RED))
            return false
        }
        // The guard is per mail and outlives the player's session: a claim still running from before a rejoin blocks.
        val claim = inFlight.start(id, uuid, player.name) ?: return false
        cache.beginClaim(uuid, id)
        logger.info("Claiming mail $id for ${player.name} ($uuid)")
        try {
            store.claim(id, uuid).whenComplete { rewards, error ->
                if (error != null) failed(claim, error) else finished(claim, rewards)
            }
        } catch (error: Exception) {
            failed(claim, error)
        }
        return true
    }

    /**
     * Server thread, from onDisable while players are still online: hands out claims whose rewards are known, and
     * reports claims whose statement is still running.
     */
    fun shutdown() {
        val (running, claimed) = inFlight.shutdown()
        for (claim in running) {
            logger.severe("Mail ${claim.id} of ${claim.name} (${claim.player}): its claim (started ${claim.startedAt}) was " +
                "still running when SneakyMail stopped, so it may be claimed without its rewards. ${CHECK_LOGS.format(claim.id)}")
        }
        for (claim in claimed) deliver(claim, claim.rewards!!)
    }

    /** SneakyDB thread. */
    private fun failed(claim: ClaimsInFlight.Claim, failure: Throwable) {
        val current = inFlight.failed(claim)
        val error = unwrap(failure)
        if (error is MailStore.ClaimTimedOut) {
            // Rolled back: the mail was definitely not claimed, so the player may simply try again.
            logger.warning("Claim of mail ${claim.id} for ${claim.name} (${claim.player}) timed out waiting for the row " +
                "(${error.cause?.javaClass?.simpleName}); nothing was claimed")
            if (!current) return
            onServerThread {
                cache.endClaim(claim.player, claim.id)
                Bukkit.getPlayer(claim.player)?.sendMessage(
                    Component.text("This mail is busy right now. Please try again in a moment", NamedTextColor.RED))
            }
            return
        }
        logger.severe("Claim of mail ${claim.id} for ${claim.name} (${claim.player}, started ${claim.startedAt}) failed " +
            "(${error.javaClass.simpleName}); it may still have committed. ${CHECK_LOGS.format(claim.id)}")
        if (!current) return
        onServerThread {
            cache.endClaim(claim.player, claim.id)
            Bukkit.getPlayer(claim.player)?.sendMessage(
                Component.text("An error occurred while collecting this mail. Please tell staff", NamedTextColor.RED))
        }
    }

    /** SneakyDB thread. */
    private fun finished(claim: ClaimsInFlight.Claim, rewards: List<MailReward>?) {
        when (inFlight.finished(claim, rewards)) {
            ClaimsInFlight.Outcome.NOTHING -> onServerThread {
                cache.endClaim(claim.player, claim.id)
                cache.gone(claim.id, claim.player)
                Bukkit.getPlayer(claim.player)?.sendMessage(Component.text("You have already collected this mail", NamedTextColor.RED))
            }
            // When this can't be scheduled the plugin is stopping, and shutdown() hands the rewards out.
            ClaimsInFlight.Outcome.HAND_OUT -> onServerThread {
                val taken = inFlight.take(claim) ?: return@onServerThread
                cache.endClaim(claim.player, claim.id)
                deliver(claim, taken)
            }
            ClaimsInFlight.Outcome.LATE_CLAIMED -> undo(claim, "its claim finished after SneakyMail stopped")
            ClaimsInFlight.Outcome.LATE_NOTHING ->
                logger.info("Mail ${claim.id} of ${claim.name}: the claim that was running at shutdown claimed nothing")
        }
    }

    private fun onServerThread(task: () -> Unit) {
        try {
            if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable(task))
        } catch (_: IllegalPluginAccessException) {
            // Disabled meanwhile.
        }
    }

    /** Server thread: the claim is this server's; hands out [rewards] or, when the player is gone, gives the mail back. */
    private fun deliver(claim: ClaimsInFlight.Claim, rewards: List<MailReward>) {
        // The same player object, or the new one when they left and came back meanwhile.
        val player = Bukkit.getPlayer(claim.player)
        if (player == null) {
            undo(claim, "${claim.name} left before the rewards were handed out")
            return
        }
        cache.gone(claim.id, claim.player)
        give(player, claim.id, rewards)
    }

    /** The claim committed but nothing was handed out: give the mail back so the next join shows it again. */
    private fun undo(claim: ClaimsInFlight.Claim, reason: String) {
        logger.warning("Mail ${claim.id} of ${claim.name} (${claim.player}) was claimed but $reason; giving it back")
        try {
            store.unclaim(claim.id, claim.player).whenComplete { _, error -> if (error != null) undoUncertain(claim, error) }
        } catch (error: Exception) {
            undoUncertain(claim, error)
        }
    }

    /**
     * The give-back failed or its reply was lost: it may have committed, and another server may have claimed and
     * handed out the mail since. Never tell staff to reset it blindly.
     */
    private fun undoUncertain(claim: ClaimsInFlight.Claim, error: Throwable) {
        logger.severe("Mail ${claim.id} of ${claim.name} (${claim.player}), claimed here at about ${claim.startedAt} without " +
            "its rewards being handed out: giving it back failed or its reply was lost (${error.javaClass.simpleName}), so " +
            "it may or may not be claimed now, and another server may have handed it out since. ${CHECK_LOGS.format(claim.id)}")
    }

    private companion object {
        /** What staff do with a claim whose outcome is unknown; never a blind reset of claimed_at or a blanket re-give. */
        const val CHECK_LOGS = "Staff: before touching claimed_at, search every server's log for \"mail %d \": the Claiming " +
            "line, one Gave or failed line per reward, and the Redeemed line. Give by hand only rewards that have no Gave " +
            "line on any server, after checking the game state where a reward shows (balance, inventory); never reset " +
            "claimed_at blindly"

        fun unwrap(error: Throwable): Throwable {
            var current = error
            while ((current is java.util.concurrent.CompletionException || current is java.util.concurrent.ExecutionException) &&
                current.cause != null) current = current.cause!!
            return current
        }
    }

    /**
     * Hands out every reward; one that fails is logged and the rest still run. Each reward given is logged before the
     * next one starts, so an interrupted redemption shows which rewards ran. The claim is never undone here.
     */
    private fun give(player: Player, id: Long, rewards: List<MailReward>) {
        val who = "${player.name} (${player.uniqueId})"
        val failed = RewardItems.giveAll(rewards, { _, reward -> giveOne(player, id, reward) },
            given = { index, reward -> logger.info("Gave reward ${index + 1}/${rewards.size} of mail $id to $who: ${MailRewards.encode(reward)}") },
        ) { index, reward, error ->
            val why = if (error != null) "${error.javaClass.simpleName}: ${error.message}" else "see the line above"
            logger.severe("Reward ${index + 1}/${rewards.size} of mail $id for $who failed ($why): ${MailRewards.encode(reward)}; " +
                "give it by hand after checking the game state")
        }
        if (failed > 0) {
            player.sendMessage(Component.text("Part of this mail could not be given. Please tell staff", NamedTextColor.RED))
        }
        logger.info("Redeemed mail $id for $who: ${rewards.size - failed} of ${rewards.size} rewards given")
    }

    private fun giveOne(player: Player, id: Long, reward: MailReward): Boolean = when (reward) {
        is MailReward.CommandReward -> {
            // Templates come from trusted senders: they run as console. The note is display text only.
            val command = reward.command.replace("{player}", player.name)
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command).also { ok ->
                if (!ok) logger.warning("Mail $id: the console did not run `$command` (unknown or refused command)")
            }
        }
        is MailReward.ItemReward -> {
            val item = SneakyItemsAPI.getByInternalName(reward.item)
            val maxStack = item?.itemStack?.maxStackSize
            if (item == null || maxStack == null) {
                logger.warning("Mail $id: SneakyItems no longer knows the item ${reward.item}")
                false
            } else {
                val stacks = RewardItems.stackAmounts(reward.amount, maxStack).mapNotNull { item.createFor(player, it) }
                for (leftover in player.inventory.addItem(*stacks.toTypedArray()).values) {
                    player.world.dropItem(player.location, leftover).owner = player.uniqueId
                }
                true
            }
        }
    }

    /** Plain copies of the item rewards' stacks, only to test whether they fit. */
    private fun templates(rewards: List<MailReward>): List<ItemStack> =
        rewards.filterIsInstance<MailReward.ItemReward>().flatMap { reward ->
            val template = SneakyItemsAPI.getItemStack(reward.item) ?: return@flatMap emptyList()
            RewardItems.stackAmounts(reward.amount, template.maxStackSize).map { amount -> template.clone().also { it.amount = amount } }
        }

    /** Whether [stacks] all fit into [player]'s main inventory, tried on a copy of it. */
    private fun hasRoom(player: Player, stacks: List<ItemStack>): Boolean {
        if (stacks.isEmpty()) return true
        val copy = Bukkit.createInventory(null, 36)
        copy.storageContents = player.inventory.storageContents.map { it?.clone() }.toTypedArray()
        return copy.addItem(*stacks.toTypedArray()).isEmpty()
    }
}
