package com.danidipp.sneakymail
import java.util.function.BooleanSupplier
import java.util.function.Supplier
/** Actual plugin enable ordering with SQL-free injection boundaries. */
class MailBootstrap {
 fun enable(databaseEnabled: BooleanSupplier, legacy: Runnable, durable: Supplier<AutoCloseable>): AutoCloseable? {
  legacy.run()
  return if (databaseEnabled.asBoolean) durable.get() else null
 }
}
