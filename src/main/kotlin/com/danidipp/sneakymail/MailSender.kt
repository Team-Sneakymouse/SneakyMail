package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.PocketbaseApi
import kotlinx.serialization.json.Json
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.OfflinePlayer
import java.util.logging.Logger

class MailSender(private val logger: Logger, val pocketbase: PocketbaseApi) {
    public fun sendMail(sender: OfflinePlayer, recipient: OfflinePlayer, note: TextComponent, reward: MailReward) {
        var characterName = PlaceholderAPI.setPlaceholders(sender, "%sneakycharacters_character_name%")
        if (characterName == "%sneakycharacters_character_name%") characterName = sender.name ?: "Unknown";
        createMailAsync(MailRecord(
            sender_name = characterName,
            sender_uuid = sender.uniqueId.toString(),
            recipient_name = "",
            recipient_uuid = recipient.uniqueId.toString(),
            available = true,
            note = MiniMessage.miniMessage().serialize(note),
            rewards = listOf(reward),
        ))
    }

    fun createMailAsync(record: MailRecord) {
        pocketbase.create("lom2_mail", PocketbaseJson.encodeCreate(record)).exceptionally {
            logger.warning("Failed to create mail record: ${it.message}")
            null
        }
    }
}
