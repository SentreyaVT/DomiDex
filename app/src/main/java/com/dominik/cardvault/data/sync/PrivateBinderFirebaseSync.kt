package com.dominik.cardvault.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dominik.cardvault.auth.canUseFirebaseAppBackend
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentVerifiedFirebaseEntitlementAccount
import com.dominik.cardvault.data.local.CardVaultDao
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CollectionLayoutEntity
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.data.local.PrivateBinderEntity
import com.dominik.cardvault.data.local.PrivateBinderPlacementEntity
import com.dominik.cardvault.data.local.PrivateBinderSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_PENDING
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val PRIVATE_SYNC_BINDER = "BINDER"
internal const val PRIVATE_SYNC_PLACEMENT = "PLACEMENT"
internal const val PRIVATE_SYNC_LAYOUT = "LAYOUT"
internal const val PRIVATE_SYNC_PRICE_SELECTION = "PRICE_SELECTION"
internal const val PRIVATE_SYNC_UPSERT = "UPSERT"
internal const val PRIVATE_SYNC_DELETE = "DELETE"

internal enum class PrivatePushAction { APPLY, ALREADY_APPLIED, REMOTE_WINS }

internal enum class PrivateSyncContinuation { COMPLETE, APPEND, RETRY }

internal fun privateSyncContinuation(
    parentDependencyPending: Boolean,
    hasMoreRemoteDocuments: Boolean,
    hasPendingMutations: Boolean,
): PrivateSyncContinuation = when {
    parentDependencyPending -> PrivateSyncContinuation.RETRY
    hasMoreRemoteDocuments || hasPendingMutations -> PrivateSyncContinuation.APPEND
    else -> PrivateSyncContinuation.COMPLETE
}

/** Recheck only dependencies deferred in this bounded run; do not poll or touch another owner. */
internal suspend fun deferredPriceSelectionParentPending(
    dao: CardVaultDao,
    ownerUid: String,
    entryKeys: Set<String>,
): Boolean {
    for (entryKey in entryKeys) {
        val selection = dao.privateBinderSyncOutbox(entryKey) ?: continue
        if (selection.ownerUid != ownerUid || selection.recordType != PRIVATE_SYNC_PRICE_SELECTION) continue
        // Any state (including CONFLICT/ERROR) remains blocked until collection sync resolves it.
        if (dao.syncOutbox("$ownerUid:${selection.recordId}") != null) return true
    }
    return false
}

internal enum class PrivatePushCommitAction {
    COMPLETE_CURRENT,
    APPLY_REMOTE,
    REBASE_SUCCESSOR,
    KEEP_SUCCESSOR,
    NONE,
}

internal enum class PrivateRemoteDependencyType {
    BINDER,
    COLLECTION_ENTRY,
}

internal sealed interface PrivateRemoteApplyResult {
    data object Checkpoint : PrivateRemoteApplyResult
    data class RetryDependency(
        val type: PrivateRemoteDependencyType,
        val id: String,
    ) : PrivateRemoteApplyResult
}

internal data class PrivateRemoteCheckpoint(
    val cursor: String?,
    val dependencyPending: Boolean,
)

internal fun privateRemoteCheckpointAfter(
    checkpoint: PrivateRemoteCheckpoint,
    documentId: String,
    result: PrivateRemoteApplyResult,
): PrivateRemoteCheckpoint = when {
    checkpoint.dependencyPending -> checkpoint
    result is PrivateRemoteApplyResult.RetryDependency -> checkpoint.copy(dependencyPending = true)
    else -> PrivateRemoteCheckpoint(cursor = documentId, dependencyPending = false)
}

internal fun shouldRestorePrivateMutationAfterRemoteApply(result: PrivateRemoteApplyResult): Boolean =
    result is PrivateRemoteApplyResult.RetryDependency

internal fun privatePushAction(
    remoteRevision: Long,
    expectedRemoteRevision: Long,
    alreadyApplied: Boolean,
    remoteDeleted: Boolean,
    localDeleted: Boolean,
): PrivatePushAction = when {
    alreadyApplied -> PrivatePushAction.ALREADY_APPLIED
    remoteRevision != expectedRemoteRevision -> PrivatePushAction.REMOTE_WINS
    remoteDeleted && !localDeleted -> PrivatePushAction.REMOTE_WINS
    else -> PrivatePushAction.APPLY
}

internal fun privatePushCommitAction(
    remoteWins: Boolean,
    sentMutationId: String,
    currentMutationId: String?,
): PrivatePushCommitAction = when {
    currentMutationId == null -> PrivatePushCommitAction.NONE
    currentMutationId != sentMutationId && remoteWins -> PrivatePushCommitAction.KEEP_SUCCESSOR
    currentMutationId != sentMutationId -> PrivatePushCommitAction.REBASE_SUCCESSOR
    remoteWins -> PrivatePushCommitAction.APPLY_REMOTE
    else -> PrivatePushCommitAction.COMPLETE_CURRENT
}

