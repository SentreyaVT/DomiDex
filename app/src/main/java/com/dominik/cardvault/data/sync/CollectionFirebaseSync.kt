package com.dominik.cardvault.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ListenableWorker
import androidx.work.ListenableWorker.Result
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dominik.cardvault.auth.FirebaseEntitlements
import com.dominik.cardvault.auth.canUseFirebaseAppBackend
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CardVaultDao
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.local.CollectionSyncProjection
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_CONFLICT
import com.dominik.cardvault.data.local.SYNC_ERROR
import com.dominik.cardvault.data.local.SYNC_PENDING
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentVerifiedFirebaseEntitlementAccount
import com.dominik.cardvault.auth.effectiveFirebaseEntitlements
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.Source
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val COLLECTION_SYNC_OPERATION_UPSERT = "UPSERT"
internal const val COLLECTION_SYNC_OPERATION_DELETE = "DELETE"
private const val REMOTE_REVISION_CONFLICT_PREFIX = "remote-revision-"
private const val REMOTE_DELETED_CONFLICT_PREFIX = "remote-deleted-revision-"

/** One source of truth shared by local validation, remote decoding and Firestore rules. */
internal object CollectionSyncLimits {
    const val NAME = 200
    const val SET_ID = 120
    const val SET_NAME = 200
    const val COLLECTOR_NUMBER = 80
    const val LANGUAGE = 30
    const val FINISH = 80
    const val VARIANT = 80
    const val RARITY = 80
    const val QUANTITY = 10_000
    const val CONDITION = 120
    const val NOTES = 4_000
    const val CURRENCY = 8
    const val EXTERNAL_SOURCE = 80
    const val URL = 2_000
    const val EFFECT = 80
    const val BINDER_ID = 160
    const val BINDER_NAME = 200
    const val SOURCE_ID = 120

    fun requireText(label: String, value: String?, maximum: Int, required: Boolean = false) {
        if (required) require(!value.isNullOrBlank()) { "$label darf nicht leer sein." }
        require(value == null || value.length <= maximum) { "$label darf höchstens $maximum Zeichen enthalten." }
    }

    fun requireQuantity(quantity: Int) {
        require(quantity in 1..QUANTITY) { "Die Anzahl muss zwischen 1 und $QUANTITY liegen." }
    }

