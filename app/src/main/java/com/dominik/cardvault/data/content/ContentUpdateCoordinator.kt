package com.dominik.cardvault.data.content

import android.content.Context
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import com.google.firebase.remoteconfig.FirebaseRemoteConfigSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Small, deliberately data-only update contract. Remote Config contains no secrets and is
 * allowed to switch only predeclared content behaviour; new executable code still ships via
 * a signed store update.
 */
data class ContentUpdateState(
    val revision: String = "eingebettet",
    val source: String = "Lokale Basis",
    val lastCheckedAtEpochMillis: Long? = null,
    val configurationActive: Boolean = false,
)

class ContentUpdateCoordinator(context: Context) {
    private val remoteConfig = FirebaseRemoteConfig.getInstance().apply {
        setConfigSettingsAsync(FirebaseRemoteConfigSettings.Builder()
            .setMinimumFetchIntervalInSeconds(6 * 60 * 60)
            .build())
        setDefaultsAsync(mapOf(
            KEY_REVISION to "eingebettet",
            KEY_CATALOG_REFRESH_ENABLED to false,
            KEY_SOURCE_LABEL to "Lokale Basis",
        ))
    }
    private val mutableState = MutableStateFlow(ContentUpdateState())
    val state: StateFlow<ContentUpdateState> = mutableState

    suspend fun refreshIfConfigured(refreshCatalog: () -> Unit) {
        val checkedAt = System.currentTimeMillis()
        val activated = runCatching { remoteConfig.fetchAndActivateSafely() }.getOrDefault(false)
        val revision = remoteConfig.getString(KEY_REVISION).ifBlank { "eingebettet" }
        val source = remoteConfig.getString(KEY_SOURCE_LABEL).ifBlank { "Lokale Basis" }
        mutableState.value = ContentUpdateState(
            revision = revision,
            source = source,
            lastCheckedAtEpochMillis = checkedAt,
            configurationActive = activated || remoteConfig.info.lastFetchStatus == FirebaseRemoteConfig.LAST_FETCH_STATUS_SUCCESS,
        )
        // A published flag can request the already vetted Cardmarket importer. It cannot inject
        // URLs, code, credentials, or an unreviewed image provider into the app.
        if (remoteConfig.getBoolean(KEY_CATALOG_REFRESH_ENABLED)) refreshCatalog()
    }

    private companion object {
        const val KEY_REVISION = "catalog_content_revision"
        const val KEY_CATALOG_REFRESH_ENABLED = "catalog_refresh_enabled"
        const val KEY_SOURCE_LABEL = "catalog_content_source_label"
    }
}

private suspend fun FirebaseRemoteConfig.fetchAndActivateSafely(): Boolean = suspendCancellableCoroutine { continuation ->
    fetchAndActivate().addOnCompleteListener { task ->
        if (continuation.isActive) continuation.resume(task.isSuccessful && task.result == true)
    }
}
