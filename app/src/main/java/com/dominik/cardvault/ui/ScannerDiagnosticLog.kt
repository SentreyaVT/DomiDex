package com.dominik.cardvault.ui

import android.util.Log
import com.dominik.cardvault.BuildConfig

/** Debug-build-only, local scanner telemetry. Values are bounded and never persisted/uploaded. */
internal object ScannerDiagnosticLog {
    const val TAG = "DomiDexScanner"

    fun event(event: String, scanId: String, vararg fields: Pair<String, Any?>) {
        if (!BuildConfig.DEBUG) return
        val payload = buildString {
            append("event=").append(clean(event))
            append(" scanId=").append(clean(scanId))
            fields.forEach { (key, value) ->
                append(' ').append(clean(key)).append('=').append(clean(value?.toString().orEmpty()))
            }
        }
        Log.i(TAG, payload)
    }

    internal fun clean(value: String): String = value
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(180)
}
