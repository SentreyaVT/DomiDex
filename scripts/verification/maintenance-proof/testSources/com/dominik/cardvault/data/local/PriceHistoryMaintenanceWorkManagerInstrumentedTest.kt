package com.dominik.cardvault.data.local

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.SystemClock
import android.util.Base64
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.impl.ExecutionListener
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.dominik.cardvault.data.sync.*
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual WorkManager 2.11.2 queue + production Worker Result mapping; not OS scheduling proof. */
@RunWith(AndroidJUnit4::class)
class PriceHistoryMaintenanceWorkManagerInstrumentedTest {
    private enum class Mode { FAILURE_CONTROL, INVALID, EXHAUSTED }
    private data class Attempt(val id: UUID, val number: Int, val epoch: Long, val elapsed: Long)

    @Test fun failedPrerequisiteReallyPoisonsAlreadyBlockedSuccessor(): Unit = exercise(Mode.FAILURE_CONTROL)
    @Test fun actualPausedWorkerAllowsBlockedFreshImportSuccessor(): Unit = exercise(Mode.INVALID)
    @Test fun actualRetryExhaustedWorkerAllowsBlockedFreshImportSuccessor(): Unit = exercise(Mode.EXHAUSTED)

    /** The only synthetic Result belongs to the deliberately failing negative-control worker. */
    private class FailureControl(context: Context, params: WorkerParameters,
        private val ready: CompletableDeferred<Unit>, private val release: CompletableDeferred<Unit>,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            ready.complete(Unit)
            release.await()
            return Result.failure()
        }
    }

    private fun exercise(mode: Mode): Unit = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val args = InstrumentationRegistry.getArguments()
        val run = requireNotNull(args.getString("maintenanceProofRun"))
        val invocation = requireNotNull(args.getString("maintenanceInvocation"))
        check(run.matches(Regex("[a-f0-9]{32}")) && invocation.matches(Regex("[a-f0-9]{32}")))
        check(context.packageName == "com.dominik.cardvault.maintenanceproof.r$run")
        check(context.applicationContext.javaClass == Application::class.java)
        check(context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        check(context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
        val uuid = UUID.randomUUID().toString()
        val root = File(context.cacheDir.canonicalFile, "maintenance-queue-$uuid")
        check(!root.exists() && root.mkdir())
        val file = File(root, "fixture.db")
        check(file.canonicalFile.parentFile == root && !file.exists())
        val reportFile = File(context.cacheDir.canonicalFile, "maintenance-queue-$invocation-${mode.name.lowercase()}.json")
        check(!reportFile.exists() && reportFile.canonicalFile.parentFile == context.cacheDir.canonicalFile)
        val report = JSONObject().put("schema", 1).put("mode", mode.name).put("status", "RUNNING")
            .put("package", context.packageName).put("runId", run).put("invocation", invocation)
        val room = Room.databaseBuilder(context, CardVaultDatabase::class.java, file.path).build()
        var manager: WorkManagerImpl? = null
        val workers = Executors.newFixedThreadPool(2)
        val tasks = Executors.newSingleThreadExecutor()
        val ready = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val activeBatches = AtomicInteger()
        val attempts = Collections.synchronizedList(mutableListOf<Attempt>())
        val errors = Collections.synchronizedList(mutableListOf<String>())
        val executions = MutableStateFlow<List<Pair<String, Boolean>>>(emptyList())
        val executionListener = ExecutionListener { id, reschedule ->
            executions.value = executions.value + (id.workSpecId to reschedule)
        }
        val eligibility = mutableMapOf<Int, Long>()
        val firstId = AtomicReference<UUID?>()
        var owned = false
        var failure: Throwable? = null
        try {
            withTimeout(180_000L) {
                assertEquals(file.canonicalPath, File(room.openHelper.writableDatabase.path).canonicalPath)
                room.cardVaultDao().putMetadata(AppMetadataEntity(OWNER_KEY, uuid))
                owned = true
                importFresh(room, REFERENCE, 100)
                room.cardVaultDao().insertRawPrices(listOf(RawPriceSnapshotEntity(
                    cardId = CARD, source = CardmarketSyncContract.SOURCE, currency = "EUR",
                    lowValueMinor = null, averageValueMinor = -3, trendValueMinor = 0, marketValueMinor = 0,
                    fetchedAtEpochMillis = REFERENCE, effectiveAtEpochMillis = REFERENCE - 1_000,
                )))
                if (mode == Mode.INVALID) room.cardVaultDao().putMetadata(AppMetadataEntity(PENDING, "invalid-ticket"))
                val before = snapshot(room)
                val actual = PriceHistoryMaintenance(room)
                val factory = object : WorkerFactory() {
                    override fun createWorker(appContext: Context, name: String, params: WorkerParameters): ListenableWorker {
                        // Include rejected factory calls in the real Processor-completion barrier.
                        attempts.add(Attempt(params.id, params.runAttemptCount, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                        try {
                            check(appContext.packageName == context.packageName)
                            val parentId = checkNotNull(firstId.get())
                            // Own WorkSpec.input remains empty (assertSpec below). WorkManager's
                            // default InputMerger additionally supplies successful parent outputs.
                            val expectedInput: Map<String, Any?> = if (params.id == parentId) emptyMap() else when (mode) {
                                Mode.FAILURE_CONTROL -> error("Failed control successor must never be created")
                                Mode.INVALID -> mapOf(
                                    PriceHistoryMaintenanceWorker.STATUS_KEY to "PAUSED_INVALID_TICKET",
                                    PriceHistoryMaintenanceWorker.COMPLETE_KEY to false,
                                    "batches" to 1, "examinedRows" to 0, "deletedRows" to 0,
                                )
                                Mode.EXHAUSTED -> mapOf(
                                    PriceHistoryMaintenanceWorker.STATUS_KEY to "RETRY_EXHAUSTED",
                                    PriceHistoryMaintenanceWorker.COMPLETE_KEY to false,
                                )
                            }
                            check(params.inputData.keyValueMap == expectedInput)
                            check(name in setOf(PriceHistoryMaintenanceWorker::class.java.name, FailureControl::class.java.name))
                            if (name == FailureControl::class.java.name) {
                                check(mode == Mode.FAILURE_CONTROL && params.runAttemptCount == 0)
                                return FailureControl(appContext, params, ready, release)
                            }
                            return PriceHistoryMaintenanceWorker(appContext, params) {
                                activeBatches.incrementAndGet()
                                try {
                                    if (params.id == firstId.get() && mode == Mode.EXHAUSTED) {
                                        check(params.runAttemptCount in 0..2)
                                        if (params.runAttemptCount == 2) { ready.complete(Unit); release.await() }
                                        throw IOException("Owned queue fixture retryable failure")
                                    }
                                    // The PAUSED predecessor delegates to the genuine coordinator before
                                    // the barrier. Its INVALID result is captured before the new import.
                                    val batch = actual.runBatch()
                                    if (params.id == firstId.get() && mode == Mode.INVALID) {
                                        check(batch.status == PriceHistoryMaintenance.Status.INVALID_TICKET)
                                        ready.complete(Unit)
                                        release.await()
                                    }
                                    batch
                                } finally { activeBatches.decrementAndGet() }
                            }
                        } catch (caught: Throwable) {
                            errors.add(caught.javaClass.name)
                            throw caught
                        }
                    }
                }
                WorkManagerTestInitHelper.initializeTestWorkManager(context,
                    Configuration.Builder().setWorkerFactory(factory).setExecutor(workers).setTaskExecutor(tasks).build(),
                    WorkManagerTestInitHelper.ExecutorsMode.USE_TIME_BASED_SCHEDULING)
                val wm = WorkManager.getInstance(context) as WorkManagerImpl
                manager = wm
                wm.processor.addExecutionListener(executionListener)
                assertEquals(listOf("androidx.work.testing.TestScheduler"), wm.schedulers.map { it.javaClass.name })
                assertTrue(wm.getWorkInfosForUniqueWork(NAME).get(10, TimeUnit.SECONDS).isEmpty())
                val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
                if (mode == Mode.FAILURE_CONTROL) {
                    val request = OneTimeWorkRequestBuilder<FailureControl>()
                        .setConstraints(PriceHistoryMaintenanceScheduler.request().workSpec.constraints).build()
                    wm.beginUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue().result.get(10, TimeUnit.SECONDS)
                } else PriceHistoryMaintenanceScheduler.enqueue(context)
                val first = chain(wm, 1) { it.single().state == WorkInfo.State.ENQUEUED }.single()
                firstId.set(first.id)
                if (mode != Mode.FAILURE_CONTROL) assertSpec(wm, first.id)
                driver.setAllConstraintsMet(first.id)
                if (mode == Mode.EXHAUSTED) for (attempt in 1..2) {
                    val retry = chain(wm, 1) {
                        it.single().state == WorkInfo.State.ENQUEUED && it.single().runAttemptCount == attempt
                    }.single()
                    val spec = requireNotNull(wm.workDatabase.workSpecDao().getWorkSpec(first.id.toString()))
                    val next = spec.lastEnqueueTime + 30_000L * (1L shl (attempt - 1))
                    assertEquals(next, spec.calculateNextRunTime())
                    assertEquals(next, retry.nextScheduleTimeMillis)
                    eligibility[attempt] = next
                    assertEquals(before, snapshot(room))
                    // Real default clock/backoff, no TestDriver delay override or synthetic runAttemptCount.
                    driver.setAllConstraintsMet(first.id)
                }
                ready.await()
                chain(wm, 1) { it.single().state == WorkInfo.State.RUNNING }
                assertEquals(before, snapshot(room))
                importFresh(room, REFERENCE + 1_000, 200)
                val fresh = snapshot(room)
                val latestRow = rows(room, "SELECT * FROM raw_price_snapshots WHERE effectiveAtEpochMillis=${REFERENCE + 1_000} ORDER BY id")
                assertEquals(1, latestRow.size)
                val expectedMetadata = rows(room, "SELECT * FROM app_metadata WHERE `key` NOT IN ('$PENDING','$STATE') ORDER BY rowid")
                assertNotNull(room.cardVaultDao().metadata(PENDING))
                PriceHistoryMaintenanceScheduler.enqueue(context)
                val blocked = chain(wm, 2) { values ->
                    values.single { it.id == first.id }.state == WorkInfo.State.RUNNING &&
                        values.single { it.id != first.id }.state == WorkInfo.State.BLOCKED
                }.single { it.id != first.id }
                assertSpec(wm, blocked.id)
                assertEquals(listOf(first.id.toString()), wm.workDatabase.dependencyDao().getPrerequisites(blocked.id.toString()))
                driver.setAllConstraintsMet(blocked.id)
                assertEquals(WorkInfo.State.BLOCKED, wm.getWorkInfoById(blocked.id).get(10, TimeUnit.SECONDS)!!.state)
                report.put("blockedBarrierObserved", true).put("predecessor", first.id.toString()).put("successor", blocked.id.toString())
                release.complete(Unit)
                val terminal = chain(wm, 2) { it.all { work -> work.state.isFinished } }
                val parent = terminal.single { it.id == first.id }
                val child = terminal.single { it.id == blocked.id }
                if (mode == Mode.FAILURE_CONTROL) {
                    assertEquals(WorkInfo.State.FAILED, parent.state)
                    assertEquals(WorkInfo.State.FAILED, child.state)
                    assertEquals(listOf(first.id), synchronized(attempts) { attempts.map { it.id } })
                    assertEquals(fresh, snapshot(room))
                } else {
                    val status = if (mode == Mode.INVALID) "PAUSED_INVALID_TICKET" else "RETRY_EXHAUSTED"
                    assertEquals(WorkInfo.State.SUCCEEDED, parent.state)
                    assertEquals(status, parent.outputData.getString(PriceHistoryMaintenanceWorker.STATUS_KEY))
                    assertFalse(parent.outputData.getBoolean(PriceHistoryMaintenanceWorker.COMPLETE_KEY, true))
                    assertEquals(WorkInfo.State.SUCCEEDED, child.state)
                    assertEquals("COMPLETE", child.outputData.getString(PriceHistoryMaintenanceWorker.STATUS_KEY))
                    assertTrue(child.outputData.getBoolean(PriceHistoryMaintenanceWorker.COMPLETE_KEY, false))
                    val after = snapshot(room)
                    assertEquals(fresh - setOf("raw_price_snapshots", "app_metadata"), after - setOf("raw_price_snapshots", "app_metadata"))
                    assertEquals(latestRow, after.getValue("raw_price_snapshots"))
                    assertEquals(expectedMetadata, after.getValue("app_metadata"))
                    assertNull(room.cardVaultDao().metadata(PENDING)); assertNull(room.cardVaultDao().metadata(STATE))
                    val observed = synchronized(attempts) { attempts.toList() }
                    assertEquals(if (mode == Mode.EXHAUSTED) listOf(0, 1, 2, 0) else listOf(0, 0), observed.map { it.number })
                    assertEquals(1, observed.count { it.id == child.id })
                    for ((number, eligible) in eligibility) {
                        assertTrue(observed.single { it.id == parent.id && it.number == number }.epoch >= eligible)
                    }
                }
                assertTrue(errors.isEmpty())
                assertEquals(0, activeBatches.get())
                val invoked = synchronized(attempts) { attempts.toList() }
                val finished = withTimeout(10_000L) { executions.first { it.size == invoked.size } }
                assertEquals(invoked.groupingBy { it.id.toString() }.eachCount(), finished.groupingBy { it.first }.eachCount())
                assertTrue(terminal.none { wm.processor.isEnqueued(it.id.toString()) })
                report.put("parentState", parent.state.name).put("childState", child.state.name)
                    .put("parentStatus", parent.outputData.getString(PriceHistoryMaintenanceWorker.STATUS_KEY))
                    .put("childStatus", child.outputData.getString(PriceHistoryMaintenanceWorker.STATUS_KEY))
                    .put("attemptCounts", JSONArray(synchronized(attempts) { attempts.map { it.number } }))
                    .put("bodyPassed", true)
            }
        } catch (caught: Throwable) { failure = caught; report.put("bodyPassed", false).put("failureType", caught.javaClass.name) }
        finally {
            withContext(NonCancellable) {
                release.complete(Unit)
                try {
                    manager?.let { wm ->
                        // Only this exact named chain under the guarded fresh proof UID is owned.
                        wm.cancelUniqueWork(NAME).result.get(10, TimeUnit.SECONDS)
                        check(wm.getWorkInfosForUniqueWork(NAME).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
                        val invoked = synchronized(attempts) { attempts.toList() }
                        withTimeout(10_000L) { executions.first { it.size == invoked.size } }
                        check(invoked.none { wm.processor.isEnqueued(it.id.toString()) }) { "Retain fixture: Processor still active" }
                        check(activeBatches.get() == 0) { "Retain fixture: batch still active" }
                        wm.processor.removeExecutionListener(executionListener)
                        WorkManagerTestInitHelper.closeWorkDatabase()
                    }
                    workers.shutdown(); tasks.shutdown()
                    check(workers.awaitTermination(10, TimeUnit.SECONDS) && tasks.awaitTermination(10, TimeUnit.SECONDS))
                    if (owned) check(room.cardVaultDao().metadata(OWNER_KEY) == uuid)
                    room.close()
                    check(!room.isOpen && root.parentFile == context.cacheDir.canonicalFile && root.name == "maintenance-queue-$uuid")
                    val files = checkNotNull(root.listFiles())
                    check(files.all { it.name in setOf("fixture.db", "fixture.db-wal", "fixture.db-shm", "fixture.db-journal") &&
                        it.isFile && it.canonicalFile.parentFile == root })
                    files.forEach { check(it.delete()) }; check(root.delete())
                    report.put("cleanupPassed", true)
                } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                    report.put("cleanupPassed", false).put("cleanupFailureType", caught.javaClass.name)
                    // No broad cleanup, DB replacement, process stop or ordinary-file fallback.
                }
                report.put("status", if (failure == null) "PASS" else "FAIL")
                val bytes = report.toString(2).toByteArray(Charsets.UTF_8)
                check(bytes.size <= 32 * 1024)
                reportFile.writeBytes(bytes)
            }
        }
        failure?.let { throw it }
    }

    private suspend fun chain(wm: WorkManager, count: Int, condition: (List<WorkInfo>) -> Boolean): List<WorkInfo> =
        withTimeout(110_000L) { wm.getWorkInfosForUniqueWorkFlow(NAME).first { it.size == count && condition(it) } }

    private fun assertSpec(wm: WorkManagerImpl, id: UUID) {
        val spec = requireNotNull(wm.workDatabase.workSpecDao().getWorkSpec(id.toString()))
        assertEquals(PriceHistoryMaintenanceWorker::class.java.name, spec.workerClassName)
        assertEquals(NetworkType.NOT_REQUIRED, spec.constraints.requiredNetworkType)
        assertTrue(spec.constraints.requiresBatteryNotLow()); assertTrue(spec.constraints.requiresStorageNotLow())
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy); assertEquals(30_000L, spec.backoffDelayDuration)
        assertTrue(spec.input.keyValueMap.isEmpty())
    }

    private suspend fun importFresh(room: CardVaultDatabase, at: Long, trend: Long) {
        val date = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))
        val reference = requireNotNull(CardmarketHistoryReference.fromPriceGuideResponse(date, at))
        val source = Instant.ofEpochMilli(at).toString()
        val prepared = prepareCardmarketImport(
            ParsedDownload(1, source, listOf(CardmarketProduct(42, "Queue fixture", 51, null, null))),
            ParsedDownload(1, source, listOf(CardmarketPrice(42, 51, null, null, trend, null, null, null, null, null, null))),
        )
        persistCardmarketImport(room, prepared, at, reference)
    }

    private fun snapshot(room: CardVaultDatabase): Map<String, List<String>> {
        val tables = room.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT IN ('android_metadata','room_master_table') ORDER BY name").use {
            buildList { while (it.moveToNext()) add(it.getString(0)) }
        }
        return tables.associateWith { table -> check(table.matches(Regex("[a-z_]+"))); rows(room, "SELECT * FROM `$table` ORDER BY rowid") }
    }

    private fun rows(room: CardVaultDatabase, sql: String): List<String> = room.openHelper.readableDatabase.query(sql).use { cursor -> buildList {
        while (cursor.moveToNext()) {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { output ->
                output.writeInt(cursor.columnCount)
                for (index in 0 until cursor.columnCount) {
                    output.writeUTF(cursor.getColumnName(index))
                    output.writeInt(cursor.getType(index))
                    when (cursor.getType(index)) {
                        Cursor.FIELD_TYPE_NULL -> Unit
                        Cursor.FIELD_TYPE_INTEGER -> output.writeLong(cursor.getLong(index))
                        Cursor.FIELD_TYPE_FLOAT -> output.writeLong(cursor.getDouble(index).toBits())
                        Cursor.FIELD_TYPE_STRING -> { val value = cursor.getString(index).toByteArray(Charsets.UTF_8); output.writeInt(value.size); output.write(value) }
                        Cursor.FIELD_TYPE_BLOB -> { val value = cursor.getBlob(index); output.writeInt(value.size); output.write(value) }
                        else -> error("Unknown stored type")
                    }
                }
            }
            add(Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        }
    } }

    private companion object {
        const val NAME = PriceHistoryMaintenanceScheduler.UNIQUE_WORK
        const val OWNER_KEY = "maintenance-queue-fixture-owner"
        const val PENDING = PriceHistoryMaintenanceCodec.PENDING_KEY
        const val STATE = PriceHistoryMaintenanceCodec.CURSOR_KEY
        const val CARD = "cm-pokemon-42"
        val REFERENCE = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
    }
}
