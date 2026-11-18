package com.danidipp.sneakymail

import com.sneakyitems.api.SneakyItemsAPI
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.IllegalPluginAccessException
import org.bukkit.plugin.Plugin
import java.util.UUID
import java.util.concurrent.CompletionException
import java.util.logging.Logger

/**
 * A player collecting a mail from the /mail GUI: checks on the server thread, the claim in the database, then the
 * rewards on the server thread at the next tick. The claim statement lets exactly one claim win on any server; a claim
 * is never undone. Every claim logs a line before its statement and one line per reward given, so staff can tell
 * from the logs what a claim handed out (docs/mail.md).
 */
class MailClaims(
    private val plugin: Plugin,
    private val store: MailStore,
    private val cache: MailCache,
    private val logger: Logger,
) {
    /** One claim attempt; compared by identity. */
    private class Attempt(val id: Long, val player: UUID, val name: String) {
        var rewards: List<MailReward> = emptyList()
        override fun toString() = "mail $id of $name ($player)"
    }

    private val pending = PendingClaims<Attempt>()

    /**
     * Starts collecting mail [id] for [player] (server thread). False when the mail stays where it is: unknown, an item
     * reward that SneakyItems doesn't know, or not enough room for the items.
     */
    fun collect(player: Player, id: Long): Boolean {
        val mail = cache.unclaimed(player.uniqueId).firstOrNull { it.id == id }
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
        val attempt = Attempt(id, player.uniqueId, player.name)
        logger.info("Claiming mail $id for ${player.name} (${player.uniqueId})")
        pending.started(attempt)
        try {
            store.claim(id, player.uniqueId).whenComplete { rewards, error -> claimed(attempt, rewards, error) }
        } catch (error: Exception) {
            claimed(attempt, null, error)
        }
        return true
    }

    /** onDisable, server thread, players still online: hands out committed claims and reports running statements. */
    fun shutdown() {
        val (running, committed) = pending.shutdown()
        for (attempt in running) {
            logger.severe("Possibly claimed, $attempt: the claim statement was still running when SneakyMail stopped. $STAFF")
        }
        for (attempt in committed) handOut(attempt)
    }

    /** SneakyDB thread. */
    private fun claimed(attempt: Attempt, rewards: List<MailReward>?, failure: Throwable?) {
        if (failure != null) {
            pending.ended(attempt)
            val error = if (failure is CompletionException && failure.cause != null) failure.cause!! else failure
            if (error is MailStore.ClaimTimedOut) {
                logger.warning("Claim of $attempt timed out waiting for the row; nothing was claimed")
                tell(attempt, "This mail is busy right now. Please try again in a moment")
            } else {
                logger.severe("Possibly claimed, $attempt: the claim statement failed (${error.javaClass.simpleName}). $STAFF")
                tell(attempt, "An error occurred while collecting this mail. Please tell staff")
            }
            return
        }
        if (rewards == null) {
            pending.ended(attempt)
            onServerThread {
                cache.gone(attempt.id, attempt.player)
                Bukkit.getPlayer(attempt.player)?.sendMessage(Component.text("You have already collected this mail", NamedTextColor.RED))
            }
            return
        }
        attempt.rewards = rewards
        if (!pending.committed(attempt)) {
            notGiven(attempt, "the claim finished after SneakyMail stopped")
            return
        }
        // When this can't be scheduled the plugin is stopping, and shutdown() hands the rewards out.
        onServerThread { if (pending.take(attempt)) handOut(attempt) }
    }

    /** Server thread. */
    private fun handOut(attempt: Attempt) {
        cache.gone(attempt.id, attempt.player)
        // The same player object, or the new one when they left and came back meanwhile.
        val player = Bukkit.getPlayer(attempt.player)
        if (player == null) notGiven(attempt, "${attempt.name} was offline when the rewards were due")
        else give(player, attempt.id, attempt.rewards)
    }

    private fun notGiven(attempt: Attempt, why: String) {
        logger.severe("Claimed, rewards not given, $attempt: $why. Rewards: ${MailRewards.encode(attempt.rewards)}. $STAFF")
    }

    private fun tell(attempt: Attempt, message: String) = onServerThread {
        Bukkit.getPlayer(attempt.player)?.sendMessage(Component.text(message, NamedTextColor.RED))
    }

    private fun onServerThread(task: () -> Unit) {
        try {
            if (plugin.isEnabled) Bukkit.getScheduler().runTask(plugin, Runnable(task))
        } catch (_: IllegalPluginAccessException) {
            // Disabled meanwhile.
        }
    }

    /**
     * Hands out every reward; one that fails is logged and the rest still run. Each reward given is logged before the
     * next one starts, so an interrupted redemption shows which rewards ran.
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

    private companion object {
        const val STAFF = "Staff: search every server's log for this mail's Gave lines and give by hand only rewards " +
            "without one (see docs/mail.md); never reset claimed_at"
    }
}