    fun requireValid(entry: CollectionSyncProjection, mutation: CollectionSyncOutboxEntity) {
        require(entry.ownerUid == mutation.ownerUid && entry.syncId == mutation.syncId) { "Synchronisationsidentität stimmt nicht überein." }
        require(entry.syncId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Synchronisations-ID ist ungültig." }
        require(mutation.mutationId.matches(Regex("[A-Za-z0-9._-]{1,128}"))) { "Mutations-ID ist ungültig." }
        requireText("Kartenname", entry.name, NAME, required = true)
        requireText("Set-ID", entry.setId, SET_ID)
        requireText("Setname", entry.setName, SET_NAME, required = true)
        requireText("Kartennummer", entry.collectorNumber, COLLECTOR_NUMBER)
        requireText("Sprache", entry.language, LANGUAGE)
        requireText("Finish", entry.finish, FINISH)
        requireText("Variante", entry.variant, VARIANT)
        requireText("Seltenheit", entry.rarity, RARITY)
        requireQuantity(entry.quantity)
        requireText("Zustand", entry.condition, CONDITION)
        requireText("Notiz", entry.notes, NOTES)
        requireText("Währung", entry.acquisitionCurrency, CURRENCY)
        requireText("Produktquelle", entry.externalProductSource, EXTERNAL_SOURCE)
        requireText("Bild-URL", entry.imageUrl, URL)
        requireText("Effekt", entry.effectOverride, EFFECT)
        require((entry.sourceSetId == null) == (entry.sourceCardId == null)) { "Quellen-IDs müssen gemeinsam vorhanden sein." }
        entry.sourceSetId?.let { require(it.matches(Regex("[a-z0-9._-]{1,$SOURCE_ID}"))) { "Quellen-Set-ID ist ungültig." } }
        entry.sourceCardId?.let { require(it.matches(Regex("[a-z0-9._-]{1,$SOURCE_ID}"))) { "Quellen-Karten-ID ist ungültig." } }
        requireText("Binder-ID", entry.binderId, BINDER_ID, required = true)
        requireText("Binder-Set-ID", entry.binderSetKey, BINDER_ID, required = true)
        requireText("Bindername", entry.binderName, BINDER_NAME, required = true)
        requireText("Binderbild", entry.binderHeroAsset, URL)
    }
}

internal fun collectionOwnerForUid(uid: String?): String = uid?.takeIf(String::isNotBlank) ?: GUEST_OWNER_UID
internal fun collectionEntryPath(uid: String, syncId: String): String = "users/$uid/collectionEntries/$syncId"
internal fun collectionSyncWorkName(uid: String): String =
    "domidex-collection-sync-${UUID.nameUUIDFromBytes(uid.toByteArray(StandardCharsets.UTF_8))}"
internal fun shouldRunCollectionSync(expectedUid: String?, currentUid: String?): Boolean =
    !expectedUid.isNullOrBlank() && expectedUid == currentUid
internal fun shouldRunEntitledCollectionSync(
    expectedUid: String?,
    currentUid: String?,
    entitlements: FirebaseEntitlements,
): Boolean = shouldRunCollectionSync(expectedUid, currentUid) &&
    canUseFirebaseAppBackend(expectedUid, currentUid, entitlements)
internal fun shouldScheduleCollectionSyncContinuation(processed: Int, limit: Int, hasPending: Boolean): Boolean =
    processed >= limit && hasPending

/** Room synchronizes observer triggers; collection and cancellation stay on the caller's IO scope. */
internal fun CoroutineScope.observeCollectionOutbox(database: CardVaultDatabase, onChange: () -> Unit): Job = launch {
    database.invalidationTracker.createFlow("collection_sync_outbox", "private_binder_sync_outbox")
        .collect { onChange() }
}

internal data class CollectionCloudQueueState(val pending: Boolean, val error: Boolean)

/** The same owner-scoped DAO aggregation used by the authenticated collection status. */
internal suspend fun readCollectionCloudQueueState(dao: CardVaultDao, uid: String): CollectionCloudQueueState {
    val pending = dao.pendingSyncOutbox(uid, 1).isNotEmpty() || dao.pendingPrivateBinderSyncOutbox(uid, 1).isNotEmpty()
    val error = dao.erroredSyncOutbox(uid).isNotEmpty() || dao.conflictedSyncOutbox(uid).isNotEmpty() ||
        dao.hasConflictedPriceSelectionOutbox(uid)
    return CollectionCloudQueueState(pending, error)
}

internal fun shouldResumeServerReread(entryState: String, outboxState: String): Boolean =
    entryState == SYNC_CLEAN && outboxState == SYNC_PENDING
internal fun remoteConflictReason(revision: Long): String = "$REMOTE_REVISION_CONFLICT_PREFIX$revision"
internal fun remoteDeletedConflictReason(revision: Long): String = "$REMOTE_DELETED_CONFLICT_PREFIX$revision"
internal fun parseRemoteConflictRevision(reason: String?): Long? = reason
    ?.takeIf { it.startsWith(REMOTE_REVISION_CONFLICT_PREFIX) }
    ?.removePrefix(REMOTE_REVISION_CONFLICT_PREFIX)
    ?.toLongOrNull()
    ?.takeIf { it >= 0L }
internal fun shouldApplyRemote(localState: String, localRevision: Long, remoteRevision: Long): Boolean =
    localState == SYNC_CLEAN && remoteRevision > localRevision
internal fun shouldApplyCloudResolutionSnapshot(localRevision: Long, remoteRevision: Long): Boolean =
    remoteRevision >= localRevision

/**
 * Owns the authenticated collection namespace. Guest rows and Firebase rows never share a query.
 * The listener mirrors clean remote snapshots into Room; Room remains the only UI source.
 */
class FirebaseCollectionSession internal constructor(
    private val database: CardVaultDatabase,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val scheduling: CollectionSessionScheduling,
    private val connectivity: CollectionConnectivitySource = NoCollectionConnectivitySource,
) : AutoCloseable {
    constructor(
        context: Context,
        database: CardVaultDatabase,
        auth: FirebaseAuth = FirebaseAuth.getInstance(),
        firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    ) : this(database, auth, firestore, DefaultCollectionSessionScheduling(context),
        AndroidCollectionConnectivitySource(context))

    private val mutableOwnerUid = MutableStateFlow(collectionOwnerForUid(canonicalCollectionOwnerUid(auth.currentUser)))
    val ownerUid: StateFlow<String> = mutableOwnerUid
    private val localDataOwnerSession = com.dominik.cardvault.data.backup.LocalDataOwnerSession(mutableOwnerUid.value)
    private val mutableCloudState = MutableStateFlow(CollectionCloudState(
        if (canonicalCollectionOwnerUid(auth.currentUser) == null) CollectionCloudStatus.SIGNED_OUT else CollectionCloudStatus.CHECKING,
        canonicalCollectionOwnerUid(auth.currentUser),
    ))
    val cloudState: StateFlow<CollectionCloudState> = mutableCloudState
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val entitlementLock = Any()
    private var remoteListener: ListenerRegistration? = null
    private var accessListener: ListenerRegistration? = null
    private var serverSnapshot = false
    private var snapshotError = false
    @Volatile private var started = false
    @Volatile private var closed = false
    @Volatile private var entitlementGeneration = 0L
    @Volatile private var entitledOwnerUid: String? = null
    private val reconnectGate = CollectionReconnectGate()
    private var connectivityRegistration: AutoCloseable? = null

    private val idTokenListener = FirebaseAuth.IdTokenListener(::handleIdTokenState)

    private fun handleIdTokenState(changedAuth: FirebaseAuth): Unit = synchronized(entitlementLock) stateChange@ {
        if (!started || closed) return@stateChange
        val user = changedAuth.currentUser
        val uid = canonicalCollectionOwnerUid(user)
        localDataOwnerSession.observeOwner(collectionOwnerForUid(uid))
        mutableOwnerUid.value = collectionOwnerForUid(uid)
        val (previousUid, generation) = synchronized(entitlementLock) {
            val previousUid = entitledOwnerUid
            entitlementGeneration += 1L
            reconnectGate.started(entitlementGeneration)
            entitledOwnerUid = null
            detachRemoteListenerLocked()
            accessListener?.remove()
            accessListener = null
            serverSnapshot = false
            snapshotError = false
            mutableCloudState.value = CollectionCloudState(
                if (uid == null) CollectionCloudStatus.SIGNED_OUT else CollectionCloudStatus.CHECKING, uid,
            )
            previousUid to entitlementGeneration
        }
        if (uid == null) scheduling.cancelAllPrivateBinderPeriodic()
        else if (previousUid != null && previousUid != uid) {
            scheduling.cancelPrivateBinderPeriodic(previousUid)
        }
        if (uid == null || user == null) {
            reconnectGate.clear()
            return@stateChange
        }
        scope.launch {
            val account = try { auth.currentVerifiedFirebaseEntitlementAccount(firestore) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    synchronized(entitlementLock) {
                        if (started && !closed && entitlementGeneration == generation &&
                            mutableOwnerUid.value == uid && canonicalCollectionOwnerUid(auth.currentUser) == uid) {
                            val denied = failure is FirebaseFirestoreException &&
                                failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED
                            mutableCloudState.value = CollectionCloudState(
                                if (denied) CollectionCloudStatus.NO_ACCESS else CollectionCloudStatus.LOCAL_ONLY, uid,
                            )
                            if (denied) reconnectGate.clear()
                            else if (reconnectGate.consumeAfterTransientFailure(generation)) handleIdTokenState(auth)
                        }
                    }
                    return@launch
                } ?: return@launch
            if (!account.entitlements.canUseAppBackend) {
                synchronized(entitlementLock) {
                    if (started && !closed && entitlementGeneration == generation && mutableOwnerUid.value == uid) {
                        scheduling.cancelPrivateBinderPeriodic(uid)
                        reconnectGate.clear()
                        mutableCloudState.value = CollectionCloudState(CollectionCloudStatus.NO_ACCESS, uid)
                    }
                }
                return@launch
            }
            synchronized(entitlementLock) {
                if (!started || closed || entitlementGeneration != generation) return@synchronized
                if (canonicalCollectionOwnerUid(auth.currentUser) != uid || account.uid != uid) return@synchronized
                reconnectGate.clear()
                entitledOwnerUid = uid
                mutableCloudState.value = CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, uid, accessConfirmed = true)
                attachRemoteListenerLocked(uid)
                accessListener = firestore.collection("accountAccess").document(uid)
                    .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                        synchronized(entitlementLock) {
                            if (entitlementGeneration != generation || mutableOwnerUid.value != uid) return@addSnapshotListener
                            if (error != null || (snapshot != null && !snapshot.metadata.isFromCache && !effectiveFirebaseEntitlements(account.entitlements, snapshot.data.orEmpty()).canUseAppBackend)) {
                                entitledOwnerUid = null
                                detachRemoteListenerLocked()
                                scheduling.cancelPrivateBinderPeriodic(uid)
                                mutableCloudState.value = CollectionCloudState(
                                    if (error == null || error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                        CollectionCloudStatus.NO_ACCESS else CollectionCloudStatus.LOCAL_ONLY, uid,
                                )
                            }
                        }
                    }
                // Keep effect admission ordered with account transitions and close.
                requestSync()
                // Wake durable evidence queued while this same entitled account was offline.
                scheduling.scheduleScanKnowledge()
            }
        }
    }

