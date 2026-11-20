package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.serialization.Serializable

/** User setting for how Cloudflare (CF proxy and Workers) is reached. */
@Serializable
enum class CfTlsMode {
    /** TLS on port 443, then TLS with a split ClientHello, then plain port 80, per network. */
    AUTO,
    /** TLS on port 443 only (plain, then split ClientHello); never port 80. */
    ON,
    /** Plain HTTP on port 80 only. */
    OFF;

    companion object {
        fun fromKey(key: String?): CfTlsMode? = entries.firstOrNull { it.name.equals(key, ignoreCase = true) }
    }
}

/** How one Cloudflare connection is made. Ordered: each step hides less from DPI-free networks. */
enum class CfTransport {
    /** TLS, port 443, ClientHello as the system sends it. HTTP/2 media is available. */
    TLS,
    /** TLS, port 443, ClientHello split into two TLS records and two TCP segments mid-SNI. */
    TLS_SPLIT,
    /** No TLS, port 80. */
    PLAIN;

    val secure: Boolean get() = this != PLAIN
    val splitHello: Boolean get() = this == TLS_SPLIT
    val port: Int get() = if (secure) 443 else 80
}

/**
 * Picks the Cloudflare transport per Android network and steps down the ladder when the network
 * drops TLS handshakes by server name.
 *
 * Observed 2026-11-19 on several networks: DPI silently dropped every TLS ClientHello whose SNI
 * was a CF proxy domain (any Cloudflare address, any `kwsN.` prefix), while the same addresses
 * answered other names. Splitting the ClientHello into two TLS records and two TCP segments in
 * the middle of the SNI passed (HTTP 101), and so did plain port 80.
 *
 * Pure policy, no I/O. The remembered step expires after [RECHECK_MS], so a network that lifts
 * the block gets plain TLS (and HTTP/2 media) back. A new network handle starts at [CfTransport.TLS].
 */
internal class CfTransportSelector(private val clock: () -> Long = System::currentTimeMillis) {
    private class NetState {
        var transport = CfTransport.TLS
        var since = 0L
        var blockedUntil = 0L
        /** The split ClientHello connects but the stream freezes here: skip it until then. */
        var avoidSplitUntil = 0L
        var stallStrikes = 0
        var firstStallAt = 0L
    }

    private val lock = Any()
    private val states = LinkedHashMap<Long?, NetState>()

    fun current(networkId: Long?, mode: CfTlsMode): CfTransport = synchronized(lock) {
        if (mode == CfTlsMode.OFF) return CfTransport.PLAIN
        val state = states[networkId] ?: return CfTransport.TLS
        if (state.transport != CfTransport.TLS && clock() - state.since >= RECHECK_MS) {
            DiagnosticLogger.event("cf_transport_recheck", "from" to state.transport.name)
            state.transport = CfTransport.TLS
            state.since = clock()
        }
        cap(state.transport, mode)
    }

    /**
     * The handshake via [from] was dropped by name. Returns the transport to try next on this
     * network, or null when [mode] allows nothing further (then failures back off briefly).
     * Idempotent across concurrent attempts: a step already taken by another attempt is reused.
     */
    fun sniBlocked(networkId: Long?, mode: CfTlsMode, from: CfTransport): CfTransport? = synchronized(lock) {
        val state = state(networkId)
        val now = clock()
        val current = cap(state.transport, mode)
        if (current > from) return current
        val next = next(from, mode, avoidSplit = state.avoidSplitUntil > now)
        if (next == null) {
            state.blockedUntil = now + BLOCKED_BACKOFF_MS
            DiagnosticLogger.event("cf_transport_exhausted", "mode" to mode.name, "backoffMs" to BLOCKED_BACKOFF_MS)
            return null
        }
        state.transport = next
        state.since = now
        DiagnosticLogger.event("cf_transport_changed", "from" to from.name, "to" to next.name, "reason" to "sni_blocked")
        next
    }

