package com.dominik.cardvault.data.sync

/** Connectivity is only a reason to recheck access, never evidence of authorization. */
internal fun interface CollectionConnectivitySource {
    fun observe(onUsableChanged: (Boolean) -> Unit): AutoCloseable
}

/** Named-client diagnostics opt out of Android connectivity without touching global state. */
internal object NoCollectionConnectivitySource : CollectionConnectivitySource {
    override fun observe(onUsableChanged: (Boolean) -> Unit): AutoCloseable = AutoCloseable { }
}

/** One default-network registration's identity and duplicate-event policy. */
internal class CollectionDefaultNetworkPolicy {
    private var currentNetwork: Long? = null
    private var usable = false

    fun available(network: Long): Boolean? {
        if (currentNetwork == network) return null
        currentNetwork = network
        // Do not carry the previous network's validated capabilities into a new one.
        return update(false)
    }

    fun capabilities(network: Long, hasInternet: Boolean, validated: Boolean): Boolean? {
        if (currentNetwork != network) return null
        return update(hasInternet && validated)
    }

    fun lost(network: Long): Boolean? {
        if (currentNetwork != network) return null
        currentNetwork = null
        return update(false)
    }

    private fun update(next: Boolean): Boolean? {
        if (usable == next) return null
        usable = next
        return next
    }
}
