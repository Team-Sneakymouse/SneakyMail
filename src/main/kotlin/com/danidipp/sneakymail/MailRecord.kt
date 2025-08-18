@file:Suppress("PROVIDED_RUNTIME_TOO_LOW")
package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.BaseRecord
import com.danidipp.sneakypocketbase.PBRunnable
import com.danidipp.sneakypocketbase.SneakyPocketbase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player

@Serializable
sealed class MailReward {
    @Serializable
    @SerialName("item")
    data class ItemReward(
        val item: String,
        val amount: Int
    ): MailReward()

    @Serializable
    @SerialName("command")
    data class CommandReward(
        val command: String,
    ): MailReward()
}

@Serializable
data class MailRecord(
    var sender_uuid: String,
    var sender_name: String,
    var recipient_uuid: String,
    var recipient_name: String,
    var rewards: List<MailReward>?,
    var available: Boolean,
    var note: String
): BaseRecord() {
    fun checkUuid() {
        if (recipient_uuid.isEmpty()) {
            if ( recipient_name.isNotEmpty()) {
                val player = Bukkit.getOfflinePlayerIfCached(recipient_name)
                recipient_uuid = player?.uniqueId.toString()
            }
        } else if (!recipient_uuid.contains('-')) {
            recipient_uuid = recipient_uuid.replaceFirst(
                "(.{8})(.{4})(.{4})(.{4})(.{12})".toRegex(),
                "$1-$2-$3-$4-$5"
            )
        }
    }

    fun redeem(player: Player): Boolean {
        if (!available) {
            player.sendMessage("You have already collected this mail")
            return false
        }
        available = false
        val id = this.id!!
        val data = Json.encodeToString(serializer(), this)
        Bukkit.getScheduler().runTaskAsynchronously(SneakyMail.getInstance(), PBRunnable {
            SneakyPocketbase.getInstance().pb().records.update<MailRecord>("lom2_mail", id, data)
        })
        if (rewards == null) {
            player.sendMessage(Component.text("This mail has no rewards", NamedTextColor.RED))
            return false
        }
        for (reward in rewards ?: emptyList()) {
            when (reward) {
                is MailReward.ItemReward -> {
                    player.sendMessage(Component.text("Item rewards are not implemented yet", NamedTextColor.RED))
                    return false
                }
                is MailReward.CommandReward -> {
                    val command = reward.command.replace("{player}", player.name)
                    SneakyMail.getInstance().logger.info("Redeeming ${player.name}'s mail $id with command: $command")
                    Bukkit.getServer().dispatchCommand(Bukkit.getConsoleSender(), command)
                }
            }
        }
        return true
    }
}

