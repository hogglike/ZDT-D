package com.android.zdtd.service.watch

import org.junit.Assert.*
import org.junit.Test

class WatchDnsObservationTest {
  private fun question() = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0,
    6, 103, 101, 109, 105, 110, 105, 6, 103, 111, 111, 103, 108, 101, 3, 99, 111, 109, 0, 0, 1, 0, 1)

  @Test fun recordingIsOptInBoundedAndExpires() {
    var now = 1000L
    val journal = WatchDnsObservation { now }
    journal.available(true)
    journal.dns(1, "udp", "sent", "198.19.255.2", 53, question())
    assertTrue(journal.snapshot().events.isEmpty())
    journal.start()
    repeat(170) { journal.dns(it, "udp", "sent", "198.19.255.2", 53, question()) }
    assertEquals(160, journal.snapshot().events.size)
    assertEquals(10, journal.snapshot().dropped)
    now += 30_000
    journal.dns(171, "udp", "sent", "198.19.255.2", 53, question())
    assertEquals(0, journal.snapshot().remaining)
    assertEquals(160, journal.snapshot().events.size)
    journal.start()
    assertTrue(journal.snapshot().events.isEmpty())
  }

  @Test fun dnsAnswersKeepRealAddressAndOriginalSource() {
    val journal = WatchDnsObservation { 1000 }
    journal.available(true); journal.start()
    val data = question().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[7] = 1 } +
      byteArrayOf(0xc0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 194.toByte(), 5, 65, 8)
    journal.dns(1, "udp", "received", "198.19.255.2", 53, data)
    val event = journal.snapshot().events.single()
    assertEquals("gemini.google.com", event.name)
    assertEquals("198.19.255.2", event.destination)
    assertEquals(listOf("194.5.65.8"), event.addresses)
    assertEquals(0, event.code)
    assertEquals(true, event.response)
  }

  @Test fun emptyIpv6AndTruncationAreNotConfusedWithNoReply() {
    val journal = WatchDnsObservation { 1000 }
    journal.available(true); journal.start()
    val empty = question().apply { this[2] = 0x81.toByte(); this[3] = 0x80.toByte(); this[size - 3] = 28 }
    journal.dns(1, "udp", "received", "fd90:7a64:7464::2", 53, empty)
    journal.dns(2, "udp", "received", "198.19.255.2", 53, empty.copyOf().apply { this[2] = 0x83.toByte() })
    assertEquals(28, journal.snapshot().events.first().type)
    assertEquals(true, journal.snapshot().events.last().truncated)
    assertTrue(journal.snapshot().events.all { it.error == null && it.response == true })
  }

  @Test fun malformedCompressionCannotLoopOrPreventForwarding() {
    val journal = WatchDnsObservation { 1000 }
    journal.available(true); journal.start()
    val data = byteArrayOf(0x12, 0x34, 1, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0xc0.toByte(), 12, 0, 1, 0, 1)
    journal.dns(1, "udp", "sent", "198.19.255.2", 53, data)
    assertNotNull(journal.snapshot().events.single().error)
    assertNull(journal.snapshot().events.single().name)
  }

  @Test fun tcpFramesHandleSplitLengthsAndCoalescedMessages() {
    val messages = mutableListOf<ByteArray>()
    val decoder = WatchDnsObservation.TcpFrames { messages += it }
    val data = question()
    val framed = byteArrayOf(0, data.size.toByte()) + data
    decoder.accept(framed.copyOfRange(0, 1), 1)
    decoder.accept(framed.copyOfRange(1, 4), 3)
    val tailAndNext = framed.copyOfRange(4, framed.size) + framed
    decoder.accept(tailAndNext, tailAndNext.size)
    assertEquals(2, messages.size)
    messages.forEach { assertArrayEquals(data, it) }
  }

  @Test fun encryptedTrafficIsNotDecodedOrRetained() {
    val journal = WatchDnsObservation { 1000 }
    journal.available(true); journal.start()
    journal.dns(1, "tcp", "sent", "1.1.1.1", 443, byteArrayOf(22, 3, 3, 0, 8))
    assertTrue(journal.snapshot().events.isEmpty())
    journal.available(false)
    journal.error(1, "tcp", "", 0, "closed")
    assertTrue(journal.snapshot().events.isEmpty())
  }
}
