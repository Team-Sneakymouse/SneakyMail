package com.danidipp.sneakymail

import com.danidipp.sneakymail.api.SneakyMailProvider
import com.danidipp.sneakypocketbase.PocketbaseApi
import com.sneakydb.SneakyDBApi
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/** One asynchronous runtime owner per plugin lifecycle. All actual storage is through manager APIs. */
class MailRuntime(database: SneakyDBApi, pocketbase: PocketbaseApi, logger: Logger) : AutoCloseable {
 private val service = DurableMailService(database)
 private val worker = MailDeliveryWorker(database, pocketbase)
 private val executor = Executors.newSingleThreadScheduledExecutor { runnable -> Thread(runnable, "SneakyMail-delivery").apply { isDaemon = true } }
 private val diagnostics = DeliveryDiagnostics(logger, java.util.function.LongSupplier(System::nanoTime))
 init {
  try {
   SneakyMailProvider.install(service)
   executor.scheduleWithFixedDelay({
    worker.pumpBatch().whenComplete { _, error ->
     if (error != null) diagnostics.report(error)
    }
   }, 0, 1, TimeUnit.SECONDS)
  } catch (error: Exception) { close(); throw error }
 }
 override fun close() {
  SneakyMailProvider.uninstall(service)
  service.close()
  worker.close()
  executor.shutdownNow()
 }
}