    fun start() {
        synchronized(entitlementLock) {
            if (started || closed) return
            started = true
            scope.observeCollectionOutbox(database, ::refreshCloudState)
            // Keep registration ordered with close when Activity teardown races IO startup.
            handleIdTokenState(auth)
            auth.addIdTokenListener(idTokenListener)
        }
        // Never call the external observer/close while holding entitlementLock:
        // callbacks may already be waiting for that lock on a network thread.
        val registration = try {
            connectivity.observe(::handleConnectivity)
        } catch (failure: RuntimeException) {
            android.util.Log.w(LOG_TAG, "Network recheck observer unavailable: ${failure.javaClass.simpleName}")
            return
        }
        val retained = synchronized(entitlementLock) {
            if (closed || !started) false else {
                connectivityRegistration = registration
                true
            }
        }
        if (!retained) registration.close()
    }

    private fun handleConnectivity(usable: Boolean) = synchronized(entitlementLock) {
        if (!started || closed) return@synchronized
        if (!reconnectGate.observe(usable)) return@synchronized
        val uid = canonicalCollectionOwnerUid(auth.currentUser) ?: return@synchronized
        val state = mutableCloudState.value
        if (mutableOwnerUid.value != uid || state.ownerUid != uid || state.accessConfirmed ||
            entitledOwnerUid != null || state.status != CollectionCloudStatus.LOCAL_ONLY) return@synchronized
        // CHECKING hints stay in the gate; a transient completion consumes one newer edge.
        handleIdTokenState(auth)
    }

    fun requestSync() = synchronized(entitlementLock) {
        if (!started || closed) return@synchronized
        val uid = entitledOwnerUid
        if (uid == null) {
            if (started && canonicalCollectionOwnerUid(auth.currentUser) != null) handleIdTokenState(auth)
            return@synchronized
        }
        if (canonicalCollectionOwnerUid(auth.currentUser) != uid || mutableOwnerUid.value != uid) return@synchronized
        scheduling.scheduleCollection(uid, userInitiated = true)
        // Separate bounded worker/outbox: no second full-collection listener is introduced.
        scheduling.schedulePrivateBinder(uid, userInitiated = true)
        scheduling.ensurePrivateBinderPeriodic(uid)
        refreshCloudState()
    }

    private fun refreshCloudState() {
        val uid = synchronized(entitlementLock) { entitledOwnerUid } ?: return
        scope.launch {
            val queue = readCollectionCloudQueueState(database.cardVaultDao(), uid)
            synchronized(entitlementLock) {
                if (started && entitledOwnerUid == uid && mutableOwnerUid.value == uid) {
                    mutableCloudState.value = CollectionCloudState(
                        collectionCloudStatus(false, true, false, serverSnapshot, queue.pending, queue.error || snapshotError), uid, true,
                    )
                }
            }
        }
    }

    private fun detachRemoteListenerLocked() {
        remoteListener?.remove()
        remoteListener = null
    }

