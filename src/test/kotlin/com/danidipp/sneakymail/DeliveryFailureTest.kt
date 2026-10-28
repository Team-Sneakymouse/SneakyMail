package com.danidipp.sneakymail

import com.danidipp.sneakypocketbase.*
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.function.Predicate
import kotlin.test.*

class DeliveryFailureTest {
 @Test fun `failure identifies stage opaque receipt and unwrapped category without secrets`() {
  val db = MailDeliveryWorkerTest.Db()
  val worker = MailDeliveryWorker(db, pb { CompletableFuture.failedFuture(java.util.concurrent.CompletionException(java.io.IOException("SECRET command token"))) })
  val failure = assertFailsWith<java.util.concurrent.CompletionException> { worker.pump().join() }.cause!!
  val diagnostic = failure.message.orEmpty()
  assertTrue(diagnostic.contains("POST")); assertTrue(diagnostic.contains("abc123abc123abc")); assertTrue(diagnostic.contains("IOException"))
  assertFalse(diagnostic.contains("SECRET")); worker.close()
 }

 private fun pb(create: () -> CompletableFuture<String>): PocketbaseApi = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when (m.name) {
  "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.AVAILABLE, PocketbaseLifecycleSnapshot.TransportState.CONNECTED, emptyMap(), 1, 1, "")
  "create" -> create()
  else -> error(m.name)
 } } as PocketbaseApi
 @Test fun `definitively unsent failure resets exact marker for backoff recovery`() {
  val db = MailDeliveryWorkerTest.Db()
  val api = pb { CompletableFuture.failedFuture(IllegalArgumentException("rejected secret payload")) }
  val constructor = MailDeliveryWorker::class.java.constructors.firstOrNull { it.parameterTypes.lastOrNull() == Predicate::class.java }
  val worker = if (constructor == null) MailDeliveryWorker(db, api) else constructor.newInstance(db, api, 15000L, Predicate<Throwable> { it is IllegalArgumentException }) as MailDeliveryWorker
  worker.pump().handle { _, _ -> null }.join()
  assertTrue(db.writes.any { it.contains("attempted_at = NULL") && it.contains("attempt_token = ?") && it.contains("next_attempt_at") })
  worker.close()
 }
 @Test fun `malformed unattempted payload is quarantined without GET or POST`() {
  val db = MailDeliveryWorkerTest.Db(); db.claimed = db.claimed + ("payload" to "{}")
  val worker = MailDeliveryWorker(db, pb { error("must not POST") })
  worker.pump().handle { _, _ -> null }.join()
  assertTrue(db.writes.any { it.contains("quarantined_at = now()") })
  assertFalse(db.writes.any { it.contains("attempted_at = now()") }); worker.close()
 }
}
