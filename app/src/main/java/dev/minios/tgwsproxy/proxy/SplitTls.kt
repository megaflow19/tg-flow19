package dev.minios.tgwsproxy.proxy

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.SSLEngineResult.HandshakeStatus

/**
 * TLS client over an already connected plain socket, driven through [SSLEngine] so that the
 * ClientHello can be sent split: two TLS records cut in the middle of the SNI, sent as two TCP
 * segments ([CfTransport.TLS_SPLIT]). The system SSLSocket always writes the ClientHello as one
 * record in one write, which SNI-filtering DPI matches.
 *
 * Verified 2026-11-20 from a phone on two networks that dropped CF proxy SNIs: plain, TCP-only
 * and record-only splits were all dropped; record split + TCP split completed (HTTP 101).
 */
internal class SplitTlsStreams private constructor(
    private val socket: Socket,
    private val engine: SSLEngine,
) {
    private val rawIn = socket.getInputStream()
    private val rawOut = socket.getOutputStream()
    private val readLock = Any()
    private val writeLock = Any()
    private var netIn: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize * 2)
    private var appIn: ByteBuffer = ByteBuffer.allocate(engine.session.applicationBufferSize * 2)
    private var netOut: ByteBuffer = ByteBuffer.allocate(engine.session.packetBufferSize)
    private val readBuf = ByteArray(engine.session.packetBufferSize)
    private var eof = false

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            synchronized(readLock) {
                while (true) {
                    if (appIn.position() > 0) {
                        appIn.flip()
                        val n = minOf(len, appIn.remaining())
                        appIn.get(b, off, n)
                        appIn.compact()
                        return n
                    }
                    if (eof) return -1
                    if (!unwrapOnce()) fillNetIn()
                }
            }
        }

        override fun available(): Int = synchronized(readLock) { appIn.position() }
        override fun close() = socket.close()
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            synchronized(writeLock) {
                val src = ByteBuffer.wrap(b, off, len)
                while (src.hasRemaining()) wrapAndSend(src)
            }
        }

        override fun flush() = rawOut.flush()
        override fun close() = socket.close()
    }

    /** One unwrap step on buffered network data. Returns false when more network bytes are needed. */
    private fun unwrapOnce(): Boolean {
        if (netIn.position() == 0) return false
        netIn.flip()
        val result = try { engine.unwrap(netIn, appIn) } finally { netIn.compact() }
        when (result.status) {
            SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                if (!netIn.hasRemaining()) netIn = grow(netIn, engine.session.packetBufferSize)
                return false
            }
            SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                appIn = grow(appIn, engine.session.applicationBufferSize)
                return true
            }
            SSLEngineResult.Status.CLOSED -> {
                eof = true
                return true
            }
            SSLEngineResult.Status.OK -> {
                handlePostHandshake(result.handshakeStatus)
                return result.bytesConsumed() > 0 || result.bytesProduced() > 0
            }
            else -> return false
        }
    }

    /** TLS 1.3 may ask for a reply (KeyUpdate) or a delegated task after the handshake. */
    private fun handlePostHandshake(status: HandshakeStatus) {
        when (status) {
            HandshakeStatus.NEED_TASK -> runTasks()
            HandshakeStatus.NEED_WRAP -> synchronized(writeLock) { wrapAndSend(EMPTY) }
            else -> Unit
        }
    }

    private fun fillNetIn() {
        if (!netIn.hasRemaining()) netIn = grow(netIn, engine.session.packetBufferSize)
        val n = rawIn.read(readBuf, 0, minOf(readBuf.size, netIn.remaining()))
        if (n < 0) {
            eof = true
            return
        }
        netIn.put(readBuf, 0, n)
    }

    private fun wrapAndSend(src: ByteBuffer) {
        while (true) {
            netOut.clear()
            val result = engine.wrap(src, netOut)
            when (result.status) {
                SSLEngineResult.Status.BUFFER_OVERFLOW -> {
                    netOut = ByteBuffer.allocate(netOut.capacity() + engine.session.packetBufferSize)
                    continue
                }
                SSLEngineResult.Status.CLOSED -> throw IOException("TLS connection closed")
                else -> Unit
            }
            netOut.flip()
            if (netOut.hasRemaining()) rawOut.write(netOut.array(), netOut.arrayOffset(), netOut.limit())
            if (result.handshakeStatus == HandshakeStatus.NEED_TASK) runTasks()
            return
        }
    }

    private fun runTasks() {
        while (true) (engine.delegatedTask ?: return).run()
    }

    /** Client handshake; the first flight (ClientHello) is sent split by [SplitTls.splitClientHello]. */
    private fun handshake(sni: String) {
        engine.beginHandshake()
        var status = engine.handshakeStatus
        var first = true
        while (status != HandshakeStatus.FINISHED && status != HandshakeStatus.NOT_HANDSHAKING) {
            status = when (status) {
                HandshakeStatus.NEED_WRAP -> {
                    netOut.clear()
                    val result = engine.wrap(EMPTY, netOut)
                    if (result.status == SSLEngineResult.Status.CLOSED) throw IOException("TLS closed during handshake")
                    netOut.flip()
                    val bytes = ByteArray(netOut.remaining()).also { netOut.get(it) }
                    if (first) {
                        first = false
                        val (head, tail) = SplitTls.splitClientHello(bytes, sni)
                        rawOut.write(head)
                        rawOut.flush()
                        // Separate TCP segments even if the stack would coalesce queued writes.
                        Thread.sleep(SplitTls.SEGMENT_GAP_MS)
                        rawOut.write(tail)
                    } else {
                        rawOut.write(bytes)
                    }
                    rawOut.flush()
                    result.handshakeStatus
                }
                HandshakeStatus.NEED_UNWRAP -> {
                    netIn.flip()
                    val result = try { engine.unwrap(netIn, appIn) } finally { netIn.compact() }
                    when (result.status) {
                        SSLEngineResult.Status.BUFFER_UNDERFLOW -> {
                            fillNetIn()
                            if (eof) throw EOFException("Connection closed during TLS handshake")
                        }
                        SSLEngineResult.Status.BUFFER_OVERFLOW ->
                            appIn = grow(appIn, engine.session.applicationBufferSize)
                        SSLEngineResult.Status.CLOSED -> throw IOException("TLS closed during handshake")
                        else -> Unit
                    }
                    result.handshakeStatus
                }
                HandshakeStatus.NEED_TASK -> {
                    runTasks()
                    engine.handshakeStatus
                }
                else -> throw IOException("Unexpected TLS handshake state $status")
            }
        }
    }

    companion object {
        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        private fun grow(buffer: ByteBuffer, extra: Int): ByteBuffer =
            ByteBuffer.allocate(buffer.capacity() + extra).also {
                buffer.flip()
                it.put(buffer)
            }

        /** Runs the TLS handshake over the connected [socket]; read timeouts come from its soTimeout. */
        fun open(socket: Socket, context: SSLContext, sni: String, port: Int): SplitTlsStreams {
            val engine = context.createSSLEngine(sni, port).apply {
                useClientMode = true
                sslParameters = sslParameters.also { it.serverNames = listOf(SNIHostName(sni)) }
            }
            return SplitTlsStreams(socket, engine).also { it.handshake(sni) }
        }
    }
}