    private fun attachRemoteListenerLocked(uid: String) {
        detachRemoteListenerLocked()
        if (entitledOwnerUid != uid) return
        val generation = entitlementGeneration
        remoteListener = firestore.collection("users").document(uid).collection("collectionEntries")
            .addSnapshotListener(MetadataChanges.INCLUDE) { snapshot, error ->
                if (entitlementGeneration != generation || mutableOwnerUid.value != uid || entitledOwnerUid != uid) return@addSnapshotListener
                if (error != null) {
                    Log.w(LOG_TAG, "Collection listener unavailable (${error.code}).")
                    synchronized(entitlementLock) {
                        if (entitlementGeneration != generation || entitledOwnerUid != uid) return@addSnapshotListener
                        snapshotError = true
                        if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                            entitledOwnerUid = null
                            detachRemoteListenerLocked()
                            mutableCloudState.value = CollectionCloudState(CollectionCloudStatus.NO_ACCESS, uid)
                        }
                    }
                    refreshCloudState()
                    return@addSnapshotListener
                }
                synchronized(entitlementLock) {
                    serverSnapshot = snapshot != null && !snapshot.metadata.isFromCache && !snapshot.metadata.hasPendingWrites()
                    snapshotError = false
                }
                snapshot?.documentChanges
                    ?.filter { it.type != DocumentChange.Type.REMOVED }
                    ?.forEach { change ->
                        scope.launchSafely {
                            applyRemoteDocumentToRoom(database, uid, change.document) {
                                entitlementGeneration == generation && mutableOwnerUid.value == uid && entitledOwnerUid == uid
                            }
                        }
                    }
                refreshCloudState()
            }
    }

    private suspend fun applyRemoteDocument(uid: String, document: DocumentSnapshot) {
        if (mutableOwnerUid.value != uid) return
        val remote = RemoteCollectionEntry.from(document, uid) ?: return
        val dao = database.cardVaultDao()
        database.withTransaction {
            val local = dao.getCollectionEntryBySyncId(uid, remote.syncId)
            if (local != null) {
                if (!shouldApplyRemote(local.syncState, local.remoteRevision, remote.revision)) return@withTransaction
                dao.applyCleanRemoteEntry(
                    ownerUid = uid,
                    syncId = remote.syncId,
                    quantity = remote.quantity,
                    condition = remote.condition,
                    acquisitionPriceMinor = remote.acquisitionPriceMinor,
                    acquisitionCurrency = remote.acquisitionCurrency,
                    acquisitionDateEpochDay = remote.acquisitionDateEpochDay,
                    notes = remote.notes,
                    externalProductSource = remote.externalProductSource,
                    externalProductId = remote.externalProductId,
                    sourceSetId = remote.sourceSetId,
                    sourceCardId = remote.sourceCardId,
                    binderId = remote.binderId,
                    updatedAtEpochMillis = remote.updatedAtEpochMillis,
                    remoteRevision = remote.revision,
                    deletedAtEpochMillis = remote.deletedAtEpochMillis,
                    finish = remote.finish,
                    variant = remote.variant,
                    effectOverride = remote.effectOverride,
                )
            } else {
                val now = System.currentTimeMillis()
                // Firestore metadata is owner-controlled, while catalog_cards is global. Never
                // let a remote document claim/overwrite a canonical TCGdex catalog id shared by
                // another account; source identity remains isolated on the owner entry itself.
                val localCardId = "cloud-${UUID.nameUUIDFromBytes("$uid:${remote.syncId}".toByteArray(StandardCharsets.UTF_8))}"
                dao.insertCatalogCards(listOf(
                    CatalogCardEntity(
                        id = localCardId,
                        tcg = "POKEMON",
                        setId = remote.setId,
                        setName = remote.setName,
                        name = remote.name,
                        collectorNumber = remote.collectorNumber,
                        language = remote.language,
                        finish = remote.finish,
                        variant = remote.variant,
                        createdAtEpochMillis = now,
                        externalSource = null,
                        imageUrl = remote.imageUrl,
                        rarity = remote.rarity,
                    ),
                ))
                dao.insertCollectionEntry(
                    CollectionEntryEntity(
                        cardId = localCardId,
                        quantity = remote.quantity,
                        condition = remote.condition,
                        acquisitionPriceMinor = remote.acquisitionPriceMinor,
                        acquisitionCurrency = remote.acquisitionCurrency,
                        acquisitionDateEpochDay = remote.acquisitionDateEpochDay,
                        notes = remote.notes,
                        frontPhotoUri = null,
                        backPhotoUri = null,
                        createdAtEpochMillis = remote.updatedAtEpochMillis,
                        updatedAtEpochMillis = remote.updatedAtEpochMillis,
                        externalProductSource = remote.externalProductSource,
                        externalProductId = remote.externalProductId,
                        binderId = remote.binderId,
                        ownerUid = uid,
                        syncId = remote.syncId,
                        syncState = SYNC_CLEAN,
                        remoteRevision = remote.revision,
                        deletedAtEpochMillis = remote.deletedAtEpochMillis,
                        finishOverride = remote.finish,
                        variantOverride = remote.variant,
                        effectOverride = remote.effectOverride,
                        sourceSetId = remote.sourceSetId,
                        sourceCardId = remote.sourceCardId,
                    ),
                )
            }
        }
    }

    /**
     * Explicit Cloud-wins resolution. Every conflicted row is fetched from the server first.
     * Offline/auth failures or a concurrently changed outbox leave that conflict untouched.
     */
    suspend fun keepCloudVersionsForConflicts(): Int {
        val uid = entitledOwnerUid ?: return 0
        if (canonicalCollectionOwnerUid(auth.currentUser) != uid) return 0
        // Capture before dispatch: waiting for an IO thread is already a suspension.
        val actionCurrent = captureConflictSession(uid)
        return withContext(Dispatchers.IO) {
            if (!actionCurrent()) throw PrivatePullAccountChangedException()
            val priceResolved = resolvePriceSelectionConflicts(uid, keepLocal = false)
            if (!actionCurrent()) return@withContext priceResolved
            val dao = database.cardVaultDao()
            var resolved = 0
            dao.conflictedSyncOutbox(uid).forEach { conflict ->
                if (!actionCurrent()) return@withContext resolved + priceResolved
                val document = readCollectionCloudConflict(actionCurrent) {
                    firestore.document(collectionEntryPath(uid, conflict.syncId))
                        .get(Source.SERVER)
                        .awaitSyncTask()
                } ?: return@forEach
                if (!document.exists() || !actionCurrent()) return@forEach
                val firstRemote = RemoteCollectionEntry.from(document, uid) ?: return@forEach
                val openedForCloud = openCollectionCloudConflict(database, uid, conflict, firstRemote, actionCurrent)
                if (!openedForCloud || !actionCurrent()) return@forEach

                // A listener event can be delivered only once. Re-read after CLEAN so a revision that
                // arrived between the first read and the Room state transition cannot be lost.
                val latestDocument = readCollectionCloudConflict(actionCurrent) {
                    firestore.document(collectionEntryPath(uid, conflict.syncId))
                        .get(Source.SERVER)
                        .awaitSyncTask()
                } ?: return@forEach
                if (!latestDocument.exists() || !actionCurrent()) return@forEach
                val latestRemote = RemoteCollectionEntry.from(latestDocument, uid) ?: return@forEach
                val applied = finishCollectionCloudConflict(database, uid, conflict, latestRemote, actionCurrent)
                resolved += applied
            }
            resolved + priceResolved
        }
    }

    suspend fun keepLocalPriceSelectionConflicts(ownerUid: String): Int = resolvePriceSelectionConflicts(ownerUid, keepLocal = true)

    /** Guard the newly suspending combined Settings action, including A -> B -> A/token refresh. */
    internal fun captureConflictSession(uid: String): () -> Boolean {
        val generation = entitlementGeneration
        return {
            !closed && entitledOwnerUid == uid && entitlementGeneration == generation &&
                canonicalCollectionOwnerUid(auth.currentUser) == uid
        }
    }

    /** Local backup/feedback must work for Guest and offline accounts without cloud access. */
    internal fun captureLocalDataSession(ownerUid: String): () -> Boolean {
        val current = localDataOwnerSession.capture(ownerUid)
        return {
            !closed && current() &&
                collectionOwnerForUid(canonicalCollectionOwnerUid(auth.currentUser)) == ownerUid
        }
    }

    /** Existing explicit Settings decisions also cover price sidecars, with a per-action auth lease. */
    private suspend fun resolvePriceSelectionConflicts(uid: String, keepLocal: Boolean): Int {
        if (entitledOwnerUid != uid || canonicalCollectionOwnerUid(auth.currentUser) != uid) return 0
        // Capture before dispatch AND DAO completion: A -> B -> A must not give an old decision
        // a new lease simply because the UID matches again.
        val actionCurrent = captureConflictSession(uid)
        return withContext(Dispatchers.IO) {
            if (!actionCurrent()) throw PrivatePullAccountChangedException()
            val conflicts = guardedConflictActionRead(actionCurrent) {
                database.cardVaultDao().conflictedPriceSelectionOutbox(uid)
            }
            if (conflicts.isEmpty()) return@withContext 0
            if (!actionCurrent()) throw PrivatePullAccountChangedException()
            PrivatePullAuthSession.use(auth, uid) { session ->
                var resolved = 0
                fun current() = actionCurrent() && session.isCurrent() && entitledOwnerUid == uid
                for (conflict in conflicts) {
                    session.requireCurrent()
                    if (!current()) throw PrivatePullAccountChangedException()
                    val applied = if (keepLocal) {
                        keepLocalPriceSelectionConflict(database, conflict, UUID.randomUUID().toString(),
                            System.currentTimeMillis(), ::current)
                    } else {
                        val document = try {
                            firestore.document(collectionPriceSelectionPath(uid, conflict.recordId))
                                .get(Source.SERVER).awaitPrivateBinderTask(session)
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: PrivatePullAccountChangedException) {
                            throw error
                        } catch (_: Exception) {
                            continue // Offline/permission failures retain the exact conflict.
                        }
                        keepCloudPriceSelectionConflict(database, conflict, document.data.orEmpty(),
                            document.exists() && !document.metadata.isFromCache && !document.metadata.hasPendingWrites(), ::current)
                    }
                    if (applied) resolved++
                }
                resolved
            }
        }
    }

    override fun close() {
        val networkRegistration = synchronized(entitlementLock) {
            closed = true
            started = false
            localDataOwnerSession.close()
            entitlementGeneration += 1L
            entitledOwnerUid = null
            reconnectGate.clear()
            detachRemoteListenerLocked()
            accessListener?.remove()
            accessListener = null
            connectivityRegistration.also { connectivityRegistration = null }
        }
        try {
            networkRegistration?.close()
        } finally {
            auth.removeIdTokenListener(idTokenListener)
            scope.cancel()
        }
    }

    private companion object { const val LOG_TAG = "DomiDexCollectionSync" }
}

