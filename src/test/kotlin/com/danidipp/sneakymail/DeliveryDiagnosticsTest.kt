package com.danidipp.sneakymail
import java.util.function.LongSupplier
import java.util.logging.*
import kotlin.test.*
class DeliveryDiagnosticsTest {
 @Test fun `first warning always logs at negative clock origin then throttles`() {
  var time = Long.MIN_VALUE + 5
  val records = mutableListOf<LogRecord>()
  val logger = Logger.getAnonymousLogger().apply { useParentHandlers = false; addHandler(object : Handler() {
   override fun publish(record: LogRecord) { records.add(record) }; override fun flush() {}; override fun close() {}
  }) }
  val type = runCatching { Class.forName("com.danidipp.sneakymail.DeliveryDiagnostics") }.getOrNull()
  assertNotNull(type, "Dedicated injected-clock diagnostic throttle required")
  val diagnostics = type.getConstructor(Logger::class.java, LongSupplier::class.java).newInstance(logger, LongSupplier { time })
  val report = type.getMethod("report", Throwable::class.java)
  report.invoke(diagnostics, IllegalStateException("SECRET")); assertEquals(1, records.size)
  report.invoke(diagnostics, IllegalStateException("SECRET")); assertEquals(1, records.size)
  time += java.util.concurrent.TimeUnit.MINUTES.toNanos(1)
  report.invoke(diagnostics, IllegalStateException("SECRET")); assertEquals(2, records.size)
  assertTrue(records.all { it.thrown == null && !it.message.contains("SECRET") })
 }
}
