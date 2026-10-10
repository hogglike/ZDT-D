package com.android.zdtd.service.watch

import java.io.DataInputStream
import java.net.*
import org.junit.Test

class WatchDnsProbeTest {
  @Test fun validAnswerArrivesThroughSocksUdp() { fixture(0) { WatchDnsProbe.check(it, 2000) } }

  @Test(expected = IllegalStateException::class)
  fun servfailDoesNotClaimWorkingDns() { fixture(2) { WatchDnsProbe.check(it, 2000) } }

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
