package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.PocketbaseApi
import com.danidipp.sneakypocketbase.PocketbaseLifecycleSnapshot.ApiState
import com.sneakydb.SneakyDBApi
import kotlinx.serialization.json.*
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A lease is not a send. Only a committed send marker permits one POST. */
class MailDeliveryWorker @JvmOverloads constructor(
 private val database: SneakyDBApi, private val pocketbase: PocketbaseApi,
 private val timeoutMillis: Long = 15000,
 private val definitelyNotSent: java.util.function.Predicate<Throwable> = java.util.function.Predicate { failure ->
  failure is com.danidipp.sneakypocketbase.PocketbaseWriteFailure &&
   failure.outcome in setOf(com.danidipp.sneakypocketbase.PocketbaseWriteFailure.Outcome.NOT_SENT, com.danidipp.sneakypocketbase.PocketbaseWriteFailure.Outcome.REJECTED)
 }
) : AutoCloseable {
 private var open = true
 private val busy = AtomicBoolean()
 private fun valid(generation: Long) = open && pocketbase.lifecycleSnapshot.let { it.generation == generation && it.apiState == ApiState.AVAILABLE }
 fun pump(): CompletableFuture<Void> = pumpBatch(1, 1000)
 @JvmOverloads fun pumpBatch(maxRequests: Int = 32, budgetMillis: Long = 1000): CompletableFuture<Void> {
  require(maxRequests in 1..1024 && budgetMillis > 0)
  if (!busy.compareAndSet(false, true)) return CompletableFuture.completedFuture(null)
  val started = System.nanoTime()
  val generation = pocketbase.lifecycleSnapshot.generation
  fun next(remaining: Int): CompletableFuture<Void> {
   var claimed = false
   return pumpOne { claimed = true }.thenCompose {
    val proceed = synchronized(this) { valid(generation) && claimed && remaining > 1 && System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(budgetMillis) }
    if (proceed) next(remaining - 1) else CompletableFuture.completedFuture(null)
   }
  }
  val result = next(maxRequests)
  result.whenComplete { _, _ -> busy.set(false) }
  return result
 }
 private fun pumpOne(onClaim: () -> Unit): CompletableFuture<Void> {
  val started = System.nanoTime()
  fun expired() = System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
  var stage = "CLAIM"
  var requestId: String? = null
  val result = object : CompletableFuture<Void>() {
   override fun completeExceptionally(error: Throwable): Boolean = super.completeExceptionally(DeliveryFailure(stage, requestId, error))
  }
  // This promise is ours, never the manager's. All late callbacks still observe original futures.
  CompletableFuture.delayedExecutor(timeoutMillis, TimeUnit.MILLISECONDS).execute {
   synchronized(this) { if (!result.isDone) result.completeExceptionally(java.util.concurrent.TimeoutException()) }
  }
  val token = UUID.randomUUID().toString()
  try {
   val generation = pocketbase.lifecycleSnapshot.generation
   synchronized(this) {
    if (!valid(generation) || !database.isAvailable()) { result.complete(null); return result }
   }
   database.query(CLAIM, listOf(token)).whenComplete { rows, error ->
    synchronized(this) {
     if (error != null) { result.completeExceptionally(error); return@whenComplete }
     if (result.isDone || !valid(generation) || expired() || rows.isEmpty()) { result.complete(null); return@whenComplete }
     try {
      onClaim()
      val row = rows.single(); val id = row["request_id"].toString(); requestId = id; stage = "PREPARE"
      fun confirm(response: String) {
       synchronized(this) {
        if (result.isDone || !valid(generation) || expired()) { result.complete(null); return }
        try {
         check(Json.parseToJsonElement(response).jsonObject["id"]?.jsonPrimitive?.content == id)
         stage = "RECEIPT"
         database.execute(CONFIRM, listOf(id, token)).whenComplete { count, failure ->
          if (failure != null) result.completeExceptionally(failure)
          else if (count != 1) result.completeExceptionally(IllegalStateException("Receipt lease lost"))
          else result.complete(null)
         }
        } catch (failure: Exception) { result.completeExceptionally(failure) }
       }
      }
      if (row["was_attempted"] == true) {
       stage = "GET"
       pocketbase.getOne("lom2_mail", id).whenComplete { response, failure ->
        if (failure != null) result.completeExceptionally(failure) else confirm(response)
       }
      } else {
       val payload = try {
        Json.parseToJsonElement(row["payload"].toString()).jsonObject.also { value ->
         for (key in listOf("recipient_uuid", "sender_name", "note", "command")) require(value[key]?.jsonPrimitive?.isString == true)
         UUID.fromString(value.getValue("recipient_uuid").jsonPrimitive.content)
         require(value.getValue("command").jsonPrimitive.content.isNotBlank())
        }
       } catch (invalid: Exception) {
        database.execute(QUARANTINE, listOf(id, token)).whenComplete { _, quarantineError ->
         result.completeExceptionally(quarantineError ?: IllegalArgumentException("Invalid durable payload"))
        }
        return@whenComplete
       }
       val record = buildJsonObject {
        put("id", id); put("sender_uuid", ""); put("sender_name", payload.getValue("sender_name"))
        put("recipient_uuid", payload.getValue("recipient_uuid")); put("recipient_name", "")
        put("available", true); put("note", payload.getValue("note"))
        put("rewards", buildJsonArray { add(buildJsonObject { put("type", "command"); put("command", payload.getValue("command")) }) })
       }.toString()
       if (result.isDone || !valid(generation) || expired()) { result.complete(null); return@whenComplete }
       stage = "MARK"
       database.execute(MARK, listOf(token, id, token)).whenComplete { count, failure ->
        synchronized(this) {
         if (failure != null) {
          // MARK's response can be lost after commit. No create was invoked, so compensation is safe.
          database.execute(RESET, listOf(id, token)).whenComplete { _, resetError -> result.completeExceptionally(resetError ?: failure) }
          return@whenComplete
         }
         if (count != 1) { result.completeExceptionally(IllegalStateException("Send lease lost")); return@whenComplete }
         if (result.isDone || !valid(generation) || expired()) {
          // This exact marker is known unsent, even if another worker has since leased the row.
          database.execute(RESET, listOf(id, token)).whenComplete { _, resetError ->
           if (resetError != null) result.completeExceptionally(resetError) else result.complete(null)
          }
         } else {
          fun sendFailed(sendError: Throwable) {
           var cause = sendError
           while ((cause is java.util.concurrent.CompletionException || cause is java.util.concurrent.ExecutionException) && cause.cause != null) cause = cause.cause!!
           if (definitelyNotSent.test(cause)) database.execute(RESET, listOf(id, token)).whenComplete { _, resetError -> result.completeExceptionally(resetError ?: cause) }
           else result.completeExceptionally(cause)
          }
          try {
           // Invocation is the uncertainty boundary; any unclassified failure from here is GET-only.
           stage = "POST"
           pocketbase.create("lom2_mail", record).whenComplete { response, sendError ->
            if (sendError != null) sendFailed(sendError) else confirm(response)
           }
          } catch (sendError: Exception) { sendFailed(sendError) }
         }
        }
       }
      }
     } catch (failure: Exception) { result.completeExceptionally(failure) }
    }
   }
  } catch (error: Exception) { result.completeExceptionally(error) }
  return result
 }
 override fun close() { synchronized(this) { open = false } }
 companion object {
  const val CLAIM = """WITH candidate AS (
   SELECT request_id, attempted_at IS NOT NULL AS was_attempted
   FROM sneakymail.command_requests
   WHERE confirmed_at IS NULL AND quarantined_at IS NULL AND (next_attempt_at IS NULL OR next_attempt_at <= now()) AND (lease_until IS NULL OR lease_until < now())
   ORDER BY (attempted_at IS NOT NULL), last_checked NULLS FIRST, created_at, request_id
   FOR UPDATE SKIP LOCKED LIMIT 1
  ) UPDATE sneakymail.command_requests AS journal
   SET last_checked = now(),
       lease_token = ?, lease_until = now() + interval '60 seconds'
   FROM candidate WHERE journal.request_id = candidate.request_id
   RETURNING journal.request_id, journal.payload::text, candidate.was_attempted"""
  const val QUARANTINE = """UPDATE sneakymail.command_requests SET quarantined_at = now()
   WHERE request_id = ? AND lease_token = ? AND attempted_at IS NULL AND confirmed_at IS NULL"""
  const val RESET = """UPDATE sneakymail.command_requests SET attempted_at = NULL, attempt_token = NULL,
   next_attempt_at = now() + interval '5 seconds'
   WHERE request_id = ? AND attempt_token = ? AND confirmed_at IS NULL"""
  const val MARK = """UPDATE sneakymail.command_requests SET attempted_at = now(), attempt_token = ?
   WHERE request_id = ? AND lease_token = ? AND lease_until > now() AND attempted_at IS NULL AND confirmed_at IS NULL"""
  const val CONFIRM = """UPDATE sneakymail.command_requests SET confirmed_at = now(), lease_until = NULL
   WHERE request_id = ? AND lease_token = ? AND confirmed_at IS NULL"""
 }
}