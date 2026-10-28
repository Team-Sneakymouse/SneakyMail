package com.danidipp.sneakymail
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
class OptionalDatabaseTest {
 @Test fun `real enable boundary initializes legacy and never resolves absent SQL provider`() {
  val type = runCatching { Class.forName("com.danidipp.sneakymail.MailBootstrap") }.getOrNull()
  assertNotNull(type, "Bootstrap must expose actual optional branch at injectable boundaries")
  for (enabled in listOf(false, true)) {
   val calls = mutableListOf<String>()
   val instance = type.getDeclaredConstructor().newInstance()
   val closeable = AutoCloseable { calls.add("close") }
   val result = type.getMethod("enable", java.util.function.BooleanSupplier::class.java, Runnable::class.java, java.util.function.Supplier::class.java).invoke(instance,
    java.util.function.BooleanSupplier { calls.add("enabled"); enabled }, Runnable { calls.add("legacy") }, java.util.function.Supplier { assertTrue(enabled); calls.add("provider"); closeable })
   assertEquals(if (enabled) listOf("legacy", "enabled", "provider") else listOf("legacy", "enabled"), calls)
   if (enabled) assertSame(closeable, result) else assertNull(result)
  }
 }

 @Test fun `database dependency is optional and bootstrap does not resolve SQL unconditionally`() {
  val descriptor = Files.readString(Path.of("src/resources/paper-plugin.yml"))
  assertTrue(descriptor.substringAfter("    SneakyDB:").substringBefore("    SneakyPocketbase:").contains("required: false"))
  val source = Files.readString(Path.of("src/main/kotlin/com/danidipp/sneakymail/SneakyMail.kt"))
  assertFalse(source.contains("com.sneakydb.SneakyDBProvider.getApi()"))
  assertTrue(source.contains("isPluginEnabled(\"SneakyDB\")"))
 }
}
