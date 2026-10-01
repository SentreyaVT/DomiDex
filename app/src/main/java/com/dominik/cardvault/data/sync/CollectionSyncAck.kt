package com.dominik.cardvault.data.sync

import androidx.room.withTransaction
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_PENDING

/**
 * Commits one already-received ACK to Room, shared by the normal and priority-sharing workers.
 * They can capture the same mutation before either finishes. A late ACK therefore must not
 * create PENDING without an outbox, lower a confirmed revision or reset a terminal successor.
 *
 * True retains the current mutation as a durable CLEAN + outbox server-reread checkpoint.
 * False leaves that responsibility to an existing successor or to the worker that completed it.
 * This function performs no Firebase/network operations and never removes an outbox row.
 */
internal suspend fun commitCollectionAck(
    database: CardVaultDatabase,
    mutation: CollectionSyncOutboxEntity,
    acknowledgedRevision: Long,
    isOwnerCurrent: (String) -> Boolean,
): Boolean = database.withTransaction {
    if (acknowledgedRevision <= 0L || !isOwnerCurrent(mutation.ownerUid)) return@withTransaction false
    val dao = database.cardVaultDao()
    val current = dao.syncOutbox(mutation.entryKey) ?: return@withTransaction false
    if (current.ownerUid != mutation.ownerUid || current.syncId != mutation.syncId || current.state != SYNC_PENDING) {
        return@withTransaction false
    }
    val entry = dao.getCollectionEntryBySyncId(mutation.ownerUid, mutation.syncId) ?: return@withTransaction false
    // Preserve an explicit conflict/error decision, including a defensive inconsistent-state case.
    if (entry.syncState != SYNC_PENDING && entry.syncState != SYNC_CLEAN) return@withTransaction false
    if (!isOwnerCurrent(mutation.ownerUid)) return@withTransaction false
    val confirmedRevision = maxOf(entry.remoteRevision, acknowledgedRevision)
    if (current.mutationId == mutation.mutationId) {
        // A listener may already have applied N+2 while another worker was awaiting ACK N+1.
        dao.markEntrySynced(mutation.ownerUid, mutation.syncId, confirmedRevision, SYNC_CLEAN)
        true
    } else {
        // A CLEAN successor is already awaiting its own reread; an older ACK must not reopen it.
        if (entry.syncState != SYNC_PENDING) return@withTransaction false
        dao.advanceOutboxBaseRevision(mutation.entryKey, confirmedRevision)
        dao.markEntrySynced(mutation.ownerUid, mutation.syncId, confirmedRevision, entry.syncState)
        false
    }
}
