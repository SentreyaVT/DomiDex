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
import com.dominik.cardvault.auth.canUseFirebaseAppBackend
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentFirebaseEntitlementAccount
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Exact-document reads only; Collection sync uses a completely separate session and namespace. */
internal class FirebaseScanKnowledge(
    context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    private val applicationContext = context.applicationContext
    private val store = ScanKnowledgeStore(applicationContext)

    fun cached(fingerprint: String): ScanKnowledgeRecord? = store.get(fingerprint)

    fun observeRelevant(fingerprint: String, onUpdate: (ScanKnowledgeRecord?) -> Unit): AutoCloseable {
        onUpdate(store.get(fingerprint))
        if (auth.currentUser == null || !fingerprint.matches(Regex("[a-f0-9]{64}"))) return AutoCloseable { }
        val listener: ListenerRegistration = firestore.collection("scanKnowledge").document(fingerprint)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.d(LOG_TAG, "ScanKnowledge nicht erreichbar (${error.code}).")
                    return@addSnapshotListener
                }
                val record = snapshot?.takeIf(DocumentSnapshot::exists)?.toKnowledgeRecord() ?: return@addSnapshotListener
                store.put(record)
                onUpdate(record)
            }
        return AutoCloseable(listener::remove)
    }

    suspend fun enqueue(evidence: ScanKnowledgeEvidence) {
        val account = runCatching { auth.currentFirebaseEntitlementAccount(forceRefresh = false) }
            .getOrNull() ?: return
        val canonicalUid = canonicalCollectionOwnerUid(auth.currentUser)
        if (!canUseFirebaseAppBackend(account.uid, canonicalUid, account.entitlements)) return
        if (store.enqueue(evidence)) ScanKnowledgeScheduler.schedule(applicationContext)
    }

    private companion object { const val LOG_TAG = "DomiDexKnowledge" }
}

internal object ScanKnowledgeScheduler {
    private const val WORK_NAME = "domidex-scan-knowledge-sync"
    internal val existingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE

    fun schedule(context: Context) {
        val request = OneTimeWorkRequestBuilder<ScanKnowledgeSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        // Never lose a wake-up that arrives while the current outbox worker is finishing.
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, existingWorkPolicy, request)
    }
}

internal class ScanKnowledgeSyncWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val auth = FirebaseAuth.getInstance()
        suspend fun entitledUid(): String? {
            val account = auth.currentFirebaseEntitlementAccount(forceRefresh = false) ?: return null
            val canonicalUid = canonicalCollectionOwnerUid(auth.currentUser)
            return account.uid.takeIf {
                canUseFirebaseAppBackend(account.uid, canonicalUid, account.entitlements)
            }
        }
        val uid = try {
            entitledUid()
        } catch (error: Throwable) {
            Log.w("DomiDexKnowledge", "Entitlement konnte nicht geprüft werden (${error.javaClass.simpleName}).")
            return Result.retry()
        } ?: return Result.success()
        val store = ScanKnowledgeStore(applicationContext)
        val firestore = FirebaseFirestore.getInstance()
        return try {
            while (true) {
                val currentUid = try {
                    entitledUid()
                } catch (error: Throwable) {
                    return Result.retry()
                }
                if (currentUid != uid) return Result.success()
                val pending = store.pending()
                if (pending.isEmpty()) break
                pending.forEach { evidence ->
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
                    firestore.collection("users").document(uid).collection("scanEvidence")
                        .document(evidence.fingerprint)
                        .set(payload, SetOptions.merge())
                        .awaitTask()
                    store.markUploaded(evidence)
                }
            }
            Result.success()
        } catch (error: Throwable) {
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
