package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.*
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.*

class ManagerOutcomeIntegrationTest {
 @Test fun `default worker retries only authoritative manager nonapplication outcomes`() {
  for (outcome in PocketbaseWriteFailure.Outcome.entries) {
   val db = MailDeliveryWorkerTest.Db()
   val api = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, method, _ ->
    when (method.name) {
     "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
     "create" -> CompletableFuture.failedFuture<String>(CompletionException(PocketbaseWriteFailure(outcome, if (outcome == PocketbaseWriteFailure.Outcome.REJECTED) 403 else null, java.io.IOException("secret"))))
     else -> error(method.name)
    }
   } as PocketbaseApi
   val worker = MailDeliveryWorker(db, api)
   worker.pump().handle { _, _ -> null }.join()
   assertEquals(outcome != PocketbaseWriteFailure.Outcome.UNKNOWN, db.writes.any { it.contains("attempted_at = NULL") }, outcome.name)
   worker.close()
  }
 }
}
