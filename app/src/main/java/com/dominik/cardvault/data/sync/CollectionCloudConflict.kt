package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_CONFLICT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Existing first phase: open presentation to newer listener updates, retaining the conflict. */
internal suspend fun openCollectionCloudConflict(
    database: CardVaultDatabase,
    ownerUid: String,
    conflict: CollectionSyncOutboxEntity,
    remote: RemoteCollectionEntry,
    isCurrent: () -> Boolean,
): Boolean = database.withPrivateSyncSession(isCurrent) {
    currentCoroutineContext().ensureActive()
    val dao = database.cardVaultDao()
    val current = dao.syncOutbox(conflict.entryKey)
    if (current?.ownerUid != ownerUid || current.mutationId != conflict.mutationId || current.state != SYNC_CONFLICT) {
        return@withPrivateSyncSession false
    }
    val local = dao.getCollectionEntryBySyncId(ownerUid, remote.syncId)
    if (local == null || (local.syncState != SYNC_CONFLICT && local.syncState != SYNC_CLEAN)) return@withPrivateSyncSession false
    dao.markEntrySynced(ownerUid, remote.syncId, maxOf(local.remoteRevision, remote.revision), SYNC_CLEAN)
    currentCoroutineContext().ensureActive()
    true
}

/** Existing second phase: use the fresh read unless a newer listener version already won. */
internal suspend fun finishCollectionCloudConflict(
    database: CardVaultDatabase,
    ownerUid: String,
    conflict: CollectionSyncOutboxEntity,
    remote: RemoteCollectionEntry,
    isCurrent: () -> Boolean,
): Int = database.withPrivateSyncSession(isCurrent) {
    currentCoroutineContext().ensureActive()
    val dao = database.cardVaultDao()
    val current = dao.syncOutbox(conflict.entryKey)
    if (current?.ownerUid != ownerUid || current.mutationId != conflict.mutationId || current.state != SYNC_CONFLICT) {
        return@withPrivateSyncSession 0
    }
    val local = dao.getCollectionEntryBySyncId(ownerUid, remote.syncId)
    if (local?.syncState != SYNC_CLEAN) return@withPrivateSyncSession 0
    if (shouldApplyCloudResolutionSnapshot(local.remoteRevision, remote.revision)) {
        dao.applyCleanRemoteEntry(ownerUid, remote.syncId, remote.quantity, remote.condition,
            remote.acquisitionPriceMinor, remote.acquisitionCurrency, remote.acquisitionDateEpochDay,
            remote.notes, remote.externalProductSource, remote.externalProductId, remote.sourceSetId,
            remote.sourceCardId, remote.binderId, remote.updatedAtEpochMillis, remote.revision,
            remote.deletedAtEpochMillis, remote.finish, remote.variant, remote.effectOverride)
    }
    dao.deleteSyncOutboxIfCurrent(conflict.entryKey, conflict.mutationId)
    currentCoroutineContext().ensureActive()
    1
}

/** Checks the original action around a suspension without converting local database failures. */
internal suspend fun <T> guardedConflictActionRead(
    isCurrent: () -> Boolean,
    read: suspend () -> T,
): T {
    suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw PrivatePullAccountChangedException()
    }
    requireCurrent()
    return try {
        read().also { requireCurrent() }
    } catch (error: CancellationException) {
        throw error
    } catch (error: PrivatePullAccountChangedException) {
        throw error
    } catch (error: Exception) {
        requireCurrent()
        throw error
    }
}

/** SERVER-read errors leave the conflict open; cancellation/session changes always propagate. */
internal suspend fun <T> readCollectionCloudConflict(
    isCurrent: () -> Boolean,
    read: suspend () -> T,
): T? = try {
    guardedConflictActionRead(isCurrent, read)
} catch (error: CancellationException) {
    throw error
} catch (error: PrivatePullAccountChangedException) {
    throw error
} catch (_: Exception) {
    null
}
