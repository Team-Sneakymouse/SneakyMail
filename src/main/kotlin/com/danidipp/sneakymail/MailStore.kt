package com.danidipp.sneakymail

import com.sneakydb.SneakyDBApi
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger

/**
 * All of SneakyMail's SQL, on `sneakymail.mail` (db/migrations/V001__mail.sql). No Bukkit. Futures complete on
 * SneakyDB threads.
 */
class MailStore(private val db: SneakyDBApi, private val logger: Logger) {
    fun isAvailable(): Boolean = db.isAvailable()

    /** [recipient]'s unclaimed mail, oldest first. A row whose rewards don't parse is left out and logged. */
    fun unclaimedFor(recipient: UUID): CompletableFuture<List<MailRecord>> =
        db.query(UNCLAIMED, listOf(recipient)).thenApply { rows -> rows.mapNotNull(::toRecord) }

    /**
     * Claims mail [id] for [recipient]: the rewards when this call claimed it, null when no unclaimed mail [id] of
     * [recipient] exists (already claimed, deleted, or someone else's).
     */
    fun claim(id: Long, recipient: UUID): CompletableFuture<List<MailReward>?> =
        db.query(CLAIM, listOf(id, recipient)).thenApply { rows ->
            rows.singleOrNull()?.let { MailRewards.parse(text(it["rewards"])) }
        }

    /** Undoes a claim whose rewards were never handed out (the player left or the plugin stopped meanwhile). */
    fun unclaim(id: Long, recipient: UUID): CompletableFuture<Int> = db.execute(UNCLAIM, listOf(id, recipient))

    /** `sneakymail.send`: the new mail's id, or the existing one for a [sourceKey] sent before with the same content. */
    fun send(sourceKey: String?, recipient: UUID, senderName: String, note: String, rewards: List<MailReward>): CompletableFuture<Long> =
        db.query(SEND, listOf(sourceKey, recipient, senderName, note, MailRewards.encode(rewards)))
            .thenApply { rows -> (rows.single()["id"] as Number).toLong() }

    private fun toRecord(row: Map<String, Any?>): MailRecord? {
        val id = (row["id"] as Number).toLong()
        val rewards = try {
            MailRewards.parse(text(row["rewards"]))
        } catch (error: Exception) {
            logger.warning("Mail $id: rewards don't parse, so it is not shown (${error.javaClass.simpleName})")
            return null
        }
        return MailRecord(
            id = id,
            recipientUuid = row["recipient_uuid"] as? UUID ?: UUID.fromString(row["recipient_uuid"].toString()),
            senderName = row["sender_name"] as String,
            note = row["note"] as String,
            rewards = rewards,
        )
    }

    /** jsonb arrives as the driver's object type; its toString() is the JSON text. */
    private fun text(value: Any?): String = value as? String ?: value.toString()

    companion object {
        const val UNCLAIMED = "select id, recipient_uuid, sender_name, note, rewards::text as rewards from sneakymail.mail " +
            "where recipient_uuid = ? and claimed_at is null order by id"

        /** The claim statement of the shared contract: rewards come back only to the one call that claimed. */
        const val CLAIM = "update sneakymail.mail set claimed_at = now() where id = ? and recipient_uuid = ? " +
            "and claimed_at is null returning rewards"

        const val UNCLAIM = "update sneakymail.mail set claimed_at = null where id = ? and recipient_uuid = ? " +
            "and claimed_at is not null"

        const val SEND = "select sneakymail.send(?::text, ?::uuid, ?::text, ?::text, ?::jsonb) as id"
    }
}
