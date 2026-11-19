package dev.minios.tgwsproxy.proxy

import java.net.Socket
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkStatus { UNKNOWN, OFFLINE, CHECKING, CAPTIVE_PORTAL, BLOCKED, UNVERIFIED, ONLINE }
enum class TransportRoute { WEBSOCKET, CLOUDFLARE, TCP }
enum class RoutePhase { CONNECTING, CONNECTED, RESPONDING, FAILED, COOLDOWN }

data class RouteKey(val dc: Int, val media: Boolean, val route: TransportRoute)
data class RouteTarget(val dc: Int, val media: Boolean)
data class RouteObservation(
    val phase: RoutePhase,
    val failures: Int = 0,
    val connections: Int = 0,
    val responses: Int = 0,
    val updatedAt: Long = 0,
)

data class ConnectivitySnapshot(
    val epoch: Long = 0,
    val networkId: Long? = null,
    val network: NetworkStatus = NetworkStatus.UNKNOWN,
    val routes: Map<RouteKey, RouteObservation> = emptyMap(),
    val exhausted: Set<RouteTarget> = emptySet(),
    val stopped: Boolean = false,
    val evidenceStartedAt: Long = 0,
    /** A CF proxy connection is trying alternate Cloudflare edges (see CfEdgeRouter). */
    val searchingAlternateRoute: Boolean = false,
) {
    val allowsTraffic: Boolean get() = !stopped && network in setOf(
        NetworkStatus.UNKNOWN, NetworkStatus.UNVERIFIED, NetworkStatus.ONLINE,
    )
    val internetConfirmed: Boolean get() = allowsTraffic &&
        (network == NetworkStatus.ONLINE || routes.values.any { it.responses > 0 })
    val allowsBackgroundWork: Boolean get() = allowsTraffic && internetConfirmed
}

/** Runtime-owned evidence. Android network changes invalidate pending dials and old results. */
class ConnectivityTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private val lock = Any()
    private val _state = MutableStateFlow(ConnectivitySnapshot())
    val state: StateFlow<ConnectivitySnapshot> = _state.asStateFlow()
    private val pending = mutableSetOf<AutoCloseable>()
    /** In-flight alternate-edge searches. Survives network changes: old dials end themselves. */
    private var alternateSearches = 0

    fun updateNetwork(networkId: Long?, status: NetworkStatus): Boolean {
        val stale: List<AutoCloseable>
        synchronized(lock) {
            val previous = _state.value
            if (previous.stopped || (previous.networkId == networkId && previous.network == status)) return false
            stale = drainPending()
            _state.value = ConnectivitySnapshot(
                previous.epoch + 1, networkId, status,
                searchingAlternateRoute = alternateSearches > 0,
            )
        }
        closeAll(stale)
        return true
    }

    fun stop() {
        val stale: List<AutoCloseable>
        synchronized(lock) {
            stale = drainPending()
            _state.value = ConnectivitySnapshot(epoch = _state.value.epoch + 1, stopped = true)
        }
        closeAll(stale)
    }

    /** Shown as "searching for a workaround" while alternate Cloudflare edges are tried. */
    fun beginAlternateRouteSearch() = synchronized(lock) {
        alternateSearches++
        publishSearching()
    }

    fun endAlternateRouteSearch() = synchronized(lock) {
        alternateSearches = (alternateSearches - 1).coerceAtLeast(0)
        publishSearching()
    }

    private fun publishSearching() {
        val searching = alternateSearches > 0 && !_state.value.stopped
        if (_state.value.searchingAlternateRoute != searching) {
            _state.value = _state.value.copy(searchingAlternateRoute = searching)
        }
    }

    fun isCurrent(epoch: Long): Boolean = synchronized(lock) {
        pruneEvidence()
        _state.value.epoch == epoch && _state.value.allowsTraffic
    }

    fun recordIfCurrent(epoch: Long, requireInternet: Boolean = false, action: () -> Unit): Boolean = synchronized(lock) {
        if (!isCurrent(epoch) || (requireInternet && !_state.value.internetConfirmed)) return@synchronized false
        action()
        true
    }

    fun trackSocket(epoch: Long, socket: Socket): Boolean = trackResource(epoch, socket)

    fun trackResource(epoch: Long, resource: AutoCloseable): Boolean = synchronized(lock) {
        if (!isCurrent(epoch)) {
            try { resource.close() } catch (_: Exception) {}
            return@synchronized false
        }
        pending.add(resource)
        true
    }

    fun releaseSocket(socket: Socket) = synchronized(lock) { pending.remove(socket) }
    fun releaseResource(resource: AutoCloseable) = synchronized(lock) { pending.remove(resource) }

    fun record(epoch: Long, key: RouteKey, phase: RoutePhase) = synchronized(lock) {
        if (!isCurrent(epoch) || key.dc !in MtProtoConstants.ALL_DCS) return@synchronized
        val snapshot = _state.value.let {
            if (it.routes.isEmpty() && it.exhausted.isEmpty()) it.copy(evidenceStartedAt = clock()) else it
        }
        val old = snapshot.routes[key]
        val eligibleFailure = phase == RoutePhase.FAILED && snapshot.internetConfirmed
        val observation = RouteObservation(
            phase = phase,
            failures = ((old?.failures ?: 0) + if (eligibleFailure) 1 else 0).coerceAtMost(1_000),
            connections = ((old?.connections ?: 0) + if (phase == RoutePhase.CONNECTED) 1 else 0).coerceAtMost(1_000),
            responses = ((old?.responses ?: 0) + if (phase == RoutePhase.RESPONDING) 1 else 0).coerceAtMost(1_000),
            updatedAt = clock(),
        )
        _state.value = snapshot.copy(
            routes = snapshot.routes + (key to observation),
            exhausted = if (phase == RoutePhase.CONNECTED || phase == RoutePhase.RESPONDING) {
                snapshot.exhausted - RouteTarget(key.dc, key.media)
            } else snapshot.exhausted,
        )
    }

    fun recordExhausted(epoch: Long, target: RouteTarget) = synchronized(lock) {
        if (!isCurrent(epoch) || !_state.value.internetConfirmed || target.dc !in MtProtoConstants.ALL_DCS) return@synchronized
        _state.value = _state.value.copy(exhausted = _state.value.exhausted + target)
    }

    /**
     * Detaches pending resources under [lock]; the caller closes them after releasing it.
     * Closing inside the iteration crashed with ConcurrentModificationException: a tracked
     * socket's close() calls [releaseSocket], which re-enters the (reentrant) lock and
     * removes itself from [pending]. Closing outside the lock also keeps slow TLS closes
     * from blocking other connectivity callers.
     */
    private fun drainPending(): List<AutoCloseable> {
        val stale = pending.toList()
        pending.clear()
        return stale
    }

    private fun closeAll(resources: List<AutoCloseable>) {
        resources.forEach { try { it.close() } catch (_: Exception) {} }
    }

    private fun pruneEvidence() {
        val snapshot = _state.value
        if (clock() - snapshot.evidenceStartedAt >= 120_000L &&
            (snapshot.routes.isNotEmpty() || snapshot.exhausted.isNotEmpty())
        ) {
            _state.value = snapshot.copy(routes = emptyMap(), exhausted = emptySet(), evidenceStartedAt = clock())
        }
    }
}
