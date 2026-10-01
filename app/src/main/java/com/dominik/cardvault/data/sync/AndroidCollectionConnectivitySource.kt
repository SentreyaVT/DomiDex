package com.dominik.cardvault.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/** A single observer registration owns its callback, network identity and close state. */
internal class AndroidCollectionConnectivitySource(context: Context) : CollectionConnectivitySource {
    private val applicationContext = context.applicationContext

    override fun observe(onUsableChanged: (Boolean) -> Unit): AutoCloseable {
        val manager = checkNotNull(applicationContext.getSystemService(ConnectivityManager::class.java)) {
            "Connectivity service unavailable"
        }
        val lock = Any()
        val policy = CollectionDefaultNetworkPolicy()
        var closed = false

        // Serialize policy updates and delivery with close. The session must close
        // this handle outside its own lock; its callback should only record/recheck.
        fun deliver(event: () -> Boolean?) {
            synchronized(lock) {
                if (!closed) event()?.let(onUsableChanged)
            }
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                deliver { policy.available(network.networkHandle) }
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                deliver {
                    policy.capabilities(
                        network.networkHandle,
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                    )
                }
            }

            override fun onLost(network: Network) {
                deliver { policy.lost(network.networkHandle) }
            }
        }

        try {
            // The initial available/capabilities callbacks also describe an already
            // connected default network. Do not race them with an activeNetwork read.
            manager.registerDefaultNetworkCallback(callback)
        } catch (error: RuntimeException) {
            synchronized(lock) { closed = true }
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // Registration did not complete; this exact callback is not registered.
            } catch (cleanup: RuntimeException) {
                error.addSuppressed(cleanup)
            }
            throw error
        }

        return AutoCloseable {
            val unregister = synchronized(lock) {
                if (closed) false else { closed = true; true }
            }
            if (unregister) manager.unregisterNetworkCallback(callback)
        }
    }
}
