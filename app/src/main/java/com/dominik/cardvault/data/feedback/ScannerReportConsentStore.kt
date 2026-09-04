package com.dominik.cardvault.data.feedback

import android.content.Context
import android.content.SharedPreferences

/** The existing per-account consent, shared by scanner and settings on this installation. */
internal class ScannerReportConsentStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "scanner_report_consent", Context.MODE_PRIVATE,
    )

    fun isEnabled(uid: String): Boolean = preferences.getBoolean(key(uid), false)

    fun setEnabled(uid: String, enabled: Boolean) {
        preferences.edit().putBoolean(key(uid), enabled).apply()
    }

    fun clearForAccount(uid: String) {
        preferences.edit().remove(key(uid)).apply()
    }

    /** Keep the listener strongly referenced until its owning composition is disposed. */
    fun observe(onChanged: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == null || changedKey.startsWith("automatic_v1_")) onChanged()
        }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        return { preferences.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun key(uid: String): String {
        require(uid.isNotBlank()) { "Consent requires an account." }
        return "automatic_v1_$uid"
    }
}
