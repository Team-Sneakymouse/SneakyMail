package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.SneakyPocketbase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.clip.placeholderapi.PlaceholderAPI
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.OfflinePlayer
import java.util.logging.Logger

class MailSender(private val logger: Logger, val sneakyPB: SneakyPocketbase) {
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
        SneakyPocketbase.asyncScope.launch(Dispatchers.IO) {
            sneakyPB.pb().records.create<MailRecord>("lom2_mail", record.toJson(MailRecord.serializer()))
        }
    }
}