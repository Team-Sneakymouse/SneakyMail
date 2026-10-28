package com.danidipp.sneakymail
import java.util.concurrent.*
import java.util.function.LongSupplier
import java.util.logging.Logger
internal fun unwrapped(error: Throwable): Throwable {
 var cause = error
 while ((cause is CompletionException || cause is ExecutionException) && cause.cause != null) cause = cause.cause!!
 return cause
}
/** No provider message, cause, payload or stack is retained in this diagnostic. */
class DeliveryFailure(stage: String, id: String?, error: Throwable) : RuntimeException(
 "stage=$stage request=${id?.takeIf { it.matches(Regex("[a-z0-9]{15}")) } ?: "unclaimed"} category=${unwrapped(error).javaClass.simpleName}", null, false, false)
class DeliveryDiagnostics(private val logger: Logger, private val clock: LongSupplier) {
 private var lastWarning: Long? = null
 @Synchronized fun report(error: Throwable) {
  val now = clock.asLong
  if (lastWarning == null || now - lastWarning!! >= TimeUnit.MINUTES.toNanos(1)) {
   lastWarning = now
   val cause = unwrapped(error)
   val detail = if (cause is DeliveryFailure) cause.message else "stage=UNKNOWN request=unclaimed category=${cause.javaClass.simpleName}"
   logger.warning("Mail delivery pending; journal retained; $detail")
  }
 }
}
