package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.proxy.CfTransport.PLAIN
import dev.minios.tgwsproxy.proxy.CfTransport.TLS
import dev.minios.tgwsproxy.proxy.CfTransport.TLS_SPLIT
import org.junit.Assert.*
import org.junit.Test

class CfTransportSelectorTest {
    private var now = 1_000L
    private val selector = CfTransportSelector({ now })
    private val net = "wifi:7"

    @Test fun autoWalksTlsThenSplitThenPlainAndRemembersPerNetwork() {
        assertEquals(TLS, selector.current(net, CfTlsMode.AUTO))
        assertEquals(TLS_SPLIT, selector.sniBlocked(net, CfTlsMode.AUTO, TLS))
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.AUTO))
        assertEquals(TLS, selector.current("wifi:8", CfTlsMode.AUTO))
        assertEquals(PLAIN, selector.sniBlocked(net, CfTlsMode.AUTO, TLS_SPLIT))
        assertEquals(PLAIN, selector.current(net, CfTlsMode.AUTO))
        assertNull(selector.sniBlocked(net, CfTlsMode.AUTO, PLAIN))
    }

    @Test fun onNeverFallsBackToPortEighty() {
        assertEquals(TLS_SPLIT, selector.sniBlocked(net, CfTlsMode.ON, TLS))
        assertNull(selector.sniBlocked(net, CfTlsMode.ON, TLS_SPLIT))
        assertTrue(selector.isBackingOff(net))
        // A step to port 80 taken in AUTO is capped while the setting is ON.
        now += CfTransportSelector.BLOCKED_BACKOFF_MS
        selector.sniBlocked(net, CfTlsMode.AUTO, TLS_SPLIT)
        assertEquals(PLAIN, selector.current(net, CfTlsMode.AUTO))
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.ON))
    }

    @Test fun offIsAlwaysPlain() {
        assertEquals(PLAIN, selector.current(net, CfTlsMode.OFF))
        selector.sniBlocked(net, CfTlsMode.AUTO, TLS)
        assertEquals(PLAIN, selector.current(net, CfTlsMode.OFF))
        assertNull(selector.sniBlocked(net, CfTlsMode.OFF, PLAIN))
    }

    @Test fun concurrentAttemptsReuseAStepAlreadyTaken() {
        assertEquals(TLS_SPLIT, selector.sniBlocked(net, CfTlsMode.AUTO, TLS))
        // A second attempt that started on TLS before the switch must not skip to port 80.
        assertEquals(TLS_SPLIT, selector.sniBlocked(net, CfTlsMode.AUTO, TLS))
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.AUTO))
    }

    @Test fun rememberedStepExpiresSoPlainTlsIsRetried() {
        selector.sniBlocked(net, CfTlsMode.AUTO, TLS)
        now += CfTransportSelector.RECHECK_MS - 1
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.AUTO))
        now += 1
        assertEquals(TLS, selector.current(net, CfTlsMode.AUTO))
        assertEquals(TLS_SPLIT, selector.sniBlocked(net, CfTlsMode.AUTO, TLS))
    }

    @Test fun exhaustedLadderBacksOffBrieflyAndSuccessClearsIt() {
        selector.sniBlocked(net, CfTlsMode.ON, TLS)
        assertNull(selector.sniBlocked(net, CfTlsMode.ON, TLS_SPLIT))
        assertTrue(selector.isBackingOff(net))
        assertFalse(selector.isBackingOff("wifi:8"))
        selector.worked(net)
        assertFalse(selector.isBackingOff(net))
        selector.sniBlocked(net, CfTlsMode.ON, TLS_SPLIT)
        now += CfTransportSelector.BLOCKED_BACKOFF_MS
        assertFalse(selector.isBackingOff(net))
    }

    /** One stall report a little later than the previous one (strikes closer than 1 s count once). */
    private fun strike(mode: CfTlsMode, transport: CfTransport, key: String = net): CfTransport? {
        now += CfTransportSelector.STALL_DEDUP_MS
        return selector.streamStalled(key, mode, transport)
    }

    @Test fun frozenSplitStepsToPlainAfterThreeStrikesAndIsSkippedOnRecheck() {
        selector.sniBlocked(net, CfTlsMode.AUTO, TLS)
        assertNull(strike(CfTlsMode.AUTO, TLS_SPLIT))
        assertNull(strike(CfTlsMode.AUTO, TLS_SPLIT))
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.AUTO))
        assertEquals(PLAIN, strike(CfTlsMode.AUTO, TLS_SPLIT))
        assertEquals(PLAIN, selector.current(net, CfTlsMode.AUTO))
        // Hourly recheck tries plain TLS; when it is dropped again, the frozen split is skipped.
        now += CfTransportSelector.RECHECK_MS
        assertEquals(TLS, selector.current(net, CfTlsMode.AUTO))
        assertEquals(PLAIN, selector.sniBlocked(net, CfTlsMode.AUTO, TLS))
        // After the avoid period the split is offered again.
        now += CfTransportSelector.AVOID_SPLIT_MS
        assertEquals(TLS, selector.current(net, CfTlsMode.AUTO))
        assertEquals(TLS_SPLIT, selector.sniBlocked(net, CfTlsMode.AUTO, TLS))
    }

    @Test fun stallsFarApartDoNotStepDown() {
        assertNull(strike(CfTlsMode.AUTO, TLS))
        assertNull(strike(CfTlsMode.AUTO, TLS))
        now += CfTransportSelector.STALL_WINDOW_MS + 1
        assertNull(strike(CfTlsMode.AUTO, TLS))
        assertNull(strike(CfTlsMode.AUTO, TLS))
        assertEquals(TLS, selector.current(net, CfTlsMode.AUTO))
    }

    @Test fun strikeWindowSlides() {
        // Seen 2026-11-21: strikes at 0 s, 30 s, then 2 min later; the old strikes must not
        // reset the count once the first one ages out.
        assertNull(strike(CfTlsMode.AUTO, TLS))
        now += 90_000L
        assertNull(strike(CfTlsMode.AUTO, TLS))
        now += 60_000L
        assertNull(strike(CfTlsMode.AUTO, TLS)) // first strike is out of the window: 2 remain
        now += 20_000L
        assertEquals(TLS_SPLIT, strike(CfTlsMode.AUTO, TLS))
    }

    @Test fun simultaneousStallsCountOnce() {
        // Telegram closes several sessions at the same moment when it goes to the background.
        repeat(5) { assertNull(selector.streamStalled(net, CfTlsMode.AUTO, TLS)) }
        now += CfTransportSelector.STALL_DEDUP_MS - 1
        assertNull(selector.streamStalled(net, CfTlsMode.AUTO, TLS))
        assertNull(strike(CfTlsMode.AUTO, TLS))
        assertEquals(TLS_SPLIT, strike(CfTlsMode.AUTO, TLS))
    }

    @Test fun frozenPlainTlsTriesSplitAndStaleOrPlainStallsAreIgnored() {
        strike(CfTlsMode.AUTO, TLS)
        strike(CfTlsMode.AUTO, TLS)
        assertEquals(TLS_SPLIT, strike(CfTlsMode.AUTO, TLS))
        // A stall reported by a connection made before the switch does not count twice.
        assertNull(strike(CfTlsMode.AUTO, TLS))
        assertNull(strike(CfTlsMode.AUTO, PLAIN))
        assertNull(strike(CfTlsMode.OFF, TLS))
        // ON keeps the split: there is nothing else it may use.
        repeat(CfTransportSelector.STALL_STRIKES * 2) { assertNull(strike(CfTlsMode.ON, TLS_SPLIT)) }
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.ON))
    }

    @Test fun deadSessionRuleMatchesObservedFreezesOnly() {
        // Frozen split sessions, 2026-11-20/21.
        assertTrue(CfTransportSelector.isDeadSession(3_800, 2_443, 0))
        assertTrue(CfTransportSelector.isDeadSession(7_039, 23_915, 0))
        assertTrue(CfTransportSelector.isDeadSession(8_016, 2_240, 298))
        // Frozen media request on another network: one ~1 KB request, nothing back.
        assertTrue(CfTransportSelector.isDeadSession(6_754, 970, 0))
        assertFalse(CfTransportSelector.isDeadSession(2_500, 970, 0))
        // Client gave up while the connection was being made.
        assertFalse(CfTransportSelector.isDeadSession(4, 731, 0))
        assertFalse(CfTransportSelector.isDeadSession(9, 22_610, 0))
        // Healthy sessions closed together by the client; idle long polls.
        assertFalse(CfTransportSelector.isDeadSession(10_027, 1_679, 797))
        assertFalse(CfTransportSelector.isDeadSession(91_317, 259, 187))
        assertFalse(CfTransportSelector.isDeadSession(30_000, 200, 0))
    }

    private class MemoryStore(var value: String? = null) : CfTransportSelector.Store {
        override fun load() = value
        override fun save(value: String) { this.value = value }
    }

    @Test fun stepsSurviveRestartForStableKeysOnly() {
        val store = MemoryStore()
        val first = CfTransportSelector({ now }, store)
        first.sniBlocked(net, CfTlsMode.AUTO, TLS)
        first.sniBlocked(net, CfTlsMode.AUTO, TLS_SPLIT)
        first.sniBlocked(CfTransportSelector.TRANSIENT_KEY_PREFIX + "42", CfTlsMode.AUTO, TLS)
        now += 10 * 60_000L

        val restarted = CfTransportSelector({ now }, store)
        assertEquals(PLAIN, restarted.current(net, CfTlsMode.AUTO))
        assertEquals(TLS, restarted.current(CfTransportSelector.TRANSIENT_KEY_PREFIX + "42", CfTlsMode.AUTO))
        assertEquals(TLS, restarted.current("wifi:other", CfTlsMode.AUTO))
    }

    @Test fun savedStepExpiresButSplitAvoidanceIsKept() {
        val store = MemoryStore()
        val first = CfTransportSelector({ now }, store)
        first.sniBlocked(net, CfTlsMode.AUTO, TLS)
        repeat(CfTransportSelector.STALL_STRIKES) {
            now += CfTransportSelector.STALL_DEDUP_MS
            first.streamStalled(net, CfTlsMode.AUTO, TLS_SPLIT)
        }
        assertEquals(PLAIN, first.current(net, CfTlsMode.AUTO))
        now += CfTransportSelector.RECHECK_MS

        val restarted = CfTransportSelector({ now }, store)
        assertEquals(TLS, restarted.current(net, CfTlsMode.AUTO))
        // Plain TLS still dropped: the frozen split is skipped straight to port 80.
        assertEquals(PLAIN, restarted.sniBlocked(net, CfTlsMode.AUTO, TLS))
    }

    @Test fun attachStoreLoadsOnceAndCorruptDataIsIgnored() {
        val late = CfTransportSelector({ now })
        late.attachStore(MemoryStore("not json"))
        assertEquals(TLS, late.current(net, CfTlsMode.AUTO))
        val store = MemoryStore()
        CfTransportSelector({ now }, store).sniBlocked(net, CfTlsMode.AUTO, TLS)
        late.attachStore(store) // ignored: already attached
        assertEquals(TLS, late.current(net, CfTlsMode.AUTO))
    }

    @Test fun transportsMapToPortsAndTls() {
        assertEquals(443, TLS.port)
        assertEquals(443, TLS_SPLIT.port)
        assertEquals(80, PLAIN.port)
        assertTrue(TLS_SPLIT.secure && TLS_SPLIT.splitHello)
        assertFalse(TLS.splitHello)
        assertFalse(PLAIN.secure)
    }
}