internal fun deterministicPrivatePlacementId(ownerUid: String, binderId: String, entrySyncId: String): String {
    val key = "$ownerUid|$binderId|$entrySyncId"
    return UUID.nameUUIDFromBytes(key.toByteArray(StandardCharsets.UTF_8)).toString()
}

internal fun privateBinderPath(uid: String, binderId: String) = "users/$uid/privateBinders/$binderId"
internal fun privateBinderPlacementPath(uid: String, placementId: String) =
    "users/$uid/privateBinderPlacements/$placementId"
internal fun collectionLayoutPath(uid: String, entrySyncId: String) =
    "users/$uid/collectionLayouts/$entrySyncId"
internal fun collectionPriceSelectionPath(uid: String, entrySyncId: String) =
    "users/$uid/collectionPriceSelections/$entrySyncId"

internal const val PRIVATE_BINDER_PERIODIC_SYNC_INTERVAL_MINUTES = 15L

internal fun privateBinderSyncWorkName(uid: String): String =
    "domidex-private-binder-sync-${UUID.nameUUIDFromBytes(uid.toByteArray(StandardCharsets.UTF_8))}"

internal fun privateBinderPeriodicSyncWorkName(uid: String): String =
    "domidex-private-binder-periodic-sync-${UUID.nameUUIDFromBytes(uid.toByteArray(StandardCharsets.UTF_8))}"

private const val PRIVATE_BINDER_PERIODIC_SYNC_TAG = "domidex-private-binder-periodic-sync"

/**
 * Private layouts intentionally use bounded one-shot pulls and a durable outbox. They never add a
 * second live listener over the user's collection and cannot block local collection persistence.
 */
internal object PrivateBinderSyncScheduler {
    internal const val INPUT_OWNER_UID = "privateBinderOwnerUid"

    fun schedule(
        context: Context,
        ownerUid: String? = canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser),
        userInitiated: Boolean = false,
    ) {
        if (ownerUid.isNullOrBlank() || ownerUid == GUEST_OWNER_UID) return
        WorkManager.getInstance(context).enqueueUniqueWork(
            privateBinderSyncWorkName(ownerUid),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(ownerUid, userInitiated),
        )
    }

    internal fun request(ownerUid: String, userInitiated: Boolean, sdk: Int = Build.VERSION.SDK_INT): OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<PrivateBinderSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(INPUT_OWNER_UID to ownerUid))
        // Match collection sync: preserve normal work on older Android and as quota fallback.
        if (userInitiated && sdk >= 31) {
            request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return request.build()
    }

    fun ensurePeriodic(
        context: Context,
        ownerUid: String? = canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser),
    ) {
        if (ownerUid.isNullOrBlank() || ownerUid == GUEST_OWNER_UID) return
        val request = PeriodicWorkRequestBuilder<PrivateBinderSyncWorker>(
            PRIVATE_BINDER_PERIODIC_SYNC_INTERVAL_MINUTES,
            TimeUnit.MINUTES,
        )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(INPUT_OWNER_UID to ownerUid))
            .addTag(PRIVATE_BINDER_PERIODIC_SYNC_TAG)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            privateBinderPeriodicSyncWorkName(ownerUid),
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    fun cancelPeriodic(context: Context, ownerUid: String?) {
        if (ownerUid.isNullOrBlank() || ownerUid == GUEST_OWNER_UID) return
        WorkManager.getInstance(context).cancelUniqueWork(privateBinderPeriodicSyncWorkName(ownerUid))
    }

    fun cancelAllPeriodic(context: Context) {
        WorkManager.getInstance(context).cancelAllWorkByTag(PRIVATE_BINDER_PERIODIC_SYNC_TAG)
    }

}

class PrivateBinderSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uid = inputData.getString(PrivateBinderSyncScheduler.INPUT_OWNER_UID)
            ?.takeIf(String::isNotBlank)
            ?: return@withContext Result.success()
        val sharingBinderId = inputData.getString(SHARING_BINDER_ID)
        if (sharingBinderId != null && !validSharingBinderId(sharingBinderId)) return@withContext Result.failure()
        val auth = FirebaseAuth.getInstance()
        suspend fun accessStillCurrent(): Boolean {
            val account = auth.currentVerifiedFirebaseEntitlementAccount(forceRefresh = false) ?: return false
            return account.uid == uid &&
                canonicalCollectionOwnerUid(auth.currentUser) == uid &&
                canUseFirebaseAppBackend(uid, canonicalCollectionOwnerUid(auth.currentUser), account.entitlements)
        }
        if (!runCatching { accessStillCurrent() }.getOrElse { return@withContext Result.retry() }) {
            return@withContext Result.success()
        }

        val database = CardVaultDatabase.get(applicationContext)
        val dao = database.cardVaultDao()
        val firestore = FirebaseFirestore.getInstance()
        if (sharingBinderId != null && dao.pendingSharingCollectionOutbox(uid, sharingBinderId, 1).isNotEmpty()) {
            BinderSharingSyncScheduler.continueBatch(applicationContext, uid, sharingBinderId)
            return@withContext Result.success()
        }
        var processed = 0
        val deferredPriceSelections = mutableSetOf<String>()
        while (processed < MAX_MUTATIONS_PER_RUN) {
            if (!runCatching { accessStillCurrent() }.getOrElse { return@withContext Result.retry() }) {
                return@withContext Result.success()
            }
            val candidates = if (sharingBinderId == null) dao.pendingPrivateBinderSyncOutbox(uid, deferredPriceSelections.size + 1)
                else dao.pendingSharingPrivateBinderOutbox(uid, sharingBinderId, 1)
            val mutation = candidates.firstOrNull { it.entryKey !in deferredPriceSelections } ?: break
            val payload = mutationPayload(dao, mutation) ?: run {
                dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                continue
            }
            // A newly added entry and its sidecar are committed locally together. Let the existing
            // collection worker publish the parent before rules validate this dependent document.
            if (mutation.recordType == PRIVATE_SYNC_PRICE_SELECTION &&
                dao.syncOutbox("$uid:${mutation.recordId}") != null) {
                // A conflicted parent must not starve unrelated binder/layout changes.
                deferredPriceSelections.add(mutation.entryKey)
                processed++
                continue
            }
            val reference = firestore.document(remotePath(mutation))
            val pushOutcome = runCatching {
                firestore.runTransaction { transaction ->
                    val current = transaction.get(reference)
                    val remoteRevision = current.getLong("revision") ?: 0L
                    val alreadyApplied = current.getString("lastMutationId") == mutation.mutationId
                    val remoteDeleted = current.getBoolean("deleted") == true
                    val localDeleted = payload["deleted"] == true
                    when (privatePushAction(
                        remoteRevision = remoteRevision,
                        expectedRemoteRevision = mutation.expectedRemoteRevision,
                        alreadyApplied = alreadyApplied,
                        remoteDeleted = remoteDeleted,
                        localDeleted = localDeleted,
                    )) {
                        PrivatePushAction.ALREADY_APPLIED -> PrivatePushOutcome(remoteRevision, remoteWins = false)
                        PrivatePushAction.REMOTE_WINS -> PrivatePushOutcome(remoteRevision, remoteWins = true)
                        PrivatePushAction.APPLY -> {
                            val nextRevision = remoteRevision + 1L
                            transaction.set(reference, payload + ("revision" to nextRevision))
                            PrivatePushOutcome(nextRevision, remoteWins = false)
                        }
                    }
                }.awaitPrivateBinderTask()
            }.getOrElse { return@withContext Result.retry() }
            val remoteDocument = if (pushOutcome.remoteWins) {
                runCatching { reference.get(Source.SERVER).awaitPrivateBinderTask() }
                    .getOrElse { return@withContext Result.retry() }
            } else {
                null
            }
            var retryRemoteApply = false
            database.withTransaction {
                val current = dao.privateBinderSyncOutbox(mutation.entryKey)
                when (privatePushCommitAction(pushOutcome.remoteWins, mutation.mutationId, current?.mutationId)) {
                    PrivatePushCommitAction.COMPLETE_CURRENT -> {
                        dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                        when (mutation.recordType) {
                            PRIVATE_SYNC_BINDER -> dao.markPrivateBinderSynced(uid, mutation.recordId, pushOutcome.revision)
                            PRIVATE_SYNC_PLACEMENT -> dao.markPrivateBinderPlacementSynced(uid, mutation.recordId, pushOutcome.revision)
                            PRIVATE_SYNC_LAYOUT -> dao.markCollectionLayoutSynced(uid, mutation.recordId, pushOutcome.revision)
                            PRIVATE_SYNC_PRICE_SELECTION -> dao.markPriceSelectionSynced(uid, mutation.recordId, pushOutcome.revision)
                        }
                    }
                    PrivatePushCommitAction.APPLY_REMOTE -> {
                        if (remoteDocument == null) return@withTransaction
                        // The remote apply guards against any pending local mutation. Remove only
                        // the mutation that was just proven current; restore it atomically if a
                        // local dependency has not arrived yet.
                        dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                        val applyResult = when (mutation.recordType) {
                            PRIVATE_SYNC_BINDER -> {
                                applyRemoteBinder(dao, uid, remoteDocument)
                                PrivateRemoteApplyResult.Checkpoint
                            }
                            PRIVATE_SYNC_PLACEMENT -> applyRemotePlacement(dao, uid, remoteDocument)
                            PRIVATE_SYNC_LAYOUT -> applyRemoteLayout(dao, uid, remoteDocument)
                            PRIVATE_SYNC_PRICE_SELECTION -> applyRemotePriceSelection(dao, uid, remoteDocument)
                            else -> PrivateRemoteApplyResult.Checkpoint
                        }
                        if (shouldRestorePrivateMutationAfterRemoteApply(applyResult)) {
                            dao.upsertPrivateBinderSyncOutbox(mutation)
                            retryRemoteApply = true
                        }
                    }
                    PrivatePushCommitAction.REBASE_SUCCESSOR -> {
                        dao.advancePrivateBinderSyncOutboxBaseRevision(mutation.entryKey, pushOutcome.revision)
                        when (mutation.recordType) {
                            PRIVATE_SYNC_BINDER -> dao.rebasePendingPrivateBinder(uid, mutation.recordId, pushOutcome.revision)
                            PRIVATE_SYNC_PLACEMENT -> dao.rebasePendingPrivateBinderPlacement(uid, mutation.recordId, pushOutcome.revision)
                            PRIVATE_SYNC_LAYOUT -> dao.rebasePendingCollectionLayout(uid, mutation.recordId, pushOutcome.revision)
                            PRIVATE_SYNC_PRICE_SELECTION -> dao.rebasePendingPriceSelection(uid, mutation.recordId, pushOutcome.revision)
                        }
                    }
                    PrivatePushCommitAction.KEEP_SUCCESSOR,
                    PrivatePushCommitAction.NONE,
                    -> Unit
                }
            }
            if (retryRemoteApply) return@withContext Result.retry()
            processed++
        }

        if (!runCatching { accessStillCurrent() }.getOrElse { return@withContext Result.retry() }) {
            return@withContext Result.success()
        }
        if (sharingBinderId != null) {
            if (dao.pendingSharingCollectionOutbox(uid, sharingBinderId, 1).isNotEmpty() ||
                dao.pendingSharingPrivateBinderOutbox(uid, sharingBinderId, 1).isNotEmpty()) {
                BinderSharingSyncScheduler.continueBatch(applicationContext, uid, sharingBinderId)
            }
            // No unrelated binder/layout pull on this foreground sharing path.
            return@withContext Result.success()
        }
        val pullResult = runCatching { pullBoundedSnapshot(database, firestore, uid) }
            .onFailure { Log.w(LOG_TAG, "Private-Binder-Abruf wird später wiederholt (${it.javaClass.simpleName}).") }
            .getOrElse { return@withContext Result.retry() }
        // The collection worker can finish while this worker performs its bounded remote pull.
        // A remembered dependency is not itself a failure: only a still-pending parent needs backoff.
        val parentStillPending = database.withTransaction {
            deferredPriceSelectionParentPending(dao, uid, deferredPriceSelections)
        }
        when (privateSyncContinuation(parentStillPending,
            pullResult == PrivateRemotePullResult.MORE_DOCUMENTS,
            dao.pendingPrivateBinderSyncOutbox(uid, 1).isNotEmpty())) {
            PrivateSyncContinuation.RETRY -> return@withContext Result.retry()
            PrivateSyncContinuation.APPEND -> PrivateBinderSyncScheduler.schedule(applicationContext, uid)
            PrivateSyncContinuation.COMPLETE -> Unit
        }
        Result.success()
    }

    private suspend fun mutationPayload(
        dao: CardVaultDao,
        mutation: PrivateBinderSyncOutboxEntity,
    ): Map<String, Any?>? = when (mutation.recordType) {
        PRIVATE_SYNC_BINDER -> dao.privateBinder(mutation.recordId, mutation.ownerUid)?.let { binder ->
            mapOf(
                "binderId" to binder.id,
                "ownerUid" to binder.ownerUid,
                "name" to binder.name,
                "description" to binder.description,
                "coverStyle" to binder.coverStyle,
                "minimumPageCount" to binder.minimumPageCount,
                "createdAtClient" to binder.createdAtEpochMillis,
                "updatedAtClient" to binder.updatedAtEpochMillis,
                "lastOpenedAtClient" to binder.lastOpenedAtEpochMillis,
                "deleted" to (binder.deletedAtEpochMillis != null || mutation.operation == PRIVATE_SYNC_DELETE),
                "lastMutationId" to mutation.mutationId,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        }
        PRIVATE_SYNC_PLACEMENT -> dao.privateBinderPlacementSyncProjection(mutation.ownerUid, mutation.recordId)?.let { placement ->
            mapOf(
                "placementId" to placement.id,
                "ownerUid" to placement.ownerUid,
                "binderId" to placement.binderId,
                "entrySyncId" to placement.collectionEntrySyncId,
                "slotPosition" to placement.slotPosition,
                "holoEffectId" to placement.holoEffectId,
                "holoIntensity" to placement.holoIntensity.toDouble(),
                "createdAtClient" to placement.createdAtEpochMillis,
                "updatedAtClient" to placement.updatedAtEpochMillis,
                "deleted" to (placement.deletedAtEpochMillis != null || mutation.operation == PRIVATE_SYNC_DELETE),
                "lastMutationId" to mutation.mutationId,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        }
        PRIVATE_SYNC_LAYOUT -> dao.collectionLayoutSyncProjection(mutation.ownerUid, mutation.recordId)?.let { layout ->
            mapOf(
                "entrySyncId" to layout.collectionEntrySyncId,
                "ownerUid" to layout.ownerUid,
                "visibleInSetBinder" to layout.visibleInSetBinder,
                "updatedAtClient" to layout.updatedAtEpochMillis,
                "lastMutationId" to mutation.mutationId,
                "updatedAt" to FieldValue.serverTimestamp(),
            )
        }
        PRIVATE_SYNC_PRICE_SELECTION -> dao.priceSelection(mutation.ownerUid, mutation.recordId)
            ?.takeIf {
                dao.getCollectionEntryBySyncId(mutation.ownerUid, mutation.recordId)
                    ?.let { entry -> entry.deletedAtEpochMillis == null } == true
            }
            ?.let { selection ->
                selection.priceSelectionPayload(mutation.mutationId) + ("updatedAt" to FieldValue.serverTimestamp())
            }
        else -> null
    }

    private fun remotePath(mutation: PrivateBinderSyncOutboxEntity): String = when (mutation.recordType) {
        PRIVATE_SYNC_BINDER -> privateBinderPath(mutation.ownerUid, mutation.recordId)
        PRIVATE_SYNC_PLACEMENT -> privateBinderPlacementPath(mutation.ownerUid, mutation.recordId)
        PRIVATE_SYNC_LAYOUT -> collectionLayoutPath(mutation.ownerUid, mutation.recordId)
        PRIVATE_SYNC_PRICE_SELECTION -> collectionPriceSelectionPath(mutation.ownerUid, mutation.recordId)
        else -> error("Unbekannter Private-Binder-Synchronisationstyp.")
    }

    private companion object {
        const val MAX_MUTATIONS_PER_RUN = 200
        const val LOG_TAG = "DomiDexPrivateBinder"
    }
}

private data class PrivatePushOutcome(val revision: Long, val remoteWins: Boolean)

private enum class PrivateRemotePullResult {
    COMPLETE,
    MORE_DOCUMENTS,
}

private suspend fun pullBoundedSnapshot(
    database: CardVaultDatabase,
    firestore: FirebaseFirestore,
    uid: String,
): PrivateRemotePullResult {
    val user = firestore.collection("users").document(uid)
    val dao = database.cardVaultDao()
    val ownerKey = UUID.nameUUIDFromBytes(uid.toByteArray(StandardCharsets.UTF_8)).toString()
    val phaseKey = "private-sync-pull-phase-$ownerKey"
    var phase = dao.metadata(phaseKey)?.toIntOrNull()?.coerceIn(0, 4) ?: 0
    var remaining = MAX_REMOTE_DOCUMENTS_PER_RUN
    val groups = listOf(
        RemotePullGroup("privateBinders") { document ->
            applyRemoteBinder(dao, uid, document)
            PrivateRemoteApplyResult.Checkpoint
        },
        RemotePullGroup("privateBinderPlacements") { document -> applyRemotePlacement(dao, uid, document) },
        RemotePullGroup("collectionLayouts") { document -> applyRemoteLayout(dao, uid, document) },
        RemotePullGroup("collectionPriceSelections") { document -> applyRemotePriceSelection(dao, uid, document) },
    )
    while (phase < groups.size) {
        val group = groups[phase]
        val cursorKey = "private-sync-pull-cursor-$ownerKey-$phase"
        var cursor = dao.metadata(cursorKey)?.takeIf(String::isNotBlank)
        while (remaining > 0) {
            val pageSize = minOf(REMOTE_PULL_PAGE_SIZE, remaining)
            var query = user.collection(group.collectionName)
                .orderBy(FieldPath.documentId())
                .limit(pageSize.toLong())
            if (cursor != null) query = query.startAfter(cursor)
            val documents = query.get(Source.SERVER).awaitPrivateBinderTask().documents
            if (documents.isNotEmpty()) {
                var checkpoint = PrivateRemoteCheckpoint(cursor = cursor, dependencyPending = false)
                for (document in documents) {
                    var applyResult = database.withTransaction { group.apply(document) }
                    val beforeDocument = checkpoint
                    checkpoint = privateRemoteCheckpointAfter(beforeDocument, document.id, applyResult)
                    val resolvedDependencies = mutableSetOf<PrivateRemoteApplyResult.RetryDependency>()
                    while (applyResult is PrivateRemoteApplyResult.RetryDependency) {
                        if (!resolvedDependencies.add(applyResult)) {
                            // The authoritative dependency read completed, but the same reference
                            // still cannot be materialized: it is orphaned or invalid.
                            applyResult = PrivateRemoteApplyResult.Checkpoint
                            break
                        }
                        resolveRemoteDependency(database, firestore, uid, applyResult)
                        applyResult = database.withTransaction { group.apply(document) }
                    }
                    checkpoint = privateRemoteCheckpointAfter(beforeDocument, document.id, applyResult)
                    database.withTransaction {
                        checkpoint.cursor
                            ?.takeIf { it != cursor }
                            ?.let { dao.putMetadata(AppMetadataEntity(cursorKey, it)) }
                    }
                    cursor = checkpoint.cursor
                }
                remaining -= documents.size
            }
            if (documents.size < pageSize) {
                database.withTransaction {
                    dao.putMetadata(AppMetadataEntity(cursorKey, ""))
                    phase++
                    dao.putMetadata(AppMetadataEntity(phaseKey, phase.toString()))
                }
                break
            }
            if (remaining == 0) return PrivateRemotePullResult.MORE_DOCUMENTS
        }
    }
    dao.putMetadata(AppMetadataEntity(phaseKey, "0"))
    return PrivateRemotePullResult.COMPLETE
}

private class RemotePullGroup(
    val collectionName: String,
    val apply: suspend (DocumentSnapshot) -> PrivateRemoteApplyResult,
)

private suspend fun resolveRemoteDependency(
    database: CardVaultDatabase,
    firestore: FirebaseFirestore,
    uid: String,
    dependency: PrivateRemoteApplyResult.RetryDependency,
) {
    if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser))) {
        throw IllegalStateException("Private-Binder-Synchronisationskonto hat gewechselt.")
    }
    when (dependency.type) {
        PrivateRemoteDependencyType.BINDER -> {
            val document = firestore.document(privateBinderPath(uid, dependency.id))
                .get(Source.SERVER)
                .awaitPrivateBinderTask()
            if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser))) {
                throw IllegalStateException("Private-Binder-Synchronisationskonto hat gewechselt.")
            }
            if (document.exists()) database.withTransaction {
                applyRemoteBinder(database.cardVaultDao(), uid, document)
            }
        }
        PrivateRemoteDependencyType.COLLECTION_ENTRY -> {
            val document = firestore.document(collectionEntryPath(uid, dependency.id))
                .get(Source.SERVER)
                .awaitPrivateBinderTask()
            if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser))) {
                throw IllegalStateException("Private-Binder-Synchronisationskonto hat gewechselt.")
            }
            if (document.exists()) applyRemoteDocumentToRoom(database, uid, document) {
                shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser))
            }
        }
    }
}

