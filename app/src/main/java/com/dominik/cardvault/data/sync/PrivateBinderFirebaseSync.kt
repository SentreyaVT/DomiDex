package com.dominik.cardvault.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
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
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.data.local.PrivateBinderSyncOutboxEntity
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
import kotlinx.coroutines.CancellationException
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
    MARK_PRICE_CONFLICT,
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
    data object DeferredLocal : PrivateRemoteApplyResult
    data object Invalid : PrivateRemoteApplyResult
    data class RetryDependency(
        val type: PrivateRemoteDependencyType,
        val id: String,
    ) : PrivateRemoteApplyResult
}

internal fun shouldRestorePrivateMutationAfterRemoteApply(result: PrivateRemoteApplyResult): Boolean =
    result != PrivateRemoteApplyResult.Checkpoint

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
    recordType: String? = null,
): PrivatePushCommitAction = when {
    currentMutationId == null -> PrivatePushCommitAction.NONE
    currentMutationId != sentMutationId && remoteWins -> PrivatePushCommitAction.KEEP_SUCCESSOR
    currentMutationId != sentMutationId -> PrivatePushCommitAction.REBASE_SUCCESSOR
    remoteWins && recordType == PRIVATE_SYNC_PRICE_SELECTION -> PrivatePushCommitAction.MARK_PRICE_CONFLICT
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
        // Previously the first guarded entitlement call resolved its default Firestore here.
        val firestore = runCatching { FirebaseFirestore.getInstance() }
            .getOrElse { return@withContext Result.retry() }
        runPrivateBinderSync(
            ownerUid = uid,
            sharingBinderId = sharingBinderId,
            auth = auth,
            firestore = firestore,
            database = { CardVaultDatabase.get(applicationContext) },
            appendPrivate = { owner -> PrivateBinderSyncScheduler.schedule(applicationContext, owner) },
            continueSharing = { owner, binder -> BinderSharingSyncScheduler.continueBatch(applicationContext, owner, binder) },
        )
    }
}

/**
 * The worker's actual authenticated sync pass, also callable with isolated SDK/Room instances.
 * No access predicate is injectable: Google ownership, server entitlements and session guards
 * remain inside this path. The database is opened only after those initial checks, as before.
 * Callbacks retain the existing scheduling decisions; invoking this function is not a test of
 * WorkManager constraints, input delivery or execution timing.
 */
internal suspend fun runPrivateBinderSync(
    ownerUid: String,
    sharingBinderId: String?,
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    database: () -> CardVaultDatabase,
    appendPrivate: (String) -> Unit,
    continueSharing: suspend (String, String) -> Unit,
): ListenableWorker.Result = PrivateBinderSyncRun(auth, firestore, database, appendPrivate, continueSharing)
    .run(ownerUid, sharingBinderId)

