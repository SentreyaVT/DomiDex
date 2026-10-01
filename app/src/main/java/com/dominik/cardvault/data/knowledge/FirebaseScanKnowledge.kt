package com.dominik.cardvault.data.knowledge

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.dominik.cardvault.auth.canUseFirebaseAppBackend
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentVerifiedFirebaseEntitlementAccount
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Exact-document reads only; Collection sync uses a completely separate session and namespace. */
internal class FirebaseScanKnowledge(
    context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val applicationContext = context.applicationContext
    private val store = ScanKnowledgeStore(applicationContext)
    private val consent = ScanKnowledgeConsent(applicationContext)

    fun cached(fingerprint: String): ScanKnowledgeRecord? = store.get(fingerprint)

    fun observeLabRelevant(
        context: com.dominik.cardvault.data.feedback.ScannerReportKnowledgeContext,
        onUpdate: (ReviewedScanKnowledgeHint?) -> Unit,
    ): AutoCloseable = FirebaseReviewedScanKnowledge(auth).observeRelevant(context, onUpdate)

    fun observeRelevant(fingerprint: String, onUpdate: (ScanKnowledgeRecord?) -> Unit): AutoCloseable {
        onUpdate(store.get(fingerprint))
        val uid = canonicalCollectionOwnerUid(auth.currentUser)
        if (uid == null || !fingerprint.matches(Regex("[a-f0-9]{64}"))) return AutoCloseable { }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val lock = Any()
        var closed = false
        var listener: ListenerRegistration? = null
        val accountListener = FirebaseAuth.AuthStateListener {
            if (canonicalCollectionOwnerUid(it.currentUser) != uid) synchronized(lock) {
                closed = true; listener?.remove(); scope.cancel()
            }
        }
        auth.addAuthStateListener(accountListener)
        scope.launch {
            val account = try { auth.currentVerifiedFirebaseEntitlementAccount(firestore) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return@launch }
            if (account == null || !canUseFirebaseAppBackend(uid, canonicalCollectionOwnerUid(auth.currentUser), account.entitlements)) return@launch
            synchronized(lock) {
                if (closed) return@synchronized
                listener = firestore.collection("scanKnowledge").document(fingerprint)
            .addSnapshotListener { snapshot, error ->
                if (canonicalCollectionOwnerUid(auth.currentUser) != uid) return@addSnapshotListener
                if (error != null) {
                    Log.d(LOG_TAG, "ScanKnowledge nicht erreichbar (${error.code}).")
                    return@addSnapshotListener
                }
                val record = snapshot?.takeIf(DocumentSnapshot::exists)?.toKnowledgeRecord() ?: return@addSnapshotListener
                store.put(record)
                onUpdate(record)
            }
            }
        }
        return AutoCloseable {
            synchronized(lock) { closed = true; listener?.remove(); scope.cancel() }
            auth.removeAuthStateListener(accountListener)
        }
    }

    suspend fun enqueue(evidence: ScanKnowledgeEvidence) {
        val uid = canonicalCollectionOwnerUid(auth.currentUser) ?: return
        if (!consent.isEnabled(uid)) return
        val account = try { auth.currentVerifiedFirebaseEntitlementAccount(firestore) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { return }
            ?: return
        val canonicalUid = canonicalCollectionOwnerUid(auth.currentUser)
        if (account.uid != uid || !canUseFirebaseAppBackend(uid, canonicalUid, account.entitlements)) return
        val queued = consent.ifEnabled(uid) {
            canonicalCollectionOwnerUid(auth.currentUser) == uid && store.enqueue(uid, evidence)
        } == true
        if (queued) ScanKnowledgeScheduler.schedule(applicationContext, uid)
    }

    private companion object { const val LOG_TAG = "DomiDexKnowledge" }
}

internal object ScanKnowledgeScheduler {
    internal const val INPUT_OWNER_UID = "ownerUid"
    internal val existingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

    fun schedule(context: Context, ownerUid: String? = canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser)) {
        if (ownerUid.isNullOrBlank() || !ScanKnowledgeConsent(context).isEnabled(ownerUid)) return
        val request = OneTimeWorkRequestBuilder<ScanKnowledgeSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(INPUT_OWNER_UID to ownerUid))
            .build()
        // Never lose a wake-up that arrives while the current outbox worker is finishing.
        WorkManager.getInstance(context).enqueueUniqueWork("domidex-scan-knowledge-${scanKnowledgeOwnerKey(ownerUid)}", existingWorkPolicy, request)
    }
}

