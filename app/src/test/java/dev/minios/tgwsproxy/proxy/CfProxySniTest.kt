package dev.minios.tgwsproxy.proxy

import org.junit.Assert.assertEquals
import org.junit.Test

class CfProxySniTest {
    @Test fun cfProxyHostUsesBareBaseDomainAsSni() {
        assertEquals("pclead.co.uk", RawWebSocket.cfProxySni("kws2.pclead.co.uk"))
        assertEquals("pclead.co.uk", RawWebSocket.cfProxySni("kws4.pclead.co.uk"))
        assertEquals("my.example.com", RawWebSocket.cfProxySni("kws5.my.example.com"))
    }

    @Test fun telegramDirectDomainsKeepTheirSni() {
        assertEquals("kws2.web.telegram.org", RawWebSocket.cfProxySni("kws2.web.telegram.org"))
        assertEquals("kws2-1.web.telegram.org", RawWebSocket.cfProxySni("kws2-1.web.telegram.org"))
    }

    @Test fun nonKwsHostsAreUnchanged() {
        assertEquals("worker.example", RawWebSocket.cfProxySni("worker.example"))
        assertEquals("kws.example.com", RawWebSocket.cfProxySni("kws.example.com"))
        assertEquals("ya.ru", RawWebSocket.cfProxySni("ya.ru"))
    }
}
