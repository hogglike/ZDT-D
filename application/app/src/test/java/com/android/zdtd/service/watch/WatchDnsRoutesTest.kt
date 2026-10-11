package com.android.zdtd.service.watch

import java.net.Inet4Address
import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class WatchDnsRoutesTest {
  @Test fun ordinaryGoogleTrafficNeverEntersDnsTun() {
    val routes = WatchDnsRoutes.routes(listOf(InetAddress.getByName("192.168.1.1")))
    assertTrue(routes.all { it.prefix == if (it.address is Inet4Address) 32 else 128 })
    assertFalse(routes.any { it.address == InetAddress.getByName("142.250.185.14") })
    assertTrue(routes.any { it.address == InetAddress.getByName(WatchDnsRoutes.IPV4_DNS) })
    assertTrue(routes.any { it.address == InetAddress.getByName(WatchDnsRoutes.IPV6_DNS) })
    assertTrue(routes.any { it.address == InetAddress.getByName("8.8.8.8") })
    assertTrue(routes.any { it.address == InetAddress.getByName("192.168.1.1") })
  }

  @Test fun networkDnsNeverCreatesDefaultOrLoopbackRoute() {
    val routes = WatchDnsRoutes.routes(listOf("0.0.0.0", "::", "127.0.0.1", "::1", "224.0.0.1", "fe80::1", "8.8.8.8")
      .map(InetAddress::getByName))
    assertFalse(routes.any { it.address.isAnyLocalAddress || it.address.isLoopbackAddress || it.address.isMulticastAddress || it.address.isLinkLocalAddress })
    assertEquals(1, routes.count { it.address == InetAddress.getByName("8.8.8.8") })
  }
}
