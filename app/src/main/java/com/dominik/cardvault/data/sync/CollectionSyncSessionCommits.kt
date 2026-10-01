package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_PENDING
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Session-bound Room commits for one actual collection-worker pass.
 * The existing mutation/revision/conflict policies remain in their shared helpers;
 * the outer transaction rolls their writes back if this run's session changes late.
 */
internal class CollectionSyncSessionCommits(
    private val database: CardVaultDatabase,
    private val ownerUid: String,
    private val isCurrent: () -> Boolean,
) {
    suspend fun acknowledge(mutation: CollectionSyncOutboxEntity, revision: Long): Boolean = commit {
        if (mutation.ownerUid != ownerUid) return@commit false
        commitCollectionAck(database, mutation, revision, ::isOwnerCurrent)
    }

    suspend fun fail(mutation: CollectionSyncOutboxEntity, state: String, reason: String): Boolean = commit {
        if (mutation.ownerUid != ownerUid) return@commit false
        commitCollectionFailure(database, mutation, state, reason, ::isOwnerCurrent)
    }

    suspend fun normalize(mutation: CollectionSyncOutboxEntity): Boolean = commit {
        if (mutation.ownerUid != ownerUid) return@commit false
        normalizePendingOutboxForTerminalEntry(database, mutation, ::isOwnerCurrent)
    }

    /** Recheck the same joined projection used by the worker, not a different row-existence rule. */
    suspend fun discard(mutation: CollectionSyncOutboxEntity): Boolean = commit {
        if (mutation.ownerUid != ownerUid) return@commit false
        val dao = database.cardVaultDao()
        val current = dao.syncOutbox(mutation.entryKey) ?: return@commit false
        if (current.ownerUid != ownerUid || current.syncId != mutation.syncId ||
            current.mutationId != mutation.mutationId || current.state != SYNC_PENDING) return@commit false
        if (dao.collectionSyncProjection(ownerUid, mutation.syncId) != null) return@commit false
        dao.deleteSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
        true
    }

    /**
     * Reread application and the captured mutation's conditional deletion share one session fence.
     * Only a successfully decoded, confirmed server read may complete this checkpoint.
     */
    suspend fun finishReread(mutation: CollectionSyncOutboxEntity, remote: RemoteCollectionEntry): Unit = commit {
        if (mutation.ownerUid != ownerUid) return@commit
        if (remote.syncId != mutation.syncId) return@commit
        applyRemoteCollectionEntryToRoom(database, ownerUid, remote, isCurrent)
        database.cardVaultDao().deleteSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
    }

    private fun isOwnerCurrent(candidate: String): Boolean = candidate == ownerUid && isCurrent()

    private suspend fun <T> commit(block: suspend () -> T): T {
        val callerContext = currentCoroutineContext()
        return database.withPrivateSyncSession({
            callerContext.ensureActive()
            // The final lease check itself is inside Room's commit boundary. Cancellation
            // observed there must throw before Room can mark this transaction successful.
            isCurrent().also { callerContext.ensureActive() }
        }) {
            currentCoroutineContext().ensureActive()
            val result = block()
            currentCoroutineContext().ensureActive()
            result
        }
    }
}
