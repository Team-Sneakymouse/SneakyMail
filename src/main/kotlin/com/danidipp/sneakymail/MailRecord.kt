@file:Suppress("PROVIDED_RUNTIME_TOO_LOW")
package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.PocketbaseProvider
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.UUID

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
        val originalRecipientUuid = recipient_uuid
        val resolvedRecipientUuid = resolveRecipientUuid(
            originalRecipientUuid,
            recipient_name
        ) { name ->
            Bukkit.getOfflinePlayerIfCached(name)?.uniqueId
        }

        recipient_uuid = resolvedRecipientUuid?.toString()
        if (resolvedRecipientUuid != null) return

        if (originalRecipientUuid.isMissingRecipientUuid()) {
            SneakyMail.getInstance().logger.warning(
                "Mail $id: recipient_uuid is missing and player with name $recipient_name was not found"
            )
        } else {
            SneakyMail.getInstance().logger.warning(
                "Mail $id: failed to parse recipient_uuid $originalRecipientUuid"
            )
        }
    }

    fun recipientUuidOrNull(): UUID? = recipientUuidOrNull(recipient_uuid)

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
                    player.closeInventory()
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
                        // Templates must come from trusted reward producers: they run with console privileges.
                        // Mail notes and other player-supplied text are display data, not command input.
                        val command = reward.command.replace("{player}", player.name)
                        SneakyMail.getInstance().logger.info("Redeeming ${player.name}'s mail $id with command: $command")
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)
                    }
                }
            })
        })
    }

}

