package com.dominik.cardvault.data.sync

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
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
import com.dominik.cardvault.data.knowledge.ScanKnowledgeScheduler
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
class FirebaseCollectionSession(
    private val context: Context,
    private val database: CardVaultDatabase,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) : AutoCloseable {
    private val mutableOwnerUid = MutableStateFlow(collectionOwnerForUid(canonicalCollectionOwnerUid(auth.currentUser)))
    val ownerUid: StateFlow<String> = mutableOwnerUid
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

    private val idTokenListener = FirebaseAuth.IdTokenListener(::handleIdTokenState)

    private fun handleIdTokenState(changedAuth: FirebaseAuth) {
        val user = changedAuth.currentUser
        val uid = canonicalCollectionOwnerUid(user)
        mutableOwnerUid.value = collectionOwnerForUid(uid)
        val (previousUid, generation) = synchronized(entitlementLock) {
            val previousUid = entitledOwnerUid
            entitlementGeneration += 1L
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
        if (uid == null) PrivateBinderSyncScheduler.cancelAllPeriodic(context)
        else if (previousUid != null && previousUid != uid) {
            PrivateBinderSyncScheduler.cancelPeriodic(context, previousUid)
        }
        if (uid == null || user == null) return
        scope.launch {
            val account = try { auth.currentVerifiedFirebaseEntitlementAccount(firestore) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    synchronized(entitlementLock) {
                        if (entitlementGeneration == generation && mutableOwnerUid.value == uid) {
                            mutableCloudState.value = CollectionCloudState(
                                if (failure is FirebaseFirestoreException && failure.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                    CollectionCloudStatus.NO_ACCESS else CollectionCloudStatus.LOCAL_ONLY, uid,
                            )
                        }
                    }
                    return@launch
                } ?: return@launch
            if (!account.entitlements.canUseAppBackend) {
                PrivateBinderSyncScheduler.cancelPeriodic(context, uid)
                synchronized(entitlementLock) {
                    if (entitlementGeneration == generation && mutableOwnerUid.value == uid)
                        mutableCloudState.value = CollectionCloudState(CollectionCloudStatus.NO_ACCESS, uid)
                }
                return@launch
            }
            val activated = synchronized(entitlementLock) {
                if (!started || entitlementGeneration != generation) return@synchronized false
                if (canonicalCollectionOwnerUid(auth.currentUser) != uid || account.uid != uid) return@synchronized false
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
                                PrivateBinderSyncScheduler.cancelPeriodic(context, uid)
                                mutableCloudState.value = CollectionCloudState(
                                    if (error == null || error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED)
                                        CollectionCloudStatus.NO_ACCESS else CollectionCloudStatus.LOCAL_ONLY, uid,
                                )
                            }
                        }
                    }
                true
            }
            if (!activated) return@launch
            requestSync()
            // Wake any durable evidence queued while this same entitled account was offline.
            ScanKnowledgeScheduler.schedule(context)
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
    }

    fun requestSync() {
        val uid = synchronized(entitlementLock) { entitledOwnerUid }
        if (uid == null) {
            if (started && canonicalCollectionOwnerUid(auth.currentUser) != null) handleIdTokenState(auth)
            return
        }
        if (canonicalCollectionOwnerUid(auth.currentUser) != uid) return
        CollectionSyncScheduler.schedule(context, uid, userInitiated = true)
        // Separate bounded worker/outbox: no second full-collection listener is introduced.
        PrivateBinderSyncScheduler.schedule(context, uid, userInitiated = true)
        PrivateBinderSyncScheduler.ensurePeriodic(context, uid)
        refreshCloudState()
    }

    private fun refreshCloudState() {
        val uid = synchronized(entitlementLock) { entitledOwnerUid } ?: return
        scope.launch {
            val dao = database.cardVaultDao()
            val pending = dao.pendingSyncOutbox(uid, 1).isNotEmpty() || dao.pendingPrivateBinderSyncOutbox(uid, 1).isNotEmpty()
            val error = dao.erroredSyncOutbox(uid).isNotEmpty() || dao.conflictedSyncOutbox(uid).isNotEmpty()
            synchronized(entitlementLock) {
                if (started && entitledOwnerUid == uid && mutableOwnerUid.value == uid) {
                    mutableCloudState.value = CollectionCloudState(
                        collectionCloudStatus(false, true, false, serverSnapshot, pending, error || snapshotError), uid, true,
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
    suspend fun keepCloudVersionsForConflicts(): Int = withContext(Dispatchers.IO) {
        val uid = entitledOwnerUid ?: return@withContext 0
        if (canonicalCollectionOwnerUid(auth.currentUser) != uid) return@withContext 0
        val actionCurrent = captureConflictSession(uid)
        val priceResolved = resolvePriceSelectionConflicts(uid, keepLocal = false)
        if (!actionCurrent()) return@withContext priceResolved
        val dao = database.cardVaultDao()
        var resolved = 0
        dao.conflictedSyncOutbox(uid).forEach { conflict ->
            if (!actionCurrent()) return@withContext resolved + priceResolved
            val document = runCatching {
                firestore.document(collectionEntryPath(uid, conflict.syncId))
                    .get(Source.SERVER)
                    .awaitSyncTask()
            }.getOrNull() ?: return@forEach
            if (!document.exists() || canonicalCollectionOwnerUid(auth.currentUser) != uid) return@forEach
            val firstRemote = RemoteCollectionEntry.from(document, uid) ?: return@forEach
            val openedForCloud = database.withTransaction {
                val current = dao.syncOutbox(conflict.entryKey)
                if (current?.ownerUid != uid || current.mutationId != conflict.mutationId || current.state != SYNC_CONFLICT) {
                    return@withTransaction false
                }
                val local = dao.getCollectionEntryBySyncId(uid, firstRemote.syncId)
                if (local == null || (local.syncState != SYNC_CONFLICT && local.syncState != SYNC_CLEAN)) {
                    return@withTransaction false
                }
                // Keep the conflicting local payload intact for now. CLEAN lets a newer listener
                // snapshot apply while we perform the mandatory second server read below.
                dao.markEntrySynced(
                    ownerUid = uid,
                    syncId = firstRemote.syncId,
                    revision = maxOf(local.remoteRevision, firstRemote.revision),
                    state = SYNC_CLEAN,
                )
                true
            }
            if (!openedForCloud || canonicalCollectionOwnerUid(auth.currentUser) != uid) return@forEach

            // A listener event can be delivered only once. Re-read after CLEAN so a revision that
            // arrived between the first read and the Room state transition cannot be lost.
            val latestDocument = runCatching {
                firestore.document(collectionEntryPath(uid, conflict.syncId))
                    .get(Source.SERVER)
                    .awaitSyncTask()
            }.getOrNull() ?: return@forEach
            if (!latestDocument.exists() || canonicalCollectionOwnerUid(auth.currentUser) != uid) return@forEach
            val latestRemote = RemoteCollectionEntry.from(latestDocument, uid) ?: return@forEach
            val applied = database.withTransaction {
                val current = dao.syncOutbox(conflict.entryKey)
                if (current?.ownerUid != uid || current.mutationId != conflict.mutationId || current.state != SYNC_CONFLICT) {
                    return@withTransaction 0
                }
                val local = dao.getCollectionEntryBySyncId(uid, latestRemote.syncId)
                if (local?.syncState != SYNC_CLEAN) return@withTransaction 0
                // A listener may already have applied an even newer revision. Never roll it back;
                // otherwise apply the fresh server snapshot, including a cloud tombstone.
                if (shouldApplyCloudResolutionSnapshot(local.remoteRevision, latestRemote.revision)) {
                    dao.applyCleanRemoteEntry(
                        ownerUid = uid,
                        syncId = latestRemote.syncId,
                        quantity = latestRemote.quantity,
                        condition = latestRemote.condition,
                        acquisitionPriceMinor = latestRemote.acquisitionPriceMinor,
                        acquisitionCurrency = latestRemote.acquisitionCurrency,
                        acquisitionDateEpochDay = latestRemote.acquisitionDateEpochDay,
                        notes = latestRemote.notes,
                        externalProductSource = latestRemote.externalProductSource,
                        externalProductId = latestRemote.externalProductId,
                        sourceSetId = latestRemote.sourceSetId,
                        sourceCardId = latestRemote.sourceCardId,
                        binderId = latestRemote.binderId,
                        updatedAtEpochMillis = latestRemote.updatedAtEpochMillis,
                        remoteRevision = latestRemote.revision,
                        deletedAtEpochMillis = latestRemote.deletedAtEpochMillis,
                        finish = latestRemote.finish,
                        variant = latestRemote.variant,
                        effectOverride = latestRemote.effectOverride,
                    )
                }
                dao.deleteSyncOutboxIfCurrent(conflict.entryKey, conflict.mutationId)
                1
            }
            resolved += applied
        }
        resolved + priceResolved
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

    /** Existing explicit Settings decisions also cover price sidecars, with a per-action auth lease. */
    private suspend fun resolvePriceSelectionConflicts(uid: String, keepLocal: Boolean): Int = withContext(Dispatchers.IO) {
        if (entitledOwnerUid != uid || canonicalCollectionOwnerUid(auth.currentUser) != uid) return@withContext 0
        val conflicts = database.cardVaultDao().conflictedPriceSelectionOutbox(uid)
        if (conflicts.isEmpty()) return@withContext 0
        PrivatePullAuthSession.use(auth, uid) { session ->
            var resolved = 0
            fun current() = session.isCurrent() && entitledOwnerUid == uid
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

    override fun close() {
        synchronized(entitlementLock) {
            closed = true
            started = false
            entitlementGeneration += 1L
            entitledOwnerUid = null
            detachRemoteListenerLocked()
            accessListener?.remove()
            accessListener = null
        }
        auth.removeIdTokenListener(idTokenListener)
        scope.cancel()
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
        suspend fun accessStillCurrent(): Boolean {
            val account = auth.currentVerifiedFirebaseEntitlementAccount(forceRefresh = false) ?: return false
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
        val database = CardVaultDatabase.get(applicationContext)
        val dao = database.cardVaultDao()
        val firestore = FirebaseFirestore.getInstance()
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
            if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) return@withContext Result.success()
            val mutation = (if (sharingBinderId == null) dao.pendingSyncOutbox(uid, 1)
                else dao.pendingSharingCollectionOutbox(uid, sharingBinderId, 1))
                .firstOrNull() ?: return@withContext Result.success()
            val entry = dao.collectionSyncProjection(uid, mutation.syncId)
            if (entry == null) {
                dao.deleteSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                continue
            }
            if (entry.syncState == SYNC_CONFLICT || entry.syncState == SYNC_ERROR) {
                // Older clients could leave a terminal entry with a pending outbox after a late
                // failure callback. Expose its existing terminal decision; never push it in a loop.
                val normalized = normalizePendingOutboxForTerminalEntry(database, mutation) { owner ->
                    owner == uid && shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))
                }
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
                val fresh = runCatching {
                    firestore.document(collectionEntryPath(uid, mutation.syncId))
                        .get(Source.SERVER)
                        .awaitSyncTask()
                }.getOrElse { if (it is CancellationException) throw it; return@withContext Result.retry() }
                if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
                    return@withContext Result.success()
                }
                runCatching {
                    applyRemoteDocumentToRoom(database, uid, fresh) {
                        shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))
                    }
                }.getOrElse { if (it is CancellationException) throw it; return@withContext Result.retry() }
                if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
                    return@withContext Result.success()
                }
                dao.deleteSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                processed++
                continue
            }
            if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) return@withContext Result.success()
            val outcome = pushMutation(firestore, mutation, entry)
            // A permission failure caused by a session switch must remain pending. Only failures
            // observed while the captured UID is still current may become terminal.
            if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) return@withContext Result.success()
            when (outcome) {
                is PushOutcome.Ack -> {
                    // Keep this idempotent outbox row until a fresh server read succeeds.
                    // CLEAN first allows an N+2 listener/read to apply after N+1 was ACKed.
                    val shouldReread = commitCollectionAck(database, mutation, outcome.revision) { owner ->
                        owner == uid && shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))
                    }
                    if (shouldReread) {
                        if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
                            return@withContext Result.success()
                        }
                        val fresh = runCatching {
                            firestore.document(collectionEntryPath(uid, mutation.syncId))
                                .get(Source.SERVER)
                                .awaitSyncTask()
                        }.getOrElse { if (it is CancellationException) throw it; return@withContext Result.retry() }
                        if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
                            return@withContext Result.success()
                        }
                        runCatching {
                            applyRemoteDocumentToRoom(database, uid, fresh) {
                                shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))
                            }
                        }.getOrElse { if (it is CancellationException) throw it; return@withContext Result.retry() }
                        if (!shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))) {
                            return@withContext Result.success()
                        }
                        dao.deleteSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                    }
                }
                is PushOutcome.Conflict -> commitCollectionFailure(database, mutation, SYNC_CONFLICT, outcome.reason) { owner ->
                    owner == uid && shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))
                }
                is PushOutcome.Terminal -> commitCollectionFailure(database, mutation, SYNC_ERROR, outcome.reason) { owner ->
                    owner == uid && shouldRunCollectionSync(uid, canonicalCollectionOwnerUid(auth.currentUser))
                }
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
            CollectionSyncScheduler.schedule(applicationContext, uid)
        }
        Result.success()
    }

    private suspend fun pushMutation(
        firestore: FirebaseFirestore,
        mutation: CollectionSyncOutboxEntity,
        entry: CollectionSyncProjection,
    ): PushOutcome {
        val reference = firestore.document(collectionEntryPath(mutation.ownerUid, mutation.syncId))
        return try {
            CollectionSyncLimits.requireValid(entry, mutation)
            val revision = firestore.runTransaction { transaction ->
                val current = transaction.get(reference)
                val currentRevision = current.getLong("revision") ?: 0L
                if (current.getString("lastMutationId") == mutation.mutationId) return@runTransaction currentRevision
                if (currentRevision != mutation.expectedRemoteRevision) {
                    throw CollectionSyncConflictException(currentRevision, current.getBoolean("deleted") == true)
                }
                val nextRevision = currentRevision + 1L
                transaction.set(reference, entry.toRemoteMap(mutation, nextRevision))
                nextRevision
            }.awaitSyncTask()
            PushOutcome.Ack(revision)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
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

private data class RemoteCollectionEntry(
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
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase sync task failed"))
    }
}
