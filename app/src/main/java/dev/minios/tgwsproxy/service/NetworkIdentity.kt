package dev.minios.tgwsproxy.service

import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.TelephonyNetworkSpecifier
import android.os.Build
import java.net.Inet4Address
import java.security.MessageDigest

/** Keeps CfTransportSelector's per-network steps across process restarts. */
internal class PrefsCfTransportStore(context: android.content.Context) :
    dev.minios.tgwsproxy.proxy.CfTransportSelector.Store {
    private val prefs = context.applicationContext.getSharedPreferences("cf_transport", android.content.Context.MODE_PRIVATE)
    override fun load(): String? = prefs.getString(KEY, null)
    override fun save(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    private companion object {
        const val KEY = "steps"
    }
}

/**
 * Stable identity of the default network for per-network policy that should survive reconnects
 * (see CfTransportSelector). Android gives each connection a new handle, so the same home Wi-Fi
 * looked like a new network after every reconnect and the app relearned its workaround.
 *
 * Uses only data available without location permission: the transport, the IPv4 default gateway
 * (Wi-Fi, Ethernet) or the SIM subscription (mobile). Two Wi-Fi networks with the same gateway
 * address share a key; per-network steps are rechecked hourly, so a wrong guess heals itself.
 * Only a short hash is kept, never the address itself.
 */
internal object NetworkIdentity {
    fun kind(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "unknown"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "other"
    }

    fun key(manager: ConnectivityManager, network: Network, capabilities: NetworkCapabilities?): String? = try {
        when (val kind = kind(capabilities)) {
            "wifi", "ethernet" -> gateway(manager.getLinkProperties(network))?.let { "$kind:${hash(it)}" }
            "cellular" -> "cellular:${subscription(capabilities) ?: "default"}"
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private fun gateway(properties: LinkProperties?): String? {
        val routes = properties?.routes ?: return null
        return routes.asSequence()
            .filter { it.isDefaultRoute }
            .mapNotNull { it.gateway }
            .firstOrNull { it is Inet4Address && !it.isAnyLocalAddress }
            ?.hostAddress
    }

    private fun subscription(capabilities: NetworkCapabilities?): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return (capabilities?.networkSpecifier as? TelephonyNetworkSpecifier)?.subscriptionId
    }

    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .take(8)
        .joinToString("") { "%02x".format(it) }
}
