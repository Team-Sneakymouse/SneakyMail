@file:Suppress("PROVIDED_RUNTIME_TOO_LOW")
package com.danidipp.sneakymail

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

/** One reward of a mail, as stored in `sneakymail.mail.rewards` (the database's `rewards_valid` defines the shape). */
@Serializable
sealed class MailReward {
    /** [amount] copies of the SneakyItems item with internal name [item]. */
    @Serializable
    @SerialName("item")
    data class ItemReward(val item: String, val amount: Int) : MailReward()

    /** A console command; `{player}` becomes the recipient's name. Only trusted senders write these. */
    @Serializable
    @SerialName("command")
    data class CommandReward(val command: String) : MailReward()
}

/** An unclaimed mail as the plugin caches it. [id] is `sneakymail.mail.id`. */
data class MailRecord(
    val id: Long,
    val recipientUuid: UUID,
    val senderName: String,
    val note: String,
    val rewards: List<MailReward>,
)

/** JSON of a `rewards` array. */
object MailRewards {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(MailReward.serializer())

    fun parse(text: String): List<MailReward> = json.decodeFromString(serializer, text)

    fun encode(rewards: List<MailReward>): String = json.encodeToString(serializer, rewards)

    /** One reward as its JSON object, for logs. */
    fun encode(reward: MailReward): String = json.encodeToString(MailReward.serializer(), reward)
}
