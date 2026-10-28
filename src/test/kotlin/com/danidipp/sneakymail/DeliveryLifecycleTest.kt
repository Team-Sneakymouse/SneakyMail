package com.danidipp.sneakymail

import com.sneakydb.SneakyDBApi
import com.danidipp.sneakypocketbase.*
import java.lang.reflect.Proxy
import java.util.concurrent.*
import java.util.function.Predicate
import kotlin.test.*

/** Controlled boundaries model committed journal state; production SQL fences are separately exercised on PostgreSQL. */
class DeliveryLifecycleTest {
 private class Fixture : SneakyDBApi {
  val row = MailDeliveryWorkerTest.Db().claimed
  var attempted = false
  var confirmed = false
  var generation = 1L
  var slowSnapshot = false
  var creates = 0; var gets = 0; var resets = 0
  var claimFuture: CompletableFuture<List<Map<String, Any?>>>? = null
  var markFuture: CompletableFuture<Int>? = null
  var postFuture: CompletableFuture<String>? = null
  var reject = false; var receiptFailure = false
  val id = "abc123abc123abc"
  val response get() = """{"id":"$id"}"""
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> { if (slowSnapshot) Thread.sleep(80); PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), generation, generation, "") }
   "create" -> { creates++; postFuture ?: if (reject) CompletableFuture.failedFuture<String>(IllegalArgumentException("known rejection")) else CompletableFuture.completedFuture(response) }
   "getOne" -> { gets++; CompletableFuture.failedFuture<String>(IllegalStateException("404 deleted")) }
   else -> error(m.name)
  } } as PocketbaseApi
  override fun isAvailable() = true
  override fun query(sql: String, parameters: List<*>) = claimFuture ?: CompletableFuture.completedFuture(if (confirmed) emptyList() else listOf(row + ("was_attempted" to attempted)))
  override fun execute(sql: String, parameters: List<*>): CompletableFuture<Int> = when(sql) {
   MailDeliveryWorker.MARK -> markFuture ?: CompletableFuture.completedFuture(1).also { attempted = true }
   MailDeliveryWorker.RESET -> CompletableFuture.completedFuture(1).also { attempted = false; resets++ }
   MailDeliveryWorker.CONFIRM -> if (receiptFailure) CompletableFuture.failedFuture(IllegalStateException("receipt unavailable")) else CompletableFuture.completedFuture(1).also { confirmed = true }
   else -> error(sql)
  }
  fun worker(timeout: Long = 1000, known: Boolean = false) = MailDeliveryWorker(this, pb, timeout, Predicate { known && it is IllegalArgumentException })
  fun commitMark() { attempted = true; markFuture!!.complete(1) }
  fun failed(future: CompletableFuture<*>) { assertFailsWith<ExecutionException> { future.get(2, TimeUnit.SECONDS) } }
 }
 @Test fun `observer deadline crossed inside lifecycle check prevents POST and compensates marker`() {
  val f = Fixture(); f.markFuture = CompletableFuture(); val worker = f.worker(50); val pending = worker.pump()
  f.slowSnapshot = true; f.commitMark(); pending.handle { _, _ -> null }.join()
  assertEquals(0, f.creates); assertFalse(f.attempted); assertEquals(1, f.resets); worker.close()
 }
 @Test fun `mark response failure after commit still compensates known-unsent marker`() {
  val f = Fixture(); f.markFuture = CompletableFuture(); val worker = f.worker(); val pending = worker.pump()
  f.attempted = true; f.markFuture!!.completeExceptionally(java.io.IOException("commit response lost"))
  f.failed(pending); assertFalse(f.attempted); assertEquals(1, f.resets); assertEquals(0, f.creates); worker.close()
 }
 @Test fun `timed out mark later commits but is undone without POST and can retry`() {
  val f = Fixture(); f.markFuture = CompletableFuture(); val worker = f.worker(30)
  f.failed(worker.pump()); assertFalse(f.markFuture!!.isDone)
  f.commitMark(); assertFalse(f.attempted); assertEquals(1, f.resets); assertEquals(0, f.creates)
  f.markFuture = null; worker.pump().join(); assertEquals(1, f.creates); assertTrue(f.confirmed); worker.close()
 }
 @Test fun `manager generation invalidates delayed marker and compensates exact known-unsent attempt`() {
  val f = Fixture(); f.markFuture = CompletableFuture(); val worker = f.worker(); val pending = worker.pump()
  f.generation++; f.commitMark(); pending.join()
  assertFalse(f.attempted); assertEquals(0, f.creates); assertEquals(1, f.resets); worker.close()
 }
 @Test fun `delayed successful PocketBase response after close cannot write receipt`() {
  val f = Fixture(); f.postFuture = CompletableFuture(); val worker = f.worker(); val pending = worker.pump()
  assertEquals(1, f.creates); worker.close(); f.postFuture!!.complete(f.response); pending.join()
  assertTrue(f.attempted); assertFalse(f.confirmed); assertEquals(0, f.resets)
 }
 @Test fun `timed out POST keeps manager future intact and recovery after deletion remains GET only`() {
  val f = Fixture(); f.postFuture = CompletableFuture(); val worker = f.worker(30)
  f.failed(worker.pump()); assertFalse(f.postFuture!!.isDone)
  f.postFuture!!.complete(f.response); assertFalse(f.confirmed)
  repeat(3) { f.failed(worker.pump()) }
  assertEquals(1, f.creates); assertEquals(3, f.gets); assertTrue(f.attempted); assertEquals(0, f.resets); worker.close()
 }
 @Test fun `receipt failure after POST cannot authorize another POST even if record deleted`() {
  val f = Fixture(); f.receiptFailure = true; val worker = f.worker()
  f.failed(worker.pump()); assertTrue(f.attempted)
  repeat(2) { f.failed(worker.pump()) }
  assertEquals(1, f.creates); assertEquals(2, f.gets); assertEquals(0, f.resets); worker.close()
 }
 @Test fun `known rejection resets marker then later recovery may POST once more`() {
  val f = Fixture(); f.reject = true; val worker = f.worker(1000, true)
  f.failed(worker.pump()); assertFalse(f.attempted); assertEquals(1, f.resets)
  f.reject = false; worker.pump().join(); assertTrue(f.confirmed); assertEquals(2, f.creates); assertEquals(0, f.gets); worker.close()
 }
 @Test fun `late definitive rejection after observer deadline still resets exact marker`() {
  val f = Fixture(); f.postFuture = CompletableFuture(); val worker = f.worker(30, true)
  f.failed(worker.pump()); f.postFuture!!.completeExceptionally(IllegalArgumentException("definitively rejected"))
  assertFalse(f.attempted); assertEquals(1, f.resets); assertEquals(1, f.creates); worker.close()
 }
 @Test fun `unclassified rejected looking error stays GET only`() {
  val f = Fixture(); f.reject = true; val worker = f.worker()
  f.failed(worker.pump()); f.failed(worker.pump())
  assertTrue(f.attempted); assertEquals(1, f.creates); assertEquals(1, f.gets); assertEquals(0, f.resets); worker.close()
 }
 @Test fun `timed out claim later committed lease leaves intent never attempted`() {
  val f = Fixture(); f.claimFuture = CompletableFuture(); val worker = f.worker(30)
  f.failed(worker.pump()); assertFalse(f.claimFuture!!.isDone)
  f.claimFuture!!.complete(listOf(f.row)); assertFalse(f.attempted); assertEquals(0, f.creates)
  f.claimFuture = null; worker.pump().join(); assertTrue(f.confirmed); assertEquals(1, f.creates); worker.close()
 }
}