internal object SplitTls {
    const val SEGMENT_GAP_MS = 50L
    private const val RECORD_HEADER = 5
    private const val HANDSHAKE_RECORD: Byte = 0x16

    /**
     * Rewrites one TLS record holding the ClientHello as two records cut in the middle of [sni]
     * (or of the payload, when the name is not found), and returns the two TCP segments to send:
     * the first ends one byte into the second record, as tested on device.
     */
    fun splitClientHello(record: ByteArray, sni: String): Pair<ByteArray, ByteArray> {
        val payloadLen = record.size - RECORD_HEADER
        if (record.size <= RECORD_HEADER + 1 || record[0] != HANDSHAKE_RECORD) {
            val cut = record.size / 2
            return record.copyOfRange(0, cut) to record.copyOfRange(cut, record.size)
        }
        val name = sni.toByteArray(Charsets.US_ASCII)
        val at = indexOf(record, name, RECORD_HEADER)
        val k = (if (at >= 0) at + name.size / 2 else RECORD_HEADER + payloadLen / 2) - RECORD_HEADER
        val cutInPayload = k.coerceIn(1, payloadLen - 1)

        val out = ByteArray(record.size + RECORD_HEADER)
        writeHeader(out, 0, record, cutInPayload)
        System.arraycopy(record, RECORD_HEADER, out, RECORD_HEADER, cutInPayload)
        val second = RECORD_HEADER + cutInPayload
        writeHeader(out, second, record, payloadLen - cutInPayload)
        System.arraycopy(record, RECORD_HEADER + cutInPayload, out, second + RECORD_HEADER, payloadLen - cutInPayload)

        val tcpCut = second + RECORD_HEADER + 1
        return out.copyOfRange(0, tcpCut) to out.copyOfRange(tcpCut, out.size)
    }

    private fun writeHeader(out: ByteArray, at: Int, original: ByteArray, length: Int) {
        out[at] = original[0]
        out[at + 1] = original[1]
        out[at + 2] = original[2]
        out[at + 3] = (length shr 8).toByte()
        out[at + 4] = length.toByte()
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty()) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