/** Shared listener/worker apply path. The owner gate is checked before and inside the transaction. */
internal suspend fun applyRemoteDocumentToRoom(
    database: CardVaultDatabase,
    uid: String,
    document: DocumentSnapshot,
    ownerIsCurrent: () -> Boolean,
) {
    if (!ownerIsCurrent()) return
    val remote = RemoteCollectionEntry.from(document, uid) ?: return
    applyRemoteCollectionEntryToRoom(database, uid, remote, ownerIsCurrent)
}

/** SDK decoding stays at the boundary; listeners and worker rereads share the same Room apply. */
internal suspend fun applyRemoteCollectionEntryToRoom(
    database: CardVaultDatabase,
    uid: String,
    remote: RemoteCollectionEntry,
    ownerIsCurrent: () -> Boolean,
) {
    val dao = database.cardVaultDao()
    database.withTransaction {
        if (!ownerIsCurrent()) return@withTransaction
        val local = dao.getCollectionEntryBySyncId(uid, remote.syncId)
        if (local != null) {
            if (!shouldApplyRemote(local.syncState, local.remoteRevision, remote.revision)) return@withTransaction
            dao.applyCleanRemoteEntry(
                ownerUid = uid,
                syncId = remote.syncId,
                quantity = remote.quantity,
                condition = remote.condition,
                acquisitionPriceMinor = remote.acquisitionPriceMinor,
                acquisitionCurrency = remote.acquisitionCurrency,
                acquisitionDateEpochDay = remote.acquisitionDateEpochDay,
                notes = remote.notes,
                externalProductSource = remote.externalProductSource,
                externalProductId = remote.externalProductId,
                sourceSetId = remote.sourceSetId,
                sourceCardId = remote.sourceCardId,
                binderId = remote.binderId,
                updatedAtEpochMillis = remote.updatedAtEpochMillis,
                remoteRevision = remote.revision,
                deletedAtEpochMillis = remote.deletedAtEpochMillis,
                finish = remote.finish,
                variant = remote.variant,
                effectOverride = remote.effectOverride,
            )
        } else {
            val now = System.currentTimeMillis()
            val localCardId = "cloud-${UUID.nameUUIDFromBytes("$uid:${remote.syncId}".toByteArray(StandardCharsets.UTF_8))}"
            dao.insertCatalogCards(listOf(
                CatalogCardEntity(
                    id = localCardId,
                    tcg = "POKEMON",
                    setId = remote.setId,
                    setName = remote.setName,
                    name = remote.name,
                    collectorNumber = remote.collectorNumber,
                    language = remote.language,
                    finish = remote.finish,
                    variant = remote.variant,
                    createdAtEpochMillis = now,
                    externalSource = null,
                    imageUrl = remote.imageUrl,
                    rarity = remote.rarity,
                ),
            ))
            dao.insertCollectionEntry(
                CollectionEntryEntity(
                    cardId = localCardId,
                    quantity = remote.quantity,
                    condition = remote.condition,
                    acquisitionPriceMinor = remote.acquisitionPriceMinor,
                    acquisitionCurrency = remote.acquisitionCurrency,
                    acquisitionDateEpochDay = remote.acquisitionDateEpochDay,
                    notes = remote.notes,
                    frontPhotoUri = null,
                    backPhotoUri = null,
                    createdAtEpochMillis = remote.updatedAtEpochMillis,
                    updatedAtEpochMillis = remote.updatedAtEpochMillis,
                    externalProductSource = remote.externalProductSource,
                    externalProductId = remote.externalProductId,
                    binderId = remote.binderId,
                    ownerUid = uid,
                    syncId = remote.syncId,
                    syncState = SYNC_CLEAN,
                    remoteRevision = remote.revision,
                    deletedAtEpochMillis = remote.deletedAtEpochMillis,
                    finishOverride = remote.finish,
                    variantOverride = remote.variant,
                    effectOverride = remote.effectOverride,
                    sourceSetId = remote.sourceSetId,
                    sourceCardId = remote.sourceCardId,
                ),
            )
        }
    }
}

