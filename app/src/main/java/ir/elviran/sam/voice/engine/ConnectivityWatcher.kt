package ir.elviran.sam.voice.engine

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Reports whether the device currently has a validated internet connection, and notifies on change.
 * Used by [SpeechRecognitionManager] to pick the online or offline recogniser automatically.
 */
class ConnectivityWatcher(context: Context) {
    private val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var callback: ConnectivityManager.NetworkCallback? = null

    fun isOnline(): Boolean {
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    fun register(onChange: (Boolean) -> Unit) {
        unregister()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(isOnline())
            override fun onLost(network: Network) = onChange(isOnline())
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = onChange(isOnline())
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    fun unregister() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }
}