/** Per-invocation dependencies only; no global override and no independent test implementation. */
private class PrivateBinderSyncRun(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val databaseProvider: () -> CardVaultDatabase,
    private val appendPrivate: (String) -> Unit,
    private val continueSharing: suspend (String, String) -> Unit,
) {
    suspend fun run(uid: String, sharingBinderId: String?): Result = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext Result.success()
        if (sharingBinderId != null && !validSharingBinderId(sharingBinderId)) return@withContext Result.failure()
        suspend fun accessStillCurrent(): Boolean {
            val account = auth.currentVerifiedFirebaseEntitlementAccount(firestore, forceRefresh = false) ?: return false
            return account.uid == uid &&
                canonicalCollectionOwnerUid(auth.currentUser) == uid &&
                canUseFirebaseAppBackend(uid, canonicalCollectionOwnerUid(auth.currentUser), account.entitlements)
        }
        if (!runCatching { accessStillCurrent() }.getOrElse { return@withContext Result.retry() }) {
            return@withContext Result.success()
        }

        try {
            PrivatePullAuthSession.use(auth, uid) { session ->
                syncWithSession(uid, sharingBinderId, auth, session)
            }
        } catch (_: PrivatePullAccountChangedException) {
            // Keep durable work for the next valid session, including an A -> B -> A transition.
            Result.retry()
        }
    }

    private suspend fun syncWithSession(
        uid: String,
        sharingBinderId: String?,
        auth: FirebaseAuth,
        session: PrivatePullAuthSession,
    ): Result = withContext(Dispatchers.IO) {
        suspend fun accessStillCurrent(): Boolean {
            session.requireCurrent()
            val account = auth.currentVerifiedFirebaseEntitlementAccount(firestore, forceRefresh = false) ?: return false
            session.requireCurrent()
            return account.uid == uid &&
                canUseFirebaseAppBackend(uid, canonicalCollectionOwnerUid(auth.currentUser), account.entitlements)
        }
        session.requireCurrent()

        val database = databaseProvider()
        val dao = database.cardVaultDao()
        if (sharingBinderId != null && dao.pendingSharingCollectionOutbox(uid, sharingBinderId, 1).isNotEmpty()) {
            continueSharing(uid, sharingBinderId)
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
                database.withPrivateSyncSession(session::isCurrent) {
                    dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                }
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
                session.requireCurrent()
                firestore.runTransaction { transaction ->
                    session.requireCurrent()
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
                            session.requireCurrent()
                            transaction.set(reference, payload + ("revision" to nextRevision))
                            PrivatePushOutcome(nextRevision, remoteWins = false)
                        }
                    }
                }.awaitPrivateBinderTask(session)
            }.getOrElse { return@withContext Result.retry() }
            val remoteDocument = if (pushOutcome.remoteWins && mutation.recordType != PRIVATE_SYNC_PRICE_SELECTION) {
                runCatching { reference.get(Source.SERVER).awaitPrivateBinderTask(session) }
                    .getOrElse { return@withContext Result.retry() }
            } else {
                null
            }
            var retryRemoteApply = false
            database.withPrivateSyncSession(session::isCurrent) {
                val current = dao.privateBinderSyncOutbox(mutation.entryKey)
                when (privatePushCommitAction(pushOutcome.remoteWins, mutation.mutationId, current?.mutationId, mutation.recordType)) {
                    PrivatePushCommitAction.MARK_PRICE_CONFLICT -> {
                        markPriceSelectionConflict(dao, mutation, pushOutcome.revision)
                    }
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
                        if (remoteDocument == null) return@withPrivateSyncSession
                        // The remote apply guards against any pending local mutation. Remove only
                        // the mutation that was just proven current; restore it atomically if a
                        // local dependency has not arrived yet.
                        dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                        val applyResult = when (mutation.recordType) {
                            PRIVATE_SYNC_BINDER -> applyRemoteBinder(dao, uid, remoteDocument)
                            PRIVATE_SYNC_PLACEMENT -> applyRemotePlacement(dao, uid, remoteDocument)
                            PRIVATE_SYNC_LAYOUT -> applyRemoteLayout(dao, uid, remoteDocument)
                            PRIVATE_SYNC_PRICE_SELECTION -> applyRemotePriceSelection(dao, uid, remoteDocument)
                            else -> PrivateRemoteApplyResult.Invalid
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
                continueSharing(uid, sharingBinderId)
            }
            // No unrelated binder/layout pull on this foreground sharing path.
            return@withContext Result.success()
        }
        val pullFlight = try {
            privateFullPullFlights.tryRun(uid) { pullBoundedSnapshot(database, firestore, auth, uid, session) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(LOG_TAG, "Private-Binder-Abruf wird später wiederholt (${error.javaClass.simpleName}).")
            return@withContext Result.retry()
        }
        val pullResult = when (pullFlight) {
            is PrivateFullPullFlightResult.Ran -> pullFlight.value
            // The active pull may already have visited a recently changed area. Preserve this
            // request with WorkManager backoff; neither claim completion nor create a hot APPEND.
            PrivateFullPullFlightResult.OwnerBusy,
            PrivateFullPullFlightResult.CapacityReached,
            -> return@withContext Result.retry()
        }
        // The collection worker can finish while this worker performs its bounded remote pull.
        // A remembered dependency is not itself a failure: only a still-pending parent needs backoff.
        val parentStillPending = database.withPrivateSyncSession(session::isCurrent) {
            deferredPriceSelectionParentPending(dao, uid, deferredPriceSelections)
        }
        when (privateSyncContinuation(parentStillPending,
            pullResult == PrivateFullPullRunResult.MORE_DOCUMENTS,
            dao.pendingPrivateBinderSyncOutbox(uid, 1).isNotEmpty())) {
            PrivateSyncContinuation.RETRY -> return@withContext Result.retry()
            PrivateSyncContinuation.APPEND -> appendPrivate(uid)
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

// Shared only by FULL pulls. Mutations and the foreground sharing return stay outside this gate.
private val privateFullPullFlights = PrivateFullPullSingleFlight()

private suspend fun pullBoundedSnapshot(
    database: CardVaultDatabase,
    firestore: FirebaseFirestore,
    auth: FirebaseAuth,
    uid: String,
    session: PrivatePullAuthSession,
): PrivateFullPullRunResult {
    session.requireCurrent()
    val user = firestore.collection("users").document(uid)
    val dao = database.cardVaultDao()
    val ownerKey = UUID.nameUUIDFromBytes(uid.toByteArray(StandardCharsets.UTF_8)).toString()
    val phaseKey = "private-sync-pull-phase-$ownerKey"
    fun cursorKey(phase: Int) = "private-sync-pull-cursor-$ownerKey-$phase"
    val groups = listOf(
        RemotePullGroup("privateBinders") { document -> applyRemoteBinder(dao, uid, document) },
        RemotePullGroup("privateBinderPlacements") { document -> applyRemotePlacement(dao, uid, document) },
        RemotePullGroup("collectionLayouts") { document -> applyRemoteLayout(dao, uid, document) },
        RemotePullGroup("collectionPriceSelections") { document -> applyRemotePriceSelection(dao, uid, document) },
    )
    val port = object : PrivateFullPullPort {
        override suspend fun readPhase(): Int = database.withPrivateSyncSession(session::isCurrent) {
            dao.metadata(phaseKey)?.toIntOrNull() ?: 0
        }

        override suspend fun readCursor(phase: Int): String? = database.withPrivateSyncSession(session::isCurrent) {
            dao.metadata(cursorKey(phase))
        }

        override suspend fun queryPage(phase: Int, after: String?, limit: Int): List<PrivateFullPullRow> {
            val group = groups[phase]
            var query = user.collection(group.collectionName)
                .orderBy(FieldPath.documentId())
                .limit(limit.toLong())
            if (after != null) query = query.startAfter(after)
            session.requireCurrent()
            val documents = query.get(Source.SERVER).awaitPrivateBinderTask(session).documents
            return documents.map { document ->
                PrivateFullPullRow(document.id) {
                    database.withPrivateSyncSession(session::isCurrent) { group.apply(document) }
                }
            }
        }

        override suspend fun resolveDependency(dependency: PrivateRemoteApplyResult.RetryDependency) {
            resolveRemoteDependency(database, firestore, auth, uid, dependency, session)
        }

        override suspend fun saveCursor(phase: Int, documentId: String) {
            database.withPrivateSyncSession(session::isCurrent) {
                dao.putMetadata(AppMetadataEntity(cursorKey(phase), documentId))
            }
        }

        override suspend fun finishPhase(phase: Int) {
            database.withPrivateSyncSession(session::isCurrent) {
                dao.putMetadata(AppMetadataEntity(cursorKey(phase), ""))
                dao.putMetadata(AppMetadataEntity(phaseKey, (phase + 1).toString()))
            }
        }

        override suspend fun resetCycle() {
            database.withPrivateSyncSession(session::isCurrent) {
                dao.putMetadata(AppMetadataEntity(phaseKey, "0"))
            }
        }
    }
    return runPrivateFullPull(port, PrivateFullPullReadBudget(uid, MAX_REMOTE_LOGICAL_READS_PER_RUN), REMOTE_PULL_PAGE_SIZE)
}

private class RemotePullGroup(
    val collectionName: String,
    val apply: suspend (DocumentSnapshot) -> PrivateRemoteApplyResult,
)

private suspend fun resolveRemoteDependency(
    database: CardVaultDatabase,
    firestore: FirebaseFirestore,
    auth: FirebaseAuth,
    uid: String,
    dependency: PrivateRemoteApplyResult.RetryDependency,
    session: PrivatePullAuthSession,
) {
    session.requireCurrent()
    if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
        throw IllegalStateException("Private-Binder-Synchronisationskonto hat gewechselt.")
    }
    when (dependency.type) {
        PrivateRemoteDependencyType.BINDER -> {
            val document = firestore.document(privateBinderPath(uid, dependency.id))
                .get(Source.SERVER)
                .awaitPrivateBinderTask(session)
            if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
                throw IllegalStateException("Private-Binder-Synchronisationskonto hat gewechselt.")
            }
            if (document.exists()) database.withPrivateSyncSession(session::isCurrent) {
                applyRemoteBinder(database.cardVaultDao(), uid, document)
            }
        }
        PrivateRemoteDependencyType.COLLECTION_ENTRY -> {
            val document = firestore.document(collectionEntryPath(uid, dependency.id))
                .get(Source.SERVER)
                .awaitPrivateBinderTask(session)
            if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
                throw IllegalStateException("Private-Binder-Synchronisationskonto hat gewechselt.")
            }
            applyConfirmedPrivateDependency(
                document.exists(), document.metadata.isFromCache, document.metadata.hasPendingWrites(),
            ) {
                database.withPrivateSyncSession(session::isCurrent) {
                    applyRemoteDocumentToRoom(database, uid, document) {
                        session.isCurrent()
                    }
                }
            }
        }
    }
}

