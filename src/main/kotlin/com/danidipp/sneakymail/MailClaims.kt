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
        if (!cache.beginClaim(uuid, id)) return false
        logger.info("Claiming mail $id for ${player.name} ($uuid)")
        val claim = inFlight.start(id, uuid, player.name)
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
            logger.severe("Mail ${claim.id} of ${claim.name} (${claim.player}): its claim was still running when SneakyMail " +
                "stopped, so it may be claimed without its rewards. Staff: check claimed_at of mail ${claim.id}")
        }
        for (claim in claimed) deliver(claim, claim.rewards!!)
    }

    /** SneakyDB thread. */
    private fun failed(claim: ClaimsInFlight.Claim, error: Throwable) {
        val current = inFlight.failed(claim)
        logger.severe("Claim of mail ${claim.id} for ${claim.name} (${claim.player}) failed (${error.javaClass.simpleName}); " +
            "it may still have committed. Staff: check claimed_at of mail ${claim.id}")
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
            store.unclaim(claim.id, claim.player).whenComplete { _, error -> if (error != null) undoFailed(claim) }
        } catch (_: Exception) {
            undoFailed(claim)
        }
    }

    private fun undoFailed(claim: ClaimsInFlight.Claim) {
        logger.severe("Mail ${claim.id} of ${claim.name} (${claim.player}) is claimed but its rewards were not handed out " +
            "and it could not be given back. Staff: set claimed_at of mail ${claim.id} to null")
    }

    /** Hands out every reward; one that fails is logged and the rest still run. The claim is never undone here. */
    private fun give(player: Player, id: Long, rewards: List<MailReward>) {
        val failed = RewardItems.giveAll(rewards, { giveOne(player, id, it) }) { reward, error ->
            // giveOne logs why it returned false; an exception is logged here.
            if (error != null) logger.severe("Mail $id of ${player.name} (${player.uniqueId}): reward ${MailRewards.encode(reward)} " +
                "failed (${error.javaClass.simpleName}: ${error.message}); give it by hand")
        }
        if (failed > 0) {
            player.sendMessage(Component.text("Part of this mail could not be given. Please tell staff", NamedTextColor.RED))
        }
        logger.info("Redeemed mail $id for ${player.name} (${player.uniqueId}): ${rewards.size - failed} of ${rewards.size} rewards given")
    }

    private fun giveOne(player: Player, id: Long, reward: MailReward): Boolean = when (reward) {
        is MailReward.CommandReward -> {
            // Templates come from trusted senders: they run as console. The note is display text only.
            val command = reward.command.replace("{player}", player.name)
            logger.info("Redeeming ${player.name}'s mail $id with command: $command")
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command).also { ok ->
                if (!ok) logger.severe("Mail $id of ${player.name} (${player.uniqueId}): reward ${MailRewards.encode(reward)} " +
                    "failed (the command was not found or refused); give it by hand")
            }
        }
        is MailReward.ItemReward -> {
            val item = SneakyItemsAPI.getByInternalName(reward.item)
            val maxStack = item?.itemStack?.maxStackSize
            if (item == null || maxStack == null) {
                logger.severe("Mail $id of ${player.name} (${player.uniqueId}): reward ${MailRewards.encode(reward)} failed " +
                    "(SneakyItems no longer knows the item); give it by hand")
                false
            } else {
                logger.info("Redeeming ${player.name}'s mail $id with item: ${reward.item} x${reward.amount}")
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
