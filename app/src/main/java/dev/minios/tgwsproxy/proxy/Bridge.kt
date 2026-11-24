package dev.minios.tgwsproxy.proxy

import android.util.Log
import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import dev.minios.tgwsproxy.diagnostics.DiagnosticRedactor
import kotlinx.coroutines.*
import kotlinx.coroutines.selects.select
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "Bridge"

internal class CfCooldownTracker(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private data class State(val strikes: Int, val blockedUntil: Long, val lastFailureAt: Long)

    private val states = ConcurrentHashMap<String, State>()

    fun isCoolingDown(domain: String): Boolean = (states[domain]?.blockedUntil ?: 0L) > clock()

    fun markRateLimited(domain: String): Long {
        val now = clock()
        val state = states.compute(domain) { _, previous ->
            val strikes = if (previous != null && now - previous.lastFailureAt <= MAX_COOLDOWN_MS) {
                previous.strikes + 1
            } else {
                1
            }
            val delay = cooldownDelayMs(strikes)
            State(strikes, now + delay, now)
        }!!
        return state.blockedUntil - now
    }

    fun clear(domain: String) {
        states.remove(domain)
    }

    fun reset() {
        states.clear()
    }

    companion object {
        private const val BASE_COOLDOWN_MS = 45_000L
        private const val MAX_COOLDOWN_MS = 300_000L

        internal fun cooldownDelayMs(strikes: Int): Long {
            var delay = BASE_COOLDOWN_MS
            repeat((strikes - 1).coerceIn(0, 3)) {
                delay = (delay * 2).coerceAtMost(MAX_COOLDOWN_MS)
            }
            return delay
        }
    }
}

/**
 * Bidirectional bridge between client TCP and upstream WebSocket/TCP.
 * Handles re-encryption of MTProto traffic.
 *
 * Matches the Python bridge.py + tg_ws_proxy.py logic including:
 * - WS blacklisting for DCs that always return HTTP redirects
 * - DC fail cooldown (30s) with reduced WS timeout (2s)
 * - MsgSplitter for splitting TCP stream into individual WS frames
 * - CF proxy multi-domain rotation with sticky active domain
 * - TCP fallback using standard DC IPs (not WS redirect IPs)
 * - Per-session stats (up/down bytes/packets, duration)
 */
