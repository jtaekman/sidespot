package com.sidespot.offline

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NetworkState(
    val isOnline: Boolean = false,
    /** Wi-Fi or another network the system doesn't consider metered. */
    val isUnmetered: Boolean = false,
)

/**
 * Tracks the device's default network.
 *
 * "Online" only means a network with internet access is up, not that the system's
 * connectivity check passed: degoogled devices often disable that check, which
 * would otherwise leave the app offline forever. Whether Spotify is actually
 * reachable is decided by the session connect attempt.
 */
class NetworkMonitor(context: Context) {

    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(
        connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.toState() ?: NetworkState()
    )
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _state.value = caps.toState()
            }

            override fun onLost(network: Network) {
                _state.value = NetworkState()
            }
        })
    }

    private fun NetworkCapabilities.toState() = NetworkState(
        isOnline = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        isUnmetered = hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
    )
}
