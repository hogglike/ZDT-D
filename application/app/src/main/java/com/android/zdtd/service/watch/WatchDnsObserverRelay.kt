package com.android.zdtd.service.watch

import java.io.Closeable
import java.io.DataInputStream
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Optional loopback SOCKS observer between HEV and the existing profile. No DNS resolver of its own. */
internal class WatchDnsObserverRelay(private val upstreamPort: Int, private val journal: WatchDnsObservation) : Closeable {
  private val loopback = InetAddress.getByName("127.0.0.1")
  private val server = ServerSocket(0, 16, loopback)
  val port: Int get() = server.localPort
  private val resources = ConcurrentHashMap.newKeySet<Closeable>()
  private val slots = Semaphore(16)
  private val ids = AtomicInteger()
  private val workers = Executors.newCachedThreadPool { task -> Thread(task, "watch-dns-observer").apply { isDaemon = true } }
  @Volatile private var closed = false

  init {
    resources.add(server)
    journal.available(true)
    workers.execute {
      while (!closed) {
        val client = try { server.accept() } catch (_: Exception) { break }
        if (!slots.tryAcquire()) { client.close(); continue }
        try {
          own(client)
          workers.execute {
            try { handle(client, ids.incrementAndGet()) }
            finally { forget(client); slots.release() }
          }
        } catch (_: Exception) { client.close(); slots.release() }
      }
    }
  }

  private fun <T : Closeable> own(resource: T): T {
    resources.add(resource)
    if (closed) { forget(resource); error("DNS observer stopped") }
    return resource
  }
  private fun forget(resource: Closeable) { resources.remove(resource); runCatching { resource.close() } }

  private data class Address(val wire: ByteArray, val host: String, val port: Int)
  private fun address(input: DataInputStream): Address {
    val type = input.readUnsignedByte()
    val bytes = when (type) {
      1 -> ByteArray(4)
      4 -> ByteArray(16)
      3 -> ByteArray(input.readUnsignedByte().also { check(it > 0) })
      else -> error("Invalid SOCKS address")
    }
    input.readFully(bytes)
    val port = input.readUnsignedShort()
    val wire = byteArrayOf(type.toByte()) + (if (type == 3) byteArrayOf(bytes.size.toByte()) else byteArrayOf()) +
      bytes + byteArrayOf((port ushr 8).toByte(), port.toByte())
    return Address(wire, if (type == 3) String(bytes, Charsets.US_ASCII) else InetAddress.getByAddress(bytes).hostAddress.orEmpty(), port)
  }

