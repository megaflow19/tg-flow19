package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.proxy.CfTransport.PLAIN
import dev.minios.tgwsproxy.proxy.CfTransport.TLS
import dev.minios.tgwsproxy.proxy.CfTransport.TLS_SPLIT
import org.junit.Assert.*
import org.junit.Test

class CfTransportSelectorTest {
    private var now = 1_000L
    private val selector = CfTransportSelector { now }
    private val net = 7L

    @Test fun autoWalksTlsThenSplitThenPlainAndRemembersPerNetwork() {
        assertEquals(TLS, selector.current(net, CfTlsMode.AUTO))
        assertEquals(TLS_SPLIT, selector.sniBlocked(net, CfTlsMode.AUTO, TLS))
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.AUTO))
        assertEquals(TLS, selector.current(8L, CfTlsMode.AUTO))
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
        assertFalse(selector.isBackingOff(8L))
        selector.worked(net)
        assertFalse(selector.isBackingOff(net))
        selector.sniBlocked(net, CfTlsMode.ON, TLS_SPLIT)
        now += CfTransportSelector.BLOCKED_BACKOFF_MS
        assertFalse(selector.isBackingOff(net))
    }

    @Test fun frozenSplitStepsToPlainAfterTwoStrikesAndIsSkippedOnRecheck() {
        selector.sniBlocked(net, CfTlsMode.AUTO, TLS)
        assertNull(selector.streamStalled(net, CfTlsMode.AUTO, TLS_SPLIT))
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.AUTO))
        assertEquals(PLAIN, selector.streamStalled(net, CfTlsMode.AUTO, TLS_SPLIT))
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
        assertNull(selector.streamStalled(net, CfTlsMode.AUTO, TLS))
        now += CfTransportSelector.STALL_WINDOW_MS + 1
        assertNull(selector.streamStalled(net, CfTlsMode.AUTO, TLS))
        assertEquals(TLS, selector.current(net, CfTlsMode.AUTO))
    }

    @Test fun frozenPlainTlsTriesSplitAndStaleOrPlainStallsAreIgnored() {
        selector.streamStalled(net, CfTlsMode.AUTO, TLS)
        assertEquals(TLS_SPLIT, selector.streamStalled(net, CfTlsMode.AUTO, TLS))
        // A stall reported by a connection made before the switch does not count twice.
        assertNull(selector.streamStalled(net, CfTlsMode.AUTO, TLS))
        assertNull(selector.streamStalled(net, CfTlsMode.AUTO, PLAIN))
        assertNull(selector.streamStalled(net, CfTlsMode.OFF, TLS))
        // ON keeps the split: there is nothing else it may use.
        selector.streamStalled(net, CfTlsMode.ON, TLS_SPLIT)
        assertNull(selector.streamStalled(net, CfTlsMode.ON, TLS_SPLIT))
        assertEquals(TLS_SPLIT, selector.current(net, CfTlsMode.ON))
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
