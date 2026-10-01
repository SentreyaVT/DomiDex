package com.dominik.cardvault.security

import com.google.firebase.appcheck.FirebaseAppCheck
import okhttp3.Request

internal object DomiDexAppCheck {
    private val cache = CachedAppCheckHeader()
    private val listener = FirebaseAppCheck.AppCheckListener { token ->
        cache.update(token.token, token.expireTimeMillis)
    }

    fun initialize(enabled: Boolean) {
        startAppCheckIfEnabled(enabled) {
            try {
                val appCheck = FirebaseAppCheck.getInstance()
                // The SDK can still fetch on a Firebase request cache miss. This flag only
                // disables its refresh timer; enabled-build cold-start costs require measurement.
                appCheck.installAppCheckProviderFactory(appCheckProviderFactory(), false)
                appCheck.addAppCheckListener(listener)
            } catch (_: Exception) {
                // Monitor-first rollout: do not turn temporary attestation failure into an
                // app-start crash or log credentials/provider exception details.
                cache.clear()
            }
        }
    }

    fun attachCachedHeader(request: Request): Request {
        val token = cache.forRequest(request.method, request.url.toString()) ?: return request
        return request.newBuilder().header(CachedAppCheckHeader.HEADER, token).build()
    }
}
