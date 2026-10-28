package com.danidipp.sneakymail
import com.danidipp.sneakypocketbase.PocketbaseApi
import java.util.logging.Logger
/** Kept out of the legacy plugin's field and method signatures. */
internal object DurableMailBootstrap {
 fun start(pocketbase: PocketbaseApi, logger: Logger): AutoCloseable =
  MailRuntime(com.sneakydb.SneakyDBProvider.getApi(), pocketbase, logger)
}
