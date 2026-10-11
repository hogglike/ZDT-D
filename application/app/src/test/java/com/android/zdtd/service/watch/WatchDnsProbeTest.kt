package com.android.zdtd.service.watch

import java.io.DataInputStream
import java.net.*
import org.junit.Assert.*
import org.junit.Test

class WatchDnsProbeTest {
  @Test fun validAnswerArrivesThroughSocksUdp() { fixture(0) { WatchDnsProbe.check(it, 2000) } }

  @Test(expected = IllegalStateException::class)
  fun servfailDoesNotClaimWorkingDns() { fixture(2) { WatchDnsProbe.check(it, 2000) } }

  @Test fun reportContainsTheActualSmartDnsAnswer() {
    fixture(0) { port ->
      val answer = WatchDnsProbe.query(port, 2000, "gemini.google.com")
      assertEquals(0, answer.code)
      assertEquals(listOf("127.0.0.2"), answer.addresses)
    }
  }

  @Test fun realTcpDnsAnswerCanBeReadInFragments() {
    ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
      server.soTimeout = 3000
      var failure: Throwable? = null
      val worker = Thread {
        try {
          server.accept().use { tcp ->
            tcp.soTimeout = 3000
            val input = DataInputStream(tcp.getInputStream())
            val greeting = ByteArray(3); input.readFully(greeting)
            check(greeting.contentEquals(byteArrayOf(5, 1, 0)))
            tcp.getOutputStream().write(byteArrayOf(5, 0))
            val request = ByteArray(10); input.readFully(request)
            check(request.contentEquals(byteArrayOf(5, 1, 0, 1, 8, 8, 8, 8, 0, 53)))
            tcp.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            val data = ByteArray(input.readUnsignedShort()); input.readFully(data)
            data[2] = 0x81.toByte(); data[3] = 0x80.toByte(); data[7] = 1
            val answer = data + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 2)
            val framed = byteArrayOf((answer.size ushr 8).toByte(), answer.size.toByte()) + answer
            framed.forEach { tcp.getOutputStream().write(it.toInt() and 255) }
            tcp.getOutputStream().flush()
          }
        } catch (e: Throwable) { failure = e }
      }
      worker.start()
      try { assertEquals(listOf("127.0.0.2"), WatchDnsProbe.query(server.localPort, 2000, "example.com", overTcp = true).addresses) }
      finally { worker.join(4000); failure?.let { throw it } }
    }
  }

  @Test fun emptyIpv6AnswerIsReportedWithoutPretendingItIsTransportFailure() {
    val data = byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 1, 0, 0, 0, 0, 0, 0, 1, 97, 0, 0, 28, 0, 1)
    val answer = WatchDnsProbe.parseAnswer(data, 0x1234)
    assertEquals(0, answer.code)
    assertEquals(0, answer.records)
    assertTrue(answer.addresses.isEmpty())
  }

  @Test(expected = IllegalStateException::class)
  fun mismatchedTransactionCannotPassDiagnostics() {
    WatchDnsProbe.parseAnswer(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0, 0, 0), 0x1235)
  }

  @Test(expected = IllegalStateException::class)
  fun truncatedResourceRecordCannotPassDiagnostics() {
    WatchDnsProbe.parseAnswer(byteArrayOf(0x12, 0x34, 0x81.toByte(), 0x80.toByte(), 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1), 0x1234)
  }

  private fun fixture(rcode: Int, run: (Int) -> Unit) {
    val loopback = InetAddress.getByName("127.0.0.1")
    ServerSocket(0, 1, loopback).use { server ->
      server.soTimeout = 3000
      DatagramSocket(0, loopback).use { udp ->
        udp.soTimeout = 3000
        var failure: Throwable? = null
        val worker = Thread {
          try {
            server.accept().use { tcp ->
              tcp.soTimeout = 3000
              val input = DataInputStream(tcp.getInputStream())
              val greeting = ByteArray(3); input.readFully(greeting)
              check(greeting.contentEquals(byteArrayOf(5, 1, 0)))
              tcp.getOutputStream().write(byteArrayOf(5, 0))
              val association = ByteArray(10); input.readFully(association)
              check(association[1] == 3.toByte())
              tcp.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1,
                (udp.localPort ushr 8).toByte(), udp.localPort.toByte()))
              val request = DatagramPacket(ByteArray(4096), 4096)
              udp.receive(request)
              val data = request.data.copyOf(request.length)
              check(data.copyOfRange(0, 10).contentEquals(byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53)))
              data[12] = 0x81.toByte(); data[13] = (0x80 or rcode).toByte()
              data[16] = 0; data[17] = if (rcode == 0) 1 else 0
              val answer = if (rcode == 0) data + byteArrayOf(
                0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 127, 0, 0, 2,
              ) else data
              udp.send(DatagramPacket(answer, answer.size, request.address, request.port))
            }
          } catch (e: Throwable) { failure = e }
        }
        worker.start()
        try { run(server.localPort) }
        finally { worker.join(4000); failure?.let { throw it } }
      }
    }
  }
}
