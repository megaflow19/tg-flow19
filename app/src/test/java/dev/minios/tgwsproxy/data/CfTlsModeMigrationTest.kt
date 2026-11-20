package dev.minios.tgwsproxy.data

import dev.minios.tgwsproxy.proxy.CfTlsMode
import org.junit.Assert.assertEquals
import org.junit.Test

class CfTlsModeMigrationTest {
    @Test fun storedModeWins() {
        assertEquals(CfTlsMode.ON, resolveCfTlsMode("ON", legacySecure = false))
        assertEquals(CfTlsMode.OFF, resolveCfTlsMode("off", legacySecure = true))
    }

    @Test fun legacySwitchMigrates() {
        assertEquals(CfTlsMode.AUTO, resolveCfTlsMode(null, legacySecure = true))
        assertEquals(CfTlsMode.OFF, resolveCfTlsMode(null, legacySecure = false))
    }

    @Test fun freshInstallAndGarbageDefaultToAuto() {
        assertEquals(CfTlsMode.AUTO, resolveCfTlsMode(null, legacySecure = null))
        assertEquals(CfTlsMode.AUTO, resolveCfTlsMode("bogus", legacySecure = null))
    }
}
