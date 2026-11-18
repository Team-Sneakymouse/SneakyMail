package com.danidipp.sneakymail

import com.danidipp.sneakymail.api.MailAcceptance
import com.danidipp.sneakymail.api.SneakyMailApi
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/**
 * The public [SneakyMailApi]: a command mail goes into `sneakymail.mail` through `sneakymail.send`, and acceptance
 * means that statement committed. Installed at enable; available once [ready] gave it the store (after migrations).
 * No blocking calls; futures complete on SneakyDB threads.
 */
class MailApiService : SneakyMailApi, AutoCloseable {
    @Volatile private var store: MailStore? = null
    @Volatile private var open = true
    private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<MailAcceptance>>()

    fun ready(store: MailStore) {
        this.store = store
    }

    override fun isAvailable(): Boolean = open && store?.isAvailable() == true

    override fun submitCommandMail(sourceKey: String?, recipientUuid: UUID?, senderName: String?, note: String?, command: String?): CompletableFuture<MailAcceptance> {
        if (sourceKey == null || recipientUuid == null || senderName == null || note == null || command == null)
            return CompletableFuture.failedFuture(IllegalArgumentException("Mail arguments must not be null"))
        if (sourceKey.isBlank() || sourceKey.length > 512 || command.isBlank())
            return CompletableFuture.failedFuture(IllegalArgumentException("Invalid source key or command"))
        val result = CompletableFuture<MailAcceptance>()
        val current = synchronized(this) {
            val current = store
            if (!open || current == null || !current.isAvailable()) return CompletableFuture.failedFuture(IllegalStateException("SneakyMail unavailable"))
            pending.add(result)
            current
        }
        result.orTimeout(15, TimeUnit.SECONDS).whenComplete { _, _ -> pending.remove(result) }
        try {
            current.send(sourceKey, recipientUuid, senderName, note, listOf(MailReward.CommandReward(command))).whenComplete { id, error ->
                if (error == null) result.complete(MailAcceptance(id.toString()))
                else result.completeExceptionally(translate(error))
            }
        } catch (error: Exception) {
            result.completeExceptionally(error)
        }
        return result
    }

    override fun close() {
        synchronized(this) { open = false }
        pending.forEach { it.completeExceptionally(IllegalStateException("SneakyMail stopped; retry same key")) }
    }

    companion object {
        /** `sneakymail.send` raises SQLSTATE 23505 when a source key comes back with different content. */
        internal fun translate(error: Throwable): Throwable {
            val cause = unwrap(error)
            return if (sqlState(cause) == "23505") IllegalArgumentException("Source key already used with different payload") else cause
        }

        private fun unwrap(error: Throwable): Throwable {
            var current = error
            while ((current is CompletionException || current is ExecutionException) && current.cause != null) current = current.cause!!
            return current
        }

        private fun sqlState(error: Throwable): String? =
            generateSequence(error) { it.cause }.take(10).filterIsInstance<SQLException>().firstNotNullOfOrNull { it.sqlState }
    }
}
