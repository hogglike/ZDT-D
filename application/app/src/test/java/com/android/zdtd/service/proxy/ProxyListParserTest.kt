package com.android.zdtd.service.proxy

import org.junit.Assert.*
import org.junit.Test

class ProxyListParserTest {
  @Test fun importsWebshareAndEncodesCredentialsWithoutLeakingThem() {
    val result = parseProxyList("\uFEFF# Webshare\n12.34.56.89:8080:alice:p@ss:word\n12.34.56.89:8080:alice:p@ss:word")
    assertEquals(1, result.entries.size)
    assertEquals(1, result.duplicates)
    assertEquals("http://alice:p%40ss%3Aword@12.34.56.89:8080", result.entries.single().url)
    assertEquals("http://***@12.34.56.89:8080", result.entries.single().display)
    assertTrue(result.invalidLines.isEmpty())
  }
  @Test fun rejectsInvalidEndpointsWithoutEchoingSecretRows() {
    val result = parseProxyList("12.34.567.89:8080:user:secret\n12.34.56.89:0000:user:secret\nhttps://user:secret@proxy.example:8080/query\nvless://user:secret@proxy.example:8080")
    assertEquals(listOf(1, 2, 3, 4), result.invalidLines)
    assertTrue(result.entries.isEmpty())
  }
  @Test fun preservesExplicitSchemesAndUsesSelectedProtocolForExports() {
    val result = parseProxyList("proxy.example:1080:name:a b\nhttps://name:pass@proxy.example:443/", "socks5")
    assertEquals(listOf("socks5://name:a%20b@proxy.example:1080", "https://name:pass@proxy.example:443"), result.entries.map { it.url })
  }
  @Test(expected = IllegalArgumentException::class) fun limitsListSize() {
    parseProxyList((1..1001).joinToString("\n") { "proxy.example:$it:user:pass" })
  }
  @Test(expected = IllegalArgumentException::class) fun limitsInputSize() {
    parseProxyList("x".repeat(MAX_PROXY_TEXT_BYTES + 1))
  }
}