internal object CollectionSyncScheduler {
    internal const val INPUT_OWNER_UID = "ownerUid"
    fun schedule(
        context: Context,
        ownerUid: String? = canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser),
        userInitiated: Boolean = false,
    ) {
        if (ownerUid.isNullOrBlank()) return
        // Per-owner chains prevent account B from replacing account A's work. APPEND_OR_REPLACE
        // also guarantees a wake-up queued during a running worker is not silently dropped.
        WorkManager.getInstance(context).enqueueUniqueWork(
            collectionSyncWorkName(ownerUid),
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            request(ownerUid, userInitiated),
        )
    }

    internal fun request(ownerUid: String, userInitiated: Boolean, sdk: Int = Build.VERSION.SDK_INT): OneTimeWorkRequest {
        val request = OneTimeWorkRequestBuilder<CollectionSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(INPUT_OWNER_UID to ownerUid))
        // On Android 12+, prioritize fresh local edits while retaining durable work if quota
        // is exhausted. Older versions would require a foreground-service notification.
        if (userInitiated && sdk >= 31) {
            request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return request.build()
    }
}

/** Pushes coalesced absolute snapshots with optimistic revisions; retries are idempotent. */
class CollectionSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val auth = FirebaseAuth.getInstance()
        val uid = inputData.getString(CollectionSyncScheduler.INPUT_OWNER_UID)
            ?.takeIf(String::isNotBlank)
            ?: return@withContext Result.success()
        val sharingBinderId = inputData.getString(SHARING_BINDER_ID)
        if (sharingBinderId != null && !validSharingBinderId(sharingBinderId)) return@withContext Result.failure()
        // The original first entitlement call resolved its default Firestore at this point.
        val firestore = try {
            FirebaseFirestore.getInstance()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.w("DomiDexCollectionSync", "Entitlement konnte nicht geprüft werden (${error.javaClass.simpleName}).")
            return@withContext Result.retry()
        }
        runCollectionSync(
            ownerUid = uid,
            sharingBinderId = sharingBinderId,
            auth = auth,
            firestore = firestore,
            database = { CardVaultDatabase.get(applicationContext) },
            appendCollection = { owner -> CollectionSyncScheduler.schedule(applicationContext, owner) },
        )
    }
}

/**
 * The worker's actual authenticated collection pass, shared with isolated SDK/Room proofs.
 * Only dependencies are explicit: entitlement checks, session guards, writes, ACKs, rereads and
 * continuation decisions remain in this path. Room is still opened after initial access succeeds.
 * Calling this function does not test WorkManager constraints, input delivery or scheduling.
 */
internal suspend fun runCollectionSync(
    ownerUid: String,
    sharingBinderId: String?,
    auth: FirebaseAuth,
    firestore: FirebaseFirestore,
    database: () -> CardVaultDatabase,
    appendCollection: (String) -> Unit,
): ListenableWorker.Result = CollectionSyncRun(auth, firestore, database, appendCollection)
    .run(ownerUid, sharingBinderId)

