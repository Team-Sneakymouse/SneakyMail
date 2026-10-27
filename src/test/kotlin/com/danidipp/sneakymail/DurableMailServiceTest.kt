package com.danidipp.sneakymail

import com.sneakydb.SneakyDBApi
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class DurableMailServiceTest {
 class Database : SneakyDBApi {
  val result = CompletableFuture<List<Map<String, Any?>>>()
  var sql = ""
  var parameters: List<*> = emptyList<Any>()
  override fun isAvailable() = true
  override fun query(sql: String, parameters: List<*>): CompletableFuture<List<Map<String, Any?>>> {
   this.sql = sql; this.parameters = parameters; return result
  }
  override fun execute(sql: String, parameters: List<*>) = CompletableFuture.completedFuture(1)
 }
 @Test fun `payload conflict fails asynchronously`() {
  val db = Database(); val service = DurableMailService(db)
  val future = service.submitCommandMail("key", UUID.randomUUID(), "Phone", "note", "command")
  db.result.complete(emptyList())
  assertFailsWith<java.util.concurrent.CompletionException> { future.join() }
  service.close()
 }
 @Test fun `database failures complete promises`() {
  val db = Database(); val service = DurableMailService(db)
  val future = service.submitCommandMail("key", UUID.randomUUID(), "Phone", "note", "command")
  db.result.completeExceptionally(IllegalStateException("SQL unavailable"))
  assertTrue(future.isCompletedExceptionally)
  service.close()
 }
 @Test fun `shutdown prevents submission and completes all admitted promises`() {
  val db = Database(); val service = DurableMailService(db)
  val futures = (1..10).map { service.submitCommandMail("key:$it", UUID.randomUUID(), "Phone", "note", "command") }
  service.close()
  assertTrue(futures.all { it.isCompletedExceptionally })
  assertTrue(service.submitCommandMail("late", UUID.randomUUID(), "Phone", "note", "command").isCompletedExceptionally)
  db.result.complete(listOf(mapOf("request_id" to "abc123abc123abc")))
  assertTrue(futures.all { it.isCompletedExceptionally })
 }
 @Test fun `blank source key or command is rejected before SQL`() {
  val db = Database(); val service = DurableMailService(db)
  assertTrue(service.submitCommandMail(" ", UUID.randomUUID(), "Phone", "note", "command").isCompletedExceptionally)
  assertTrue(service.submitCommandMail("key", UUID.randomUUID(), "Phone", "note", " ").isCompletedExceptionally)
  assertEquals("", db.sql)
  service.close()
 }
 @Test fun `acceptance waits for durable atomic payload matching`() {
  val db = Database()
  val type = Class.forName("com.danidipp.sneakymail.DurableMailService")
  val service = type.getConstructor(SneakyDBApi::class.java).newInstance(db) as com.danidipp.sneakymail.api.SneakyMailApi
  val future = service.submitCommandMail("phone:one", UUID.randomUUID(), "Phone", "note", "reward")
  assertFalse(future.isDone)
  assertTrue(db.sql.contains("ON CONFLICT"))
  assertTrue(db.sql.contains("payload = EXCLUDED.payload"))
  db.result.complete(listOf(mapOf("request_id" to "abc123abc123abc")))
  assertEquals("abc123abc123abc", future.join().requestId())
  (service as AutoCloseable).close()
 }
}
