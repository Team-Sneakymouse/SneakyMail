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
 */
class MailClaims(
    private val plugin: Plugin,
    private val store: MailStore,
    private val cache: MailCache,
    private val logger: Logger,
) {
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
        store.claim(id, uuid).whenComplete { rewards, error -> claimed(player, id, rewards, error) }
        return true
    }

    /** Runs on a SneakyDB thread. */
    private fun claimed(player: Player, id: Long, rewards: List<MailReward>?, error: Throwable?) {
        val uuid = player.uniqueId
        val handOut = Runnable {
            cache.endClaim(uuid, id)
            when {
                error != null -> {
                    logger.warning("Could not claim mail $id for ${player.name}: ${error.javaClass.simpleName}")
                    player.sendMessage(Component.text("An error occurred while collecting this mail. Please tell staff", NamedTextColor.RED))
                }
                rewards == null -> {
                    cache.gone(id, uuid)
                    player.sendMessage(Component.text("You have already collected this mail", NamedTextColor.RED))
                }
                else -> {
                    // The same player object, or the new one when they left and came back meanwhile.
                    val online = Bukkit.getPlayer(uuid)
                    if (online == null) {
                        undo(player, id)
                    } else {
                        cache.gone(id, uuid)
                        give(online, id, rewards)
                    }
                }
            }
        }
        try {
            if (!plugin.isEnabled) throw IllegalPluginAccessException("disabled")
            Bukkit.getScheduler().runTask(plugin, handOut)
        } catch (_: IllegalPluginAccessException) {
            if (error == null && rewards != null) undo(player, id)
        }
    }

    /** The claim committed but nothing was handed out: give the mail back so the next join shows it again. */
    private fun undo(player: Player, id: Long) {
        logger.warning("Mail $id was claimed while ${player.name} left or the plugin stopped; returning it")
        store.unclaim(id, player.uniqueId).whenComplete { _, error ->
            if (error != null) logger.severe("Mail $id of ${player.name} (${player.uniqueId}) was claimed but not handed out; resend its rewards by hand")
        }
    }

    private fun give(player: Player, id: Long, rewards: List<MailReward>) {
        for (reward in rewards) when (reward) {
            is MailReward.CommandReward -> {
                // Templates come from trusted senders: they run as console. The note is display text only.
                val command = reward.command.replace("{player}", player.name)
                logger.info("Redeeming ${player.name}'s mail $id with command: $command")
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
            }
            is MailReward.ItemReward -> {
                val item = SneakyItemsAPI.getByInternalName(reward.item)
                val maxStack = item?.itemStack?.maxStackSize
                if (item == null || maxStack == null) {
                    logger.severe("Mail $id of ${player.name} was claimed, but item ${reward.item} x${reward.amount} no longer exists; give it by hand")
                    player.sendMessage(Component.text("An item of this mail could not be given. Please tell staff", NamedTextColor.RED))
                    continue
                }
                logger.info("Redeeming ${player.name}'s mail $id with item: ${reward.item} x${reward.amount}")
                val stacks = RewardItems.stackAmounts(reward.amount, maxStack).mapNotNull { item.createFor(player, it) }
                for (leftover in player.inventory.addItem(*stacks.toTypedArray()).values) {
                    player.world.dropItem(player.location, leftover).owner = player.uniqueId
                }
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
