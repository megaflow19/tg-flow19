package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.proxy.CfEdgeRouter.Hop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CfEdgeRouterTest {
    private val net = "wifi:7"
    private val other = "wifi:8"
    private val edges = listOf("e0", "e1", "e2", "e3", "e4")

    private fun router(clock: () -> Long = { 1_000L }) = CfEdgeRouter(clock, edges)

    @Test fun freshNetworkTriesDnsFirstThenEdgesOnlyAsFallback() {
        val r = router()
        val hops = r.plan(net)
        assertEquals(Hop.Dns, hops.first())
        assertEquals(listOf("e0", "e1", "e2"), hops.drop(1).map { (it as Hop.Edge).ip })
        assertFalse(r.isFallback(hops, Hop.Dns))
        assertTrue(r.isFallback(hops, hops[1]))
    }

    @Test fun edgeAfterDnsFailureBecomesPreferredAndDnsIsSkipped() {
        val r = router()
        val hops = r.plan(net)
        r.edgeWorked(net, hops[2] as Hop.Edge, afterDnsFailure = true)

        val next = r.plan(net)
        assertEquals(Hop.Edge("e1", 1), next.first())
        assertFalse("DNS address confirmed blocked", next.contains(Hop.Dns))
        // Nothing is a fallback any more: the remembered edge is tried directly.
        assertFalse(r.isFallback(next, next.first()))
        assertEquals("e1", r.h2Override(net))
    }

    @Test fun droppedPreferredEdgeIsNotTriedFirstByParallelAttempts() {
        var now = 1_000L
        val r = router { now }
        r.edgeWorked(net, Hop.Edge("e1", 1), afterDnsFailure = false)
        assertEquals(Hop.Edge("e1", 1), r.plan(net).first())

        r.edgeDropped(net, Hop.Edge("e1", 1))
        val plan = r.plan(net)
        assertEquals(Hop.Dns, plan.first())
        assertTrue("still available as a fallback", plan.contains(Hop.Edge("e1", 1)))

        now += CfEdgeRouter.EDGE_SUSPECT_MS
        assertEquals(Hop.Edge("e1", 1), r.plan(net).first())

        // Working again clears the suspicion at once.
        r.edgeDropped(net, Hop.Edge("e1", 1))
        r.edgeWorked(net, Hop.Edge("e1", 1), afterDnsFailure = false)
        assertEquals(Hop.Edge("e1", 1), r.plan(net).first())
    }

    @Test fun dnsBlockExpiresAndAnotherNetworkStartsClean() {
        var now = 1_000L
        val r = router { now }
        r.edgeWorked(net, Hop.Edge("e0", 0), afterDnsFailure = true)
        assertNull(r.h2Override(other))
        assertEquals(Hop.Dns, r.plan(other).first())

        now += CfEdgeRouter.DNS_BLOCK_TTL_MS
        val hops = r.plan(net)
        assertEquals(Hop.Edge("e0", 0), hops[0])
        assertEquals(Hop.Dns, hops[1])
        assertEquals("HTTP/2 follows the edge WebSocket tries first", "e0", r.h2Override(net))
    }

    @Test fun edgeThatWorksWhileDnsIsFineDoesNotBlockDns() {
        val r = router()
        r.edgeWorked(net, Hop.Edge("e2", 2), afterDnsFailure = false)
        val hops = r.plan(net)
        assertEquals(Hop.Edge("e2", 2), hops[0])
        assertTrue(hops.contains(Hop.Dns))
        assertEquals("e2", r.h2Override(net))
    }

    @Test fun failedEdgesCoolDownAndForbiddenOnesLonger() {
        var now = 1_000L
        val r = router { now }
        fun edgesInPlan() = r.plan(net).filterIsInstance<Hop.Edge>().map { it.ip }
        r.edgeFailed(net, Hop.Edge("e0", 0), forbidden = false)
        r.edgeFailed(net, Hop.Edge("e1", 1), forbidden = true)
        r.edgeFailed(net, Hop.Edge("e2", 2), forbidden = false)
        r.edgeFailed(net, Hop.Edge("e3", 3), forbidden = false)
        assertEquals(listOf("e4"), edgesInPlan())

        now += CfEdgeRouter.EDGE_FAIL_COOLDOWN_MS
        // Short cooldowns are over (e0, e2, e3 eligible again); the forbidden e1 is not.
        // The walk starts after the last failed edge (e3), so the next hops are e4, e0, e2.
        assertEquals(listOf("e4", "e0", "e2"), edgesInPlan())

        now += CfEdgeRouter.EDGE_FORBIDDEN_COOLDOWN_MS
        // Cool down the others so only e0 and e1 remain eligible: e1 must be back.
        listOf("e2", "e3", "e4").forEach { r.edgeFailed(net, Hop.Edge(it, edges.indexOf(it)), forbidden = false) }
        assertEquals(setOf("e0", "e1"), r.plan(net).filterIsInstance<Hop.Edge>().map { it.ip }.toSet())
    }

    @Test fun noRouteFailuresAreNotTreatedAsAddressBlocking() {
        // Messages as Android reports them (2026-11-19 device log, network switch).
        assertTrue(CfEdgeRouter.isLocalNetworkFailure(java.net.ConnectException(
            "failed to connect to /1.2.3.4 (port 443) from /:: (port 0) after 5000ms: connect failed: ENETUNREACH (Network is unreachable)")))
        assertTrue(CfEdgeRouter.isLocalNetworkFailure(java.net.SocketException("Software caused connection abort")))
        assertTrue(CfEdgeRouter.isLocalNetworkFailure(java.net.NoRouteToHostException("No route to host")))
        assertTrue(CfEdgeRouter.isLocalNetworkFailure(java.io.IOException("wrapped", java.net.ConnectException("connect failed: EHOSTUNREACH"))))
    }

    @Test fun blockingLikeFailuresStillCountAgainstTheAddress() {
        assertFalse(CfEdgeRouter.isLocalNetworkFailure(java.net.SocketTimeoutException("Read timed out")))
        assertFalse(CfEdgeRouter.isLocalNetworkFailure(java.net.SocketTimeoutException("failed to connect to /1.2.3.4 (port 443) after 5000ms")))
        assertFalse(CfEdgeRouter.isLocalNetworkFailure(java.net.ConnectException("connect failed: ECONNREFUSED (Connection refused)")))
        assertFalse(CfEdgeRouter.isLocalNetworkFailure(java.net.SocketException("Connection reset")))
        assertFalse(CfEdgeRouter.isLocalNetworkFailure(javax.net.ssl.SSLHandshakeException("Connection closed by peer")))
    }

    @Test fun failingPreferredEdgeIsForgotten() {
        val r = router()
        r.edgeWorked(net, Hop.Edge("e3", 3), afterDnsFailure = true)
        r.edgeFailed(net, Hop.Edge("e3", 3), forbidden = false)
        val hops = r.plan(net)
        assertFalse(hops.contains(Hop.Edge("e3", 3)))
        assertNull(r.h2Override(net))
        // DNS is still blocked, so the plan is edges only, starting after the failed one.
        assertEquals("e4", (hops.first() as Hop.Edge).ip)
    }
}