private suspend fun applyRemoteBinder(dao: CardVaultDao, uid: String, document: DocumentSnapshot) {
    val id = document.getString("binderId") ?: return
    if (id != document.id || document.getString("ownerUid") != uid || !validRemoteId(id)) return
    if (dao.privateBinderSyncOutbox("$uid:$PRIVATE_SYNC_BINDER:$id") != null) return
    val revision = document.getLong("revision")?.takeIf { it > 0L } ?: return
    val updatedAt = document.getLong("updatedAtClient")?.takeIf { it > 0L } ?: return
    val existing = dao.privateBinder(id, uid)
    if (existing != null && existing.remoteRevision >= revision) return
    val deleted = document.getBoolean("deleted") == true
    if (deleted && existing == null) return
    val name = document.getString("name")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 } ?: return
    val description = document.getString("description")?.takeIf { it.length <= 1_000 }
    val coverStyle = document.getString("coverStyle")?.takeIf { it.isNotBlank() && it.length <= 80 } ?: "DOMIDEX"
    val minimumPageCount = document.getLong("minimumPageCount")
        ?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }
        ?.toInt()
        ?: 1
    dao.upsertPrivateBinder(
        PrivateBinderEntity(
            id = id,
            ownerUid = uid,
            name = name,
            description = description,
            coverStyle = coverStyle,
            minimumPageCount = minimumPageCount,
            createdAtEpochMillis = document.getLong("createdAtClient")?.takeIf { it > 0L }
                ?: existing?.createdAtEpochMillis ?: updatedAt,
            updatedAtEpochMillis = updatedAt,
            lastOpenedAtEpochMillis = document.getLong("lastOpenedAtClient"),
            syncState = SYNC_CLEAN,
            remoteRevision = revision,
            deletedAtEpochMillis = updatedAt.takeIf { deleted },
        ),
    )
}

