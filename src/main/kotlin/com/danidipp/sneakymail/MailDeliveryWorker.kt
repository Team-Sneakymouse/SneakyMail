package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.PocketbaseApi
import com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot.ApiState
import com.sneakydb.SneakyDBApi
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** At most one POST per permanent intent. An attempted intent is never re-created. */
class MailDeliveryWorker(private val database: SneakyDBApi, private val pocketbase: PocketbaseApi) : AutoCloseable {
 private var open = true
 private val busy = AtomicBoolean()
 private fun valid(generation: Long) = open && pocketbase.lifecycleSnapshot.let { it.generation == generation && it.apiState == ApiState.AVAILABLE }
 fun pump(): CompletableFuture<Void> {
  if (!busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(null)
  val completion = CompletableFuture<Void>()
  completion.whenComplete { _, _ -> busy.set(false) }
  try {
   val snapshot = pocketbase.lifecycleSnapshot
   synchronized(this) {
    if (!open || !database.isAvailable() || snapshot.apiState != ApiState.AVAILABLE) { completion.complete(null); return completion }
   }
   val token = UUID.randomUUID().toString()
   database.query(CLAIM, listOf(token)).orTimeout(15, TimeUnit.SECONDS).thenCompose { rows ->
    synchronized(this) {
     if (rows.isEmpty() || !valid(snapshot.generation)) return@thenCompose CompletableFuture.completedFuture<Void>(null)
     val row = rows.single()
     val id = row["request_id"].toString()
     val payload = Json.parseToJsonElement(row["payload"].toString()).jsonObject
     val record = buildJsonObject {
      put("id", id); put("sender_uuid", ""); put("sender_name", payload.getValue("sender_name"))
      put("recipient_uuid", payload.getValue("recipient_uuid")); put("recipient_name", "")
      put("available", true); put("note", payload.getValue("note"))
      put("rewards", buildJsonArray { add(buildJsonObject { put("type", "command"); put("command", payload.getValue("command")) }) })
     }
     val delivery = if (row["was_attempted"] == true) pocketbase.getOne("lom2_mail", id)
       else pocketbase.create("lom2_mail", record.toString())
     delivery.orTimeout(15, TimeUnit.SECONDS).thenCompose { response ->
      synchronized(this) {
       if (!valid(snapshot.generation)) return@thenCompose CompletableFuture.completedFuture<Void>(null)
       check(Json.parseToJsonElement(response).jsonObject["id"]?.jsonPrimitive?.content == id) { "Unexpected mail ID" }
       database.execute(CONFIRM, listOf(id, token)).orTimeout(15, TimeUnit.SECONDS).thenApply { count -> check(count == 1) { "Receipt lease lost" }; null }
      }
     }
    }
   }.whenComplete { _, error -> if (error == null) completion.complete(null) else completion.completeExceptionally(error) }
  } catch (error: Exception) { completion.completeExceptionally(error) }
  return completion
 }
 override fun close() { synchronized(this) { open = false } }
 companion object {
  const val CLAIM = """WITH candidate AS (
   SELECT request_id, attempted_at IS NOT NULL AS was_attempted
   FROM sneakymail.command_requests
   WHERE confirmed_at IS NULL AND (lease_until IS NULL OR lease_until < now())
   ORDER BY (attempted_at IS NOT NULL), last_checked NULLS FIRST, created_at, request_id
   FOR UPDATE SKIP LOCKED LIMIT 1
  ) UPDATE sneakymail.command_requests AS journal
   SET attempted_at = COALESCE(journal.attempted_at, now()), last_checked = now(),
       lease_token = ?, lease_until = now() + interval '60 seconds'
   FROM candidate WHERE journal.request_id = candidate.request_id
   RETURNING journal.request_id, journal.payload::text, candidate.was_attempted"""
  const val CONFIRM = """UPDATE sneakymail.command_requests SET confirmed_at = now(), lease_until = NULL
   WHERE request_id = ? AND lease_token = ? AND confirmed_at IS NULL"""
 }
}
