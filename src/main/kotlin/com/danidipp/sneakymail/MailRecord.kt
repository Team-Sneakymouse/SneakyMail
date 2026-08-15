@file:Suppress("PROVIDED_RUNTIME_TOO_LOW")
package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.PocketbaseProvider
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID
import kotlin.reflect.typeOf

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
    @SerialName("id") val id: String? = null,
    var sender_uuid: String?,
    var sender_name: String,
    var recipient_uuid: String?,
    var recipient_name: String?,
    var rewards: List<MailReward>?,
    var available: Boolean,
    var note: String
) {
    fun checkUuid() {

        if (recipient_uuid.isNullOrEmpty() || recipient_uuid == "null") {
            if (recipient_name?.isNotEmpty() ?: false) {
                val player = Bukkit.getOfflinePlayerIfCached(recipient_name!!)
                recipient_uuid = player?.uniqueId?.toString()
            }
        }
        if (recipient_uuid == null) {
            SneakyMail.getInstance().logger.warning("Mail $id: recipient_uuid is null and player with name $recipient_name not found")
            return
        }
        // add dashes if missing
        if (recipient_uuid!!.matches("^[0-9a-fA-F]{32}$".toRegex())) {
            recipient_uuid = recipient_uuid!!.replaceFirst(
                "(.{8})(.{4})(.{4})(.{4})(.{12})".toRegex(),
                "$1-$2-$3-$4-$5"
            )
        } else {
            try {
                UUID.fromString(recipient_uuid)
            } catch (e: IllegalArgumentException) {
                SneakyMail.getInstance().logger.warning("Mail $id: failed to parse recipient_uuid $recipient_uuid (${if(recipient_uuid == null) "null" else "string"}): ${e.message}")
            }
        }
    }

    fun redeem(player: Player) {
        if (!available) {
            player.sendMessage(Component.text("You have already collected this mail", NamedTextColor.RED))
            return
        }
        available = false
        val id = this.id!!
        val data = PocketbaseJson.encodeUpdate(this)
        Bukkit.getScheduler().runTaskAsynchronously(SneakyMail.getInstance(), Runnable {
            val updatedRecord = try {
                Json { ignoreUnknownKeys = true }.decodeFromString<MailRecord>(
                    PocketbaseProvider.getApi().update("lom2_mail", id, data).join()
                )
            } catch (e: Exception) {
                SneakyMail.getInstance().logger.warning("Failed to update mail record $id: ${e.message}")
                Bukkit.getScheduler().runTask(SneakyMail.getInstance(), Runnable {
                    available = true
                    player.sendMessage(Component.text("An error occurred while redeeming this mail. Please tell Dani", NamedTextColor.RED))
                })
                return@Runnable
            }
            rewards = updatedRecord.rewards

            Bukkit.getScheduler().runTask(SneakyMail.getInstance(), Runnable {
                if (rewards == null) {
                    player.sendMessage(Component.text("This mail has no rewards", NamedTextColor.RED))
                    return@Runnable
                }
                for (reward in rewards ?: emptyList()) when (reward) {
                    is MailReward.ItemReward ->
                        player.sendMessage(Component.text("Item rewards are not implemented yet", NamedTextColor.RED))
                    is MailReward.CommandReward -> {
                        val command = reward.command.replace("{player}", player.name)
                        SneakyMail.getInstance().logger.info("Redeeming ${player.name}'s mail $id with command: $command")
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                    }
                }
            })
        })
    }

    fun validRecipient(): Boolean {
        return recipient_uuid != null && recipient_uuid!!.isNotEmpty()
    }
}