private suspend fun applyRemotePlacement(
    dao: CardVaultDao,
    uid: String,
    document: DocumentSnapshot,
): PrivateRemoteApplyResult {
    val checkpoint = PrivateRemoteApplyResult.Checkpoint
    val id = document.getString("placementId") ?: return checkpoint
    if (id != document.id || document.getString("ownerUid") != uid || !validRemoteId(id)) return checkpoint
    if (dao.privateBinderSyncOutbox("$uid:$PRIVATE_SYNC_PLACEMENT:$id") != null) return checkpoint
    val revision = document.getLong("revision")?.takeIf { it > 0L } ?: return checkpoint
    val updatedAt = document.getLong("updatedAtClient")?.takeIf { it > 0L } ?: return checkpoint
    val existing = dao.privateBinderPlacement(id, uid)
    if (existing != null && existing.remoteRevision >= revision) return checkpoint
    val deleted = document.getBoolean("deleted") == true
    if (deleted) {
        if (existing != null) {
            dao.updatePrivateBinderPlacement(
                existing.copy(
                    updatedAtEpochMillis = updatedAt,
                    syncState = SYNC_CLEAN,
                    remoteRevision = revision,
                    deletedAtEpochMillis = updatedAt,
                ),
            )
        }
        return checkpoint
    }
    val binderId = document.getString("binderId")?.takeIf(::validRemoteId) ?: return checkpoint
    val entrySyncId = document.getString("entrySyncId")?.takeIf(::validRemoteId) ?: return checkpoint
    val requestedPosition = document.getLong("slotPosition")?.takeIf { it >= 0L } ?: return checkpoint
    val remoteHoloEffectId = when (val value = document.get("holoEffectId")) {
        null -> null
        is String -> value.trim().takeIf(String::isNotEmpty)
            ?.takeIf(::validPrivateBinderAppearanceId)
            ?: return checkpoint
        else -> return checkpoint
    }
    val remoteHoloIntensity = when (val value = document.get("holoIntensity")) {
        null -> 0f
        is Number -> value.toFloat().takeIf { it.isFinite() && it in 0f..1f } ?: return checkpoint
        else -> return checkpoint
    }
    if (remoteHoloEffectId == null && remoteHoloIntensity != 0f) return checkpoint
    val binder = dao.privateBinder(binderId, uid)
        ?: return PrivateRemoteApplyResult.RetryDependency(PrivateRemoteDependencyType.BINDER, binderId)
    if (binder.deletedAtEpochMillis != null) return checkpoint
    val entry = dao.getCollectionEntryBySyncId(uid, entrySyncId)
        ?: return PrivateRemoteApplyResult.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, entrySyncId)
    if (entry.deletedAtEpochMillis != null) return checkpoint
    val membership = dao.privateBinderPlacementForEntry(binder.id, entry.id, uid)
        ?.takeIf { it.id != id && it.deletedAtEpochMillis == null }
    if (membership != null) {
        val resolutionTime = System.currentTimeMillis()
        // Concurrent reassignments can create two immutable generations. Resolve them by stable
        // document id rather than incomparable device clocks, then tombstone the loser.
        if (membership.id < id) {
            val losingRemote = PrivateBinderPlacementEntity(
                id = id,
                ownerUid = uid,
                binderId = binder.id,
                collectionEntryId = entry.id,
                slotPosition = requestedPosition,
                createdAtEpochMillis = document.getLong("createdAtClient")?.takeIf { it > 0L } ?: updatedAt,
                updatedAtEpochMillis = resolutionTime,
                syncState = SYNC_PENDING,
                remoteRevision = revision,
                deletedAtEpochMillis = resolutionTime,
            )
            dao.upsertPrivateBinderPlacement(losingRemote)
            queueRemoteResolution(dao, losingRemote, PRIVATE_SYNC_DELETE)
            return checkpoint
        }
        val tombstone = membership.copy(
            updatedAtEpochMillis = resolutionTime,
            syncState = SYNC_PENDING,
            deletedAtEpochMillis = resolutionTime,
        )
        dao.updatePrivateBinderPlacement(tombstone)
        queueRemoteResolution(dao, tombstone, PRIVATE_SYNC_DELETE)
    }
    val localPosition = remotePrivatePlacementPosition(dao, uid, binder.id, id, requestedPosition)
    val resolvedLocally = localPosition != requestedPosition
    val placement = PrivateBinderPlacementEntity(
        id = id,
        ownerUid = uid,
        binderId = binder.id,
        collectionEntryId = entry.id,
        slotPosition = localPosition,
        holoEffectId = remoteHoloEffectId,
        holoIntensity = remoteHoloIntensity,
        createdAtEpochMillis = document.getLong("createdAtClient")?.takeIf { it > 0L }
            ?: existing?.createdAtEpochMillis ?: updatedAt,
        updatedAtEpochMillis = updatedAt,
        syncState = if (resolvedLocally) SYNC_PENDING else SYNC_CLEAN,
        remoteRevision = revision,
        deletedAtEpochMillis = null,
    )
    dao.upsertPrivateBinderPlacement(placement)
    if (resolvedLocally) queueRemoteResolution(dao, placement, PRIVATE_SYNC_UPSERT)
    return checkpoint
}

