package com.danidipp.sneakymail

import com.danidipp.sneakymail.api.SneakyMailProvider
import com.danidipp.sneakypocketbase.PocketbaseApi
import com.sneakydb.SneakyDBApi
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** One asynchronous runtime owner per plugin lifecycle. All actual storage is through manager APIs. */
class MailRuntime(private val database: SneakyDBApi, pocketbase: PocketbaseApi, private val logger: Logger) : AutoCloseable {
 private val service = DurableMailService(database)
 private val worker = MailDeliveryWorker(database, pocketbase)
 private val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "SneakyMail-delivery").apply { isDaemon = true } }
 private val diagnostics = DeliveryDiagnostics(logger, java.util.function.LongSupplier(System::nanoTime))
 @Volatile private var closed = false
 @Volatile private var active = false
 init {
  val migrated = try {
   database.migrate(SneakyMail.IDENTIFIER, DurableMailService::class.java)
  } catch (error: RuntimeException) {
   CompletableFuture.failedFuture(error)
  }
  migrated.whenComplete { _, failure ->
   synchronized(this) {
    if (closed) return@whenComplete
    if (failure != null) {
     val cause = failure.cause ?: failure
     logger.warning("SneakyMail migrations failed (${cause.message ?: cause}); durable mail API stays unavailable.")
     return@whenComplete
    }
    try {
     start()
    } catch (error: Exception) {
     logger.warning("SneakyMail durable runtime failed to start (${error.message ?: error}); durable mail API stays unavailable.")
     close()
    }
   }
  }
 }
 private fun start() {
  if (active) return
  active = true
  SneakyMailProvider.install(service)
  executor.scheduleWithFixedDelay({
   worker.pumpBatch().whenComplete { _, error ->
    if (error != null) diagnostics.report(error)
   }
  }, 0, 1, TimeUnit.SECONDS)
 }
 override fun close() {
  synchronized(this) { closed = true }
  if (active) SneakyMailProvider.uninstall(service)
  service.close()
  worker.close()
  executor.shutdownNow()
 }
}