package dev.minios.tgwsproxy.proxy

import dev.minios.tgwsproxy.diagnostics.DiagnosticLogger
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.*

object RouteHealthProbe {
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val ROUNDS = 2

    suspend fun run(
        config: ProxyConfig,
        runtimeId: Long,
        connectivity: ConnectivityTracker,
        epoch: Long,
    ) {
        fun allowed(): Boolean = connectivity.isCurrent(epoch) && connectivity.state.value.allowsBackgroundWork
        if (!allowed()) return
        val targets = config.dcRedirects.filterKeys { it == 2 || it == 4 }.toList()
        if (targets.isEmpty()) return
        val dnsResolver = CfDnsResolver()
        DiagnosticLogger.event("route_probe_started", "runtimeId" to runtimeId, "epoch" to epoch)
        repeat(ROUNDS) { round ->
            currentCoroutineContext().ensureActive()
            if (!allowed()) return
            coroutineScope {
                targets.flatMap { (dc, targetIp) ->
                    TransportRoute.entries.map { route ->
                        async(Dispatchers.IO) { probe(dc, targetIp, route, config, runtimeId, connectivity, epoch, dnsResolver) }
                    }
                }.awaitAll()
            }
            if (round + 1 < ROUNDS) delay(1_000)
        }
        if (allowed()) DiagnosticLogger.event("route_probe_completed", "runtimeId" to runtimeId, "epoch" to epoch)
    }

    private suspend fun probe(
        dc: Int,
        targetIp: String,
        route: TransportRoute,
        config: ProxyConfig,
        runtimeId: Long,
        connectivity: ConnectivityTracker,
        epoch: Long,
        dnsResolver: CfDnsResolver,
    ) {
        val key = RouteKey(dc, false, route)
        val context = currentCoroutineContext()
        fun checkCurrent() {
            context.ensureActive()
            if (!connectivity.isCurrent(epoch)) throw CancellationException("Probe connectivity changed")
        }
        checkCurrent()
        // Probe CF the way connections are made now on this network (TLS, split or port 80).
        val cfTransport = CfTransportSelector.shared.current(connectivity.state.value.transportKey, config.cfTlsMode)
        val sockets = mutableListOf<Socket>()
        val owner = Job(context[Job])
        val cancelMonitor = CoroutineScope(Dispatchers.Default + owner).launch(start = CoroutineStart.UNDISPATCHED) {
            try { awaitCancellation() } finally {
                synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
            }
        }
        fun track(socket: Socket) {
            synchronized(sockets) {
                sockets.add(socket)
                if (!owner.isActive || !connectivity.trackSocket(epoch, socket)) {
                    socket.close()
                    throw CancellationException("Probe cancelled")
                }
            }
        }
        fun connectWs(cfDomain: String? = null, edgeIp: String? = null) {
            var ws: RawWebSocket? = null
            try {
                ws = RawWebSocket.connectToDc(
                    dc = dc, isMedia = false, targetIp = cfDomain ?: targetIp,
                    bufferSize = config.bufferSize, cfProxyDomain = cfDomain,
                    connectTimeoutMs = CONNECT_TIMEOUT_MS, onSocketCreated = ::track,
                    resolveCfAddress = { domain ->
                        checkCurrent()
                        if (edgeIp != null) null else dnsResolver.resolve(domain, connectivity, epoch)
                    },
                    cachedCfAddress = { domain -> edgeIp ?: dnsResolver.cached(domain) },
                    secure = cfTransport.secure,
                    splitHello = cfTransport.splitHello,
                    isTestDc = config.forceTestDc,
                )
                checkCurrent()
            } finally { ws?.abort() }
        }
        try {
            connectivity.record(epoch, key, RoutePhase.CONNECTING)
            when (route) {
                TransportRoute.WEBSOCKET -> connectWs()
                TransportRoute.CLOUDFLARE -> {
                    if (config.forceTestDc) return
                    val domains = config.customCfDomains().ifEmpty { CfProxyDomains.getDomainsForDc(dc).take(2) }
                    // Probe the address real connections use on this network: the Cloudflare edge
                    // they remembered first, then the DNS address. Seen 2026-11-22: the DNS address
                    // was blocked outright, so a DNS-only probe kept failing while traffic flowed.
                    val edgeIp = (CfEdgeRouter.shared.plan(connectivity.state.value.transportKey)
                        .firstOrNull() as? CfEdgeRouter.Hop.Edge)?.ip
                    val addresses = listOfNotNull(edgeIp) + listOf<String?>(null)
                    var error: Exception? = null
                    var connected = false
                    attempts@ for (domain in domains) {
                        for (address in addresses) {
                            checkCurrent()
                            try {
                                connectWs(domain, address)
                                connected = true
                                break@attempts
                            } catch (e: CancellationException) { throw e }
                            catch (e: Exception) { error = e }
                        }
                    }
                    if (!connected) throw error ?: ProxyException("No CF domains available")
                }
                TransportRoute.TCP -> {
                    val ip = (if (config.forceTestDc) MtProtoConstants.DC_TEST_IPS else MtProtoConstants.DC_IPS)[dc] ?: return
                    Socket().use { socket ->
                        track(socket)
                        socket.connect(InetSocketAddress(ip, 443), CONNECT_TIMEOUT_MS)
                        checkCurrent()
                    }
                }
            }
            connectivity.record(epoch, key, RoutePhase.CONNECTED)
            DiagnosticLogger.event("route_probe_ready", "dc" to dc, "route" to route.name, "runtimeId" to runtimeId, "epoch" to epoch)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            checkCurrent()
            connectivity.record(epoch, key, RoutePhase.FAILED)
            DiagnosticLogger.failure("route_probe_failed", e, "dc" to dc, "route" to route.name, "runtimeId" to runtimeId, "epoch" to epoch)
        } finally {
            synchronized(sockets) {
                sockets.forEach {
                    runCatching { it.close() }
                    connectivity.releaseSocket(it)
                }
            }
            cancelMonitor.cancel()
            owner.cancel()
        }
    }
}