    /**
     * A media stream over [transport] froze: requests went out, nothing came back. Seen
     * 2026-11-20 on a network that let the split ClientHello through and then stopped every
     * flow to the blocked name after ~24 KB (other names over the same split TLS were fine),
     * while port 80 kept working. [STALL_STRIKES] freezes within [STALL_WINDOW_MS] step this
     * network down; a frozen split is avoided for [AVOID_SPLIT_MS], so rechecks skip it.
     * Returns the transport now in use when this stall changed it, else null.
     */
    fun streamStalled(networkId: Long?, mode: CfTlsMode, transport: CfTransport): CfTransport? = synchronized(lock) {
        if (!transport.secure || mode == CfTlsMode.OFF) return null
        val state = state(networkId)
        val now = clock()
        if (cap(state.transport, mode) != transport) return null
        if (now - state.firstStallAt > STALL_WINDOW_MS) {
            state.firstStallAt = now
            state.stallStrikes = 0
        }
        state.stallStrikes++
        if (state.stallStrikes < STALL_STRIKES) return null
        state.stallStrikes = 0
        if (transport == CfTransport.TLS_SPLIT) state.avoidSplitUntil = now + AVOID_SPLIT_MS
        val next = next(transport, mode, avoidSplit = state.avoidSplitUntil > now) ?: return null
        state.transport = next
        state.since = now
        DiagnosticLogger.event("cf_transport_changed", "from" to transport.name, "to" to next.name, "reason" to "stream_frozen")
        cap(next, mode)
    }

    /** A connection via [transport] completed: clears the short backoff. */
    fun worked(networkId: Long?) = synchronized(lock) {
        states[networkId]?.blockedUntil = 0L
    }

    /** True while every allowed transport was just found blocked on this network. */
    fun isBackingOff(networkId: Long?): Boolean = synchronized(lock) {
        (states[networkId]?.blockedUntil ?: 0L) > clock()
    }

    fun reset() = synchronized(lock) { states.clear() }

    private fun cap(transport: CfTransport, mode: CfTlsMode): CfTransport =
        if (mode == CfTlsMode.ON && transport == CfTransport.PLAIN) CfTransport.TLS_SPLIT else transport

    private fun next(from: CfTransport, mode: CfTlsMode, avoidSplit: Boolean): CfTransport? = when (mode) {
        CfTlsMode.OFF -> null
        // ON has nothing else to offer, so it keeps trying the split even where it froze.
        CfTlsMode.ON -> if (from == CfTransport.TLS) CfTransport.TLS_SPLIT else null
        CfTlsMode.AUTO -> when (from) {
            CfTransport.TLS -> if (avoidSplit) CfTransport.PLAIN else CfTransport.TLS_SPLIT
            CfTransport.TLS_SPLIT -> CfTransport.PLAIN
            CfTransport.PLAIN -> null
        }
    }

    private fun state(networkId: Long?): NetState = states.getOrPut(networkId) {
        if (states.size >= MAX_NETWORKS) states.remove(states.keys.first())
        NetState()
    }

    companion object {
        /** Process-wide: proxy runtimes restart often on the same network. */
        val shared: CfTransportSelector by lazy { CfTransportSelector() }

        const val RECHECK_MS = 60 * 60_000L
        const val BLOCKED_BACKOFF_MS = 30_000L
        const val STALL_STRIKES = 2
        const val STALL_WINDOW_MS = 5 * 60_000L
        const val AVOID_SPLIT_MS = 6 * 60 * 60_000L
        /** Addresses that must drop the handshake before it counts as blocking by name. */
        const val SNI_BLOCK_ADDRESSES = 2
        private const val MAX_NETWORKS = 16
    }
}

/**
 * The TCP connection to a Cloudflare address succeeded but the TLS handshake then timed out or
 * was reset. Repeated on several addresses with the same name, that is DPI blocking by SNI, not
 * a dead address. Only thrown on the CF route; direct WebSocket keeps the original exception.
 */
class TlsHandshakeDropped(cause: java.io.IOException) :
    java.io.IOException("TLS handshake dropped: ${cause.javaClass.simpleName}: ${cause.message}", cause)

/** Every address dropped the handshake for this name with the given transport. */
class SniBlockedException(val transport: CfTransport, cause: Throwable?) :
    java.io.IOException("TLS handshake dropped by name via $transport", cause)