private suspend fun applyRemoteLayout(
    dao: CardVaultDao,
    uid: String,
    document: DocumentSnapshot,
): PrivateRemoteApplyResult {
    val checkpoint = PrivateRemoteApplyResult.Checkpoint
    val entrySyncId = document.getString("entrySyncId") ?: return checkpoint
    if (entrySyncId != document.id || document.getString("ownerUid") != uid || !validRemoteId(entrySyncId)) return checkpoint
    if (dao.privateBinderSyncOutbox("$uid:$PRIVATE_SYNC_LAYOUT:$entrySyncId") != null) return checkpoint
    val revision = document.getLong("revision")?.takeIf { it > 0L } ?: return checkpoint
    val updatedAt = document.getLong("updatedAtClient")?.takeIf { it > 0L } ?: return checkpoint
    val visible = document.getBoolean("visibleInSetBinder") ?: return checkpoint
    val entry = dao.getCollectionEntryBySyncId(uid, entrySyncId)
        ?: return PrivateRemoteApplyResult.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, entrySyncId)
    if (entry.deletedAtEpochMillis != null) return checkpoint
    val existing = dao.collectionLayout(entry.id, uid)
    if (existing != null && existing.remoteRevision >= revision) return checkpoint
    dao.upsertCollectionLayout(
        CollectionLayoutEntity(entry.id, uid, visible, updatedAt, SYNC_CLEAN, revision),
    )
    return checkpoint
}

