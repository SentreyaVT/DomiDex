package com.dominik.cardvault.data.sync

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal const val SHARING_BINDER_ID = "sharingBinderId"
internal fun validSharingBinderId(value: String): Boolean = Regex("[A-Za-z0-9_-]{1,128}").matches(value)
internal fun sharingSyncWorkName(ownerUid: String, binderId: String): String =
    "domidex-sharing-sync-${UUID.nameUUIDFromBytes("$ownerUid\u0000$binderId".toByteArray(Charsets.UTF_8))}"

internal fun sharingSyncWorkPolicy(states: List<Pair<WorkInfo.State, Int>>): ExistingWorkPolicy =
    if (states.none { it.first == WorkInfo.State.RUNNING } &&
        states.any { it.first == WorkInfo.State.ENQUEUED && it.second > 0 }) ExistingWorkPolicy.REPLACE
    else ExistingWorkPolicy.KEEP

/** Same revision/ACK workers as ordinary sync, with a separate, narrowly scoped queue. */
internal object BinderSharingSyncScheduler {
    private val scheduling = Mutex()

    suspend fun schedule(context: Context, ownerUid: String, binderId: String) {
        require(ownerUid.isNotBlank() && ownerUid != GUEST_OWNER_UID && validSharingBinderId(binderId))
        scheduling.withLock {
            val manager = WorkManager.getInstance(context.applicationContext)
            val name = sharingSyncWorkName(ownerUid, binderId)
            val existing = manager.getWorkInfosForUniqueWorkFlow(name).first()
            // A fresh explicit click may wake a failed priority request, but must
            // not cancel a running chain or alter the ordinary owner sync queue.
            enqueue(manager, ownerUid, binderId, sharingSyncWorkPolicy(existing.map { it.state to it.runAttemptCount }))
        }
    }

    suspend fun continueBatch(context: Context, ownerUid: String, binderId: String) {
        scheduling.withLock {
            enqueue(WorkManager.getInstance(context.applicationContext), ownerUid, binderId, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
    }

    private suspend fun enqueue(manager: WorkManager, ownerUid: String, binderId: String, policy: ExistingWorkPolicy) {
        val (collection, binder) = requests(ownerUid, binderId)
        manager.beginUniqueWork(sharingSyncWorkName(ownerUid, binderId), policy, collection)
            .then(binder).enqueue().awaitSharingEnqueue()
    }

    internal fun requests(ownerUid: String, binderId: String, sdk: Int = Build.VERSION.SDK_INT): Pair<OneTimeWorkRequest, OneTimeWorkRequest> {
        require(ownerUid.isNotBlank() && ownerUid != GUEST_OWNER_UID && validSharingBinderId(binderId))
        val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val collection = OneTimeWorkRequestBuilder<CollectionSyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(CollectionSyncScheduler.INPUT_OWNER_UID to ownerUid, SHARING_BINDER_ID to binderId))
        val binder = OneTimeWorkRequestBuilder<PrivateBinderSyncWorker>()
            .setConstraints(network)
            .setInputData(workDataOf(PrivateBinderSyncScheduler.INPUT_OWNER_UID to ownerUid, SHARING_BINDER_ID to binderId))
        // Earlier Android versions require a new foreground-service contract for
        // expedited work. Keep their existing normal worker behavior unchanged.
        if (sdk >= 31) {
            collection.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            binder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        }
        return collection.build() to binder.build()
    }
}

private suspend fun Operation.awaitSharingEnqueue() = suspendCancellableCoroutine<Unit> { continuation ->
    val completed = result
    completed.addListener({
        if (!continuation.isActive) return@addListener
        try { completed.get(); continuation.resume(Unit) }
        catch (error: Exception) { continuation.resumeWithException(error) }
    }, Executor { it.run() })
}
