package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

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
 * Picks the Cloudflare transport per network and steps down the ladder when the network drops
 * TLS handshakes by server name or freezes the streams after the handshake.
 *
 * Observed 2026-11-19 on several networks: DPI silently dropped every TLS ClientHello whose SNI
 * was a CF proxy domain (any Cloudflare address, any `kwsN.` prefix), while the same addresses
 * answered other names. Splitting the ClientHello into two TLS records and two TCP segments in
 * the middle of the SNI passed (HTTP 101), and so did plain port 80.
 *
 * Pure policy, no I/O except the optional [Store]. Networks are keyed by
 * [ConnectivitySnapshot.transportKey], which stays the same when the phone reconnects to the same
 * Wi-Fi or mobile network; steps for such keys are saved so a restart keeps them. The remembered
 * step expires after [RECHECK_MS], so a network that lifts the block gets plain TLS (and HTTP/2
 * media) back. An unknown network starts at [CfTransport.TLS].
 */
internal class CfTransportSelector(
    private val clock: () -> Long = System::currentTimeMillis,
    private var store: Store? = null,
) {
    /** Persists remembered steps as an opaque string (SharedPreferences in the app). */
    interface Store {
        fun load(): String?
        fun save(value: String)
    }

    @Serializable
    private data class Saved(
        val key: String,
        val transport: CfTransport,
        val since: Long,
        val avoidSplitUntil: Long = 0L,
    )

    private class NetState {
        var transport = CfTransport.TLS
        var since = 0L
        var blockedUntil = 0L
        /** The split ClientHello connects but the stream freezes here: skip it until then. */
        var avoidSplitUntil = 0L
        /** Times of recent strikes, oldest first (a sliding [STALL_WINDOW_MS] window). */
        val strikes = ArrayDeque<Long>()
    }

    private val lock = Any()
    private val states = LinkedHashMap<String?, NetState>()

    init {
        store?.let { load(it) }
    }

    /** Attaches persistence once (the process-wide instance is created before a Context exists). */
    fun attachStore(store: Store) = synchronized(lock) {
        if (this.store != null) return
        this.store = store
        load(store)
    }

    fun current(networkKey: String?, mode: CfTlsMode): CfTransport = synchronized(lock) {
        if (mode == CfTlsMode.OFF) return CfTransport.PLAIN
        val state = states[networkKey] ?: return CfTransport.TLS
        if (state.transport != CfTransport.TLS && clock() - state.since >= RECHECK_MS) {
            DiagnosticLogger.event("cf_transport_recheck", "from" to state.transport.name)
            state.transport = CfTransport.TLS
            state.since = clock()
            persist()
        }
        cap(state.transport, mode)
    }

    /**
     * The handshake via [from] was dropped by name. Returns the transport to try next on this
     * network, or null when [mode] allows nothing further (then failures back off briefly).
     * Idempotent across concurrent attempts: a step already taken by another attempt is reused.
     */
    fun sniBlocked(networkKey: String?, mode: CfTlsMode, from: CfTransport): CfTransport? = synchronized(lock) {
        val state = state(networkKey)
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
        state.strikes.clear()
        persist()
        DiagnosticLogger.event("cf_transport_changed", "from" to from.name, "to" to next.name, "reason" to "sni_blocked")
        next
    }

    /**
     * A stream over [transport] froze: requests went out, (almost) nothing came back. Reported by
     * the media watchdog and by every CF session that ends that way (see [isDeadSession]).
     *
     * Seen 2026-11-20 on a network that let the split ClientHello through and then stopped every
     * flow to the blocked name after a few hundred bytes to ~24 KB, while port 80 kept working.
     * Telegram closes such connections itself after 4-8 s, before a 6 s watchdog fires, so the
     * session ends count too. [STALL_STRIKES] strikes within [STALL_WINDOW_MS] step this network
     * down; strikes closer than [STALL_DEDUP_MS] count once (Telegram closes several sessions at
     * once when it goes to the background). A frozen split is avoided for [AVOID_SPLIT_MS], so
     * rechecks skip it. Returns the transport now in use when this strike changed it, else null.
     */
    fun streamStalled(networkKey: String?, mode: CfTlsMode, transport: CfTransport): CfTransport? = synchronized(lock) {
        if (!transport.secure || mode == CfTlsMode.OFF) return null
        val state = state(networkKey)
        val now = clock()
        if (cap(state.transport, mode) != transport) return null
        if (state.strikes.lastOrNull()?.let { now - it < STALL_DEDUP_MS } == true) return null
        while (state.strikes.isNotEmpty() && now - state.strikes.first() > STALL_WINDOW_MS) state.strikes.removeFirst()
        state.strikes.addLast(now)
        if (state.strikes.size < STALL_STRIKES) return null
        state.strikes.clear()
        if (transport == CfTransport.TLS_SPLIT) state.avoidSplitUntil = now + AVOID_SPLIT_MS
        val next = next(transport, mode, avoidSplit = state.avoidSplitUntil > now)
        if (next == null) {
            persist()
            return null
        }
        state.transport = next
        state.since = now
        persist()
        DiagnosticLogger.event("cf_transport_changed", "from" to transport.name, "to" to next.name, "reason" to "stream_frozen")
        cap(next, mode)
    }

    /** A connection via [transport] completed: clears the short backoff. */
    fun worked(networkKey: String?) = synchronized(lock) {
        states[networkKey]?.blockedUntil = 0L
    }

    /** True while every allowed transport was just found blocked on this network. */
    fun isBackingOff(networkKey: String?): Boolean = synchronized(lock) {
        (states[networkKey]?.blockedUntil ?: 0L) > clock()
    }

    fun reset() = synchronized(lock) {
        states.clear()
        persist()
    }

    /**
     * The network known as [from] got its full identity [to] (see NetworkIdentity). What the full
     * key already remembers wins; otherwise steps learned under the partial key are copied over
     * (copied, not moved: another router without IPv6 may share the partial key).
     */
    fun rekey(from: String?, to: String) = synchronized(lock) {
        if (from == to || to in states) return
        val partial = states[from] ?: return
        state(to).apply {
            transport = partial.transport
            since = partial.since
            blockedUntil = partial.blockedUntil
            avoidSplitUntil = partial.avoidSplitUntil
            strikes.addAll(partial.strikes)
        }
        persist()
    }

    private fun load(store: Store) {
        val saved = try {
            store.load()?.let { json.decodeFromString<List<Saved>>(it) }
        } catch (_: Exception) {
            null
        } ?: return
        val now = clock()
        for (entry in saved) {
            if (entry.key in states) continue
            // Steps past their recheck are useless; keep only the split avoidance if still valid.
            val expired = now - entry.since >= RECHECK_MS
            if (expired && entry.avoidSplitUntil <= now) continue
            state(entry.key).apply {
                transport = if (expired) CfTransport.TLS else entry.transport
                since = if (expired) now else entry.since
                avoidSplitUntil = entry.avoidSplitUntil
            }
        }
    }

    private fun persist() {
        val store = store ?: return
        val now = clock()
        val saved = states.mapNotNull { (key, state) ->
            if (key == null || key.startsWith(TRANSIENT_KEY_PREFIX)) return@mapNotNull null
            if (state.transport == CfTransport.TLS && state.avoidSplitUntil <= now) return@mapNotNull null
            Saved(key, state.transport, state.since, state.avoidSplitUntil)
        }
        try {
            store.save(json.encodeToString(saved))
        } catch (_: Exception) {
        }
    }

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

    private fun state(networkKey: String?): NetState = states.getOrPut(networkKey) {
        if (states.size >= MAX_NETWORKS) states.remove(states.keys.first())
        NetState()
    }

    companion object {
        /** Process-wide: proxy runtimes restart often on the same network. */
        val shared: CfTransportSelector by lazy { CfTransportSelector() }

        private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

        /** Keys built from a bare Android network handle change on reconnect: never saved. */
        const val TRANSIENT_KEY_PREFIX = "handle:"

        const val RECHECK_MS = 60 * 60_000L
        const val BLOCKED_BACKOFF_MS = 30_000L
        const val STALL_STRIKES = 3
        const val STALL_WINDOW_MS = 2 * 60_000L
        const val STALL_DEDUP_MS = 1_000L
        /**
         * A network where the split froze kept freezing it for days (2026-11-20/21); with 6 h the
         * avoidance ran out between hourly rechecks and the split froze Telegram again.
         */
        const val AVOID_SPLIT_MS = 24 * 60 * 60_000L
        /**
         * Live check on open CF sessions: this many bytes sent after the last byte came back and
         * unanswered this long count as a strike before Telegram gives up on the connection.
         */
        const val LIVE_TAIL_MIN_BYTES = 512L
        const val LIVE_TAIL_SILENT_MS = 6_000L
        /** Addresses that must drop the handshake before it counts as blocking by name. */
        const val SNI_BLOCK_ADDRESSES = 2
        private const val MAX_NETWORKS = 16

        /**
         * A CF session that sent requests and got (almost) nothing back before it closed. Tuned on
         * logs from 2026-11-19..21: frozen sessions sent 1-35 KB and got 0-320 bytes in 3-15 s,
         * and frozen media sessions sent one 250-1000 byte request and got nothing in 3-9 s.
         * Healthy sessions closed at the same moment got 700+ bytes; of 120 healthy port-80
         * sessions none got 0 bytes in 3 s or more; sessions shorter than 2 s are clients that
         * gave up while the connection was still being made.
         *
         * Cut off mid-stream (2026-11-21, Wi-Fi, split TLS): each flow passed 10-30 KB, then went
         * silent; Telegram closed it after 4-17 s and reconnected, so the app looked connected
         * while Telegram kept "updating". [tailUpBytes] is what was sent after the last byte came
         * back and [tailSilentMs] how long the first of those bytes stayed unanswered: a healthy
         * server answers within a second, an idle connection has no unanswered tail.
         */
        fun isDeadSession(
            durationMs: Long,
            bytesUp: Long,
            bytesDown: Long,
            tailUpBytes: Long = 0L,
            tailSilentMs: Long = 0L,
        ): Boolean =
            (durationMs >= 2_000L && bytesUp >= 1_024L && bytesDown < 512L) ||
                (durationMs >= 3_000L && bytesUp >= 256L && bytesDown == 0L) ||
                (tailUpBytes >= 512L && tailSilentMs >= 3_000L)
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
