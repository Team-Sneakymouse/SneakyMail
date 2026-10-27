package com.danidipp.sneakymail.api;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
/** Durable admission, not delivery or redemption. Retry uncertain calls with identical key and payload. */
public interface SneakyMailApi {
 CompletableFuture<MailAcceptance> submitCommandMail(String sourceKey, UUID recipientUuid, String senderName, String note, String command);
 boolean isAvailable();
}
