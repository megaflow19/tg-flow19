package dev.minios.tgwsproxy.proxy

import java.util.concurrent.ConcurrentHashMap

internal data class DirectWsStallResult(
    val strikes: Int,
    val activated: Boolean,
    val blockedForMs: Long,
)

/**
 * Remembers that direct WebSocket media freezes on the current Android network.
 *
 * Observed on home Wi-Fi (2026-11-18): direct WS to Telegram completes its handshake, but
 * DPI freezes media sessions after ~2 KB, so route probes call the path healthy while every
 * media download hangs. The memory is therefore:
 *  - keyed by the Android network handle, not by the proxy runtime: auto-mode switches,
 *    network re-validation and manual restarts recreate the runtime, and used to wipe it,
 *    so every attempt paid the full stall timeout again;
 *  - network-wide for media: the freeze is the network's policy, not one DC's (DC2 and DC4
 *    both froze), so one stall moves all media DCs on that network to Cloudflare;
 *  - time-limited ([NETWORK_BLOCK_DURATION_MS]) so a transient stall does not pin the route.
 * A new network handle (network change or reconnect) starts clean.
 */
internal class DirectWsCircuitBreaker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class Slot(val networkId: Long?, val media: Boolean)
    private data class State(val strikes: Int, val blockedUntil: Long)

    private val states = ConcurrentHashMap<Slot, State>()

    fun isBlocked(networkId: Long?, isMedia: Boolean): Boolean {
        val state = states[Slot(networkId, isMedia)] ?: return false
        return state.blockedUntil > clock()
    }

    fun recordStall(networkId: Long?, isMedia: Boolean): DirectWsStallResult {
        val now = clock()
        var activated = false
        val state = states.compute(Slot(networkId, isMedia)) { _, previous ->
            if (previous != null && previous.blockedUntil > now) {
                previous
            } else {
                activated = true
                State((previous?.strikes ?: 0) + 1, now + NETWORK_BLOCK_DURATION_MS)
            }
        }!!
        pruneOtherExpired(now, Slot(networkId, isMedia))
        return DirectWsStallResult(
            strikes = state.strikes,
            activated = activated,
            blockedForMs = (state.blockedUntil - now).coerceAtLeast(0L),
        )
    }

    /** A healthy direct session clears an expired block's history; an active block stays. */
    fun recordResponsive(networkId: Long?, isMedia: Boolean) {
        states.computeIfPresent(Slot(networkId, isMedia)) { _, state ->
            if (state.blockedUntil > clock()) state else null
        }
    }

    fun reset() {
        states.clear()
    }

    private fun pruneOtherExpired(now: Long, keep: Slot) {
        states.entries.removeIf { it.key != keep && it.value.blockedUntil <= now }
    }

    companion object {
        /** Process-wide memory shared by every proxy runtime (see class doc). */
        val shared = DirectWsCircuitBreaker()

        /** No downstream byte for this long while a request is outstanding = frozen. */
        internal const val STALL_SILENCE_MS = 6_000L
        internal const val MIN_PENDING_BYTES = 1_024L
        internal const val MIN_MEANINGFUL_DOWN_BYTES = 4_096L
        internal const val NETWORK_BLOCK_DURATION_MS = 30 * 60_000L

        /**
         * Silence is measured from the later of the first unanswered request and the last
         * downstream byte, so a slow upload that keeps receiving small acks is not a stall,
         * while a DPI freeze (downstream stops completely) is caught in [STALL_SILENCE_MS].
         */
        internal fun isStalled(
            pendingSince: Long,
            lastDownAt: Long,
            pendingBytesUp: Long,
            pendingBytesDown: Long,
            now: Long,
        ): Boolean = pendingSince > 0L &&
                pendingBytesUp >= MIN_PENDING_BYTES &&
                pendingBytesDown < MIN_MEANINGFUL_DOWN_BYTES &&
                now - maxOf(pendingSince, lastDownAt) >= STALL_SILENCE_MS
    }
}
