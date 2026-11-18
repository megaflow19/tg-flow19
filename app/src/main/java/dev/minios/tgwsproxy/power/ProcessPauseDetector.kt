package dev.minios.tgwsproxy.power

/**
 * Classifies gaps in a periodic heartbeat so the diagnostic log shows when the OS paused the
 * proxy process (see [BackgroundProtection]).
 *
 * The heartbeat sleeps with coroutine `delay`, which runs on CLOCK_MONOTONIC: it does not
 * advance while the device is in deep sleep. `SystemClock.elapsedRealtime()` always advances,
 * `SystemClock.uptimeMillis()` (also CLOCK_MONOTONIC) stops in deep sleep. So after a late tick:
 *  - small uptime gap, large elapsed gap  -> the whole device slept (normal, not our problem);
 *  - large uptime gap                     -> the CPU ran for others but not for us: frozen
 *                                           (vendor freezer, cgroup freezer) or starved.
 */
object ProcessPauseDetector {
    const val INTERVAL_MS = 30_000L
    /** Lateness beyond the interval that is worth reporting. */
    const val REPORT_EXTRA_MS = 60_000L

    enum class Kind { FROZEN, DEEP_SLEEP }

    data class Pause(val gapMs: Long, val awakeMs: Long, val kind: Kind)

    fun classify(elapsedGapMs: Long, uptimeGapMs: Long, intervalMs: Long = INTERVAL_MS): Pause? {
        if (elapsedGapMs < intervalMs + REPORT_EXTRA_MS) return null
        val kind = if (uptimeGapMs >= intervalMs + REPORT_EXTRA_MS) Kind.FROZEN else Kind.DEEP_SLEEP
        return Pause(elapsedGapMs, uptimeGapMs, kind)
    }
}
