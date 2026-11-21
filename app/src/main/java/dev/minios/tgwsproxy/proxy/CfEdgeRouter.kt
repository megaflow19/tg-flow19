package dev.minios.tgwsproxy.proxy

/**
 * Alternate Cloudflare edge addresses for the CF proxy route, remembered per Android network.
 *
 * Observed 2026-11-19 from a phone: a network blocked exactly the Cloudflare addresses that DNS
 * returns for the proxy domains (two IPs, any SNI), while most other Cloudflare edge addresses
 * passed and served the same zones: with TLS SNI `<base>` and `Host: kwsN.<base>` they answered
 * `101 Switching Protocols`. Cloudflare routes by SNI/Host on its anycast edge, so connecting to
 * another edge address reaches the same proxy.
 *
 * Policy (pure, no I/O; the caller dials):
 *  - try the edge that worked last on this network first;
 *  - then the DNS address, unless it was confirmed blocked on this network;
 *  - after a network-level failure of the DNS address, try up to [EDGE_ATTEMPTS] other edges;
 *  - an edge that worked right after the DNS address failed confirms the block, so the DNS
 *    address is skipped on this network for [DNS_BLOCK_TTL_MS];
 *  - an edge that fails is skipped for [EDGE_FAIL_COOLDOWN_MS]; one answering 403 (does not
 *    serve the zones) for [EDGE_FORBIDDEN_COOLDOWN_MS].
 * A new network handle starts clean.
 */