class Bridge internal constructor(
    private val connectivity: ConnectivityTracker,
    // Process-wide by default: must survive runtime restarts (see DirectWsCircuitBreaker).
    private val directWsCircuitBreaker: DirectWsCircuitBreaker,
    // Process-wide by default, keyed by network: remembers which Cloudflare edge works.
    private val cfEdgeRouter: CfEdgeRouter = CfEdgeRouter.shared,
    // Process-wide, keyed by network: TLS, split ClientHello or port 80 (see CfTransportSelector).
    private val cfTransports: CfTransportSelector = CfTransportSelector.shared,
) {
    constructor(connectivity: ConnectivityTracker) : this(connectivity, DirectWsCircuitBreaker.shared)

    private val cfDnsResolver = CfDnsResolver()
    private val h2Pool = CfH2Pool(connectivity)

    private val DC_FAIL_COOLDOWN_MS = 30_000L
    private val WS_FAIL_TIMEOUT_MS = 2_000
    private val WS_NORMAL_TIMEOUT_MS = 10_000
    // CF proxy attempt via the DNS address. Healthy CF handshakes take 1-2 s; when the address
    // is blocked, every second here delays the alternate edges (CfEdgeRouter), so 6 s, not 10.
    private val CF_DNS_TIMEOUT_MS = 6_000
    private val DIRECT_IP_FAIL_COOLDOWN_MS = 60 * 60_000L
    private val CF_FALLBACK_PARALLELISM = 2
    private val RELAY_INIT_TIMEOUT_MS = 10_000L
    private val RECHECK_DC = 2

    private fun checkEpoch(epoch: Long) {
        if (!connectivity.isCurrent(epoch)) throw CancellationException("Connectivity changed")
        UpstreamSetupGuard.checkActive()
    }

    private fun record(epoch: Long, dc: Int, media: Boolean, route: TransportRoute, phase: RoutePhase) {
        connectivity.recordIfCurrent(epoch, requireInternet = phase == RoutePhase.FAILED || phase == RoutePhase.COOLDOWN) {
            connectivity.record(epoch, RouteKey(dc, media, route), phase)
        }
    }

    /**
     * Dials a WebSocket. For the CF proxy route the transport comes from [CfTransportSelector]
     * (TLS, TLS with a split ClientHello, or port 80); when every address drops the handshake
     * for this name, the next allowed transport is tried right away.
     */
    private fun dial(epoch: Long, dc: Int, media: Boolean, target: String, buffer: Int,
                     timeout: Int, cfDomain: String? = null, tlsMode: CfTlsMode = CfTlsMode.AUTO,
                     isTestDc: Boolean = false): RawWebSocket {
        if (cfDomain == null) {
            // Direct WebSocket: the whole setup (TCP, TLS, upgrade) within the timeout, not each read.
            return dialVia(epoch, dc, media, target, buffer, timeout, null, CfTransport.TLS, isTestDc,
                edgeIp = null, deadlineMs = timeout.toLong())
        }
        val networkKey = connectivity.state.value.transportKey
        var transport = cfTransports.current(networkKey, tlsMode)
        if (cfTransports.isBackingOff(networkKey)) {
            throw SniBlockedException(transport, null)
        }
        while (true) {
            try {
                // Port 80 uses the same address plan: on 2026-11-22 the DNS address timed out on a
                // network half of the time over port 80 too, costing 6 s per connection, while
                // the alternate edges answered.
                val ws = dialEdges(epoch, dc, media, target, buffer, timeout, cfDomain, transport, isTestDc, networkKey)
                connectivity.recordIfCurrent(epoch) { cfTransports.worked(networkKey) }
                ws.cfTransport = transport
                return ws
            } catch (e: SniBlockedException) {
                checkEpoch(epoch)
                var next: CfTransport? = null
                connectivity.recordIfCurrent(epoch, requireInternet = true) {
                    next = cfTransports.sniBlocked(networkKey, tlsMode, e.transport)
                }
                transport = next ?: throw e
            }
        }
    }

    /**
     * Background recheck of the CF transport on the current network (see
     * [CfTransportSelector.recheckPlan]): one test handshake per higher step, off the connections
     * Telegram uses. A step works when its handshake passes; an HTTP answer counts too (the name
     * got through). Dropped handshakes mean still blocked; network-level failures prove nothing.
     */
    internal fun recheckCfTransport(config: ProxyConfig) {
        if (config.forceTestDc || !config.cfProxyEnabled) return
        val snapshot = connectivity.state.value
        if (!snapshot.allowsBackgroundWork) return
        val networkKey = snapshot.transportKey
        val plan = cfTransports.recheckPlan(networkKey, config.cfTlsMode)
        if (plan.isEmpty()) return
        val epoch = snapshot.epoch
        val domain = config.customCfDomains().firstOrNull() ?: CfProxyDomains.getDomainsForDc(RECHECK_DC).firstOrNull() ?: return
        var working: CfTransport? = null
        var conclusive = true
        for (transport in plan) {
            try {
                dialEdges(epoch, RECHECK_DC, false, domain, config.bufferSize, CF_DNS_TIMEOUT_MS, domain,
                    transport, config.forceTestDc, networkKey, probe = true).abort()
                working = transport
                break
            } catch (e: CancellationException) {
                return
            } catch (e: WsHandshakeError) {
                working = transport
                break
            } catch (e: Exception) {
                if (e is SniBlockedException || e is TlsHandshakeDropped) continue
                conclusive = false
                break
            }
        }
        // The network changed meanwhile: the result belongs to another network.
        if (!connectivity.isCurrent(epoch)) return
        cfTransports.recheckDone(networkKey, config.cfTlsMode, working, conclusive)
    }

    /**
     * One TLS transport over [CfEdgeRouter]'s plan: the remembered edge, the DNS address, then
     * alternate Cloudflare edges after a network-level failure of the DNS address. An HTTP answer
     * from the proxy (other than 403 from an edge that does not serve the zone) is a server
     * response, not address blocking, so it is rethrown without trying other addresses. A
     * handshake dropped after the TCP connect on [CfTransportSelector.SNI_BLOCK_ADDRESSES]
     * addresses is blocking by name: no address is penalized and [SniBlockedException] is thrown.
     */
    private fun dialEdges(epoch: Long, dc: Int, media: Boolean, target: String, buffer: Int,
                          timeout: Int, cfDomain: String, transport: CfTransport,
                          isTestDc: Boolean, networkKey: String?, probe: Boolean = false): RawWebSocket {
        val hops = cfEdgeRouter.plan(networkKey)
        var dnsFailed = false
        var dropped = 0
        val droppedEdges = ArrayList<CfEdgeRouter.Hop.Edge>(2)
        var lastError: Exception? = null
        var searching = false
        // A background recheck learns nothing about addresses: the same edges are used for the
        // working step meanwhile, and a recheck must not push them down the plan.
        val cfEdgeRouter = if (probe) CfEdgeRouter() else this.cfEdgeRouter
        try {
        for (hop in hops) {
            if (cfEdgeRouter.isFallback(hops, hop) && !dnsFailed) break
            checkEpoch(epoch)
            // Any edge after the first hop means the usual address failed: tell the UI.
            if (!probe && !searching && hop is CfEdgeRouter.Hop.Edge && hop != hops.first()) {
                searching = true
                connectivity.beginAlternateRouteSearch()
            }
            try {
                val ws = when (hop) {
                    CfEdgeRouter.Hop.Dns ->
                        dialVia(epoch, dc, media, target, buffer, timeout, cfDomain, transport, isTestDc, edgeIp = null)
                    is CfEdgeRouter.Hop.Edge ->
                        dialVia(epoch, dc, media, target, buffer, minOf(timeout, CfEdgeRouter.EDGE_TIMEOUT_MS),
                            cfDomain, transport, isTestDc, edgeIp = hop.ip)
                }
                connectivity.recordIfCurrent(epoch) {
                    // The same name and transport passed here, so an edge that dropped the
                    // handshake earlier in this attempt is bad as an address: cool it down.
                    // Seen 2026-11-20: a remembered edge dropped every split handshake, costing
                    // each new connection a timeout before the DNS address answered.
                    droppedEdges.forEach { cfEdgeRouter.edgeFailed(networkKey, it, forbidden = false) }
                    when (hop) {
                        CfEdgeRouter.Hop.Dns -> cfEdgeRouter.dnsWorked(networkKey)
                        is CfEdgeRouter.Hop.Edge -> {
                            cfEdgeRouter.edgeWorked(networkKey, hop, afterDnsFailure = dnsFailed)
                            DiagnosticLogger.event(
                                "cf_edge_used", "dc" to dc, "media" to media,
                                "edge" to hop.index, "afterDnsFailure" to dnsFailed,
                            )
                        }
                    }
                }
                return ws
            } catch (e: CancellationException) {
                throw e
            } catch (e: WsHandshakeError) {
                if (hop is CfEdgeRouter.Hop.Edge && e.statusCode == 403) {
                    connectivity.recordIfCurrent(epoch, requireInternet = true) {
                        cfEdgeRouter.edgeFailed(networkKey, hop, forbidden = true)
                    }
                    DiagnosticLogger.event("cf_edge_failed", "edge" to hop.index, "reason" to "forbidden")
                    lastError = e
                    continue
                }
                throw e
            } catch (e: Exception) {
                lastError = e
                if (e is TlsHandshakeDropped) {
                    // TCP reached the address but the handshake for this name died: possibly
                    // blocking by SNI, which no other address fixes. Never penalize the address.
                    dropped++
                    if (hop == CfEdgeRouter.Hop.Dns) dnsFailed = true
                    if (hop is CfEdgeRouter.Hop.Edge) {
                        droppedEdges += hop
                        connectivity.recordIfCurrent(epoch) { cfEdgeRouter.edgeDropped(networkKey, hop) }
                        DiagnosticLogger.event("cf_edge_failed", "edge" to hop.index, "reason" to "handshake_dropped")
                    }
                    if (dropped >= CfTransportSelector.SNI_BLOCK_ADDRESSES) throw SniBlockedException(transport, e)
                    continue
                }
                if (CfEdgeRouter.isLocalNetworkFailure(e)) {
                    // The phone had no route at all (network switching or gone): not address
                    // blocking. Do not penalize edges, and do not walk the rest of the list.
                    // Seen 2026-11-19: one network switch cooled 13 of 16 edges in a second.
                    if (hop is CfEdgeRouter.Hop.Edge) {
                        DiagnosticLogger.event("cf_edge_failed", "edge" to hop.index, "reason" to "no_network")
                    }
                    break
                }
                when (hop) {
                    CfEdgeRouter.Hop.Dns -> dnsFailed = true
                    is CfEdgeRouter.Hop.Edge -> {
                        connectivity.recordIfCurrent(epoch, requireInternet = true) {
                            cfEdgeRouter.edgeFailed(networkKey, hop, forbidden = false)
                        }
                        DiagnosticLogger.event(
                            "cf_edge_failed", "edge" to hop.index, "reason" to (e::class.simpleName ?: "error"),
                        )
                    }
                }
            }
        }
        // A recheck that saw the name dropped anywhere and passed nowhere: still blocked.
        if (probe && dropped > 0) throw SniBlockedException(transport, lastError)
        throw lastError ?: WebSocketException("No CF proxy address available for DC $dc")
        } finally {
            if (searching) connectivity.endAlternateRouteSearch()
        }
    }

    /**
     * One WebSocket attempt; [edgeIp] overrides the CF proxy address (TLS SNI/Host unchanged).
     * [deadlineMs] bounds the whole attempt (see [SetupDeadline]).
     */
    private fun dialVia(epoch: Long, dc: Int, media: Boolean, target: String, buffer: Int,
                        timeout: Int, cfDomain: String?, transport: CfTransport,
                        isTestDc: Boolean, edgeIp: String?, deadlineMs: Long? = null): RawWebSocket {
        checkEpoch(epoch)
        val sockets = java.util.Collections.synchronizedList(mutableListOf<Socket>())
        val guard = UpstreamSetupGuard.current()
        fun connect(): RawWebSocket =
            RawWebSocket.connectToDc(dc, media, target, buffer, cfDomain, timeout,
                onSocketCreated = { socket ->
                    sockets.add(socket)
                    pendingSockets.add(socket)
                    guard?.track(socket)
                    if (!connectivity.trackSocket(epoch, socket)) checkEpoch(epoch)
                },
                resolveCfAddress = { domain ->
                    checkEpoch(epoch)
                    if (edgeIp != null) null
                    else cfDnsResolver.resolve(domain, connectivity, epoch).also { checkEpoch(epoch) }
                },
                cachedCfAddress = { domain ->
                    var address: String? = edgeIp
                    if (address == null) connectivity.recordIfCurrent(epoch) { address = cfDnsResolver.cached(domain) }
                    address
                 }, secure = transport.secure, isTestDc = isTestDc, splitHello = transport.splitHello)
        try {
            val ws = if (deadlineMs == null) connect() else SetupDeadline.bounded(deadlineMs, close = {
                synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            }, block = ::connect)
            if (!connectivity.isCurrent(epoch) || guard?.cancelled == true) {
                ws.abort()
                checkEpoch(epoch)
            }
            return ws
        } catch (e: Exception) {
            // Sockets closed because Telegram gave up: not a failure of this address or route.
            if (e !is CancellationException) UpstreamSetupGuard.checkActive(e)
            throw e
        } finally {
            sockets.forEach {
                connectivity.releaseSocket(it)
                pendingSockets.remove(it)
            }
        }
    }

    enum class UpstreamType { WEBSOCKET, CFPROXY, TCP }

    // WS blacklist: DCs that always return redirects (permanent, like Python ws_blacklist)
    private val wsBlacklist = ConcurrentHashMap.newKeySet<String>()

    // DC fail cooldown: timestamp until which WS timeout is reduced
    private val dcFailUntil = ConcurrentHashMap<String, Long>()
    private val directIpFailUntil = ConcurrentHashMap<String, Long>()
    private val cfCooldowns = CfCooldownTracker()
    private val pendingSockets = ConcurrentHashMap.newKeySet<Socket>()
    private val workerRetryAfter = ConcurrentHashMap<String, Long>()
    private val activeWorkers = ConcurrentHashMap<Int, String>()
    private val activeCustomCf = ConcurrentHashMap<Int, String>()

    /**
     * Reset blacklist and cooldown state (called on server restart).
     */
    fun resetState() {
        pendingSockets.forEach { try { it.close() } catch (_: Exception) {} }
        pendingSockets.clear()
        wsBlacklist.clear()
        dcFailUntil.clear()
        directIpFailUntil.clear()
        cfCooldowns.reset()
        cfDnsResolver.reset()
        // directWsCircuitBreaker is deliberately kept: it is scoped to the Android network,
        // and runtime restarts on the same network must not forget a frozen direct path.
        h2Pool.reset()
        workerRetryAfter.clear()
        activeWorkers.clear()
        activeCustomCf.clear()
    }

    /**
     * Get a summary of currently blacklisted DCs (for stats logging, #12).
     */
    fun blacklistSummary(): String {
        val bl = wsBlacklist.toList()
        return if (bl.isEmpty()) "none" else bl.sorted().joinToString(", ") { "DC$it" }
    }

    data class UpstreamConnection(
        val type: UpstreamType,
        val ws: RawWebSocket? = null,
        val tcpSocket: Socket? = null,
        val epoch: Long = 0,
        val h2: CfH2Pool.Channel? = null,
        val worker: Boolean = false,
    ) : AutoCloseable {
        fun abort() {
            ws?.abort()
            h2?.close()
            try { tcpSocket?.close() } catch (_: Exception) {}
        }
        override fun close() {
            ws?.close()
            h2?.close()
            try { tcpSocket?.close() } catch (_: Exception) {}
        }
    }

    /**
     * Establish upstream connection with fallback chain matching Python:
     * 1. Optionally try CF before every direct route
     * 2. Check WS blacklist / DC not in config -> skip WS, go to fallback
     * 3. Try WS pool hit, then direct WS with an adaptive timeout
     * 4. On WS failure -> update blacklist/cooldown -> fallback (CF proxy / TCP)
     *
     * #1 FIX: Removed outer domain loop — connectToDc() handles domain iteration internally.
     */
    internal suspend fun connectUpstream(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        pool: WsPool?,
        protoTag: Int,
        relayInit: ByteArray,
        tcpFallback: TcpFallbackConnector,
        epoch: Long = connectivity.state.value.epoch,
    ): UpstreamConnection = coroutineScope {
        // Cancelling this setup (Telegram gave up, see ClientSetupInput) closes its sockets.
        val guard = UpstreamSetupGuard()
        val monitor = launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally { guard.cancel() }
        }
        try {
            withContext(Dispatchers.IO + guard.asContextElement()) {
                connectUpstreamGuarded(dc, isMedia, config, pool, relayInit, tcpFallback, epoch, protoTag)
            }
        } finally {
            guard.finish()
            monitor.cancel()
        }
    }

    private suspend fun connectUpstreamGuarded(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        pool: WsPool?,
        relayInit: ByteArray,
        tcpFallback: TcpFallbackConnector,
        epoch: Long,
        protoTag: Int,
    ): UpstreamConnection = withContext(Dispatchers.IO) {
        checkEpoch(epoch)
        val attemptStartedAt = System.currentTimeMillis()
        val mediaTag = if (isMedia) " media" else ""
        val dcKey = "$dc${if (isMedia) "m" else ""}${if (config.forceTestDc) "t" else ""}"
        var adaptiveCfFirst = false
        connectivity.recordIfCurrent(epoch) {
            adaptiveCfFirst = !config.forceTestDc && config.autoOptimizeConnection && config.cfProxyEnabled &&
                    directWsCircuitBreaker.isBlocked(connectivity.state.value.networkId, isMedia)
        }
        val cfAttempted = !config.forceTestDc && config.cfProxyEnabled && (config.cfProxyFirst || adaptiveCfFirst)

        if (cfAttempted) {
            if (adaptiveCfFirst) {
                DiagnosticLogger.event(
                    "adaptive_route_override",
                    "dc" to dc,
                    "media" to isMedia,
                    "route" to "cloudflare",
                )
            }
            DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
            val conn = tryWorkerFallback(dc, isMedia, config, epoch)
                ?: tryCfProxyFallback(dc, isMedia, config, epoch)
            if (conn != null) return@withContext conn
        }

        // If DC not in config or WS blacklisted -> skip WS, go straight to fallback
        val targetIp = config.dcRedirects[dc]
        if (targetIp == null || dcKey in wsBlacklist) {
            if (targetIp == null) {
                Log.i(TAG, "DC$dc not in config -> fallback")
            } else {
                Log.i(TAG, "DC$dc$mediaTag WS blacklisted -> fallback")
            }
            if (dcKey in wsBlacklist) record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.COOLDOWN)
            val conn = doFallback(dc, isMedia, config, protoTag, relayInit, tcpFallback, epoch, allowCf = !cfAttempted)
            if (conn != null) return@withContext conn
            connectivity.recordExhausted(epoch, RouteTarget(dc, isMedia))
            throw ProxyException("DC$dc$mediaTag no fallback available")
        }

        // Determine WS timeout (adaptive, like Python)
        val now = System.currentTimeMillis()
        val ipFailUntil = directIpFailUntil[targetIp] ?: 0L
        if (config.cfProxyEnabled && now < ipFailUntil) {
            Log.i(TAG, "Direct WS IP $targetIp is cooling down -> fallback")
            record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.COOLDOWN)
            val conn = doFallback(dc, isMedia, config, protoTag, relayInit, tcpFallback, epoch, allowCf = !cfAttempted)
            if (conn != null) return@withContext conn
            connectivity.recordExhausted(epoch, RouteTarget(dc, isMedia))
            throw ProxyException("All upstream routes unavailable")
        }
        val failUntil = dcFailUntil[dcKey] ?: 0L
        val wsTimeout = if (now < failUntil) WS_FAIL_TIMEOUT_MS else WS_NORMAL_TIMEOUT_MS

        // 1. Try WS pool
        checkEpoch(epoch)
        var pooledWs: RawWebSocket? = null
        connectivity.recordIfCurrent(epoch) { if (!config.forceTestDc) pooledWs = pool?.get(dc, isMedia) }
        if (pooledWs != null) {
            val ready = pooledWs!!
            if (!connectivity.recordIfCurrent(epoch) {
                ProxyStats.poolHits.incrementAndGet()
                ProxyStats.connectionsWs.incrementAndGet()
                if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Pool hit for DC$dc$mediaTag")
                dcFailUntil.remove(dcKey)
                directIpFailUntil.remove(targetIp)
                DiagnosticLogger.event(
                    "upstream_route_ready",
                    "dc" to dc,
                    "media" to isMedia,
                    "route" to "ws_pool",
                    "elapsedMs" to System.currentTimeMillis() - attemptStartedAt,
                )
                record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.CONNECTED)
            }) { ready.abort(); checkEpoch(epoch) }
            return@withContext UpstreamConnection(UpstreamType.WEBSOCKET, ws = ready, epoch = epoch)
        }
        connectivity.recordIfCurrent(epoch) { ProxyStats.poolMisses.incrementAndGet() }

        // 2. Try WS direct — single call to connectToDc which handles domain iteration
        var ws: RawWebSocket? = null
        var anyRedirect = false
        var allRedirects = true

        Log.i(TAG, "DC$dc$mediaTag -> WS direct via $targetIp (timeout=${wsTimeout}ms)")
        DiagnosticLogger.event(
            "upstream_attempt",
            "dc" to dc,
            "media" to isMedia,
            "route" to "ws_direct",
            "timeoutMs" to wsTimeout,
        )
        try {
            record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.CONNECTING)
            ws = dial(epoch, dc, isMedia, targetIp, config.bufferSize, wsTimeout, isTestDc = config.forceTestDc)
            allRedirects = false
        } catch (e: WsHandshakeError) {
            connectivity.recordIfCurrent(epoch, requireInternet = true) { ProxyStats.wsErrors.incrementAndGet() }
            if (e.isRedirect) {
                anyRedirect = true
                Log.w(TAG, "DC$dc$mediaTag WS redirect HTTP ${e.statusCode}")
            } else {
                allRedirects = false
                Log.w(TAG, "DC$dc$mediaTag WS handshake HTTP ${e.statusCode}")
            }
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "handshake",
                "status" to e.statusCode,
            )
        } catch (e: SocketTimeoutException) {
            connectivity.recordIfCurrent(epoch, requireInternet = true) { ProxyStats.wsErrors.incrementAndGet() }
            allRedirects = false
            connectivity.recordIfCurrent(epoch, requireInternet = true) {
                directIpFailUntil[targetIp] = now + DIRECT_IP_FAIL_COOLDOWN_MS
            }
            Log.w(TAG, "DC$dc$mediaTag WS timeout via $targetIp; IP cooldown enabled")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "timeout",
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            connectivity.recordIfCurrent(epoch, requireInternet = true) { ProxyStats.wsErrors.incrementAndGet() }
            allRedirects = false
            Log.w(TAG, "DC$dc$mediaTag WS connect failed: ${cfTransportErrorForLog(e)}")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "route" to "ws_direct",
                "stage" to "connect",
            )
        }

        // WS success
        if (ws != null) {
            if (!connectivity.recordIfCurrent(epoch) {
                dcFailUntil.remove(dcKey)
                directIpFailUntil.remove(targetIp)
                ProxyStats.connectionsWs.incrementAndGet()
                DiagnosticLogger.event(
                    "upstream_route_ready",
                    "dc" to dc,
                    "media" to isMedia,
                    "route" to "ws_direct",
                    "elapsedMs" to System.currentTimeMillis() - attemptStartedAt,
                )
                record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.CONNECTED)
            }) { ws.abort(); checkEpoch(epoch) }
            return@withContext UpstreamConnection(UpstreamType.WEBSOCKET, ws = ws, epoch = epoch)
        }

        // WS failed -> update blacklist/cooldown
        record(epoch, dc, isMedia, TransportRoute.WEBSOCKET, RoutePhase.FAILED)
        connectivity.recordIfCurrent(epoch, requireInternet = true) {
            if (anyRedirect && allRedirects) {
                wsBlacklist.add(dcKey)
                Log.w(TAG, "DC$dc$mediaTag blacklisted for WS (all redirects)")
            } else {
                dcFailUntil[dcKey] = now + DC_FAIL_COOLDOWN_MS
                Log.i(TAG, "DC$dc$mediaTag WS cooldown for ${DC_FAIL_COOLDOWN_MS / 1000}s")
            }
        }

        // 3. Fallback (CF proxy / TCP)
        val conn = doFallback(dc, isMedia, config, protoTag, relayInit, tcpFallback, epoch, allowCf = !cfAttempted)
        if (conn != null) return@withContext conn

        connectivity.recordExhausted(epoch, RouteTarget(dc, isMedia))
        throw ProxyException("All upstream connection attempts failed for DC $dc")
    }

    /**
     * Fallback chain: CF proxy / TCP direct (order based on cfProxyPriority).
     * Matches Python do_fallback().
     */
    private suspend fun doFallback(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        protoTag: Int,
        relayInit: ByteArray,
        tcpFallback: TcpFallbackConnector,
        epoch: Long,
        allowCf: Boolean = true,
    ): UpstreamConnection? {
        val mediaTag = if (isMedia) " media" else ""
        val methods = mutableListOf<String>()
        // Do not repeat Workers after an unsuccessful CF-first chain.
        if ((allowCf || config.forceTestDc) && config.cfWorkerDomains.isNotEmpty()) {
            methods.add("worker")
        }
        methods.add("tcp")

        if (config.cfProxyEnabled && allowCf && !config.forceTestDc) {
            if (config.cfProxyPriority) {
                methods.add(methods.indexOf("tcp"), "cf")
            } else {
                methods.add("cf")     // CF after TCP
            }
        }

        for (method in methods) {
            checkEpoch(epoch)
            when (method) {
                "worker" -> tryWorkerFallback(dc, isMedia, config, epoch)?.let { return it }
                "cf" -> {
                    DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "cloudflare")
                    val conn = tryCfProxyFallback(dc, isMedia, config, epoch)
                    if (conn != null) return conn
                }
                "tcp" -> {
                    // TCP fallback uses standard DC IPs (like Python DC_DEFAULT_IPS),
                    // NOT the WS redirect IPs
                    val fallbackIp = (if (config.forceTestDc) MtProtoConstants.DC_TEST_IPS else MtProtoConstants.DC_IPS)[dc]
                    if (fallbackIp != null) {
                        DiagnosticLogger.event("upstream_attempt", "dc" to dc, "media" to isMedia, "route" to "tcp")
                        Log.i(TAG, "DC$dc$mediaTag -> TCP fallback to $fallbackIp:443")
                        val conn = tryTcpDirect(dc, isMedia, fallbackIp, config.bufferSize, relayInit, tcpFallback, epoch)
                        if (conn != null) return conn
                    }
                }
            }
        }
        return null
    }

    private fun tryWorkerFallback(dc: Int, isMedia: Boolean, config: ProxyConfig, epoch: Long): UpstreamConnection? {
        val destination = (if (config.forceTestDc) MtProtoConstants.DC_TEST_IPS else MtProtoConstants.DC_IPS)[dc]
            ?: return null
        val domains = config.cfWorkerDomains.sortedBy { it != activeWorkers[dc] }
        // Workers follow the CF proxy route's transport on this network (no ladder of their own).
        val transport = cfTransports.current(connectivity.state.value.transportKey, config.cfTlsMode)
        for (domain in domains) {
            checkEpoch(epoch)
            if ((workerRetryAfter[domain] ?: 0) > System.currentTimeMillis()) continue
            val sockets = mutableListOf<Socket>()
            try {
                record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTING)
                val ws = RawWebSocket.connect(
                    domain, domain,
                    path = "/apiws?dst=${java.net.URLEncoder.encode(destination, "UTF-8")}&dc=$dc",
                    port = transport.port,
                    secure = transport.secure,
                    splitHello = transport.splitHello,
                    bufferSize = config.bufferSize,
                    onSocketCreated = { socket ->
                        sockets.add(socket)
                        pendingSockets.add(socket)
                        UpstreamSetupGuard.current()?.track(socket)
                        if (!connectivity.trackSocket(epoch, socket)) checkEpoch(epoch)
                    },
                )
                if (UpstreamSetupGuard.current()?.cancelled == true) { ws.abort(); checkEpoch(epoch) }
                if (!connectivity.recordIfCurrent(epoch) {
                    workerRetryAfter.remove(domain)
                    activeWorkers[dc] = domain
                    ProxyStats.connectionsCfProxy.incrementAndGet()
                    record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTED)
                    DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "media" to isMedia, "route" to "cf_worker")
                }) { ws.abort(); checkEpoch(epoch) }
                return UpstreamConnection(UpstreamType.CFPROXY, ws = ws, epoch = epoch, worker = true)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                UpstreamSetupGuard.checkActive(e)
                connectivity.recordIfCurrent(epoch, requireInternet = true) {
                    workerRetryAfter[domain] = System.currentTimeMillis() + 30_000
                }
                record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.FAILED)
                DiagnosticLogger.failure("upstream_attempt_failed", e, "dc" to dc, "route" to "cf_worker")
            } finally {
                sockets.forEach {
                    pendingSockets.remove(it)
                    connectivity.releaseSocket(it)
                }
            }
        }
        return null
    }

    /**
     * CF proxy fallback with multi-domain rotation (matches Python _cfproxy_fallback).
     * Tries active domain first, then others. Updates active domain on success.
     */
    private suspend fun tryCfProxyFallback(
        dc: Int,
        isMedia: Boolean,
        config: ProxyConfig,
        epoch: Long,
    ): UpstreamConnection? {
        checkEpoch(epoch)
        if (config.forceTestDc || !config.cfProxyEnabled) return null
        val mediaTag = if (isMedia) " media" else ""
        val allDomains = config.customCfDomains().sortedBy { it != activeCustomCf[dc] }
            .ifEmpty { CfProxyDomains.getDomainsForDc(dc) }
        if (allDomains.isEmpty()) return null
        // HTTP/2 media uses the system TLS stack: only while plain TLS works on this network.
        val transport = cfTransports.current(connectivity.state.value.transportKey, config.cfTlsMode)
        if (isMedia && config.cfH2Media && transport == CfTransport.TLS) {
            h2Pool.open(dc, allDomains, epoch)?.let { channel ->
                if (!connectivity.recordIfCurrent(epoch) {
                    ProxyStats.connectionsCfProxy.incrementAndGet()
                    record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTED)
                }) { channel.close(); checkEpoch(epoch) }
                return UpstreamConnection(UpstreamType.CFPROXY, epoch = epoch, h2 = channel)
            }
        }

        val availableDomains = allDomains.filterNot { cfCooldowns.isCoolingDown(it) }
        if (availableDomains.isEmpty()) {
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.COOLDOWN)
            Log.i(TAG, "DC$dc$mediaTag -> all CF domains are cooling down")
            return null
        }

        Log.i(TAG, "DC$dc$mediaTag -> trying CF proxy (${availableDomains.size} available)")

        // Keep the sticky domain fast, then cap the remaining connection pressure at two.
        // All domains go to the same Cloudflare addresses: when the phone had no route at all,
        // the others fail the same way. Seen 2026-11-24 on a fading Wi-Fi: 20 attempts per
        // Telegram connection, ~1000 a minute, until the phone switched networks.
        val noRoute = AtomicBoolean(false)
        val first = tryCfDomain(dc, isMedia, config.bufferSize, availableDomains.first(), epoch, config.cfTlsMode, noRoute)
        if (first != null) return cfConnectionReady(dc, isMedia, first.first, first.second, epoch)

        for (batch in availableDomains.drop(1).chunked(CF_FALLBACK_PARALLELISM)) {
            if (noRoute.get()) {
                DiagnosticLogger.event("cf_domains_skipped", "dc" to dc, "media" to isMedia, "reason" to "no_route")
                return null
            }
            val candidates = ConcurrentHashMap.newKeySet<RawWebSocket>()
            try {
                val results = coroutineScope {
                    batch.map { baseDomain ->
                        async(Dispatchers.IO) {
                            tryCfDomain(dc, isMedia, config.bufferSize, baseDomain, epoch, config.cfTlsMode, noRoute)?.also {
                                candidates.add(it.second)
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
                val winner = results.firstOrNull() ?: continue
                val connection = cfConnectionReady(dc, isMedia, winner.first, winner.second, epoch)
                candidates.remove(winner.second)
                return connection
            } finally {
                candidates.forEach { it.abort() }
            }
        }
        return null
    }

    private fun tryCfDomain(
        dc: Int,
        isMedia: Boolean,
        bufferSize: Int,
        baseDomain: String,
        epoch: Long,
        tlsMode: CfTlsMode,
        noRoute: AtomicBoolean? = null,
    ): Pair<String, RawWebSocket>? {
        checkEpoch(epoch)
        var skip = false
        connectivity.recordIfCurrent(epoch, requireInternet = true) { skip = cfDnsResolver.shouldSkipConnectionAttempt() }
        if (skip) {
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.COOLDOWN)
            return null
        }
        val mediaTag = if (isMedia) " media" else ""
        return try {
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTING)
            val ws = dial(epoch, dc, isMedia, baseDomain, bufferSize, CF_DNS_TIMEOUT_MS, baseDomain, tlsMode)
            if (!connectivity.recordIfCurrent(epoch) {
                cfDnsResolver.markAvailable()
                cfCooldowns.clear(baseDomain)
            }) { ws.abort(); checkEpoch(epoch) }
            baseDomain to ws
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (CfEdgeRouter.isLocalNetworkFailure(e)) noRoute?.set(true)
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.FAILED)
            connectivity.recordIfCurrent(epoch, requireInternet = true) {
                if (e is WsHandshakeError && e.statusCode == 429) {
                    val delay = cfCooldowns.markRateLimited(baseDomain)
                    Log.w(TAG, "CF proxy ${DiagnosticRedactor.field("domain", baseDomain)} cooling down for ${delay / 1000}s after HTTP 429")
                }
            }
            Log.w(TAG, "DC$dc$mediaTag CF proxy via ${DiagnosticRedactor.field("domain", baseDomain)} failed: ${cfTransportErrorForLog(e)}")
            DiagnosticLogger.failure(
                "upstream_attempt_failed",
                e,
                "dc" to dc,
                "media" to isMedia,
                "route" to "cloudflare",
            )
            null
        }
    }

    private fun cfConnectionReady(
        dc: Int,
        isMedia: Boolean,
        baseDomain: String,
        ws: RawWebSocket,
        epoch: Long,
    ): UpstreamConnection {
        if (!connectivity.recordIfCurrent(epoch) {
            CfProxyDomains.setActiveDomain(dc, baseDomain)
            activeCustomCf[dc] = baseDomain
            ProxyStats.connectionsCfProxy.incrementAndGet()
            DiagnosticLogger.event(
                "upstream_route_ready", "dc" to dc, "media" to isMedia, "route" to "cloudflare",
                "transport" to ws.cfTransport?.name,
            )
            record(epoch, dc, isMedia, TransportRoute.CLOUDFLARE, RoutePhase.CONNECTED)
        }) { ws.abort(); checkEpoch(epoch) }
        return UpstreamConnection(UpstreamType.CFPROXY, ws = ws, epoch = epoch)
    }

    private suspend fun tryTcpDirect(
        dc: Int,
        isMedia: Boolean,
        ip: String,
        bufferSize: Int,
        relayInit: ByteArray,
        tcpFallback: TcpFallbackConnector,
        epoch: Long,
    ): UpstreamConnection? {
        val context = currentCoroutineContext()
        return try {
            checkEpoch(epoch)
            record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.CONNECTING)
            val guard = UpstreamSetupGuard.current()
            val socket = tcpFallback.connect(ip, 443, bufferSize, relayInit,
                canRecordFailure = { connectivity.isCurrent(epoch) && connectivity.state.value.internetConfirmed },
                isActive = { context.isActive && connectivity.isCurrent(epoch) && guard?.cancelled != true },
                onSocketCreated = { guard?.track(it) })
                ?: run {
                    record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.COOLDOWN)
                    return null
                }
            if (!connectivity.recordIfCurrent(epoch) {
                ProxyStats.connectionsTcpFallback.incrementAndGet()
                DiagnosticLogger.event("upstream_route_ready", "dc" to dc, "route" to "tcp")
                record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.CONNECTED)
            }) { socket.close(); checkEpoch(epoch) }
            UpstreamConnection(UpstreamType.TCP, tcpSocket = socket, epoch = epoch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            UpstreamSetupGuard.checkActive(e)
            record(epoch, dc, isMedia, TransportRoute.TCP, RoutePhase.FAILED)
            Log.w(TAG, "TCP direct to DC $dc failed: ${cfTransportErrorForLog(e)}")
            DiagnosticLogger.failure("upstream_attempt_failed", e, "dc" to dc, "route" to "tcp")
            null
        }
    }

    /**
     * Run bidirectional bridge with re-encryption.
     * Client <-> Proxy <-> Telegram
     *
     * For WS/CF upstream: uses MsgSplitter to split TCP data into individual
     * MTProto transport packets, each sent as a separate WS frame (matching Python).
     *
     * Tracks per-session traffic and duration and logs a summary on close.
     * on close (matching Python bridge.py:298-303).
     */
    suspend fun bridgeReencrypt(
        clientInput: InputStream,
        clientOutput: OutputStream,
        upstream: UpstreamConnection,
        cryptoCtx: CryptoCtx,
        relayInit: ByteArray,
        bufferSize: Int,
        protoTag: Int,
        dc: Int,
        isMedia: Boolean,
        adaptiveRoutingEnabled: Boolean,
        webSocketPingIntervalSeconds: Int,
        cfTlsMode: CfTlsMode = CfTlsMode.AUTO,
        closeClient: () -> Unit,
    ) = coroutineScope {
        val epoch = upstream.epoch
        checkEpoch(epoch)
        if (upstream.h2 != null) {
            upstream.h2.bridge(clientInput, clientOutput, cryptoCtx, protoTag, closeClient)
            return@coroutineScope
        }
        val route = when (upstream.type) {
            UpstreamType.WEBSOCKET -> TransportRoute.WEBSOCKET
            UpstreamType.CFPROXY -> TransportRoute.CLOUDFLARE
            UpstreamType.TCP -> TransportRoute.TCP
        }
        var responded = false
        var lastResponseAt = 0L
        fun markResponse(bytes: Int) {
            val now = System.nanoTime() / 1_000_000
            // Refresh live traffic evidence without publishing a UI update for every packet.
            if (bytes > 0 && (!responded || now - lastResponseAt >= 30_000L)) {
                responded = true
                lastResponseAt = now
                record(epoch, dc, isMedia, route, RoutePhase.RESPONDING)
            }
        }
        // Per-session traffic statistics.
        val startTime = System.currentTimeMillis()
        val mediaTag = if (isMedia) " media" else ""
        var upBytes = 0L
        var downBytes = 0L
        var upPackets = 0L
        var downPackets = 0L
        val lastActivityAt = AtomicLong(System.currentTimeMillis())
        val pendingLock = Any()
        var pendingSince = 0L
        var pendingBytes = 0L
        var pendingDownBytes = 0L
        var pendingLastDownAt = 0L
        val stalled = AtomicBoolean(false)
        // Which side ended the session first: "client" (Telegram closed or gave up), "upstream"
        // (the server or network closed it) or "watchdog" (closed here as frozen).
        val endedBy = java.util.concurrent.atomic.AtomicReference<String?>(null)
        // Unanswered tail: bytes sent since the last byte came back, and when the first of them
        // was sent. Catches flows that DPI cuts off mid-stream after 10-30 KB (seen 2026-11-21).
        val tailLock = Any()
        var tailUpBytes = 0L
        var tailSince = 0L
        fun markTailUp(bytes: Int) = synchronized(tailLock) {
            if (tailUpBytes == 0L) tailSince = System.currentTimeMillis()
            tailUpBytes += bytes
        }
        fun markTailDown() = synchronized(tailLock) { tailUpBytes = 0L }
        // The live tail check already struck for this session (counted once per session).
        val tailStruck = AtomicBoolean(false)
        // Upstream writes in progress: a blocked write means data is still being pushed out.
        val writesInFlight = java.util.concurrent.atomic.AtomicInteger(0)
        // CF over TLS (plain or split) can be frozen by DPI after the handshake: watch media
        // streams like direct WS, and step the network's CF transport down when they freeze.
        val cfStreamTransport = upstream.ws?.cfTransport
            ?.takeIf { upstream.type == UpstreamType.CFPROXY && !upstream.worker && it.secure && cfTlsMode != CfTlsMode.OFF }
        val trackPending = upstream.type == UpstreamType.WEBSOCKET || cfStreamTransport != null

        fun markDirectRequest(bytes: Int) {
            synchronized(pendingLock) {
                if (pendingSince == 0L) pendingSince = System.currentTimeMillis()
                pendingBytes += bytes
            }
        }

        fun markDirectResponse(bytes: Int) {
            val meaningful = synchronized(pendingLock) {
                if (pendingSince > 0L) {
                    pendingDownBytes += bytes
                    pendingLastDownAt = System.currentTimeMillis()
                }
                if (pendingDownBytes >= DirectWsCircuitBreaker.MIN_MEANINGFUL_DOWN_BYTES) {
                    pendingSince = 0L
                    pendingBytes = 0L
                    pendingDownBytes = 0L
                    pendingLastDownAt = 0L
                    true
                } else {
                    false
                }
            }
            if (meaningful && upstream.type == UpstreamType.WEBSOCKET) connectivity.recordIfCurrent(epoch) {
                directWsCircuitBreaker.recordResponsive(connectivity.state.value.networkId, isMedia)
            }
        }

        // Send relay init to upstream
        when (upstream.type) {
            UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                val ws = upstream.ws!!
                // A send that never drains must not hold the connection: bounded, then aborted.
                SetupDeadline.bounded(RELAY_INIT_TIMEOUT_MS, close = ws::abort) { ws.sendBinary(relayInit) }
            }
            UpstreamType.TCP -> Unit // TCP setup has already sent relayInit.
        }

        // Reuse decrypted client bytes for WS packet boundaries.
        val splitter: MsgSplitter? = if (upstream.type != UpstreamType.TCP && !upstream.worker) {
            MsgSplitter(protoTag)
        } else null

        // Cache the TCP output stream for the session.
        val tcpOutput = if (upstream.type == UpstreamType.TCP) {
            upstream.tcpSocket!!.getOutputStream()
        } else null

        val pingJob = upstream.ws?.let { ws ->
            val pingIntervalMs = webSocketPingIntervalSeconds.coerceIn(30, 300) * 1_000L
            launch(Dispatchers.IO) {
                while (isActive) {
                    delay(pingIntervalMs)
                    if (System.currentTimeMillis() - lastActivityAt.get() < pingIntervalMs) continue
                    try {
                        ws.sendPing()
                    } catch (_: Exception) {
                        ws.abort()
                        break
                    }
                }
            }
        }

        val stallWatchdogJob = if (
            cfStreamTransport != null || (isMedia && adaptiveRoutingEnabled && upstream.type == UpstreamType.WEBSOCKET)
        ) {
            launch(Dispatchers.IO) {
                while (isActive) {
                    delay(1_000)
                    if (!connectivity.isCurrent(epoch) || !connectivity.state.value.internetConfirmed) continue
                    val now = System.currentTimeMillis()
                    // Any CF session: requests unanswered for a while count as a strike right away
                    // instead of when Telegram gives up (~16 s). The session is not closed: a slow
                    // upload looks similar, so a write still pushing data out is not counted.
                    if (cfStreamTransport != null && !tailStruck.get() && writesInFlight.get() == 0) {
                        val silentTail = synchronized(tailLock) {
                            tailUpBytes.takeIf {
                                it >= CfTransportSelector.LIVE_TAIL_MIN_BYTES &&
                                    now - tailSince >= CfTransportSelector.LIVE_TAIL_SILENT_MS
                            }
                        }
                        if (silentTail != null && tailStruck.compareAndSet(false, true)) {
                            var changedTo: CfTransport? = null
                            connectivity.recordIfCurrent(epoch, requireInternet = true) {
                                changedTo = cfTransports.streamStalled(
                                    connectivity.state.value.transportKey, cfTlsMode, cfStreamTransport,
                                )
                            }
                            DiagnosticLogger.event(
                                "cf_stream_stalled", "dc" to dc, "media" to isMedia,
                                "transport" to cfStreamTransport.name, "reason" to "unanswered",
                                "tailUpBytes" to silentTail, "sessionBytesDown" to downBytes,
                                "switchedTo" to changedTo?.name,
                            )
                        }
                    }
                    if (!isMedia) continue
                    val stalledTraffic = synchronized(pendingLock) {
                        if (DirectWsCircuitBreaker.isStalled(
                                pendingSince = pendingSince,
                                lastDownAt = pendingLastDownAt,
                                pendingBytesUp = pendingBytes,
                                pendingBytesDown = pendingDownBytes,
                                now = now,
                            )
                        ) {
                            pendingBytes to pendingDownBytes
                        } else {
                            null
                        }
                    }
                    if (stalledTraffic == null) continue

                    if (cfStreamTransport != null) {
                        if (!stalled.compareAndSet(false, true)) break
                        var changedTo: CfTransport? = null
                        connectivity.recordIfCurrent(epoch, requireInternet = true) {
                            changedTo = cfTransports.streamStalled(
                                connectivity.state.value.transportKey, cfTlsMode, cfStreamTransport,
                            )
                        }
                        DiagnosticLogger.event(
                            "cf_stream_stalled",
                            "dc" to dc,
                            "transport" to cfStreamTransport.name,
                            "pendingBytesUp" to stalledTraffic.first,
                            "pendingBytesDown" to stalledTraffic.second,
                            "sessionBytesDown" to downBytes,
                            "switchedTo" to changedTo?.name,
                        )
                        Log.w(TAG, "DC$dc$mediaTag CF stream stalled via $cfStreamTransport; closing session")
                        endedBy.compareAndSet(null, "watchdog")
                        upstream.ws?.abort()
                        break
                    }

                    var result: DirectWsStallResult? = null
                    connectivity.recordIfCurrent(epoch, requireInternet = true) {
                        if (stalled.compareAndSet(false, true)) {
                            result = directWsCircuitBreaker.recordStall(connectivity.state.value.networkId, isMedia)
                        }
                    }
                    val recorded = result ?: continue
                    DiagnosticLogger.event(
                        "direct_ws_stalled",
                        "dc" to dc,
                        "media" to isMedia,
                        "pendingBytesUp" to stalledTraffic.first,
                        "pendingBytesDown" to stalledTraffic.second,
                        "strikes" to recorded.strikes,
                        "overrideActivated" to recorded.activated,
                        "blockedForMs" to recorded.blockedForMs,
                        "scope" to "network-media",
                    )
                    Log.w(TAG, "DC$dc$mediaTag direct WS stalled; closing session (strike ${recorded.strikes})")
                    endedBy.compareAndSet(null, "watchdog")
                    upstream.ws?.abort()
                    break
                }
            }
        } else null

        val clientToUpstream = launch(Dispatchers.IO) {
            try {
                val buf = ByteArray(65536) // Match Python's reader.read(65536)
                while (isActive) {
                    val read = clientInput.read(buf)
                    if (read <= 0) {
                        // Flush splitter on EOF (matching Python)
                        if (splitter != null && upstream.ws != null) {
                            val tail = splitter.flush()
                            if (tail.isNotEmpty()) {
                                upstream.ws.sendBinary(tail.first())
                            }
                        }
                        break
                    }

                    val data = buf.copyOf(read)
                    connectivity.recordIfCurrent(epoch) { ProxyStats.bytesUp.addAndGet(read.toLong()) }
                    lastActivityAt.set(System.currentTimeMillis())
                    upBytes += read
                    upPackets++
                    markTailUp(read)

                    // Decrypt from client, re-encrypt for telegram
                    val decrypted = cryptoCtx.clientDecryptor.update(data)
                    val reencrypted = cryptoCtx.telegramEncryptor.update(decrypted)

                    when (upstream.type) {
                        UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                            writesInFlight.incrementAndGet()
                            try {
                                if (splitter != null) {
                                    val parts = splitter.split(reencrypted, decrypted)
                                    if (parts.isEmpty()) continue
                                    if (trackPending) markDirectRequest(read)
                                    if (parts.size > 1) {
                                        upstream.ws!!.sendBatch(parts)
                                    } else {
                                        upstream.ws!!.sendBinary(parts[0])
                                    }
                                } else {
                                    if (trackPending) markDirectRequest(read)
                                    upstream.ws!!.sendBinary(reencrypted)
                                }
                            } finally {
                                writesInFlight.decrementAndGet()
                            }
                        }
                        UpstreamType.TCP -> {
                            tcpOutput!!.write(reencrypted)
                            tcpOutput.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Client->upstream ended: ${cfTransportErrorForLog(e)}")
            } finally {
                endedBy.compareAndSet(null, "client")
            }
        }

        val upstreamToClient = launch(Dispatchers.IO) {
            try {
                when (upstream.type) {
                    UpstreamType.WEBSOCKET, UpstreamType.CFPROXY -> {
                        while (isActive) {
                            val data = upstream.ws!!.readBinary() ?: break
                            markResponse(data.size)
                            connectivity.recordIfCurrent(epoch) { ProxyStats.bytesDown.addAndGet(data.size.toLong()) }
                            lastActivityAt.set(System.currentTimeMillis())
                            downBytes += data.size
                            downPackets++
                            markTailDown()
                            if (trackPending) markDirectResponse(data.size)

                            // Decrypt from telegram, re-encrypt for client
                            val decrypted = cryptoCtx.telegramDecryptor.update(data)
                            val reencrypted = cryptoCtx.clientEncryptor.update(decrypted)

                            clientOutput.write(reencrypted)
                            clientOutput.flush()
                        }
                    }
                    UpstreamType.TCP -> {
                        val buf = ByteArray(65536) // Match Python's reader.read(65536)
                        val tcpInput = upstream.tcpSocket!!.getInputStream()
                        while (isActive) {
                            val read = tcpInput.read(buf)
                            if (read <= 0) break
                            markResponse(read)
                            connectivity.recordIfCurrent(epoch) { ProxyStats.bytesDown.addAndGet(read.toLong()) }
                            lastActivityAt.set(System.currentTimeMillis())
                            downBytes += read
                            downPackets++
                            markTailDown()

                            val data = buf.copyOf(read)
                            val decrypted = cryptoCtx.telegramDecryptor.update(data)
                            val reencrypted = cryptoCtx.clientEncryptor.update(decrypted)

                            clientOutput.write(reencrypted)
                            clientOutput.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                if (TgWsProxyServer.globalVerbose) Log.d(TAG, "Upstream->client ended: ${cfTransportErrorForLog(e)}")
            } finally {
                endedBy.compareAndSet(null, "upstream")
            }
        }

        // Wait for either direction to complete, then cancel the other
        try {
            awaitFirstCompletion(clientToUpstream, upstreamToClient)
        } finally {
            // Coroutine cancellation does not interrupt blocking socket reads. Close the
            // transport first so both bridge directions can actually finish.
            closeBridgeEndpoints(closeClient, upstream::abort)
            clientToUpstream.cancel()
            upstreamToClient.cancel()
            pingJob?.cancel()
            stallWatchdogJob?.cancel()

            val endedAt = System.currentTimeMillis()
            val durationMs = endedAt - startTime
            val (tailUp, tailSilentMs) = synchronized(tailLock) {
                tailUpBytes to (if (tailUpBytes > 0L) endedAt - tailSince else 0L)
            }
            // A CF session that got (almost) nothing back, or whose requests stopped being
            // answered mid-stream, counts like a frozen stream: Telegram closes such connections
            // within seconds, before the media watchdog would fire.
            var deadSession = false
            var switchedTo: CfTransport? = null
            if (cfStreamTransport != null && !stalled.get() && !tailStruck.get() &&
                CfTransportSelector.isDeadSession(durationMs, upBytes, downBytes, tailUp, tailSilentMs)
            ) {
                connectivity.recordIfCurrent(epoch, requireInternet = true) {
                    deadSession = true
                    switchedTo = cfTransports.streamStalled(
                        connectivity.state.value.transportKey, cfTlsMode, cfStreamTransport,
                    )
                }
            }
            // Log a compact per-session summary.
            val elapsed = durationMs / 1000.0
            Log.i(TAG, "Session: ${upstream.type} %.1fs up=%s(%d) down=%s(%d)".format(
                elapsed,
                MtProtoConstants.humanBytes(upBytes), upPackets,
                MtProtoConstants.humanBytes(downBytes), downPackets,
            ))
            DiagnosticLogger.event(
                "session_completed",
                "dc" to dc,
                "media" to isMedia,
                "route" to upstream.type,
                "durationMs" to durationMs,
                "bytesUp" to upBytes,
                "bytesDown" to downBytes,
                "packetsUp" to upPackets,
                "packetsDown" to downPackets,
                "stalled" to stalled.get(),
                "endedBy" to endedBy.get(),
                "tailUpBytes" to tailUp,
                "tailSilentMs" to tailSilentMs,
                "transport" to upstream.ws?.cfTransport?.name,
                "dead" to (deadSession || tailStruck.get()),
                "switchedTo" to switchedTo?.name,
            )
        }
    }

    private suspend fun awaitFirstCompletion(vararg jobs: Job) {
        try {
            // Wait for first job to complete using select
            val deferreds = jobs.map { job ->
                CompletableDeferred<Unit>().also { d ->
                    job.invokeOnCompletion { d.complete(Unit) }
                }
            }
            // Use kotlinx select to await whichever completes first
            select<Unit> {
                for (d in deferreds) {
                    d.onAwait {}
                }
            }
        } catch (_: Exception) {
        }
    }
}

// Exception messages can contain private endpoint paths that hostname redaction cannot hide.
internal fun cfTransportErrorForLog(error: Exception): String {
    val type = DiagnosticRedactor.field("message", error.javaClass.simpleName)
    return if (error is WsHandshakeError) "$type (HTTP ${error.statusCode})" else type
}

internal fun closeBridgeEndpoints(closeClient: () -> Unit, closeUpstream: () -> Unit) {
    try { closeClient() } catch (_: Exception) {}
    try { closeUpstream() } catch (_: Exception) {}
}

class ProxyException(message: String, cause: Throwable? = null) : Exception(message, cause)
