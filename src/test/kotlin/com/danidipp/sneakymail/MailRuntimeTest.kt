package com.danidipp.sneakymail

import com.danidipp.sneakymail.api.SneakyMailProvider
import com.danidipp.sneakypocketbase.*
import com.sneakydb.SneakyDBApi
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.util.logging.Logger
import kotlin.test.*

class MailRuntimeTest {
 @Test fun `runtime owns provider and stops outstanding acceptance`() {
  val db = DurableMailServiceTest.Database()
  val pb = Proxy.newProxyInstance(PocketbaseApi::class.java.classLoader, arrayOf(PocketbaseApi::class.java)) { _, m, _ -> when(m.name) {
   "getLifecycleSnapshot" -> PocketbaseLifecycleSnapshot(PocketbaseLifecycleSnapshot.ApiState.UNAVAILABLE, PocketbaseLifecycleSnapshot.TransportState.DISCONNECTED, emptyMap(), 1, 1, "")
   else -> error(m.name)
  } } as PocketbaseApi
  val type = Class.forName("com.danidipp.sneakymail.MailRuntime")
  val runtime = type.getConstructor(SneakyDBApi::class.java, PocketbaseApi::class.java, Logger::class.java).newInstance(db, pb, Logger.getAnonymousLogger()) as AutoCloseable
  val api = SneakyMailProvider.getApi()
  val pending = api.submitCommandMail("key", java.util.UUID.randomUUID(), "Phone", "note", "command")
  assertFalse(pending.isDone)
  runtime.close()
  assertTrue(pending.isCompletedExceptionally)
  assertFalse(api.isAvailable)
  assertFailsWith<IllegalStateException> { SneakyMailProvider.getApi() }
 }
}
