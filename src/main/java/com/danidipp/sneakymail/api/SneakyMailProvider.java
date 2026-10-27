package com.danidipp.sneakymail.api;
import java.util.Objects;
/** Runtime-owned registry. Consumers compileOnly this artifact; never shade it. */
public final class SneakyMailProvider {
 private static volatile SneakyMailApi api;
 private SneakyMailProvider() {}
 public static SneakyMailApi getApi() {
  SneakyMailApi current = api;
  if (current == null || !current.isAvailable()) throw new IllegalStateException("SneakyMail unavailable");
  return current;
 }
 /** Runtime lifecycle seam; rejects replacing another active owner. */
 public static synchronized void install(SneakyMailApi implementation) {
  Objects.requireNonNull(implementation);
  if (api != null && api != implementation) throw new IllegalStateException("SneakyMail already installed");
  api = implementation;
 }
 /** Stale shutdown cannot remove a newer owner. */
 public static synchronized void uninstall(SneakyMailApi implementation) {
  if (api == implementation) api = null;
 }
}
