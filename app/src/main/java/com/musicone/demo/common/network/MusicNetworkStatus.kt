package com.musicone.demo

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

internal fun NetworkCapabilities?.hasUsableInternet(): Boolean = this != null &&
    hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
    hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

internal fun Context.hasMusicNetwork(): Boolean {
    val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return manager.getNetworkCapabilities(manager.activeNetwork).hasUsableInternet()
}

/** 监听实际互联网能力；仅连上无外网的 Wi-Fi 也按离线展示。 */
internal fun musicNetworkChanges(context: Context) = callbackFlow {
    val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networks = mutableMapOf<Network, Boolean>()
    val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (Build.VERSION.SDK_INT >= 24) trySend(capabilities.hasUsableInternet())
            else { networks[network] = capabilities.hasUsableInternet(); trySend(networks.values.any { it }) }
        }
        override fun onLost(network: Network) {
            networks.remove(network)
            trySend(if (Build.VERSION.SDK_INT >= 24) false else networks.values.any { it })
        }
    }
    if (Build.VERSION.SDK_INT >= 24) manager.registerDefaultNetworkCallback(callback)
    else manager.registerNetworkCallback(NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
    trySend(context.hasMusicNetwork())
    awaitClose { manager.unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
