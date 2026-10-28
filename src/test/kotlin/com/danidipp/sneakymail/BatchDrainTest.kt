package com.danidipp.sneakymail
import com.sneakydb.SneakyDBApi
import com.danidipp.sneakypocketbase.*
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import kotlin.test.*
class BatchDrainTest {
 @Test fun `time budget stops drain even with more queued work`() {
  val db = MailDeliveryWorkerTest.Db(); var creates = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
   "create" -> { creates++; Thread.sleep(10); CompletableFuture.completedFuture("""{"id":"abc123abc123abc"}""") }
   else -> error(m.name)
  } } as PocketbaseApi
  MailDeliveryWorker(db, pb).use { it.pumpBatch(32, 1).join() }; assertEquals(1, creates)
 }

 @Test fun `batch stops when manager generation changes during first dispatch`() {
  val db = MailDeliveryWorkerTest.Db(); var generation = 1L; var creates = 0
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), generation, generation, "")
   "create" -> { creates++; generation++; CompletableFuture.completedFuture("""{"id":"abc123abc123abc"}""") }
   else -> error(m.name)
  } } as PocketbaseApi
  MailDeliveryWorker(db, pb).use { it.pumpBatch(4, 10000).join() }; assertEquals(1, creates)
 }

 @Test fun `batch is bounded and stops immediately on empty queue`() {
  for (available in listOf(0, 3, 100)) {
   var claims = 0; var creates = 0
   val row = MailDeliveryWorkerTest.Db().claimed
   val db = object : SneakyDBApi {
    override fun isAvailable() = true
    override fun query(sql: String, parameters: List<*>) = CompletableFuture.completedFuture(if (claims++ < available) listOf(row) else emptyList())
    override fun execute(sql: String, parameters: List<*>) = CompletableFuture.completedFuture(1)
   }
   val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
    "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
    "create" -> { creates++; CompletableFuture.completedFuture("""{"id":"abc123abc123abc"}""") }
    else -> error(m.name)
   } } as PocketbaseApi
   val worker = MailDeliveryWorker(db, pb)
   val method = worker.javaClass.methods.firstOrNull { it.name == "pumpBatch" && it.parameterCount == 2 }
   if (method == null) worker.pump().join() else (method.invoke(worker, 4, 10000L) as CompletableFuture<*>).join()
   assertEquals(minOf(available, 4), creates)
   assertEquals(if (available < 4) available + 1 else 4, claims)
   worker.close()
  }
 }
}
