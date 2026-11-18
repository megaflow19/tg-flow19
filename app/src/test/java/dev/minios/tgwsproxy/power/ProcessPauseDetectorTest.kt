package dev.minios.tgwsproxy.power

import dev.minios.tgwsproxy.power.ProcessPauseDetector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcessPauseDetectorTest {
    private val interval = ProcessPauseDetector.INTERVAL_MS

    @Test fun onTimeOrSlightlyLateTickIsNotReported() {
        assertNull(ProcessPauseDetector.classify(interval, interval))
        assertNull(ProcessPauseDetector.classify(interval + 59_000L, interval + 59_000L))
    }

    @Test fun frozenProcessWhileCpuRanForOthers() {
        // 2026-11-18 11:35 -> 11:57: ~21 min with no work, CPU awake (uptime advanced too).
        val gap = 21 * 60_000L
        val pause = ProcessPauseDetector.classify(gap, gap - 5_000L)!!
        assertEquals(Kind.FROZEN, pause.kind)
        assertEquals(gap, pause.gapMs)
    }

    @Test fun wholeDeviceDeepSleepIsDistinguished() {
        // Elapsed time jumped, but CLOCK_MONOTONIC (uptime) only advanced by the interval.
        val pause = ProcessPauseDetector.classify(40 * 60_000L, interval + 300L)!!
        assertEquals(Kind.DEEP_SLEEP, pause.kind)
    }
}
