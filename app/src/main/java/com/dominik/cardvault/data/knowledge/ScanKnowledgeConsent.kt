package com.dominik.cardvault.data.knowledge

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest

internal const val SCAN_KNOWLEDGE_CONSENT_VERSION = 1

/** A filesystem-safe account namespace without exposing the UID in filenames. */
internal fun scanKnowledgeOwnerKey(uid: String): String {
    require(uid.isNotBlank() && uid.length <= 128) { "Ungültiges Scannerwissen-Konto." }
    return MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

internal fun scanKnowledgeConsentAllowsUpload(enabled: Boolean, version: Int, acceptedAt: Long): Boolean =
    enabled && version == SCAN_KNOWLEDGE_CONSENT_VERSION && acceptedAt > 0L

/** Device/account-local opt-in. Deliberately excluded from backup and photo/research consent. */
internal class ScanKnowledgeConsent private constructor(
    private val prefs: SharedPreferences,
    private val clearPending: (String) -> Unit,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE),
        { uid -> ScanKnowledgeStore(context.applicationContext).clearPending(uid) },
    )
    internal constructor(prefs: SharedPreferences, store: ScanKnowledgeStore) : this(prefs, store::clearPending)

    fun isEnabled(uid: String?): Boolean {
        if (uid.isNullOrBlank()) return false
        val key = scanKnowledgeOwnerKey(uid)
        return scanKnowledgeConsentAllowsUpload(
            prefs.getBoolean("$key.enabled", false), prefs.getInt("$key.version", 0), prefs.getLong("$key.acceptedAt", 0L),
        )
    }

    fun setEnabled(uid: String, enabled: Boolean) = synchronized(lock) {
        val key = scanKnowledgeOwnerKey(uid)
        check(prefs.edit().putBoolean("$key.enabled", enabled)
            .putInt("$key.version", SCAN_KNOWLEDGE_CONSENT_VERSION)
            .putLong("$key.acceptedAt", if (enabled) System.currentTimeMillis() else 0L).commit()) {
            "Die Auswahl konnte nicht gespeichert werden."
        }
        if (!enabled) clearPending(uid)
    }

    /** Shares the opt-out lock so a new outbox item cannot race pending-data removal. */
    fun <T> ifEnabled(uid: String, block: () -> T): T? = synchronized(lock) {
        if (isEnabled(uid)) block() else null
    }

    internal companion object {
        const val PREFERENCES = "domidex_scan_knowledge_consent"
        private val lock = Any()
    }
}
