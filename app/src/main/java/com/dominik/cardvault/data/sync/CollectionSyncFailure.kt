package com.dominik.cardvault.data.sync

import androidx.room.withTransaction
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_CONFLICT
import com.dominik.cardvault.data.local.SYNC_ERROR
import com.dominik.cardvault.data.local.SYNC_PENDING

internal const val COLLECTION_SYNC_STATE_MISMATCH = "local-sync-state-mismatch"

/** A failure for M1 must not mark M2's entry after the outbox's conditional update rejected M1. */
internal suspend fun commitCollectionFailure(
    database: CardVaultDatabase,
    mutation: CollectionSyncOutboxEntity,
    failureState: String,
    reason: String,
    isOwnerCurrent: (String) -> Boolean,
): Boolean = database.withTransaction {
    require(failureState == SYNC_CONFLICT || failureState == SYNC_ERROR)
    if (!isOwnerCurrent(mutation.ownerUid)) return@withTransaction false
    val dao = database.cardVaultDao()
    val current = dao.syncOutbox(mutation.entryKey) ?: return@withTransaction false
    if (!current.matchesPending(mutation)) return@withTransaction false
    val entry = dao.getCollectionEntryBySyncId(mutation.ownerUid, mutation.syncId) ?: return@withTransaction false
    // CLEAN + pending outbox already acknowledges this commit; a late failure cannot undo it.
    if (entry.syncState != SYNC_PENDING || !isOwnerCurrent(mutation.ownerUid)) return@withTransaction false
    if (failureState == SYNC_CONFLICT) {
        dao.markOutboxConflict(mutation.entryKey, mutation.mutationId, reason)
        dao.markEntryConflict(mutation.ownerUid, mutation.syncId)
    } else {
        dao.markOutboxError(mutation.entryKey, mutation.mutationId, reason)
        dao.markEntrySyncError(mutation.ownerUid, mutation.syncId)
    }
    true
}

/**
 * Exposes an existing terminal entry through the existing outbox-based Settings UI.
 * Only the matching pending outbox state/reason changes. No entry, payload, revision or mutation
 * identity is changed and nothing is automatically retried or released for sharing.
 * ERROR is picked up by explicit retrySyncErrors. CONFLICT has no invented remote revision:
 * local-wins retry deliberately cannot rebase it; explicit cloud-wins still requires server reads.
 */
internal suspend fun normalizePendingOutboxForTerminalEntry(
    database: CardVaultDatabase,
    mutation: CollectionSyncOutboxEntity,
    isOwnerCurrent: (String) -> Boolean,
): Boolean = database.withTransaction {
    if (!isOwnerCurrent(mutation.ownerUid)) return@withTransaction false
    val dao = database.cardVaultDao()
    val current = dao.syncOutbox(mutation.entryKey) ?: return@withTransaction false
    if (!current.matchesPending(mutation)) return@withTransaction false
    val entry = dao.getCollectionEntryBySyncId(mutation.ownerUid, mutation.syncId) ?: return@withTransaction false
    if (!isOwnerCurrent(mutation.ownerUid)) return@withTransaction false
    when (entry.syncState) {
        SYNC_ERROR -> dao.markOutboxError(mutation.entryKey, mutation.mutationId, COLLECTION_SYNC_STATE_MISMATCH)
        SYNC_CONFLICT -> dao.markOutboxConflict(mutation.entryKey, mutation.mutationId, COLLECTION_SYNC_STATE_MISMATCH)
        else -> return@withTransaction false
    }
    true
}

private fun CollectionSyncOutboxEntity.matchesPending(sent: CollectionSyncOutboxEntity): Boolean =
    ownerUid == sent.ownerUid && syncId == sent.syncId && mutationId == sent.mutationId && state == SYNC_PENDING
