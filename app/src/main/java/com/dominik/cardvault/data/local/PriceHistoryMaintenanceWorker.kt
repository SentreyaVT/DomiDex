package com.dominik.cardvault.data.local

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val MAINTENANCE_MAX_BATCHES = 128
internal const val MAINTENANCE_SLICE_MILLIS = 30_000L
internal const val MAINTENANCE_MAX_ATTEMPTS = 3

internal enum class MaintenanceSliceStatus { COMPLETE, CONTINUED, PAUSED_INVALID_TICKET }
internal data class MaintenanceSlice(
    val status: MaintenanceSliceStatus,
    val batches: Int,
    val examinedRows: Int,
    val deletedRows: Int,
)

/**
 * Injectable coordination only. The monotonic budget is checked BETWEEN transactions; it is
 * not a SQL deadline and never turns cancellation/failed enqueue into completion. Each real
 * batch still reads current all-owner protection and commits cursor + deletion atomically.
 */
internal suspend fun runPriceHistoryMaintenanceSlice(
    nextBatch: suspend () -> PriceHistoryMaintenance.Batch,
    enqueueContinuation: suspend () -> Unit,
    elapsedRealtimeMillis: () -> Long,
): MaintenanceSlice {
    val started = elapsedRealtimeMillis()
    var batches = 0
    var examined = 0
    var deleted = 0
    fun result(status: MaintenanceSliceStatus) = MaintenanceSlice(status, batches, examined, deleted)
    while (batches < MAINTENANCE_MAX_BATCHES) {
        currentCoroutineContext().ensureActive()
        val elapsed = elapsedRealtimeMillis() - started
        check(elapsed >= 0L) { "Monotonic maintenance clock moved backwards" }
        if (elapsed >= MAINTENANCE_SLICE_MILLIS) break
        val batch = nextBatch()
        currentCoroutineContext().ensureActive()
        batches++
        examined += batch.examinedRows
        deleted += batch.deletedRows
        when (batch.status) {
            PriceHistoryMaintenance.Status.NO_TICKET -> return result(MaintenanceSliceStatus.COMPLETE)
            PriceHistoryMaintenance.Status.INVALID_TICKET -> return result(MaintenanceSliceStatus.PAUSED_INVALID_TICKET)
            PriceHistoryMaintenance.Status.PASS_COMPLETE -> if (!batch.pendingAnotherPass) {
                return result(MaintenanceSliceStatus.COMPLETE)
            }
            PriceHistoryMaintenance.Status.MORE -> Unit
        }
    }
    currentCoroutineContext().ensureActive()
    enqueueContinuation() // Must acknowledge durable WorkManager enqueue before CONTINUED.
    currentCoroutineContext().ensureActive()
    return result(MaintenanceSliceStatus.CONTINUED)
}

/** runAttemptCount is zero-based: initial run plus at most two automatic retries. */
internal fun maintenanceCanRetry(runAttemptCount: Int): Boolean =
    runAttemptCount >= 0 && runAttemptCount < MAINTENANCE_MAX_ATTEMPTS - 1

/**
 * Global, local-only queue: no owner, Firebase, network constraint, close, checkpoint or VACUUM.
 * A ticket is the durable truth, not WorkManager output. COMPLETE means no pending ticket at
 * the coordinator's transaction boundary; a later import always adds its own successor.
 */
class PriceHistoryMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private var suppliedNextBatch: (suspend () -> PriceHistoryMaintenance.Batch)? = null

    /** Per-instance coordinator dependency; ordinary WorkManager uses the unchanged two-arg constructor. */
    internal constructor(
        context: Context,
        params: WorkerParameters,
        nextBatch: suspend () -> PriceHistoryMaintenance.Batch,
    ) : this(context, params) {
        suppliedNextBatch = nextBatch
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val maintenance = if (suppliedNextBatch == null) PriceHistoryMaintenance(CardVaultDatabase.get(applicationContext)) else null
            val outcome = runPriceHistoryMaintenanceSlice(
                nextBatch = suppliedNextBatch ?: { checkNotNull(maintenance).runBatch(PriceHistoryRetention.MAX_EXAMINED_ROWS) },
                enqueueContinuation = { PriceHistoryMaintenanceScheduler.enqueue(applicationContext) },
                elapsedRealtimeMillis = SystemClock::elapsedRealtime,
            )
            // Deliberately SUCCESS even for PAUSED: WorkManager FAILURE poisons successors
            // already appended by a fresh import. The explicit output is NOT a cleanup PASS.
            Result.success(workDataOf(
                STATUS_KEY to outcome.status.name,
                COMPLETE_KEY to (outcome.status == MaintenanceSliceStatus.COMPLETE),
                "batches" to outcome.batches,
                "examinedRows" to outcome.examinedRows,
                "deletedRows" to outcome.deletedRows,
            ))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // No exception payload, ticket, card, owner or personal data is logged.
            if (maintenanceCanRetry(runAttemptCount)) {
                Log.w(LOG_TAG, "RETRY_PENDING: committed maintenance intent retained.")
                Result.retry()
            } else {
                Log.w(LOG_TAG, "RETRY_EXHAUSTED: committed maintenance intent retained.")
                // Terminal transport completion unblocks fresh successors; no self-enqueue.
                Result.success(workDataOf(STATUS_KEY to "RETRY_EXHAUSTED", COMPLETE_KEY to false))
            }
        }
    }

    companion object {
        internal const val STATUS_KEY = "maintenanceStatus"
        internal const val COMPLETE_KEY = "maintenanceComplete"
        internal const val LOG_TAG = "PriceHistoryMaintenance"
    }
}

internal object PriceHistoryMaintenanceScheduler {
    internal const val UNIQUE_WORK = "domidex-price-history-maintenance-v1"

    internal fun request(): OneTimeWorkRequest = OneTimeWorkRequestBuilder<PriceHistoryMaintenanceWorker>()
        .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
        .build()

    /** Do not replace a running worker or KEEP away an import arriving during its last batch. */
    internal suspend fun enqueue(context: Context) {
        currentCoroutineContext().ensureActive()
        WorkManager.getInstance(context.applicationContext)
            .beginUniqueWork(UNIQUE_WORK, ExistingWorkPolicy.APPEND_OR_REPLACE, request())
            .enqueue().awaitMaintenanceEnqueue()
        currentCoroutineContext().ensureActive()
    }

    /**
     * Called only after a committed successful import (outside its mutex/error handler) or
     * successful local startup preparation. Failure leaves the existing ticket recoverable;
     * it never relabels an already committed import ERROR or manufactures a clock/ticket.
     * The bounded worker itself uses strict enqueue(), not this best-effort recovery wrapper.
     */
    internal suspend fun recoverPendingAfterCommit(context: Context, database: CardVaultDatabase) {
        try {
            val pending = withContext(Dispatchers.IO) {
                database.cardVaultDao().metadata(PriceHistoryMaintenanceCodec.PENDING_KEY)
            }
            currentCoroutineContext().ensureActive()
            if (pending != null) enqueue(context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            Log.w(PriceHistoryMaintenanceWorker.LOG_TAG, "ENQUEUE_DEFERRED: committed maintenance intent retained.")
        }
    }
}

/** Same non-cancelling Operation-result bridge already used by awaitSharingEnqueue. */
private suspend fun Operation.awaitMaintenanceEnqueue() = suspendCancellableCoroutine<Unit> { continuation ->
    val completed = result
    completed.addListener({
        if (!continuation.isActive) return@addListener
        try { completed.get(); continuation.resume(Unit) }
        catch (error: Exception) { continuation.resumeWithException(error) }
    }, Executor { it.run() })
}
