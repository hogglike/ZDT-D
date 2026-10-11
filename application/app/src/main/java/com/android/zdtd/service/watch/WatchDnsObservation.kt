package com.android.zdtd.service.watch

import java.io.ByteArrayOutputStream

/** Bounded, user-started window. Keeps DNS metadata in memory, never packet contents. */
internal class WatchDnsObservation(private val clock: () -> Long = System::currentTimeMillis) {
  data class Event(val time: Long, val flow: Int, val transport: String, val direction: String,
    val destination: String, val port: Int, val id: Int? = null, val name: String? = null,
    val type: Int? = null, val response: Boolean? = null, val code: Int? = null,
    val addresses: List<String> = emptyList(), val truncated: Boolean = false, val error: String? = null)
  data class Snapshot(val available: Boolean, val started: Long, val until: Long,
    val remaining: Int, val dropped: Int, val events: List<Event>)
  private var available = false
  private var started = 0L
  private var until = 0L
  private var dropped = 0
  private val events = ArrayDeque<Event>()

  @Synchronized fun available(value: Boolean) { available = value }
  @Synchronized fun reset() { available = false; started = 0; until = 0; dropped = 0; events.clear() }
  @Synchronized fun start() {
    check(available) { "Включи диагностику и перезапусти DNS часов" }
    started = clock(); until = started + 30_000; dropped = 0; events.clear()
  }
  @Synchronized fun snapshot(): Snapshot = Snapshot(available, started, until,
    ((until - clock()).coerceAtLeast(0) + 999).div(1000).toInt(), dropped, events.toList())
  @Synchronized private fun add(event: Event) {
    if (!available || started == 0L || clock() >= until) return
    if (events.size == 160) { events.removeFirst(); dropped++ }
    events.addLast(event)
  }

  fun error(flow: Int, transport: String, destination: String, port: Int, error: String) =
    add(Event(clock(), flow, transport, "error", destination, port, error = error.take(240)))

  fun dns(flow: Int, transport: String, direction: String, destination: String, port: Int, data: ByteArray) {
    if (port != 53) return
    // Skip parsing altogether outside the requested window.
    if (snapshot().remaining == 0) return
    try {
      check(data.size >= 12)
      fun word(at: Int) = ((data[at].toInt() and 255) shl 8) or (data[at + 1].toInt() and 255)
      check(word(4) > 0)
      val labels = mutableListOf<String>()
      var at = 12
      var questionEnd = -1
      val visited = mutableSetOf<Int>()
      while (true) {
        check(at in data.indices && visited.add(at) && visited.size <= 128)
        val length = data[at].toInt() and 255
        if (length == 0) { if (questionEnd < 0) questionEnd = at + 1; break }
        if (length and 0xc0 == 0xc0) {
          check(at + 1 < data.size)
          if (questionEnd < 0) questionEnd = at + 2
          at = word(at) and 0x3fff
        } else {
          check(length in 1..63 && at + 1 + length <= data.size)
          val label = String(data, at + 1, length, Charsets.US_ASCII)
          check(label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "-_" })
          labels += label
          check(labels.sumOf { it.length + 1 } <= 254)
          at += length + 1
        }
      }
      check(questionEnd + 4 <= data.size)
      val flags = word(2)
      val response = flags and 0x8000 != 0
      val truncated = flags and 0x0200 != 0
      val answer = if (response && !truncated) WatchDnsProbe.parseAnswer(data, word(0)) else null
      add(Event(clock(), flow, transport, direction, destination, port, word(0),
        labels.joinToString(".").lowercase(), word(questionEnd), response,
        if (response) flags and 15 else null, answer?.addresses.orEmpty(), truncated))
    } catch (_: Exception) {
      error(flow, transport, destination, port, "DNS metadata could not be decoded; packet still forwarded")
    }
  }

  /** TCP can split both the two-byte length and the DNS message across any reads. */
  class TcpFrames(private val consume: (ByteArray) -> Unit) {
    private var high = -1
    private var wanted = -1
    private val body = ByteArrayOutputStream()
    fun accept(data: ByteArray, count: Int) {
      require(count in 0..data.size)
      repeat(count) { at ->
        val byte = data[at].toInt() and 255
        when {
          high < 0 -> high = byte
          wanted < 0 -> {
            wanted = (high shl 8) or byte
            if (wanted == 0) { high = -1; wanted = -1 }
          }
          else -> {
            body.write(byte)
            if (body.size() == wanted) {
              consume(body.toByteArray()); body.reset(); high = -1; wanted = -1
            }
          }
        }
      }
    }
  }
}

internal object WatchDnsObserved { val journal = WatchDnsObservation() }