private suspend fun applyRemoteRecord(
    dao: CardVaultDao,
    uid: String,
    recordType: String,
    document: DocumentSnapshot,
): PrivateRemoteApplyResult {
    val data = document.data ?: return PrivateRemoteApplyResult.Invalid
    return when (val result = applyPrivateRemoteRecord(
        dao, uid, recordType, document.id, data,
        confirmedServer = !document.metadata.isFromCache && !document.metadata.hasPendingWrites(),
    )) {
        PrivateRemoteApplyOutcome.Completed -> PrivateRemoteApplyResult.Checkpoint
        PrivateRemoteApplyOutcome.DeferredLocal -> PrivateRemoteApplyResult.DeferredLocal
        PrivateRemoteApplyOutcome.Invalid -> PrivateRemoteApplyResult.Invalid
        is PrivateRemoteApplyOutcome.RetryDependency -> PrivateRemoteApplyResult.RetryDependency(result.type, result.id)
    }
}

private suspend fun applyRemoteBinder(dao: CardVaultDao, uid: String, document: DocumentSnapshot) =
    applyRemoteRecord(dao, uid, PRIVATE_SYNC_BINDER, document)

private suspend fun applyRemotePlacement(dao: CardVaultDao, uid: String, document: DocumentSnapshot) =
    applyRemoteRecord(dao, uid, PRIVATE_SYNC_PLACEMENT, document)

private suspend fun applyRemoteLayout(dao: CardVaultDao, uid: String, document: DocumentSnapshot) =
    applyRemoteRecord(dao, uid, PRIVATE_SYNC_LAYOUT, document)

private suspend fun applyRemotePriceSelection(dao: CardVaultDao, uid: String, document: DocumentSnapshot) =
    applyRemoteRecord(dao, uid, PRIVATE_SYNC_PRICE_SELECTION, document)

private const val REMOTE_PULL_PAGE_SIZE = 200
private const val MAX_REMOTE_LOGICAL_READS_PER_RUN = 1_000

internal suspend fun <T> Task<T>.awaitPrivateBinderTask(session: PrivatePullAuthSession): T {
    session.requireCurrent()
    val result = suspendCancellableCoroutine<T> { continuation ->
        addOnCompleteListener { task ->
            if (!continuation.isActive) return@addOnCompleteListener
            if (task.isSuccessful) continuation.resumeWith(kotlin.Result.success(task.result))
            else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase private-binder task failed"))
        }
    }
    // An already-sent transaction may commit remotely. Preserve its idempotent outbox entry
    // instead of ACKing that result into a now-stale local account session.
    session.requireCurrent()
    return result
}