internal class ScanKnowledgeSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val auth = FirebaseAuth.getInstance()
        val consent = ScanKnowledgeConsent(applicationContext)
        val expectedUid = inputData.getString(ScanKnowledgeScheduler.INPUT_OWNER_UID) ?: return Result.success()
        if (!consent.isEnabled(expectedUid) || canonicalCollectionOwnerUid(auth.currentUser) != expectedUid) return Result.success()
        suspend fun entitledUid(): String? {
            if (!consent.isEnabled(expectedUid) || canonicalCollectionOwnerUid(auth.currentUser) != expectedUid) return null
            val account = auth.currentVerifiedFirebaseEntitlementAccount() ?: return null
            val canonicalUid = canonicalCollectionOwnerUid(auth.currentUser)
            return account.uid.takeIf {
                account.uid == expectedUid && canUseFirebaseAppBackend(account.uid, canonicalUid, account.entitlements)
            }
        }
        val uid = try {
            entitledUid()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.w("DomiDexKnowledge", "Entitlement konnte nicht geprüft werden (${error.javaClass.simpleName}).")
            return Result.retry()
        } ?: return Result.success()
        val store = ScanKnowledgeStore(applicationContext)
        val firestore = FirebaseFirestore.getInstance()
        return try {
            while (true) {
                val currentUid = try {
                    entitledUid()
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    return Result.retry()
                }
                if (currentUid != uid) return Result.success()
                val pending = store.pending(uid)
                if (pending.isEmpty()) break
                pending.forEach { evidence ->
                    if (entitledUid() != uid || !consent.isEnabled(uid) || canonicalCollectionOwnerUid(auth.currentUser) != uid) {
                        return Result.success()
                    }
                    val payload = hashMapOf<String, Any?>(
                    "fingerprint" to evidence.fingerprint,
                    "confirmedCardId" to evidence.confirmedCardId,
                    "sourceSetId" to evidence.sourceSetId,
                    "cardNumber" to evidence.cardNumber,
                    "setCode" to evidence.setCode,
                    "ocrTokens" to evidence.ocrTokens,
                    "visualFingerprint" to evidence.visualFingerprint,
                    "candidateIds" to evidence.candidateIds,
                    "originalConfidence" to evidence.originalConfidence,
                    "corrected" to evidence.corrected,
                    "scannerVersion" to evidence.scannerVersion,
                    "knowledgeVersion" to evidence.knowledgeVersion,
                    "observedAtClient" to evidence.observedAtClient,
                    "updatedAt" to FieldValue.serverTimestamp(),
                )
                    // The consent lock prevents a queued send from beginning after opt-out.
                    val task = consent.ifEnabled(uid) {
                        if (canonicalCollectionOwnerUid(auth.currentUser) != uid) null else
                        firestore.collection("users").document(uid).collection("scanEvidence")
                        .document(evidence.fingerprint)
                        .set(payload, SetOptions.merge())
                    } ?: return Result.success()
                    task.awaitTask()
                    store.markUploaded(uid, evidence)
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            Log.w("DomiDexKnowledge", "ScanKnowledge-Sync wird später wiederholt (${error.javaClass.simpleName}).")
            Result.retry()
        }
    }
}

private fun DocumentSnapshot.toKnowledgeRecord(): ScanKnowledgeRecord? {
    val fingerprint = getString("fingerprint")?.takeIf { it == id && it.matches(Regex("[a-f0-9]{64}")) } ?: return null
    val status = getString("status")?.let { runCatching { ScanKnowledgeStatus.valueOf(it) }.getOrNull() } ?: return null
    return ScanKnowledgeRecord(
        fingerprint = fingerprint,
        cardId = getString("cardId")?.takeIf(String::isNotBlank) ?: return null,
        sourceSetId = getString("sourceSetId"),
        cardNumber = getString("cardNumber").orEmpty(),
        status = status,
        confidence = (getLong("confidence") ?: 0L).toInt().coerceIn(0, 100),
        confirmations = (getLong("confirmations") ?: 0L).toInt().coerceAtLeast(0),
        corrections = (getLong("corrections") ?: 0L).toInt().coerceAtLeast(0),
        knowledgeVersion = (getLong("knowledgeVersion") ?: 1L).toInt().coerceAtLeast(1),
        updatedAtEpochMillis = getTimestamp("lastUpdatedAt")?.toDate()?.time ?: 0L,
    )
}

private suspend fun <T> Task<T>.awaitTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase operation failed"))
    }
}