/** Per-invocation dependencies; no global override and no separate test implementation. */
private class CollectionSyncRun(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore,
    private val databaseProvider: () -> CardVaultDatabase,
    private val appendCollection: (String) -> Unit,
) {
    suspend fun run(uid: String, sharingBinderId: String?): Result = withContext(Dispatchers.IO) {
        if (uid.isBlank()) return@withContext Result.success()
        if (sharingBinderId != null && !validSharingBinderId(sharingBinderId)) return@withContext Result.failure()
        // Jobs for a currently signed-out/different owner remain no-ops. Once a run has
        // acquired its lease, any observed account/token change invalidates that entire run.
        if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) return@withContext Result.success()
        try {
            PrivatePullAuthSession.use(auth, uid) { session -> runSession(uid, sharingBinderId, session) }
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: PrivatePullAccountChangedException) {
            Result.retry()
        }
    }

    private suspend fun runSession(
        uid: String,
        sharingBinderId: String?,
        session: PrivatePullAuthSession,
    ): Result = withContext(Dispatchers.IO) {
        suspend fun accessStillCurrent(): Boolean {
            val account = guardedConflictActionRead(session::isCurrent) {
                auth.currentVerifiedFirebaseEntitlementAccount(firestore, forceRefresh = false)
            } ?: return false
            return shouldRunEntitledCollectionSync(
                expectedUid = uid,
                currentUid = canonicalCollectionOwnerUid(auth.currentUser),
                entitlements = account.entitlements,
            ) && account.uid == uid
        }
        val initiallyAllowed = try {
            accessStillCurrent()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.w("DomiDexCollectionSync", "Entitlement konnte nicht geprüft werden (${error.javaClass.simpleName}).")
            return@withContext Result.retry()
        }
        if (!initiallyAllowed) return@withContext Result.success()
        val database = databaseProvider()
        val dao = database.cardVaultDao()
        val commits = CollectionSyncSessionCommits(database, uid, session::isCurrent)

        suspend fun reread(mutation: CollectionSyncOutboxEntity) {
            val fresh = guardedConflictActionRead(session::isCurrent) {
                firestore.document(collectionEntryPath(uid, mutation.syncId)).get(Source.SERVER).awaitSyncTask()
            }
            // SERVER can still fail to provide a usable document. An absent, local-overlay or
            // malformed read is not confirmation: retain the durable checkpoint for retry.
            check(fresh.exists() && !fresh.metadata.isFromCache && !fresh.metadata.hasPendingWrites())
            val remote = checkNotNull(RemoteCollectionEntry.from(fresh, uid))
            // Keep the existing revision/tombstone policy and exact mutation deletion. A newer
            // legitimate remote mutation is valid too; do not require our lastMutationId.
            commits.finishReread(mutation, remote)
        }
        var processed = 0
        while (processed < MAX_MUTATIONS_PER_RUN) {
            val stillAllowed = try {
                accessStillCurrent()
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                Log.w("DomiDexCollectionSync", "Entitlement konnte nicht erneut geprüft werden (${error.javaClass.simpleName}).")
                return@withContext Result.retry()
            }
            if (!stillAllowed) return@withContext Result.success()
            // An auth switch ends this owner's run immediately. Its durable outbox remains for
            // the next time that same UID signs in; it is never sent under the new identity.
            session.requireCurrent()
            val mutation = (if (sharingBinderId == null) dao.pendingSyncOutbox(uid, 1)
                else dao.pendingSharingCollectionOutbox(uid, sharingBinderId, 1))
                .firstOrNull() ?: return@withContext Result.success()
            val entry = dao.collectionSyncProjection(uid, mutation.syncId)
            if (entry == null) {
                commits.discard(mutation)
                continue
            }
            if (entry.syncState == SYNC_CONFLICT || entry.syncState == SYNC_ERROR) {
                // Older clients could leave a terminal entry with a pending outbox after a late
                // failure callback. Expose its existing terminal decision; never push it in a loop.
                val normalized = commits.normalize(mutation)
                if (normalized) {
                    Log.w("DomiDexCollectionSync", "Lokaler Syncstatus benötigt eine ausdrückliche Entscheidung.")
                    // The terminal row is excluded from the next pending query. Do not fail
                    // the APPEND chain and stall unrelated healthy edits/sharing jobs.
                    processed++
                    continue
                }
                // A newer mutation/account change won the race. Preserve it without retrying here.
                return@withContext Result.success()
            }
            // CLEAN + pending outbox is the durable "commit ACKed, server reread pending"
            // checkpoint. Resume the pull directly after process death instead of replaying the
            // old payload against a potentially newer remote revision.
            if (shouldResumeServerReread(entry.syncState, mutation.state)) {
                runCatching { reread(mutation) }
                    .getOrElse { if (it is CancellationException) throw it; return@withContext Result.retry() }
                processed++
                continue
            }
            session.requireCurrent()
            val outcome = pushMutation(firestore, mutation, entry, session)
            // A permission failure caused by a session switch must remain pending. Only failures
            // observed while the captured UID is still current may become terminal.
            session.requireCurrent()
            when (outcome) {
                is PushOutcome.Ack -> {
                    // Keep this idempotent outbox row until a fresh server read succeeds.
                    // CLEAN first allows an N+2 listener/read to apply after N+1 was ACKed.
                    val shouldReread = commits.acknowledge(mutation, outcome.revision)
                    if (shouldReread) {
                        runCatching { reread(mutation) }
                            .getOrElse { if (it is CancellationException) throw it; return@withContext Result.retry() }
                    }
                }
                is PushOutcome.Conflict -> commits.fail(mutation, SYNC_CONFLICT, outcome.reason)
                is PushOutcome.Terminal -> commits.fail(mutation, SYNC_ERROR, outcome.reason)
                PushOutcome.Retry -> return@withContext Result.retry()
            }
            processed++
        }
        // The dependent priority binder worker schedules another scoped batch
        // when needed; ordinary sync continuation remains exactly as before.
        if (sharingBinderId != null) return@withContext Result.success()
        val hasPending = dao.pendingSyncOutbox(uid, 1).isNotEmpty()
        val mayContinue = try {
            accessStillCurrent()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            return@withContext Result.retry()
        }
        if (shouldScheduleCollectionSyncContinuation(processed, MAX_MUTATIONS_PER_RUN, hasPending) && mayContinue) {
            // Finishing a successful batch is not an error. Append the next bounded batch
            // immediately instead of applying WorkManager's exponential failure backoff.
            session.requireCurrent()
            appendCollection(uid)
        }
        Result.success()
    }

    private suspend fun pushMutation(
        firestore: FirebaseFirestore,
        mutation: CollectionSyncOutboxEntity,
        entry: CollectionSyncProjection,
        session: PrivatePullAuthSession,
    ): PushOutcome {
        val reference = firestore.document(collectionEntryPath(mutation.ownerUid, mutation.syncId))
        return try {
            CollectionSyncLimits.requireValid(entry, mutation)
            val revision = guardedConflictActionRead(session::isCurrent) {
                firestore.runTransaction { transaction ->
                    session.requireCurrent()
                    val current = transaction.get(reference)
                    session.requireCurrent()
                    val currentRevision = current.getLong("revision") ?: 0L
                    if (current.getString("lastMutationId") == mutation.mutationId) return@runTransaction currentRevision
                    if (currentRevision != mutation.expectedRemoteRevision) {
                        throw CollectionSyncConflictException(currentRevision, current.getBoolean("deleted") == true)
                    }
                    val nextRevision = currentRevision + 1L
                    transaction.set(reference, entry.toRemoteMap(mutation, nextRevision))
                    nextRevision
                }.awaitSyncTask()
            }
            PushOutcome.Ack(revision)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (changed: PrivatePullAccountChangedException) { throw changed
        } catch (error: Exception) {
            // A failed SDK task/transaction can also arrive after A -> B -> A. It must
            // never turn the original owner's still-retryable mutation into a terminal error.
            session.requireCurrent()
            val conflict = generateSequence<Throwable>(error) { it.cause }.filterIsInstance<CollectionSyncConflictException>().firstOrNull()
            if (conflict != null) PushOutcome.Conflict(
                if (conflict.remoteDeleted) remoteDeletedConflictReason(conflict.remoteRevision)
                else remoteConflictReason(conflict.remoteRevision),
            )
            else when {
                error is IllegalArgumentException -> PushOutcome.Terminal("invalid-payload")
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .any { it.code == FirebaseFirestoreException.Code.PERMISSION_DENIED } -> PushOutcome.Terminal("permission-denied")
                generateSequence<Throwable>(error) { it.cause }
                    .filterIsInstance<FirebaseFirestoreException>()
                    .any { it.code == FirebaseFirestoreException.Code.INVALID_ARGUMENT } -> PushOutcome.Terminal("invalid-argument")
                else -> PushOutcome.Retry
            }
        }
    }

    private companion object { const val MAX_MUTATIONS_PER_RUN = 200 }
}

