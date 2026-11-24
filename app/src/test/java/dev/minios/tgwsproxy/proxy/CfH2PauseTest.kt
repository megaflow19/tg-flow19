package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CfH2PauseTest {
    @Test fun setupTimeoutPausesHttp2OnThatNetworkOnly() {
        val now = 5_000_000L
        assertFalse(CfH2Pool.pausedOn("cellular:test-a", now))
        CfH2Pool.setupTimedOut("cellular:test-a", now)
        assertTrue(CfH2Pool.pausedOn("cellular:test-a", now + CfH2Pool.SETUP_TIMEOUT_PAUSE_MS - 1))
        assertFalse(CfH2Pool.pausedOn("cellular:test-a", now + CfH2Pool.SETUP_TIMEOUT_PAUSE_MS))
        assertFalse(CfH2Pool.pausedOn("wifi:test-b", now))
    }
}