  private fun handle(client: Socket, id: Int) {
    var upstream: Socket? = null
    var destination = Address(byteArrayOf(), "", 0)
    var transport = "socks"
    try {
      client.soTimeout = 10_000
      val input = DataInputStream(client.getInputStream())
      val output = client.getOutputStream()
      check(input.readUnsignedByte() == 5)
      val methods = ByteArray(input.readUnsignedByte()); input.readFully(methods)
      if (0.toByte() !in methods) { output.write(byteArrayOf(5, 0xff.toByte())); return }
      output.write(byteArrayOf(5, 0)); output.flush()
      check(input.readUnsignedByte() == 5)
      val command = input.readUnsignedByte(); check(input.readUnsignedByte() == 0)
      destination = address(input)
      check(command == 1 || command == 3) { "Unsupported SOCKS command" }
      transport = if (command == 3) "udp" else "tcp"
      val peer = own(Socket())
      upstream = peer
      peer.soTimeout = 10_000
      peer.connect(InetSocketAddress(loopback, upstreamPort), 10_000)
      val reply = DataInputStream(peer.getInputStream())
      val send = peer.getOutputStream()
      send.write(byteArrayOf(5, 1, 0)); send.flush()
      check(reply.readUnsignedByte() == 5 && reply.readUnsignedByte() == 0) { "Profile SOCKS negotiation failed" }
      val request = if (command == 3) byteArrayOf(5, 3, 0, 1, 0, 0, 0, 0, 0, 0)
        else byteArrayOf(5, 1, 0) + destination.wire
      send.write(request); send.flush()
      check(reply.readUnsignedByte() == 5)
      val code = reply.readUnsignedByte(); check(reply.readUnsignedByte() == 0)
      val bound = address(reply)
      if (code != 0) {
        output.write(byteArrayOf(5, code.toByte(), 0) + bound.wire); output.flush()
        error("Profile SOCKS rejected connection (code $code)")
      }
      if (command == 3) udp(client, input, output, bound, id)
      else {
        output.write(byteArrayOf(5, 0, 0) + bound.wire); output.flush()
        client.soTimeout = 300_000; peer.soTimeout = 300_000
        tcp(client, peer, destination, id)
      }
    } catch (e: Exception) {
      if (!closed) journal.error(id, transport, destination.host, destination.port,
        "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
    } finally { upstream?.let { forget(it) } }
  }

  private fun tcp(client: Socket, peer: Socket, destination: Address, id: Int) {
    val done = CountDownLatch(2)
    fun pump(source: Socket, target: Socket, direction: String) {
      workers.execute {
        try {
          val frames = if (destination.port == 53) WatchDnsObservation.TcpFrames {
            journal.dns(id, "tcp", direction, destination.host, destination.port, it)
          } else null
          val buffer = ByteArray(8192)
          val input = source.getInputStream(); val output = target.getOutputStream()
          while (!closed) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            frames?.accept(buffer, count)
          }
          runCatching { target.shutdownOutput() }
        } catch (e: Exception) {
          if (!closed) journal.error(id, "tcp", destination.host, destination.port,
            "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
          runCatching { source.close() }; runCatching { target.close() }
        } finally { done.countDown() }
      }
    }
    pump(client, peer, "sent"); pump(peer, client, "received")
    while (!closed && !done.await(1, TimeUnit.SECONDS)) { /* close() releases blocked readers. */ }
  }

  private fun udp(client: Socket, input: DataInputStream, output: java.io.OutputStream, bound: Address, id: Int) {
    check(bound.wire.first() != 3.toByte()) { "Profile UDP relay must use a literal loopback address" }
    val remoteAddress = InetAddress.getByName(bound.host).let { if (it.isAnyLocalAddress) loopback else it }
    check(remoteAddress.isLoopbackAddress && remoteAddress.address.size == 4 && bound.port > 0) { "Invalid profile UDP relay" }
    val socket = own(DatagramSocket(0, loopback))
    try {
      output.write(byteArrayOf(5, 0, 0, 1, 127, 0, 0, 1,
        (socket.localPort ushr 8).toByte(), socket.localPort.toByte())); output.flush()
      socket.soTimeout = 1000
      val stopped = CountDownLatch(1)
      workers.execute {
        var clientPort = -1
        try {
          while (!closed && !socket.isClosed) {
            val packet = DatagramPacket(ByteArray(65535), 65535)
            try { socket.receive(packet) } catch (_: SocketTimeoutException) { continue }
            if (!packet.address.isLoopbackAddress) continue
            val inbound = packet.address == remoteAddress && packet.port == bound.port
            if (!inbound) {
              if (clientPort < 0) clientPort = packet.port
              if (packet.port != clientPort) continue
            } else if (clientPort < 0) continue
            val bytes = packet.data.copyOf(packet.length)
            // Forward the exact SOCKS datagram, including original destination and reply source.
            socket.send(DatagramPacket(bytes, bytes.size, if (inbound) loopback else remoteAddress,
              if (inbound) clientPort else bound.port))
            runCatching {
              val header = DataInputStream(bytes.inputStream())
              check(header.readUnsignedShort() == 0 && header.readUnsignedByte() == 0)
              val address = address(header)
              val offset = bytes.size - header.available()
              journal.dns(id, "udp", if (inbound) "received" else "sent", address.host, address.port,
                bytes.copyOfRange(offset, bytes.size))
            }
          }
        } catch (e: Exception) {
          if (!closed && !socket.isClosed) journal.error(id, "udp", "", 0,
            "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
          runCatching { client.close() }
        } finally { stopped.countDown() }
      }
      client.soTimeout = 1000
      while (!closed && stopped.count > 0) {
        try { if (input.read() < 0) break } catch (_: SocketTimeoutException) { /* TCP control keeps association alive. */ }
      }
    } finally { forget(socket) }
  }

  override fun close() {
    closed = true
    journal.available(false)
    resources.toList().forEach { forget(it) }
    workers.shutdownNow()
  }
}
