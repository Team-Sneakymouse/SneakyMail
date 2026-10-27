package com.danidipp.sneakymail

import kotlin.test.*

class PublicApiTest {
 @Test fun `provider lifecycle is identity fenced`() {
  val p = Class.forName("com.danidipp.sneakymail.api.SneakyMailProvider")
  assertFailsWith<java.lang.reflect.InvocationTargetException> { p.getMethod("getApi").invoke(null) }
  val type = Class.forName("com.danidipp.sneakymail.api.SneakyMailApi")
  val instance = java.lang.reflect.Proxy.newProxyInstance(type.classLoader, arrayOf(type)) { _, m, _ -> if (m.name == "isAvailable") true else null }
  p.getMethod("install", type).invoke(null, instance)
  assertSame(instance, p.getMethod("getApi").invoke(null))
  p.getMethod("uninstall", type).invoke(null, instance)
  assertFailsWith<java.lang.reflect.InvocationTargetException> { p.getMethod("getApi").invoke(null) }
 }

 @Test fun `public contract exists without implementation dependencies`() {
  val api = Class.forName("com.danidipp.sneakymail.api.SneakyMailApi")
  assertEquals(java.util.concurrent.CompletableFuture::class.java, api.getMethod("submitCommandMail", String::class.java, java.util.UUID::class.java, String::class.java, String::class.java, String::class.java).returnType)
  val receipt = Class.forName("com.danidipp.sneakymail.api.MailAcceptance")
  assertTrue(receipt.isRecord)
  assertEquals(listOf("requestId"), receipt.recordComponents.map { it.name })
 }
}
