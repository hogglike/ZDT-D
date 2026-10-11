package com.android.zdtd.service.watch

import java.net.Inet4Address
import java.net.InetAddress

/** Host routes keep ordinary companion traffic on the underlying network. */
internal object WatchDnsRoutes {
  const val IPV4_ADDRESS = "198.19.255.1"
  const val IPV6_ADDRESS = "fd90:7a64:7464::1"
  const val IPV4_DNS = "198.19.255.2"
  const val IPV6_DNS = "fd90:7a64:7464::2"

  // Wear OS may retain a public resolver rather than Android's VPN DNS hint.
  private val knownDns = listOf(IPV4_DNS, IPV6_DNS,
    "8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1", "9.9.9.9", "149.112.112.112",
    "2001:4860:4860::8888", "2001:4860:4860::8844", "2606:4700:4700::1111", "2606:4700:4700::1001")
    .map(InetAddress::getByName)

  data class Route(val address: InetAddress, val prefix: Int) {
    val display: String get() = "${address.hostAddress}/$prefix"
  }

  fun routes(networkDns: Collection<InetAddress>): List<Route> = (knownDns + networkDns)
    .filterNot { it.isAnyLocalAddress || it.isLoopbackAddress || it.isMulticastAddress || it.isLinkLocalAddress }
    .distinct().map { Route(it, if (it is Inet4Address) 32 else 128) }
}
