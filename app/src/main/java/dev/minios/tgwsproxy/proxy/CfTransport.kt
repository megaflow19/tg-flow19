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
 * Wi-Fi or mobile network; steps for such keys are saved so a restart keeps them. An unknown
 * network starts at [CfTransport.TLS].
 *
 * A network that lifts the block gets plain TLS (and HTTP/2 media) back through a background
 * recheck ([recheckPlan], [recheckDone]): live connections stay on the working step meanwhile.
 * Seen 2026-11-24: rechecking on live connections cost Telegram 5-50 s, once 5 minutes, every
 * hour on networks where TLS stayed blocked all day. Each failed recheck doubles the interval.
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
        val recheckAt: Long = 0L,
        val recheckFailures: Int = 0,
        val splitFreezes: Int = 0,
    )

    private class NetState {
        var transport = CfTransport.TLS
        var since = 0L
        var blockedUntil = 0L
        /** The split ClientHello connects but the stream freezes here: skip it until then. */
        var avoidSplitUntil = 0L
        /** How many times the split froze here; each time it is avoided twice as long. */
        var splitFreezes = 0
        /** When the background recheck of a higher step is due (0: not scheduled). */
        var recheckAt = 0L
        /** Rechecks in a row that found plain TLS still blocked here. */
        var recheckFailures = 0
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
        cap(state.transport, mode)
    }

    /**
     * Higher steps a background recheck should try now on this network, best first; empty when
     * no recheck is due. Plain TLS is always tried; from port 80 the split is tried too unless it
     * froze here recently.
     */
    fun recheckPlan(networkKey: String?, mode: CfTlsMode): List<CfTransport> = synchronized(lock) {
        if (mode == CfTlsMode.OFF) return emptyList()
        val state = states[networkKey] ?: return emptyList()
        val current = cap(state.transport, mode)
        if (current == CfTransport.TLS || clock() < state.recheckAt) return emptyList()
        buildList {
            add(CfTransport.TLS)
            if (current == CfTransport.PLAIN && mode == CfTlsMode.AUTO && state.avoidSplitUntil <= clock()) {
                add(CfTransport.TLS_SPLIT)
            }
        }
    }

    /**
     * A background recheck finished. [working] is the best step whose handshake passed, or null.
     * [conclusive] is false when the network itself failed (no route, timeouts before any
     * handshake): then the recheck is retried soon and nothing is learned.
     */
    fun recheckDone(networkKey: String?, mode: CfTlsMode, working: CfTransport?, conclusive: Boolean) =
        synchronized(lock) {
            val state = states[networkKey] ?: return
            val now = clock()
            val from = cap(state.transport, mode)
            if (!conclusive && working == null) {
                state.recheckAt = now + RECHECK_RETRY_MS
                DiagnosticLogger.event("cf_transport_recheck", "from" to from.name, "result" to "inconclusive")
                return
            }
            if (working == CfTransport.TLS) {
                // The failure count is kept: if plain TLS gets blocked or frozen again here, the
                // next recheck waits as long as before instead of starting over at an hour.
                state.recheckAt = 0L
            } else {
                state.recheckFailures = (state.recheckFailures + 1).coerceAtMost(MAX_RECHECK_FAILURES)
                state.recheckAt = now + recheckInterval(state.recheckFailures)
            }
            DiagnosticLogger.event(
                "cf_transport_recheck", "from" to from.name, "result" to (working?.name ?: "blocked"),
                "failures" to state.recheckFailures,
                "nextInMin" to if (state.recheckAt > 0L) (state.recheckAt - now) / 60_000L else null,
            )
            if (working != null && working < from) {
                state.transport = working
                state.since = now
                state.strikes.clear()
                state.blockedUntil = 0L
                DiagnosticLogger.event("cf_transport_changed", "from" to from.name, "to" to working.name, "reason" to "recheck")
            }
            persist()
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
        scheduleRecheck(state, now)
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
     * once when it goes to the background). A frozen split is avoided for [AVOID_SPLIT_MS], longer
     * after each further freeze here (see [avoidSplitDuration]), so rechecks skip it.
     * Seen 2026-11-24: the split froze again on two networks the day after its 24 h avoidance.
     * Returns the transport now in use when this strike changed it, else null.
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
        if (transport == CfTransport.TLS_SPLIT) {
            state.splitFreezes = (state.splitFreezes + 1).coerceAtMost(MAX_SPLIT_FREEZES)
            state.avoidSplitUntil = now + avoidSplitDuration(state.splitFreezes)
        } else {
            // Plain TLS passes the handshake but freezes: a passing recheck proves little here,
            // so the next one waits longer instead of bringing the freeze back every hour.
            state.recheckFailures = (state.recheckFailures + 1).coerceAtMost(MAX_RECHECK_FAILURES)
        }
        val next = next(transport, mode, avoidSplit = state.avoidSplitUntil > now)
        if (next == null) {
            persist()
            return null
        }
        state.transport = next
        state.since = now
        scheduleRecheck(state, now)
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
            splitFreezes = partial.splitFreezes
            recheckAt = partial.recheckAt
            recheckFailures = partial.recheckFailures
            strikes.addAll(partial.strikes)
        }
        persist()
    }

    private fun scheduleRecheck(state: NetState, now: Long) {
        if (state.transport != CfTransport.TLS && state.recheckAt == 0L) {
            state.recheckAt = now + recheckInterval(state.recheckFailures)
        }
    }

    private fun load(store: Store) {
        val saved = try {
            store.load()?.let { json.decodeFromString<List<Saved>>(it) }
        } catch (_: Exception) {
            null
        } ?: return
        for (entry in saved) {
            if (entry.key in states) continue
            // Steps are kept until a background recheck finds a better one (1.0.4 and older
            // saved no recheck time: recheck an hour after the step was taken).
            state(entry.key).apply {
                transport = entry.transport
                since = entry.since
                avoidSplitUntil = entry.avoidSplitUntil
                // 1.0.4 and older did not count freezes, but only set the avoidance after one.
                splitFreezes = if (entry.splitFreezes == 0 && entry.avoidSplitUntil > 0L) 1 else entry.splitFreezes
                recheckFailures = entry.recheckFailures
                recheckAt = when {
                    entry.recheckAt > 0L -> entry.recheckAt
                    entry.transport != CfTransport.TLS -> entry.since + RECHECK_MS
                    else -> 0L
                }
            }
        }
    }

    private fun persist() {
        val store = store ?: return
        val now = clock()
        val saved = states.mapNotNull { (key, state) ->
            if (key == null || key.startsWith(TRANSIENT_KEY_PREFIX)) return@mapNotNull null
            if (state.transport == CfTransport.TLS && state.avoidSplitUntil <= now && state.splitFreezes == 0) {
                return@mapNotNull null
            }
            Saved(
                key, state.transport, state.since, state.avoidSplitUntil,
                state.recheckAt, state.recheckFailures, state.splitFreezes,
            )
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

        /** First background recheck after a step down; doubles per failed recheck. */
        const val RECHECK_MS = 60 * 60_000L
        const val RECHECK_MAX_MS = 24 * 60 * 60_000L
        /** Retry of a recheck that the network itself spoiled (no route, timeouts). */
        const val RECHECK_RETRY_MS = 5 * 60_000L
        const val BLOCKED_BACKOFF_MS = 30_000L
        const val STALL_STRIKES = 3
        const val STALL_WINDOW_MS = 2 * 60_000L
        const val STALL_DEDUP_MS = 1_000L
        /**
         * A network where the split froze kept freezing it for days (2026-11-20/21); with 6 h the
         * avoidance ran out between hourly rechecks and the split froze Telegram again.
         */
        const val AVOID_SPLIT_MS = 24 * 60 * 60_000L
        const val AVOID_SPLIT_MAX_MS = 30 * 24 * 60 * 60_000L
        private const val MAX_SPLIT_FREEZES = 16
        private const val MAX_RECHECK_FAILURES = 16

        fun recheckInterval(failures: Int): Long =
            (RECHECK_MS shl failures.coerceIn(0, 16)).coerceAtMost(RECHECK_MAX_MS)

        /**
         * 1 day, then 7, then 30 days. Each retry costs Telegram a frozen minute (seen 2026-11-25:
         * the split passed a recheck handshake, then froze all media for 50 s), and on networks
         * seen so far a split that froze once kept freezing.
         */
        fun avoidSplitDuration(freezes: Int): Long = when {
            freezes <= 1 -> AVOID_SPLIT_MS
            freezes == 2 -> AVOID_SPLIT_MS * 7
            else -> AVOID_SPLIT_MAX_MS
        }
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
