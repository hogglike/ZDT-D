package com.android.zdtd.service.watch

import java.io.DataInputStream
import java.net.*
import org.junit.Assert.*
import org.junit.Test

class WatchDnsObserverRelayTest {
  private val loopback = InetAddress.getByName("127.0.0.1")

  @Test fun udpQueriesAndRepliesPassThroughTheExistingProfileUnchanged() {
    ServerSocket(0, 1, loopback).use { core ->
      core.soTimeout = 3000
      DatagramSocket(0, loopback).use { upstreamUdp ->
        upstreamUdp.soTimeout = 3000
        var failure: Throwable? = null
        val worker = Thread {
          try {
            core.accept().use { peer ->
              peer.soTimeout = 3000
              val input = negotiate(peer)
              val request = ByteArray(10); input.readFully(request)
              assertArrayEquals(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0), request)
              peer.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0,
                (upstreamUdp.localPort ushr 8).toByte(), upstreamUdp.localPort.toByte()))
              val packet = DatagramPacket(ByteArray(4096), 4096); upstreamUdp.receive(packet)
              val data = packet.data.copyOf(packet.length)
              assertArrayEquals(byteArrayOf(0, 0, 0, 1, 8, 8, 8, 8, 0, 53), data.copyOfRange(0, 10))
              data[12] = 0x81.toByte(); data[13] = 0x80.toByte(); data[17] = 1
              val answer = data + byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 194.toByte(), 5, 65, 8)
              upstreamUdp.send(DatagramPacket(answer, answer.size, packet.address, packet.port))
              assertEquals(-1, input.read()) // Observer releases upstream control when HEV closes its association.
            }
          } catch (e: Throwable) { failure = e }
        }
        worker.start()
        val journal = WatchDnsObservation()
        try {
          WatchDnsObserverRelay(core.localPort, journal).use { relay ->
            journal.start()
            assertEquals(listOf("194.5.65.8"), WatchDnsProbe.query(relay.port, 2000, "gemini.google.com").addresses)
            awaitEvents(journal, 2)
            assertEquals(listOf(false, true), journal.snapshot().events.map { it.response })
            assertEquals(listOf("gemini.google.com", "gemini.google.com"), journal.snapshot().events.map { it.name })
            assertTrue(journal.snapshot().events.all { it.destination == "8.8.8.8" })
          }
        } finally { worker.join(4000); assertFalse(worker.isAlive); failure?.let { throw it } }
      }
    }
  }

  @Test fun ipv6DestinationAndNonDnsBytesAreForwardedExactly() {
    ServerSocket(0, 1, loopback).use { core ->
      core.soTimeout = 3000
      DatagramSocket(0, loopback).use { upstreamUdp ->
        upstreamUdp.soTimeout = 3000
        val data = byteArrayOf(0, 0, 0, 4) + InetAddress.getByName("fd90:7a64:7464::2").address +
          byteArrayOf(1, 0xbb.toByte(), 22, 3, 3, 0, 0)
        var failure: Throwable? = null
        val worker = Thread {
          try {
            core.accept().use { peer ->
              peer.soTimeout = 3000
              val input = negotiate(peer); input.readFully(ByteArray(10))
              peer.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1,
                (upstreamUdp.localPort ushr 8).toByte(), upstreamUdp.localPort.toByte()))
              val packet = DatagramPacket(ByteArray(4096), 4096); upstreamUdp.receive(packet)
              assertArrayEquals(data, packet.data.copyOf(packet.length))
              upstreamUdp.send(DatagramPacket(data, data.size, packet.address, packet.port))
              input.read()
            }
          } catch (e: Throwable) { failure = e }
        }
        worker.start()
        val journal = WatchDnsObservation()
        try {
          WatchDnsObserverRelay(core.localPort, journal).use { relay ->
            journal.start()
            Socket(loopback, relay.port).use { client ->
              client.soTimeout = 2000
              val input = negotiateClient(client)
              client.getOutputStream().write(byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0))
              val reply = ByteArray(10); input.readFully(reply)
              val relayPort = ((reply[8].toInt() and 255) shl 8) or (reply[9].toInt() and 255)
              DatagramSocket(0, loopback).use { udp ->
                udp.soTimeout = 2000
                udp.send(DatagramPacket(data, data.size, loopback, relayPort))
                val packet = DatagramPacket(ByteArray(4096), 4096); udp.receive(packet)
                assertArrayEquals(data, packet.data.copyOf(packet.length))
              }
            }
            assertTrue(journal.snapshot().events.isEmpty())
          }
        } finally { worker.join(4000); assertFalse(worker.isAlive); failure?.let { throw it } }
      }
    }
  }

  @Test fun tcpDnsWorksWithFragmentedCoreRepliesAndHalfClose() {
    ServerSocket(0, 1, loopback).use { core ->
      core.soTimeout = 3000
      var failure: Throwable? = null
      val worker = Thread {
        try {
          core.accept().use { peer ->
            peer.soTimeout = 3000
            val input = negotiate(peer)
            val request = ByteArray(10); input.readFully(request)
            assertArrayEquals(byteArrayOf(5, 1, 0, 1, 8, 8, 8, 8, 0, 53), request)
            peer.getOutputStream().write(byteArrayOf(5, 0, 0, 1, 0, 0, 0, 0, 0, 0))
            val data = ByteArray(input.readUnsignedShort()); input.readFully(data)
            data[2] = 0x81.toByte(); data[3] = 0x80.toByte()
            val framed = byteArrayOf((data.size ushr 8).toByte(), data.size.toByte()) + data
            framed.forEach { peer.getOutputStream().write(it.toInt() and 255); peer.getOutputStream().flush() }
            peer.shutdownOutput()
            assertEquals(-1, input.read())
          }
        } catch (e: Throwable) { failure = e }
      }
      worker.start()
      val journal = WatchDnsObservation()
      try {
        WatchDnsObserverRelay(core.localPort, journal).use { relay ->
          journal.start()
          val answer = WatchDnsProbe.query(relay.port, 2000, "gemini.google.com", overTcp = true)
          assertEquals(0, answer.code)
          awaitEvents(journal, 2)
          assertTrue(journal.snapshot().events.all { it.transport == "tcp" && it.error == null })
        }
      } finally { worker.join(4000); assertFalse(worker.isAlive); failure?.let { throw it } }
    }
  }

  private fun negotiate(peer: Socket): DataInputStream {
    val input = DataInputStream(peer.getInputStream())
    val greeting = ByteArray(3); input.readFully(greeting)
    assertArrayEquals(byteArrayOf(5, 1, 0), greeting)
    peer.getOutputStream().write(byteArrayOf(5, 0)); peer.getOutputStream().flush()
    return input
  }
  private fun negotiateClient(client: Socket): DataInputStream {
    client.getOutputStream().write(byteArrayOf(5, 1, 0)); client.getOutputStream().flush()
    val input = DataInputStream(client.getInputStream())
    assertEquals(5, input.readUnsignedByte()); assertEquals(0, input.readUnsignedByte())
    return input
  }
  private fun awaitEvents(journal: WatchDnsObservation, count: Int) {
    val until = System.nanoTime() + 2_000_000_000L
    while (journal.snapshot().events.size < count && System.nanoTime() < until) Thread.sleep(10)
    assertEquals(count, journal.snapshot().events.size)
  }
}
