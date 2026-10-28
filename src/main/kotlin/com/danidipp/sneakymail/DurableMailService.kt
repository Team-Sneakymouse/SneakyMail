package com.danidipp.sneakymail

import com.danidipp.sneakymail.api.MailAcceptance
import com.danidipp.sneakymail.api.SneakyMailApi
import com.sneakydb.SneakyDBApi
import kotlinx.serialization.json.*
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Owns durable admission independently of PocketBase availability. No blocking joins or server-thread IO. */
class DurableMailService(private val database: SneakyDBApi) : SneakyMailApi, AutoCloseable {
 @Volatile private var open = true
 private val pending = ConcurrentHashMap.newKeySet<CompletableFuture<MailAcceptance>>()
 override fun isAvailable() = open && database.isAvailable()
 override fun submitCommandMail(sourceKey: String?, recipientUuid: UUID?, senderName: String?, note: String?, command: String?): CompletableFuture<MailAcceptance> {
  if (sourceKey == null || recipientUuid == null || senderName == null || note == null || command == null)
   return CompletableFuture.failedFuture(IllegalArgumentException("Mail arguments must not be null"))
  val result = CompletableFuture<MailAcceptance>()
  synchronized(this) {
   if (!isAvailable()) return CompletableFuture.failedFuture(IllegalStateException("SneakyMail unavailable"))
   if (sourceKey.isBlank() || sourceKey.length > 512 || command.isBlank()) return CompletableFuture.failedFuture(IllegalArgumentException("Invalid source key or command"))
   pending.add(result)
  }
  result.orTimeout(15, TimeUnit.SECONDS).whenComplete { _, _ -> pending.remove(result) }
  val payload = buildJsonObject {
   put("recipient_uuid", recipientUuid.toString()); put("sender_name", senderName)
   put("note", note); put("command", command)
  }.toString()
  try {
   database.query(SUBMIT, listOf(sourceKey, newId(), payload)).whenComplete { rows, error ->
    if (error != null) result.completeExceptionally(error)
    else if (rows.size != 1) result.completeExceptionally(IllegalArgumentException("Source key already used with different payload"))
    else result.complete(MailAcceptance(rows.single()["request_id"].toString()))
   }
  } catch (error: Exception) { result.completeExceptionally(error) }
  return result
 }
 override fun close() {
  synchronized(this) { open = false }
  pending.forEach { it.completeExceptionally(IllegalStateException("SneakyMail stopped; retry same key")) }
 }
 companion object {
  private val random = SecureRandom()
  private const val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789"
  private fun newId() = (1..15).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")
  const val SUBMIT = """WITH accepted AS (
   INSERT INTO sneakymail.command_requests AS journal (source_key, request_id, payload)
   VALUES (?, ?, ?::jsonb)
   ON CONFLICT (source_key) DO UPDATE SET source_key = EXCLUDED.source_key
   WHERE journal.payload = EXCLUDED.payload
   RETURNING request_id
  ) SELECT request_id FROM accepted"""
 }
}
