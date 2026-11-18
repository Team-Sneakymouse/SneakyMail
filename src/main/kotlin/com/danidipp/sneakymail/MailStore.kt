package com.danidipp.sneakymail

import com.sneakydb.SneakyDBApi
import java.sql.SQLException
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
     *
     * The statement runs in its own transaction with a lock and a statement timeout, so a row locked elsewhere (an
     * idle DBeaver transaction) can't hold the claim forever. When the statement times out the future fails with
     * [ClaimTimedOut]: the transaction rolled back, so the mail was definitely not claimed. Any other failure may
     * still have committed.
     */
    fun claim(id: Long, recipient: UUID): CompletableFuture<List<MailReward>?> =
        db.transaction { tx ->
            tx.execute(LOCK_TIMEOUT, emptyList<Any>())
            tx.execute(STATEMENT_TIMEOUT, emptyList<Any>())
            val rows = try {
                tx.query(CLAIM, listOf(id, recipient))
            } catch (error: SQLException) {
                if (error.sqlState in TIMEOUT_STATES) throw ClaimTimedOut(id, error)
                throw error
            }
            rows.singleOrNull()?.let { MailRewards.parse(text(it["rewards"])) }
        }

    /** The claim statement of mail [id] hit its lock or statement timeout and was rolled back: nothing was claimed. */
    class ClaimTimedOut(val id: Long, cause: SQLException) : RuntimeException("Claim of mail $id timed out", cause)

    /**
     * `sneakymail.send`: the new mail's id, or the existing one for a [sourceKey] sent before with the same content.
     * Runs with the claim's lock and statement timeouts, so waiting on an uncommitted insert of the same key can't
     * hold a SneakyDB worker forever; a timeout (SQLSTATE 55P03 or 57014) rolled back and may be retried with the key.
     */
    fun send(sourceKey: String?, recipient: UUID, senderName: String, note: String, rewards: List<MailReward>): CompletableFuture<Long> =
        db.transaction { tx ->
            tx.execute(LOCK_TIMEOUT, emptyList<Any>())
            tx.execute(STATEMENT_TIMEOUT, emptyList<Any>())
            val rows = tx.query(SEND, listOf(sourceKey, recipient, senderName, note, MailRewards.encode(rewards)))
            (rows.single()["id"] as Number).toLong()
        }

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

        const val LOCK_TIMEOUT = "set local lock_timeout = '5s'"
        const val STATEMENT_TIMEOUT = "set local statement_timeout = '15s'"

        /** lock_not_available (lock_timeout) and query_canceled (statement_timeout). */
        val TIMEOUT_STATES = setOf("55P03", "57014")

        const val SEND = "select sneakymail.send(?::text, ?::uuid, ?::text, ?::text, ?::jsonb) as id"
    }
}