internal class CfEdgeRouter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val edges: List<String> = EDGE_IPS,
) {
    sealed class Hop {
        object Dns : Hop() { override fun toString() = "dns" }
        data class Edge(val ip: String, val index: Int) : Hop()
    }

    private class NetState {
        var preferred: String? = null
        var dnsBlockedUntil = 0L
        val edgeCooldown = HashMap<String, Long>()
        /** Edges that just dropped a handshake: not tried first until then (see [edgeDropped]). */
        val edgeSuspect = HashMap<String, Long>()
        var cursor = 0
    }

    private val lock = Any()
    private val states = LinkedHashMap<Long?, NetState>()

    /** Ordered hops for one connection attempt on [networkId]. Edges after Dns are fallbacks. */
    fun plan(networkId: Long?): List<Hop> = synchronized(lock) {
        val now = clock()
        val state = state(networkId)
        val hops = ArrayList<Hop>(EDGE_ATTEMPTS + 2)
        val preferred = state.preferred?.takeIf {
            (state.edgeCooldown[it] ?: 0L) <= now && (state.edgeSuspect[it] ?: 0L) <= now
        }
        if (preferred != null) hops += edgeHop(preferred)
        if (state.dnsBlockedUntil <= now) hops += Hop.Dns
        var added = 0
        for (offset in edges.indices) {
            if (added >= EDGE_ATTEMPTS) break
            val ip = edges[(state.cursor + offset) % edges.size]
            if (ip == preferred || (state.edgeCooldown[ip] ?: 0L) > now) continue
            hops += edgeHop(ip)
            added++
        }
        hops
    }

    /** True when [hop] should only be tried after a network-level failure of an earlier hop. */
    fun isFallback(hops: List<Hop>, hop: Hop): Boolean {
        val dnsIndex = hops.indexOf(Hop.Dns)
        return dnsIndex >= 0 && hops.indexOf(hop) > dnsIndex
    }

    fun dnsWorked(networkId: Long?) = synchronized(lock) {
        state(networkId).dnsBlockedUntil = 0L
    }

    /** [afterDnsFailure]: the DNS address failed at network level earlier in the same attempt. */
    fun edgeWorked(networkId: Long?, hop: Hop.Edge, afterDnsFailure: Boolean) = synchronized(lock) {
        val state = state(networkId)
        state.preferred = hop.ip
        state.edgeCooldown.remove(hop.ip)
        state.edgeSuspect.remove(hop.ip)
        if (afterDnsFailure) state.dnsBlockedUntil = clock() + DNS_BLOCK_TTL_MS
    }

    /**
     * [hop] dropped a TLS handshake. That may be blocking by name (no address helps) or a bad
     * address, which is only known once another address passes. Until then, attempts running in
     * parallel stop trying it first for [EDGE_SUSPECT_MS], so they do not all wait out its
     * timeout. Seen 2026-11-20/21: a burst of connections each lost 5 s on the same dropped edge.
     */
    fun edgeDropped(networkId: Long?, hop: Hop.Edge) = synchronized(lock) {
        state(networkId).edgeSuspect[hop.ip] = clock() + EDGE_SUSPECT_MS
    }

    fun edgeFailed(networkId: Long?, hop: Hop.Edge, forbidden: Boolean) = synchronized(lock) {
        val state = state(networkId)
        state.edgeCooldown[hop.ip] = clock() + if (forbidden) EDGE_FORBIDDEN_COOLDOWN_MS else EDGE_FAIL_COOLDOWN_MS
        if (state.preferred == hop.ip) state.preferred = null
        // Next attempt starts after this edge, spreading load across ranges.
        state.cursor = (hop.index + 1) % edges.size
    }

    /** Addresses for an HTTP/2 lane: the remembered edge while the DNS address is blocked. */
    fun h2Override(networkId: Long?): String? = synchronized(lock) {
        val state = states[networkId] ?: return null
        if (state.dnsBlockedUntil <= clock()) return null
        state.preferred?.takeIf { (state.edgeCooldown[it] ?: 0L) <= clock() }
    }

    fun reset() = synchronized(lock) { states.clear() }

    private fun edgeHop(ip: String) = Hop.Edge(ip, edges.indexOf(ip))

    private fun state(networkId: Long?): NetState = states.getOrPut(networkId) {
        if (states.size >= MAX_NETWORKS) states.remove(states.keys.first())
        NetState()
    }

    companion object {
        /**
         * Process-wide: proxy runtimes restart often on the same network. Lazy, because an
         * eager instance here would be built before [EDGE_IPS] below is initialized.
         */
        val shared: CfEdgeRouter by lazy { CfEdgeRouter() }

        private val NO_ROUTE = Regex(
            "ENETUNREACH|EHOSTUNREACH|Network is unreachable|No route to host|Software caused connection abort",
            RegexOption.IGNORE_CASE,
        )

        /**
         * True when the device itself had no usable route (network switching or gone), seen as an
         * immediate ENETUNREACH/EHOSTUNREACH or an aborted socket. That is not address blocking:
         * blocking looks like a TCP connect that times out, a TLS handshake that times out or is
         * reset, or a refused connect (ECONNREFUSED, an injected RST) — all of which still count
         * against the address.
         */
        fun isLocalNetworkFailure(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is java.net.NoRouteToHostException) return true
                if (cause is java.net.SocketException && cause.message?.let { NO_ROUTE.containsMatchIn(it) } == true) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }

        const val EDGE_ATTEMPTS = 3
        const val EDGE_TIMEOUT_MS = 5_000
        const val DNS_BLOCK_TTL_MS = 10 * 60_000L
        const val EDGE_FAIL_COOLDOWN_MS = 10 * 60_000L
        const val EDGE_SUSPECT_MS = 60_000L
        const val EDGE_FORBIDDEN_COOLDOWN_MS = 6 * 60 * 60_000L
        private const val MAX_NETWORKS = 16

        /**
         * Cloudflare edge addresses that served the proxy zones (HTTP 101 for several CF proxy
         * domains, 2026-11-19) and passed on a network that blocked the DNS addresses.
         * Interleaved by range so consecutive fallbacks hit different subnets.
         */
        val EDGE_IPS = listOf(
            "104.18.32.47", "172.64.155.249", "162.159.135.42", "188.114.96.1",
            "104.16.1.1", "103.21.244.1", "104.17.1.1", "172.66.1.1",
            "104.19.1.1", "188.114.98.1", "104.16.200.1", "104.18.1.1",
            "104.17.200.1", "104.19.200.1", "104.18.200.1", "104.21.200.1",
        )
    }
}
