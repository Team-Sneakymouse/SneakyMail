package com.danidipp.sneakymail

import com.sneakydb.SneakyDBApi
import com.danidipp.sneakypocketbase.*
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.*

class MailDeliveryWorkerTest {
 @Test fun `claim timeout never completes manager future and late claim cannot dispatch`() {
  val db = Db(); db.delayed = CompletableFuture(); var creates = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
   "create" -> { creates++; CompletableFuture.completedFuture("{}") }
   else -> error(m.name)
  } } as PocketbaseApi
  val worker = MailDeliveryWorker(db, pb); val pending = worker.pump()
  assertFails { pending.get(17, java.util.concurrent.TimeUnit.SECONDS) }
  assertFalse(db.delayed!!.isDone, "Manager future must be untouched")
  db.delayed!!.complete(listOf(db.claimed)); assertEquals(0, creates); assertTrue(db.writes.isEmpty()); worker.close()
 }

 @Test fun `late mark after shutdown is compensated without dispatch`() {
  val db = Db(); db.delayedMark = CompletableFuture(); var creates = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
   "create" -> { creates++; CompletableFuture.completedFuture("""{"id":"abc123abc123abc"}""") }
   else -> error(m.name)
  } } as PocketbaseApi
  val worker = MailDeliveryWorker(db, pb); val pending = worker.pump(); worker.close()
  db.delayedMark!!.complete(1); pending.handle { _, _ -> null }.join()
  assertEquals(0, creates); assertTrue(db.writes.any { it.contains("attempted_at = NULL") })
 }

 @Test fun `claim leases without marking any possible send`() {
  val setClause = MailDeliveryWorker.CLAIM.substringAfter(") UPDATE").substringBefore("FROM candidate")
  assertFalse(setClause.contains("attempted_at ="), "A lease must not imply a POST")
 }
 @Test fun `send marker is committed after payload preparation before create`() {
  val db = Db(); var creates = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
   "create" -> { assertTrue(db.writes.any { it.contains("attempted_at = now()") }); creates++; CompletableFuture.completedFuture("""{"id":"abc123abc123abc"}""") }
   else -> error(m.name)
  } } as PocketbaseApi
  MailDeliveryWorker(db, pb).use { it.pump().join() }; assertEquals(1, creates)
 }

 class Db : SneakyDBApi {
  var claimed: Map<String, Any?> = mapOf("request_id" to "abc123abc123abc", "was_attempted" to false, "payload" to """{"recipient_uuid":"00000000-0000-0000-0000-000000000001","sender_name":"Phone","note":"note","command":"reward"}""")
  val writes = mutableListOf<String>()
  var delayedMark: CompletableFuture<Int>? = null
  override fun isAvailable() = true
  var delayed: CompletableFuture<List<Map<String, Any?>>>? = null
  override fun query(sql: String, parameters: List<*>) = delayed ?: CompletableFuture.completedFuture(listOf(claimed))
  override fun execute(sql: String, parameters: List<*>): CompletableFuture<Int> { writes.add(sql); return if (sql.contains("attempted_at = now()")) delayedMark ?: CompletableFuture.completedFuture(1) else CompletableFuture.completedFuture(1) }
 }
 @Test fun `shutdown and manager reload fence late claim callbacks`() {
  for (shutdown in listOf(true, false)) {
   val db = Db(); db.delayed = CompletableFuture()
   var generation = 1L; var creates = 0
   val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
    "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), generation, generation, "")
    "create" -> { creates++; CompletableFuture.completedFuture("{}") }
    else -> error(m.name)
   } } as PocketbaseApi
   val worker = MailDeliveryWorker(db, pb)
   val pending = worker.pump()
   assertFalse(pending.isDone)
   if (shutdown) worker.close() else generation++
   db.delayed!!.complete(listOf(db.claimed))
   pending.join()
   assertEquals(0, creates)
   assertTrue(db.writes.isEmpty())
   worker.close()
  }
 }
 @Test fun `recovered existing record confirms without posting`() {
  val db = Db(); db.claimed = db.claimed + ("was_attempted" to true)
  var gets = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
   "getOne" -> { gets++; CompletableFuture.completedFuture("""{"id":"abc123abc123abc","available":false}""") }
   else -> error("Unexpected call: ${m.name}")
  } } as PocketbaseApi
  val worker = MailDeliveryWorker(db, pb)
  worker.pump().join()
  assertEquals(1, gets)
  assertEquals(1, db.writes.size)
  worker.close()
 }
 @Test fun `uncertain deleted record is GET only forever`() {
  val db = Db(); db.claimed = db.claimed + ("was_attempted" to true)
  var gets = 0; var creates = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
   "getOne" -> { gets++; CompletableFuture.failedFuture<String>(IllegalStateException("404")) }
   "create" -> { creates++; CompletableFuture.completedFuture("""{"id":"abc123abc123abc"}""") }
   else -> error(m.name)
  } } as PocketbaseApi
  val type = Class.forName("com.danidipp.sneakymail.MailDeliveryWorker")
  val worker = type.getConstructor(SneakyDBApi::class.java, PocketbaseApi::class.java).newInstance(db, pb)
  repeat(2) { (type.getMethod("pump").invoke(worker) as CompletableFuture<*>).handle { _, _ -> null }.join() }
  assertEquals(0, creates)
  assertEquals(2, gets)
  assertTrue(db.writes.isEmpty())
  (worker as AutoCloseable).close()
 }
 @Test fun `durable claim precedes one create and permanent receipt`() {
  val db = Db()
  var creates = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, a -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
   "create" -> { creates++; assertTrue(a[1].toString().contains("abc123abc123abc")); CompletableFuture.completedFuture("""{"id":"abc123abc123abc"}""") }
   else -> error(m.name)
  } } as PocketbaseApi
  val type = Class.forName("com.danidipp.sneakymail.MailDeliveryWorker")
  val worker = type.getConstructor(SneakyDBApi::class.java, PocketbaseApi::class.java).newInstance(db, pb)
  (type.getMethod("pump").invoke(worker) as CompletableFuture<*>).join()
  assertEquals(1, creates)
  assertTrue(db.writes.last().contains("confirmed_at"))
  (worker as AutoCloseable).close()
 }
}
