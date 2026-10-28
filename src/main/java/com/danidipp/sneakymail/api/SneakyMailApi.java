package com.danidipp.sneakymail.api;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
/** Durable admission, not delivery or redemption. Retry uncertain calls with identical key and payload. */
public interface SneakyMailApi {
 /**
  * Returns durable acceptance, not delivery. All arguments must be non-null.
  * Invalid arguments (including Java nulls), unavailable storage and stopped service
  * are reported by exceptional completion, not by a synchronous argument exception.
  */
 CompletableFuture<MailAcceptance> submitCommandMail(String sourceKey, UUID recipientUuid, String senderName, String note, String command);
 boolean isAvailable();
}
