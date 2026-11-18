package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectWsCircuitBreakerTest {
    private val wifi = 101L
    private val lte = 202L

    @Test
    fun detectsFrozenDownstreamAfterSilence() {
        // Observed freeze: ~2.3 KB up, ~1.3 KB down shortly after the request, then nothing.
        assertTrue(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L,
                lastDownAt = 1_200L,
                pendingBytesUp = 2_307L,
                pendingBytesDown = 1_300L,
                now = 1_200L + DirectWsCircuitBreaker.STALL_SILENCE_MS,
            )
        )
        assertFalse(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L,
                lastDownAt = 1_200L,
                pendingBytesUp = 2_307L,
                pendingBytesDown = 1_300L,
                now = 1_200L + DirectWsCircuitBreaker.STALL_SILENCE_MS - 1,
            )
        )
    }

    @Test
    fun slowUploadThatKeepsReceivingAcksIsNotAStall() {
        // 20 s into an upload, small acks still arriving: downstream is alive.
        assertFalse(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L,
                lastDownAt = 20_000L,
                pendingBytesUp = 900_000L,
                pendingBytesDown = 2_000L,
                now = 21_000L,
            )
        )
    }

    @Test
    fun ignoresSmallRequestsAndMeaningfulMediaResponses() {
        assertFalse(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L, lastDownAt = 0L,
                pendingBytesUp = 420L, pendingBytesDown = 205L, now = 61_000L,
            )
        )
        assertFalse(
            DirectWsCircuitBreaker.isStalled(
                pendingSince = 1_000L, lastDownAt = 0L,
                pendingBytesUp = 1_945L, pendingBytesDown = 4_096L, now = 61_000L,
            )
        )
    }

    @Test
    fun oneMediaStallMovesAllMediaOnThatNetworkForTheBlockDuration() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }

        val first = breaker.recordStall(wifi, isMedia = true)
        assertEquals(1, first.strikes)
        assertTrue(first.activated)
        assertEquals(DirectWsCircuitBreaker.NETWORK_BLOCK_DURATION_MS, first.blockedForMs)
        assertTrue(breaker.isBlocked(wifi, isMedia = true))
        assertFalse(breaker.isBlocked(wifi, isMedia = false))

        now += first.blockedForMs - 1
        assertTrue(breaker.isBlocked(wifi, isMedia = true))
        now += 1
        assertFalse(breaker.isBlocked(wifi, isMedia = true))
    }

    @Test
    fun anotherNetworkStartsClean() {
        val breaker = DirectWsCircuitBreaker { 1_000L }
        breaker.recordStall(wifi, isMedia = true)
        assertFalse(breaker.isBlocked(lte, isMedia = true))
        assertFalse(breaker.isBlocked(null, isMedia = true))
        assertTrue(breaker.isBlocked(wifi, isMedia = true))
    }

    @Test
    fun memorySurvivesRuntimeRestartBecauseBridgesShareTheBreaker() {
        // Runtime restarts recreate ConnectivityTracker/Bridge; the breaker instance is shared.
        val breaker = DirectWsCircuitBreaker { 1_000L }
        Bridge(ConnectivityTracker(), breaker).resetState()
        breaker.recordStall(wifi, isMedia = true)
        Bridge(ConnectivityTracker(), breaker).resetState()
        assertTrue(breaker.isBlocked(wifi, isMedia = true))
    }

    @Test
    fun repeatedStallDuringActiveBlockDoesNotExtendOrRecount() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }
        breaker.recordStall(wifi, isMedia = true)
        now += 60_000L
        val again = breaker.recordStall(wifi, isMedia = true)
        assertFalse(again.activated)
        assertEquals(1, again.strikes)
        assertEquals(DirectWsCircuitBreaker.NETWORK_BLOCK_DURATION_MS - 60_000L, again.blockedForMs)
    }

    @Test
    fun stallAfterExpiryBlocksAgainAndCountsStrike() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }
        val first = breaker.recordStall(wifi, isMedia = true)
        now += first.blockedForMs
        val second = breaker.recordStall(wifi, isMedia = true)
        assertTrue(second.activated)
        assertEquals(2, second.strikes)
        assertTrue(breaker.isBlocked(wifi, isMedia = true))
    }

    @Test
    fun responsiveSessionKeepsActiveBlockButClearsExpiredHistory() {
        var now = 1_000L
        val breaker = DirectWsCircuitBreaker { now }
        val first = breaker.recordStall(wifi, isMedia = true)
        breaker.recordResponsive(wifi, isMedia = true)
        assertTrue(breaker.isBlocked(wifi, isMedia = true))

        now += first.blockedForMs
        breaker.recordResponsive(wifi, isMedia = true)
        assertEquals(1, breaker.recordStall(wifi, isMedia = true).strikes)
    }
}