private sealed interface PushOutcome {
    data class Ack(val revision: Long) : PushOutcome
    data class Conflict(val reason: String) : PushOutcome
    data class Terminal(val reason: String) : PushOutcome
    data object Retry : PushOutcome
}

private class CollectionSyncConflictException(
    val remoteRevision: Long,
    val remoteDeleted: Boolean,
) : RuntimeException()

private fun CollectionSyncProjection.toRemoteMap(mutation: CollectionSyncOutboxEntity, revision: Long): Map<String, Any?> = mapOf(
    "syncId" to syncId,
    "ownerUid" to ownerUid,
    "revision" to revision,
    "lastMutationId" to mutation.mutationId,
    "updatedAt" to FieldValue.serverTimestamp(),
    "updatedAtClient" to updatedAtEpochMillis,
    // Immutable local creation evidence is used only for cross-client display sorting.
    "createdAtClient" to createdAtEpochMillis,
    "deleted" to (deletedAtEpochMillis != null || mutation.operation == COLLECTION_SYNC_OPERATION_DELETE),
    "deletedAtClient" to deletedAtEpochMillis,
    "name" to name,
    "setId" to setId,
    "setName" to setName,
    "collectorNumber" to collectorNumber,
    "language" to language,
    "finish" to finish,
    "variant" to variant,
    "rarity" to rarity,
    "quantity" to quantity,
    "condition" to condition,
    "notes" to notes,
    "acquisitionPriceMinor" to acquisitionPriceMinor,
    "acquisitionCurrency" to acquisitionCurrency,
    "acquisitionDateEpochDay" to acquisitionDateEpochDay,
    "externalProductSource" to externalProductSource,
    "externalProductId" to externalProductId,
    "imageUrl" to imageUrl,
    "effectOverride" to effectOverride,
    "sourceSetId" to sourceSetId,
    "sourceCardId" to sourceCardId,
    "binderId" to binderId,
    "binderSetKey" to binderSetKey,
    "binderName" to binderName,
    "binderHeroAsset" to binderHeroAsset,
)

internal data class RemoteCollectionEntry(
    val syncId: String,
    val revision: Long,
    val updatedAtEpochMillis: Long,
    val deletedAtEpochMillis: Long?,
    val name: String,
    val setId: String,
    val setName: String,
    val collectorNumber: String,
    val language: String,
    val finish: String,
    val variant: String,
    val rarity: String,
    val quantity: Int,
    val condition: String,
    val notes: String,
    val acquisitionPriceMinor: Long?,
    val acquisitionCurrency: String?,
    val acquisitionDateEpochDay: Long?,
    val externalProductSource: String?,
    val externalProductId: Long?,
    val imageUrl: String?,
    val effectOverride: String?,
    val sourceSetId: String?,
    val sourceCardId: String?,
    val binderId: String,
    val binderSetKey: String,
    val binderName: String,
    val binderHeroAsset: String?,
) {
    companion object {
        fun from(document: DocumentSnapshot, expectedOwner: String): RemoteCollectionEntry? {
            val syncId = document.getString("syncId") ?: return null
            if (document.id != syncId || document.getString("ownerUid") != expectedOwner) return null
            val revision = document.getLong("revision")?.takeIf { it > 0L } ?: return null
            val updated = document.getTimestamp("updatedAt")?.toDate()?.time
                ?: document.getLong("updatedAtClient") ?: return null
            val deleted = document.getBoolean("deleted") == true
            val sourceSetId = document.getString("sourceSetId")
            val sourceCardId = document.getString("sourceCardId")
            if ((sourceSetId == null) != (sourceCardId == null)) return null
            val sourcePattern = Regex("[a-z0-9._-]{1,${CollectionSyncLimits.SOURCE_ID}}")
            if (sourceSetId != null && (!sourceSetId.matches(sourcePattern) || !sourceCardId!!.matches(sourcePattern))) return null
            return RemoteCollectionEntry(
                syncId = syncId,
                revision = revision,
                updatedAtEpochMillis = updated,
                deletedAtEpochMillis = if (deleted) document.getLong("deletedAtClient") ?: updated else null,
                name = document.getString("name")?.take(200) ?: return null,
                setId = document.getString("setId")?.take(120) ?: return null,
                setName = document.getString("setName")?.take(200) ?: return null,
                collectorNumber = document.getString("collectorNumber")?.take(80).orEmpty(),
                language = document.getString("language")?.take(30) ?: "GERMAN",
                finish = document.getString("finish")?.take(80) ?: "Normal",
                variant = document.getString("variant")?.take(80) ?: "Standard",
                rarity = document.getString("rarity")?.take(80) ?: "UNKNOWN",
                quantity = document.getLong("quantity")?.toInt()?.coerceIn(1, 10_000) ?: 1,
                condition = document.getString("condition")?.take(120).orEmpty(),
                notes = document.getString("notes")?.take(4_000).orEmpty(),
                acquisitionPriceMinor = document.getLong("acquisitionPriceMinor"),
                acquisitionCurrency = document.getString("acquisitionCurrency")?.take(8),
                acquisitionDateEpochDay = document.getLong("acquisitionDateEpochDay"),
                externalProductSource = document.getString("externalProductSource")?.take(80),
                externalProductId = document.getLong("externalProductId"),
                imageUrl = document.getString("imageUrl")?.take(2_000),
                effectOverride = document.getString("effectOverride")?.take(80),
                sourceSetId = sourceSetId,
                sourceCardId = sourceCardId,
                binderId = document.getString("binderId")?.take(160) ?: "inbox",
                binderSetKey = document.getString("binderSetKey")?.take(160) ?: "inbox",
                binderName = document.getString("binderName")?.take(200) ?: "Ohne Setzuordnung",
                binderHeroAsset = document.getString("binderHeroAsset")?.take(2_000),
            )
        }
    }
}

private fun CoroutineScope.launchSafely(block: suspend () -> Unit) {
    launch {
        runCatching { block() }.onFailure { error ->
            if (error is CancellationException) throw error
            Log.w("DomiDexCollectionSync", "Remote snapshot was ignored (${error.javaClass.simpleName}).")
        }
    }
}

private suspend fun <T> Task<T>.awaitSyncTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(kotlin.Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase sync task failed"))
    }
}
