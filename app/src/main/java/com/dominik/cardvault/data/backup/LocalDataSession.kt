package com.dominik.cardvault.data.backup

import java.io.InputStream
import java.io.OutputStream

internal fun isLocalDataSessionCurrent(owner: String, currentOwner: String, generation: Long, currentGeneration: Long, closed: Boolean): Boolean =
    !closed && owner == currentOwner && generation == currentGeneration

/** Local owner lifecycle, separate from network access/token-verification generations. */
internal class LocalDataOwnerSession(initialOwner: String) {
    private var owner = initialOwner
    private var generation = 0L
    private var closed = false

    @Synchronized fun observeOwner(currentOwner: String) {
        // Same-UID token refresh / local onSyncNeeded is not a local account change.
        if (owner != currentOwner) { generation++; owner = currentOwner }
    }

    @Synchronized fun capture(expectedOwner: String): () -> Boolean {
        val captured = generation
        return { current(expectedOwner, captured) }
    }

    @Synchronized private fun current(expectedOwner: String, captured: Long): Boolean =
        isLocalDataSessionCurrent(expectedOwner, owner, captured, generation, closed)

    @Synchronized fun close() { closed = true; generation++ }
}

/** Captured before a picker/dispatch; a local-data boundary, never a cloud entitlement. */
class LocalDataSession internal constructor(val ownerUid: String, private val isCurrent: () -> Boolean) {
    init { require(ownerUid.isNotBlank() && ownerUid.length <= 128 && ownerUid.none(Char::isISOControl)) }

    fun requireCurrent() {
        check(isCurrent()) { "Konto während des Vorgangs geändert. Bitte mit dem gewünschten Konto neu starten." }
    }

    /** A revoked session never starts another chunk; a partially written external file may remain. */
    fun copyTo(source: InputStream, target: OutputStream) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            requireCurrent()
            val count = source.read(buffer)
            requireCurrent()
            if (count < 0) break
            target.write(buffer, 0, count)
        }
        requireCurrent()
    }
}
