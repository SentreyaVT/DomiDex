package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.priceIdentityCard
import com.dominik.cardvault.data.selectionOrNull
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

private const val PRICE_CONFLICT_PREFIX = "price-selection-conflict:"

internal fun priceSelectionConflictRevision(reason: String?): Long? = reason
    ?.takeIf { it.startsWith(PRICE_CONFLICT_PREFIX) }
    ?.removePrefix(PRICE_CONFLICT_PREFIX)
    ?.takeIf { it.matches(Regex("0|[1-9][0-9]*")) }
    ?.toLongOrNull()?.takeIf { it in 0..MAX_PRICE_SELECTION_INTEGER }

private fun priceMutation(mutation: PrivateBinderSyncOutboxEntity): Boolean =
    mutation.recordType == PRIVATE_SYNC_PRICE_SELECTION && mutation.ownerUid != GUEST_OWNER_UID &&
        mutation.ownerUid.isNotBlank() &&
        mutation.entryKey == "${mutation.ownerUid}:$PRIVATE_SYNC_PRICE_SELECTION:${mutation.recordId}"

/** Worker calls inside its session-guarded Room transaction. Preserve payload AND old base revision. */
internal suspend fun markPriceSelectionConflict(
    dao: CardVaultDao,
    mutation: PrivateBinderSyncOutboxEntity,
    remoteRevision: Long,
): Boolean {
    if (!priceMutation(mutation) || remoteRevision !in 0..MAX_PRICE_SELECTION_INTEGER ||
        dao.privateBinderSyncOutbox(mutation.entryKey) != mutation) return false
    val selection = dao.priceSelection(mutation.ownerUid, mutation.recordId) ?: return false
    dao.upsertPrivateBinderSyncOutbox(mutation.copy(lastError = "$PRICE_CONFLICT_PREFIX$remoteRevision"))
    dao.upsertPriceSelection(selection.copy(syncState = SYNC_CONFLICT))
    return true
}

/** Explicit local-wins only. A later remote write produces another conflict, never an automatic rebase. */
internal suspend fun keepLocalPriceSelectionConflict(
    database: CardVaultDatabase,
    conflict: PrivateBinderSyncOutboxEntity,
    newMutationId: String,
    now: Long,
    isCurrent: () -> Boolean,
): Boolean = database.withPrivateSyncSession(isCurrent) {
    currentCoroutineContext().ensureActive()
    val dao = database.cardVaultDao()
    val remoteRevision = priceSelectionConflictRevision(conflict.lastError) ?: return@withPrivateSyncSession false
    if (!priceMutation(conflict) || newMutationId == conflict.mutationId ||
        !newMutationId.matches(Regex("[A-Za-z0-9._-]{1,128}")) || now <= 0 ||
        dao.privateBinderSyncOutbox(conflict.entryKey) != conflict) return@withPrivateSyncSession false
    val local = dao.priceSelection(conflict.ownerUid, conflict.recordId)
        ?.takeIf { it.syncState == SYNC_CONFLICT } ?: return@withPrivateSyncSession false
    if (!stablePriceSelectionParent(dao, local, allowDeletedClear = false)) return@withPrivateSyncSession false
    // Preserve the selected source snapshot and selectedAt; this is not new market data.
    dao.upsertPrivateBinderSyncOutbox(conflict.copy(mutationId = newMutationId,
        expectedRemoteRevision = remoteRevision, updatedAtEpochMillis = now, createdAtEpochMillis = now, lastError = null))
    dao.upsertPriceSelection(local.copy(remoteRevision = remoteRevision, syncState = SYNC_PENDING, updatedAtEpochMillis = now))
    currentCoroutineContext().ensureActive()
    true
}

/**
 * Explicit cloud-wins. No delete occurs before all guards pass; failures retain the exact outbox.
 * Equal revision is allowed only here after explicit user consent, never through ordinary pull.
 * confirmedServer requires an existing snapshot with !fromCache AND !hasPendingWrites.
 */
internal suspend fun keepCloudPriceSelectionConflict(
    database: CardVaultDatabase,
    conflict: PrivateBinderSyncOutboxEntity,
    data: Map<String, Any?>,
    confirmedServer: Boolean,
    isCurrent: () -> Boolean,
): Boolean = database.withPrivateSyncSession(isCurrent) {
    currentCoroutineContext().ensureActive()
    val dao = database.cardVaultDao()
    val minimumRevision = priceSelectionConflictRevision(conflict.lastError) ?: return@withPrivateSyncSession false
    if (!priceMutation(conflict) || !confirmedServer ||
        dao.privateBinderSyncOutbox(conflict.entryKey) != conflict) return@withPrivateSyncSession false
    val local = dao.priceSelection(conflict.ownerUid, conflict.recordId)
        ?.takeIf { it.syncState == SYNC_CONFLICT } ?: return@withPrivateSyncSession false
    val remote = parsePriceSelectionSidecar(data, conflict.ownerUid, conflict.recordId, local.collectionEntryId)
        ?: return@withPrivateSyncSession false
    if (remote.remoteRevision < maxOf(minimumRevision, local.remoteRevision) ||
        !stablePriceSelectionParent(dao, remote, allowDeletedClear = true)) return@withPrivateSyncSession false
    dao.upsertPriceSelection(remote)
    dao.deletePrivateBinderSyncOutboxIfCurrent(conflict.entryKey, conflict.mutationId)
    currentCoroutineContext().ensureActive()
    true
}

private suspend fun stablePriceSelectionParent(
    dao: CardVaultDao,
    selection: CollectionPriceSelectionEntity,
    allowDeletedClear: Boolean,
): Boolean {
    val parent = dao.getCollectionEntryBySyncId(selection.ownerUid, selection.entrySyncId) ?: return false
    if (parent.id != selection.collectionEntryId || parent.syncState != SYNC_CLEAN || parent.remoteRevision <= 0 ||
        dao.syncOutbox("${selection.ownerUid}:${selection.entrySyncId}") != null) return false
    if (parent.deletedAtEpochMillis != null && !(allowDeletedClear && selection.cleared)) return false
    if (selection.cleared) return listOf(selection.sourceSetId, selection.sourceCardId, selection.collectorNumber,
        selection.finish, selection.variant, selection.language, selection.productId, selection.metric,
        selection.amountMinor, selection.currency, selection.sourceAt, selection.selectedAt).all { it == null }
    if (parent.externalProductSource != CardmarketSyncContract.SOURCE) return false
    val identity = dao.collectionSyncProjection(selection.ownerUid, selection.entrySyncId)?.priceIdentityCard() ?: return false
    return selection.selectionOrNull()?.matches(identity) == true
}
