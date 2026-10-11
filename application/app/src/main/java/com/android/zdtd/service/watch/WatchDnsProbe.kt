package com.android.zdtd.service.watch

import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.random.Random

/** Real UDP/TCP queries through the same profile as the DNS-address TUN. */
internal object WatchDnsProbe {
  data class Answer(val code: Int, val records: Int, val addresses: List<String>)

  fun check(port: Int, timeout: Int) {
    val answer = query(port, timeout, "example.com")
    check(answer.code == 0 && answer.records > 0) {
      "DNS-профиль не ответил на example.com через SOCKS UDP (код ${answer.code})"
    }
  }

  fun query(port: Int, timeout: Int, name: String, type: Int = 1, overTcp: Boolean = false): Answer {
    val labels = name.split('.')
    require(name.length <= 253 && labels.all { label -> label.length in 1..63 && label.all { it.isLetterOrDigit() && it.code < 128 || it == '-' } })
    require(type == 1 || type == 28)
    val id = Random.nextInt(65536)
    val dns = byteArrayOf((id ushr 8).toByte(), id.toByte(), 1, 0, 0, 1, 0, 0, 0, 0, 0, 0) +
      labels.flatMap { listOf(it.length.toByte()) + it.toByteArray(Charsets.US_ASCII).toList() }.toByteArray() +
      byteArrayOf(0, 0, type.toByte(), 0, 1)
    Socket().use { tcp ->
      tcp.soTimeout = timeout
      tcp.connect(InetSocketAddress("127.0.0.1", port), timeout)
      val input = DataInputStream(tcp.getInputStream())
      val output = tcp.getOutputStream()
      output.write(byteArrayOf(5, 1, 0))
      output.flush()
      check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "DNS SOCKS: negotiation failed" }
      output.write(if (overTcp) byteArrayOf(5, 1, 0, 1, 8, 8, 8, 8, 0, 53)
        else byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
      output.flush()
      check(input.readUnsignedByte() == 5 && input.readUnsignedByte() == 0) { "DNS SOCKS: ${if (overTcp) "TCP" else "UDP"} is unavailable" }
      input.readUnsignedByte()
      val addressBytes = when (input.readUnsignedByte()) {
        1 -> ByteArray(4)
        4 -> ByteArray(16)
        else -> error("DNS SOCKS: invalid loopback relay")
      }
      input.readFully(addressBytes)
      var address = InetAddress.getByAddress(addressBytes)
      if (address.isAnyLocalAddress) address = InetAddress.getByName("127.0.0.1")
      check(overTcp || address.isLoopbackAddress) { "DNS SOCKS: relay must remain on loopback" }
      val relayPort = input.readUnsignedShort()
      if (overTcp) {
        output.write(byteArrayOf((dns.size ushr 8).toByte(), dns.size.toByte()) + dns)
        output.flush()
        val size = input.readUnsignedShort()
        check(size in 12..65535) { "DNS SOCKS: truncated TCP DNS answer" }
        val data = ByteArray(size)
        input.readFully(data)
        return parseAnswer(data, id)
      }
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
        return parseAnswer(data.copyOfRange(offset, response.length), id)
      }
    }
  }

  internal fun parseAnswer(data: ByteArray, id: Int): Answer {
    fun u16(index: Int): Int {
      check(index >= 0 && index + 2 <= data.size) { "DNS: truncated answer" }
      return ((data[index].toInt() and 255) shl 8) or (data[index + 1].toInt() and 255)
    }
    check(data.size >= 12 && u16(0) == id && u16(2) and 0x8000 != 0) { "DNS: invalid answer or transaction ID" }
    check(u16(2) and 0x0200 == 0) { "DNS: truncated response; retry over TCP" }
    fun skipName(start: Int): Int {
      var at = start
      repeat(128) {
        check(at < data.size) { "DNS: truncated name" }
        val length = data[at].toInt() and 255
        if (length == 0) return at + 1
        if (length and 0xc0 == 0xc0) {
          check((u16(at) and 0x3fff) < data.size) { "DNS: invalid name pointer" }
          return at + 2
        }
        check(length in 1..63 && at + 1 + length <= data.size) { "DNS: invalid name" }
        at += 1 + length
      }
      error("DNS: name is too long")
    }
    var at = 12
    repeat(u16(4)) { at = skipName(at); u16(at + 2); at += 4 }
    val addresses = mutableListOf<String>()
    val records = u16(6)
    repeat(records) {
      at = skipName(at)
      val type = u16(at)
      val clazz = u16(at + 2)
      val length = u16(at + 8)
      at += 10
      check(at + length <= data.size) { "DNS: truncated record" }
      if (clazz == 1 && (type == 1 && length == 4 || type == 28 && length == 16))
        addresses += InetAddress.getByAddress(data.copyOfRange(at, at + length)).hostAddress.orEmpty()
      at += length
    }
    return Answer(u16(2) and 15, records, addresses.distinct())
  }
}