private suspend fun applyRemotePriceSelection(
    dao: CardVaultDao,
    uid: String,
    document: DocumentSnapshot,
): PrivateRemoteApplyResult {
    val checkpoint = PrivateRemoteApplyResult.Checkpoint
    val data = document.data ?: return checkpoint
    // Validate the whole envelope before requesting any dependency from a remote path.
    val parsed = parsePriceSelectionSidecar(data, uid, document.id, 0L) ?: return checkpoint
    if (dao.privateBinderSyncOutbox("$uid:$PRIVATE_SYNC_PRICE_SELECTION:${document.id}") != null) return checkpoint
    val entry = dao.getCollectionEntryBySyncId(uid, document.id)
        ?: return PrivateRemoteApplyResult.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, document.id)
    if (entry.deletedAtEpochMillis != null) return checkpoint
    val existing = dao.priceSelection(uid, document.id)
    if (existing != null && existing.remoteRevision >= parsed.remoteRevision) return checkpoint
    // Retain a newer complete snapshot even if the entry identity changed. Evaluation rejects it;
    // retaining its revision prevents an older local choice from being resurrected later.
    dao.upsertPriceSelection(parsed.copy(collectionEntryId = entry.id))
    return checkpoint
}

private suspend fun queueRemoteResolution(
    dao: CardVaultDao,
    placement: PrivateBinderPlacementEntity,
    operation: String,
) {
    val mutationId = UUID.randomUUID().toString()
    dao.upsertPrivateBinderSyncOutbox(
        PrivateBinderSyncOutboxEntity(
            entryKey = "${placement.ownerUid}:$PRIVATE_SYNC_PLACEMENT:${placement.id}",
            mutationId = mutationId,
            ownerUid = placement.ownerUid,
            recordType = PRIVATE_SYNC_PLACEMENT,
            recordId = placement.id,
            operation = operation,
            expectedRemoteRevision = placement.remoteRevision,
            updatedAtEpochMillis = placement.updatedAtEpochMillis,
            createdAtEpochMillis = System.currentTimeMillis(),
        ),
    )
}

private fun validRemoteId(value: String): Boolean = value.matches(Regex("[A-Za-z0-9._-]{1,128}"))

private fun validPrivateBinderAppearanceId(value: String): Boolean =
    value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}"))

private const val REMOTE_PULL_PAGE_SIZE = 200
private const val MAX_REMOTE_DOCUMENTS_PER_RUN = 1_000

private suspend fun <T> Task<T>.awaitPrivateBinderTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase private-binder task failed"))
    }
}
