package dev.minios.tgwsproxy.proxy

import java.io.File
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.KeyStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test

class SplitTlsTest {
    companion object {
        private const val SNI = "proxy-zone.example"
        /** Larger than one TLS record (16 KiB), so the reply arrives in several records. */
        private val BIG = ByteArray(40_000) { (it % 251).toByte() }
        private lateinit var keystoreDir: File
        private lateinit var serverContext: SSLContext

        /** Throwaway self-signed key from the test JDK's keytool: no key material in the repo. */
        @JvmStatic @BeforeClass fun createServerKey() {
            keystoreDir = java.nio.file.Files.createTempDirectory("split-tls").toFile()
            val store = File(keystoreDir, "server.p12")
            val keytool = File(System.getProperty("java.home"), "bin/keytool" + if (File.separatorChar == '\\') ".exe" else "")
            val process = ProcessBuilder(
                keytool.path, "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "2", "-dname", "CN=$SNI", "-storetype", "PKCS12",
                "-keystore", store.path, "-storepass", "testpass", "-keypass", "testpass",
            ).redirectErrorStream(true).start()
            process.inputStream.readBytes()
            check(process.waitFor() == 0) { "keytool failed" }
            val ks = KeyStore.getInstance("PKCS12").apply { store.inputStream().use { load(it, "testpass".toCharArray()) } }
            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(ks, "testpass".toCharArray()) }
            serverContext = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        }

        @JvmStatic @AfterClass fun cleanup() {
            keystoreDir.deleteRecursively()
        }
    }

    /** A real ClientHello record from the JDK's SSLEngine, with [SNI]. */
    private fun clientHelloRecord(): ByteArray {
        val engine = SSLContext.getDefault().createSSLEngine(SNI, 443).apply {
            useClientMode = true
            sslParameters = sslParameters.also { it.serverNames = listOf(SNIHostName(SNI)) }
        }
        val out = ByteBuffer.allocate(engine.session.packetBufferSize)
        engine.wrap(ByteBuffer.allocate(0), out)
        out.flip()
        return ByteArray(out.remaining()).also { out.get(it) }
    }

    @Test fun clientHelloIsCutIntoTwoRecordsInsideTheServerName() {
        val record = clientHelloRecord()
        val (head, tail) = SplitTls.splitClientHello(record, SNI)
        val wire = head + tail
        assertEquals(record.size + 5, wire.size)

        val firstLen = ((wire[3].toInt() and 0xFF) shl 8) or (wire[4].toInt() and 0xFF)
        val second = 5 + firstLen
        val secondLen = ((wire[second + 3].toInt() and 0xFF) shl 8) or (wire[second + 4].toInt() and 0xFF)
        // Both records are handshake records with the original version bytes.
        for (at in listOf(0, second)) {
            assertEquals(0x16.toByte(), wire[at])
            assertEquals(record[1], wire[at + 1])
            assertEquals(record[2], wire[at + 2])
        }
        assertEquals(record.size - 5, firstLen + secondLen)
        // Payloads joined back give the original handshake message.
        val joined = wire.copyOfRange(5, second) + wire.copyOfRange(second + 5, wire.size)
        assertArrayEquals(record.copyOfRange(5, record.size), joined)
        // The server name is in neither record whole.
        val name = SNI.toByteArray()
        assertFalse(contains(wire.copyOfRange(5, second), name))
        assertFalse(contains(wire.copyOfRange(second + 5, wire.size), name))
        // The first TCP segment ends one byte into the second record.
        assertEquals(second + 6, head.size)
    }

    @Test fun recordWithoutTheNameIsCutInTheMiddle() {
        val record = clientHelloRecord()
        val (head, tail) = SplitTls.splitClientHello(record, "absent.example")
        val firstLen = ((head[3].toInt() and 0xFF) shl 8) or (head[4].toInt() and 0xFF)
        assertEquals((record.size - 5) / 2, firstLen)
        assertEquals(record.size + 5, head.size + tail.size)
    }

    @Test fun splitHelloCompletesTlsAndCarriesWebSocketBothWays() {
        val executor = Executors.newSingleThreadExecutor()
        (serverContext.serverSocketFactory.createServerSocket(0) as SSLServerSocket).use { server ->
            server.soTimeout = 5_000
            val peer = executor.submit<String> {
                (server.accept() as SSLSocket).use { socket ->
                    socket.soTimeout = 5_000
                    val input = socket.inputStream
                    val output = socket.outputStream
                    val header = StringBuilder()
                    while (!header.endsWith("\r\n\r\n")) {
                        val next = input.read()
                        check(next >= 0)
                        header.append(next.toChar())
                    }
                    val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()
                    val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                        .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
                    output.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\n" +
                        "Connection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
                    output.flush()
                    check(input.read() == 0x82)
                    val len = input.read() and 0x7F
                    val mask = ByteArray(4) { input.read().toByte() }
                    val payload = ByteArray(len) { (input.read() xor mask[it % 4].toInt()).toByte() }
                    // Reply with a frame larger than one TLS record to exercise reassembly.
                    output.write(byteArrayOf(0x82.toByte(), 126.toByte(),
                        (BIG.size shr 8).toByte(), BIG.size.toByte()) + BIG)
                    output.flush()
                    val sni = (socket.session as javax.net.ssl.ExtendedSSLSession).requestedServerNames
                        .filterIsInstance<SNIHostName>().firstOrNull()?.asciiName
                    header.lines().first() + "|" + payload.joinToString(",") + "|" + sni
                }
            }
            try {
                RawWebSocket.connect("127.0.0.1", "kws2.$SNI", port = server.localPort,
                    sni = SNI, splitHello = true,
                    onSocketCreated = { assertFalse(it is SSLSocket) },
                ).use { ws ->
                    ws.sendBinary(byteArrayOf(7, 8, 9))
                    assertArrayEquals(BIG, ws.readBinary())
                }
            } finally {
                executor.shutdown()
            }
            assertEquals("GET /apiws HTTP/1.1|7,8,9|$SNI", peer.get(5, TimeUnit.SECONDS))
        }
    }

    @Test fun silentServerAfterTcpConnectIsReportedAsDroppedHandshake() {
        for (split in listOf(false, true)) {
            ServerSocket(0).use { server ->
                val accepted = Executors.newSingleThreadExecutor()
                val held = accepted.submit<java.net.Socket> { server.accept() }
                try {
                    val error = runCatching {
                        RawWebSocket.connect("127.0.0.1", "kws2.$SNI", port = server.localPort,
                            sni = SNI, splitHello = split, connectTimeoutMs = 700, markHandshakeDrops = true)
                    }.exceptionOrNull()
                    assertTrue("split=$split: $error", error is TlsHandshakeDropped)
                    assertTrue(error!!.cause is SocketTimeoutException)

                    val unmarked = runCatching {
                        RawWebSocket.connect("127.0.0.1", "kws2.$SNI", port = server.localPort,
                            sni = SNI, splitHello = split, connectTimeoutMs = 700)
                    }.exceptionOrNull()
                    assertTrue("split=$split: $unmarked", unmarked is SocketTimeoutException)
                } finally {
                    runCatching { held.get(1, TimeUnit.SECONDS).close() }
                    accepted.shutdownNow()
                }
            }
        }
    }

    private fun contains(haystack: ByteArray, needle: ByteArray): Boolean =
        (0..haystack.size - needle.size).any { i -> needle.indices.all { haystack[i + it] == needle[it] } }
}
