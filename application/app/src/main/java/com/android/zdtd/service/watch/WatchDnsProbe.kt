package com.android.zdtd.service.watch

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/** Probe the very same SOCKS UDP/DNS path used by the Android TUN bridge. */
internal object WatchDnsProbe {
  fun check(port: Int, timeout: Int) {
    Socket().use { tcp ->
      tcp.soTimeout = timeout
      tcp.connect(InetSocketAddress("127.0.0.1", port), timeout)
      val input = DataInputStream(tcp.getInputStream())
      val output = tcp.getOutputStream()
      output.write(byteArrayOf(5, 1, 0))
      output.flush()
      check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "DNS SOCKS: negotiation failed" }
      output.write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
      output.flush()
      check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "DNS SOCKS: UDP is unavailable" }
      input.readUnsignedByte()
      val addressBytes = when (input.readUnsignedByte()) {
        1 -> ByteArray(4)
        4 -> ByteArray(16)
        else -> error("DNS SOCKS: invalid loopback relay")
      }
      input.readFully(addressBytes)
      var address = InetAddress.getByAddress(addressBytes)
      if (address.isAnyLocalAddress) address = InetAddress.getByName("127.0.0.1")
      check(address.isLoopbackAddress) { "DNS SOCKS: relay must remain on loopback" }
      val relayPort = input.readUnsignedShort()
      val id = Random.nextInt(65536)
      val dns = byteArrayOf(
        (id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
        7, 101, 120, 97, 109, 112, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1,
      )
      val request = byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53) + dns
      DatagramSocket().use { udp ->
        udp.soTimeout = timeout
        udp.connect(address, relayPort)
        udp.send(DatagramPacket(request, request.size))
        val response = DatagramPacket(ByteArray(4096), 4096)
        udp.receive(response)
        val data = response.data
        check(response.length > 4 && data[0] == 0.toByte() && data[1] == 0.toByte() && data[2] == 0.toByte()) {
          "DNS SOCKS: invalid UDP response"
        }
        val offset = when (data[3].toInt() and 255) {
          1 -> 10
          4 -> 22
          3 -> 7 + (data[4].toInt() and 255)
          else -> error("DNS SOCKS: invalid UDP address")
        }
        check(response.length >= offset + 12) { "DNS SOCKS: truncated DNS answer" }
        fun u16(index: Int) = ((data[index].toInt() and 255) shl 8) or (data[index + 1].toInt() and 255)
        check(u16(offset) == id && u16(offset + 2) and 0x800f == 0x8000 && u16(offset + 6) > 0) {
          "DNS-профиль не ответил на example.com через SOCKS UDP"
        }
      }
    }
  }
}
