package com.dominik.cardvault.data.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.security.NetworkSecurityPolicy
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.BackoffPolicy
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.WorkManagerImpl
import androidx.work.testing.WorkManagerTestInitHelper
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentVerifiedFirebaseEntitlementAccount
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.PrivateBinderEntity
import com.dominik.cardvault.data.local.PrivateBinderSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_PENDING
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual production scheduler -> WorkManager 2.11.2 -> unchanged production worker.
 * TestDriver controls CONNECTED only; system Clock/default RunnableScheduler retain
 * the real default backoff. No direct doWork/shared-run invocation or fake Result.
 * This is NOT JobScheduler, physical connectivity or process-restart evidence.
 */
@RunWith(AndroidJUnit4::class)
class PrivateBinderWorkManagerInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var run: String
    private var room: CardVaultDatabase? = null
    private val database get() = checkNotNull(room)
    private val dao get() = database.cardVaultDao()
    private var workManager: WorkManagerImpl? = null
    private var gate: PrivatePullDemoGrpcGate? = null
    private val clients = mutableListOf<Client>()
    private lateinit var subject: Client
    private lateinit var witness: Client
    private lateinit var other: Client
    private val attempts = Collections.synchronizedList(mutableListOf<Attempt>())
    private val factoryFailures = Collections.synchronizedList(mutableListOf<String>())
    private val workers = Executors.newFixedThreadPool(2)
    private val tasks = Executors.newSingleThreadExecutor()
    private val report = JSONObject().put("kind", "actualPrivateWorkManagerRetry").put("status", "RUNNING")
    private var reportFile: File? = null
    private var ownsRoom = false

    private data class Client(val app: FirebaseApp, val auth: FirebaseAuth, val firestore: FirebaseFirestore)
    private data class Attempt(val id: UUID, val attempt: Int, val epoch: Long, val elapsed: Long)

    @Test fun actualPrivateWorkerRetriesAndAcknowledgesWithoutBypassingScheduler() = runBlocking<Unit>(Dispatchers.IO) {
        var failure: Throwable? = null
        try {
            withTimeout(150_000) {
                setup()
                exercise()
            }
            report.put("bodyPassed", true)
        } catch (caught: Throwable) {
            failure = caught
            report.put("bodyPassed", false).put("failureType", caught.javaClass.name)
        } finally {
            withContext(NonCancellable) {
                try { cleanup() } catch (caught: Throwable) {
                    if (failure == null) failure = caught else failure!!.addSuppressed(caught)
                    report.put("cleanupFailureType", caught.javaClass.name)
                }
                report.put("status", if (failure == null) "PASS" else "FAIL")
                reportFile?.let { file ->
                    check(file.canonicalFile.parentFile == context.cacheDir.canonicalFile)
                    file.writeText(report.toString(2), Charsets.UTF_8)
                }
            }
        }
        failure?.let { throw it }
    }

    private suspend fun setup() {
        run = checkNotNull(InstrumentationRegistry.getArguments().getString("wmProofRun"))
        check(run.matches(Regex("[0-9a-f]{32}")))
        check(InstrumentationRegistry.getArguments().getString("wmProofMode") == "demo-loopback-only")
        check(context.packageName == "com.dominik.cardvault.wmproof.r$run")
        requireOfflineEmulator()
        report.put("runId", run).put("packageName", context.packageName)
        reportFile = File(context.cacheDir, "wm-proof-$run.json").also { check(!it.exists()) }
        reportFile!!.writeText(report.toString(), Charsets.UTF_8)
        check(FirebaseApp.getApps(context).isEmpty()) { "A provider initialized Firebase before the proof" }
        check(runCatching { WorkManager.getInstance(context) }.exceptionOrNull() is IllegalStateException) {
            "WorkManager must not be initialized by a provider"
        }
        val proofPath = context.getDatabasePath("cardvault.db").canonicalFile
        check(proofPath.path.startsWith(context.dataDir.canonicalPath + File.separator))
        check(context.packageName in proofPath.path)
        check(listOf("", "-wal", "-shm", "-journal").none { File(proofPath.path + it).exists() })
        for (port in listOf(PrivatePullDemoGrpcGate.AUTH_PORT, PrivatePullDemoGrpcGate.FIRESTORE_PORT)) {
            Socket().use { it.connect(InetSocketAddress(PrivatePullDemoGrpcGate.HOST, port), 3_000) }
        }
        gate = PrivatePullDemoGrpcGate().also { it.verifyForeignDatabaseFailsBeforeStart() }
        subject = client(null, OWNER)
        witness = client("wm-witness-$run", OWNER)
        other = client("wm-other-$run", OTHER)
        assertEquals(OWNER, subject.auth.currentVerifiedFirebaseEntitlementAccount(subject.firestore, false)?.uid)
        assertEmptyServer(witness, OWNER)
        assertEmptyServer(other, OTHER)

        // The literal production filename is untouched. Its package directory is unique per run.
        room = CardVaultDatabase.get(context)
        assertTrue(snapshot().values.all { it.isEmpty() })
        database.withTransaction {
            dao.putMetadata(AppMetadataEntity(MARKER, "$run|$OWNER|${context.packageName}"))
            dao.insertPrivateBinder(pendingBinder(OWNER))
            dao.upsertPrivateBinderSyncOutbox(mutation(OWNER))
            dao.insertPrivateBinder(pendingBinder(OTHER))
            dao.upsertPrivateBinderSyncOutbox(mutation(OTHER))
        }
        ownsRoom = true
        val openedPath = File(database.openHelper.writableDatabase.path)
        // Android may expose /data/user/0 and /data/data aliases for this same
        // package-private file. Compare canonical files, retaining exact ownership.
        report.put("roomOpenedPath", openedPath.path).put("roomCanonicalPath", openedPath.canonicalPath)
        check(openedPath.canonicalFile == proofPath)

        val factory = object : WorkerFactory() {
            override fun createWorker(appContext: Context, workerClassName: String, params: WorkerParameters): ListenableWorker {
                try {
                    check(appContext.packageName == context.packageName)
                    check(workerClassName == PrivateBinderSyncWorker::class.java.name)
                    check(params.inputData.keyValueMap == mapOf(PrivateBinderSyncScheduler.INPUT_OWNER_UID to OWNER))
                    check(FirebaseApp.getInstance().options.projectId == PrivatePullDemoGrpcGate.PROJECT)
                    check(FirebaseAuth.getInstance().currentUser?.uid == OWNER)
                    check(FirebaseFirestore.getInstance() === subject.firestore)
                    check(CardVaultDatabase.get(appContext) === database)
                    attempts.add(Attempt(params.id, params.runAttemptCount, System.currentTimeMillis(), SystemClock.elapsedRealtime()))
                    return PrivateBinderSyncWorker(appContext, params)
                } catch (error: Throwable) {
                    factoryFailures.add(error.javaClass.name)
                    throw error
                }
            }
        }
        val configuration = Configuration.Builder().setWorkerFactory(factory)
            .setExecutor(workers).setTaskExecutor(tasks).build()
        WorkManagerTestInitHelper.initializeTestWorkManager(context, configuration,
            WorkManagerTestInitHelper.ExecutorsMode.USE_TIME_BASED_SCHEDULING)
        workManager = WorkManager.getInstance(context) as WorkManagerImpl
        assertEquals(listOf("androidx.work.testing.TestScheduler"), workManager!!.schedulers.map { it.javaClass.name })
        assertTrue(workIds().isEmpty())
        checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
    }

    private suspend fun exercise() {
        val wm = checkNotNull(workManager)
        val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(context))
        val before = snapshot()
        assertEquals(2, before.getValue("private_binders").size)
        assertEquals(2, before.getValue("private_binder_sync_outbox").size)
        assertEquals(1, before.getValue("app_metadata").size)
        assertTrue((before - setOf("private_binders", "private_binder_sync_outbox", "app_metadata")).values.all { it.isEmpty() })

        subject.firestore.disableNetwork().bounded()
        PrivateBinderSyncScheduler.schedule(context, OWNER, userInitiated = false)
        val initial = awaitChain(1) { it.single().state == WorkInfo.State.ENQUEUED }.single()
        val id = initial.id
        assertSpec(id, expectedAttempts = 0)
        // Bounded negative observation, not an Android connectivity/SLA claim.
        delay(750)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(id).get(5, TimeUnit.SECONDS)!!.state)
        assertTrue(attempts.isEmpty())
        assertEquals(before, snapshot())
        assertEmptyServer(witness, OWNER)
        assertEmptyServer(other, OTHER)
        report.put("constraintsBlockedWithoutInvocation", true)

        driver.setAllConstraintsMet(id)
        val retry = awaitChain(1) {
            it.single().state == WorkInfo.State.ENQUEUED && it.single().runAttemptCount == 1
        }.single()
        assertEquals(listOf(0), attemptSnapshot().map { it.attempt })
        assertSpec(id, expectedAttempts = 1)
        val retrySpec = checkNotNull(wm.workDatabase.workSpecDao().getWorkSpec(id.toString()))
        val eligibleAt = retrySpec.calculateNextRunTime()
        assertEquals(retrySpec.lastEnqueueTime + 30_000L, eligibleAt)
        assertEquals(eligibleAt, retry.nextScheduleTimeMillis)
        assertEquals(before, snapshot())
        assertEmptyServer(witness, OWNER)
        assertEmptyServer(other, OTHER)
        report.put("retryObserved", true).put("retryEligibleEpochMillis", eligibleAt)
            .put("retryLastEnqueueEpochMillis", retrySpec.lastEnqueueTime)

        subject.firestore.enableNetwork().bounded()
        assertEquals(OWNER, subject.auth.currentVerifiedFirebaseEntitlementAccount(subject.firestore, false)?.uid)
        assertEquals(before, snapshot())
        // No clock replacement, schedule override, initialDelay shortcut or direct Processor call.
        assertTrue("The measured retry window must still permit an early-execution check",
            System.currentTimeMillis() + 2_000 < eligibleAt)
        driver.setAllConstraintsMet(id)
        delay(1_000)
        assertEquals(1, attempts.size)
        assertEquals(WorkInfo.State.ENQUEUED, wm.getWorkInfoById(id).get(5, TimeUnit.SECONDS)!!.state)
        assertEquals(before, snapshot())
        val completed = awaitChain(1, timeout = 55_000) { it.single().state == WorkInfo.State.SUCCEEDED }.single()
        assertEquals(id, completed.id)
        assertEquals(listOf(0, 1), attemptSnapshot().map { it.attempt })
        val second = attemptSnapshot()[1]
        assertTrue("Actual WorkManager retry must not run before its untouched eligibility", second.epoch >= eligibleAt)
        assertTrue(second.elapsed > attemptSnapshot()[0].elapsed)
        report.put("secondAttemptEpochMillis", second.epoch).put("secondAttemptElapsedMillis", second.elapsed)
            .put("backoffUsedRealClock", true)
        assertRecovered(before)
        val recovered = snapshot()
        val remote = assertServerBinder()
        assertEmptyServer(other, OTHER)

        // A second production APPEND_OR_REPLACE enqueue, not direct invocation, proves idempotence.
        PrivateBinderSyncScheduler.schedule(context, OWNER, userInitiated = false)
        val chain = awaitChain(2) { rows -> rows.count { it.state == WorkInfo.State.ENQUEUED } == 1 }
        val next = chain.single { it.id != id }
        assertEquals(WorkInfo.State.ENQUEUED, next.state)
        assertSpec(next.id, expectedAttempts = 0)
        val prerequisites = wm.workDatabase.dependencyDao().getPrerequisites(next.id.toString())
        assertEquals(listOf(id.toString()), prerequisites)
        assertEquals(2, attempts.size)
        driver.setAllConstraintsMet(next.id)
        awaitChain(2) { it.all { info -> info.state == WorkInfo.State.SUCCEEDED } }
        assertEquals(listOf(0, 1, 0), attemptSnapshot().map { it.attempt })
        assertEquals(listOf(id, id, next.id), attemptSnapshot().map { it.id })
        assertEquals(recovered, snapshot())
        assertEquals(remote, assertServerBinder())
        assertEmptyServer(other, OTHER)
        assertTrue(factoryFailures.isEmpty())
        report.put("secondEnqueueIdempotent", true).put("exactWorkSpecCount", 2)
            .put("unchangedForeignOwner", true).put("roomTables", JSONArray(TABLES))
            .put("attempts", JSONArray(attemptSnapshot().map { attempt -> JSONObject()
                .put("id", attempt.id.toString()).put("attempt", attempt.attempt)
                .put("epochMillis", attempt.epoch).put("elapsedMillis", attempt.elapsed) }))
        requireOfflineEmulator()
    }

    private fun assertSpec(id: UUID, expectedAttempts: Int) {
        val spec = checkNotNull(workManager!!.workDatabase.workSpecDao().getWorkSpec(id.toString()))
        assertEquals(PrivateBinderSyncWorker::class.java.name, spec.workerClassName)
        assertEquals(mapOf(PrivateBinderSyncScheduler.INPUT_OWNER_UID to OWNER), spec.input.keyValueMap)
        assertEquals(NetworkType.CONNECTED, spec.constraints.requiredNetworkType)
        assertFalse(spec.expedited)
        assertFalse(spec.isPeriodic)
        assertEquals(0L, spec.initialDelay)
        assertEquals(BackoffPolicy.EXPONENTIAL, spec.backoffPolicy)
        assertEquals(30_000L, spec.backoffDelayDuration)
        assertEquals(expectedAttempts, spec.runAttemptCount)
        assertEquals(Long.MAX_VALUE, spec.nextScheduleTimeOverride)
    }

    private suspend fun awaitChain(count: Int, timeout: Long = 25_000, predicate: (List<WorkInfo>) -> Boolean): List<WorkInfo> =
        withTimeout(timeout) {
            while (true) {
                assertTrue("Unknown worker/factory invocation", factoryFailures.isEmpty())
                val rows = workManager!!.getWorkInfosForUniqueWork(privateBinderSyncWorkName(OWNER)).get(5, TimeUnit.SECONDS)
                assertTrue("Unexpected continuation/work", rows.size <= count)
                assertFalse("Actual worker failed/cancelled", rows.any { it.state == WorkInfo.State.FAILED || it.state == WorkInfo.State.CANCELLED })
                if (rows.size == count && predicate(rows)) {
                    assertEquals(rows.map { it.id.toString() }.toSet(), workIds().toSet())
                    return@withTimeout rows
                }
                delay(25)
            }
            @Suppress("UNREACHABLE_CODE") emptyList()
        }

    private fun workIds(): List<String> = workManager!!.workDatabase.openHelper.readableDatabase
        .query("SELECT id FROM WorkSpec ORDER BY id").use { cursor -> buildList {
            while (cursor.moveToNext()) add(cursor.getString(0))
        } }

    private fun attemptSnapshot() = synchronized(attempts) { attempts.toList() }

    private suspend fun client(name: String?, owner: String): Client {
        val options = FirebaseOptions.Builder().setProjectId(PrivatePullDemoGrpcGate.PROJECT)
            .setApplicationId("1:1234567890:android:0000000000000000000000")
            .setApiKey("demo-wm-$run-${name ?: "default"}").build()
        val app = if (name == null) checkNotNull(FirebaseApp.initializeApp(context, options))
            else FirebaseApp.initializeApp(context, options, name)
        val auth = FirebaseAuth.getInstance(app)
        auth.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.AUTH_PORT)
        val firestore = FirebaseFirestore.getInstance(app)
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        firestore.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.FIRESTORE_PORT)
        val client = Client(app, auth, firestore).also(clients::add)
        check(app.options.projectId == PrivatePullDemoGrpcGate.PROJECT)
        check(firestore.firestoreSettings.host == "127.0.0.1:8187" && !firestore.firestoreSettings.isSslEnabled)
        val credential = GoogleAuthProvider.getCredential(
            """{"sub":"$owner-google","email":"$owner@example.invalid","email_verified":true}""", null)
        val user = checkNotNull(auth.signInWithCredential(credential).bounded().user)
        assertEquals(owner, user.uid)
        assertEquals("$owner@example.invalid", user.email)
        assertTrue(user.isEmailVerified)
        assertEquals("$owner-google", user.providerData.single { it.providerId == "google.com" }.uid)
        assertEquals(owner, canonicalCollectionOwnerUid(user))
        val token = user.getIdToken(false).bounded()
        assertEquals(true, token.claims["appAccess"])
        assertFalse(token.claims.containsKey("admin"))
        assertFalse(token.claims.containsKey("pretester"))
        assertEquals("google.com", (token.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider"))
        return client
    }

    private suspend fun remoteRows(client: Client, owner: String, collection: String): Map<String, Map<String, Any?>> {
        check(owner in setOf(OWNER, OTHER) && collection in COLLECTIONS)
        val result = client.firestore.collection("users/$owner/$collection").limit(3).get(Source.SERVER).bounded()
        assertFalse(result.metadata.isFromCache)
        assertFalse(result.metadata.hasPendingWrites())
        assertTrue(result.size() < 3)
        return result.documents.associate { doc ->
            assertFalse(doc.metadata.isFromCache)
            assertFalse(doc.metadata.hasPendingWrites())
            doc.id to checkNotNull(doc.data)
        }
    }

    private suspend fun assertEmptyServer(client: Client, owner: String) {
        for (collection in COLLECTIONS) assertTrue(remoteRows(client, owner, collection).isEmpty())
        assertAccessAbsent(client, owner)
    }

    private suspend fun assertAccessAbsent(client: Client, owner: String) {
        val access = client.firestore.document("accountAccess/$owner").get(Source.SERVER).bounded()
        assertFalse(access.metadata.isFromCache)
        assertFalse(access.metadata.hasPendingWrites())
        assertFalse(access.exists())
    }

    private suspend fun assertServerBinder(): Map<String, Any?> {
        val rows = remoteRows(witness, OWNER, "privateBinders")
        assertEquals(setOf(BINDER), rows.keys)
        val actual = rows.getValue(BINDER)
        assertTrue(actual["updatedAt"] is Timestamp)
        val expected = mapOf("binderId" to BINDER, "ownerUid" to OWNER, "name" to "Synthetic WorkManager binder",
            "description" to null, "coverStyle" to "DOMIDEX", "minimumPageCount" to 1L,
            "createdAtClient" to TIME, "updatedAtClient" to TIME, "lastOpenedAtClient" to null,
            "deleted" to false, "revision" to 1L, "lastMutationId" to "wm-proof-$run-binder",
            "updatedAt" to actual["updatedAt"])
        assertEquals(expected, actual)
        for (collection in COLLECTIONS - "privateBinders") assertTrue(remoteRows(witness, OWNER, collection).isEmpty())
        assertAccessAbsent(witness, OWNER)
        return actual
    }

    private fun pendingBinder(owner: String) = PrivateBinderEntity(
        id = BINDER, ownerUid = owner, name = "Synthetic WorkManager binder", description = null,
        coverStyle = "DOMIDEX", minimumPageCount = 1, createdAtEpochMillis = TIME,
        updatedAtEpochMillis = TIME, lastOpenedAtEpochMillis = null,
        syncState = SYNC_PENDING, remoteRevision = 0, deletedAtEpochMillis = null)

    private fun mutation(owner: String) = PrivateBinderSyncOutboxEntity(
        entryKey = "$owner:$PRIVATE_SYNC_BINDER:$BINDER",
        mutationId = if (owner == OWNER) "wm-proof-$run-binder" else "wm-proof-$run-other",
        ownerUid = owner, recordType = PRIVATE_SYNC_BINDER, recordId = BINDER,
        operation = PRIVATE_SYNC_UPSERT, expectedRemoteRevision = 0,
        updatedAtEpochMillis = TIME, createdAtEpochMillis = TIME, lastError = null)

    private suspend fun assertRecovered(before: Map<String, List<List<String?>>>) {
        assertEquals(pendingBinder(OWNER).copy(syncState = SYNC_CLEAN, remoteRevision = 1), dao.privateBinder(BINDER, OWNER))
        assertNull(dao.privateBinderSyncOutbox(mutation(OWNER).entryKey))
        assertEquals(pendingBinder(OTHER), dao.privateBinder(BINDER, OTHER))
        assertEquals(mutation(OTHER), dao.privateBinderSyncOutbox(mutation(OTHER).entryKey))
        val expectedMetadata = buildMap {
            put(MARKER, "$run|$OWNER|${context.packageName}")
            put(PrivateFullPullMetadataStore.phaseKey(OWNER), "rr1|0|0")
            for (group in 0..3) put(PrivateFullPullMetadataStore.cursorKey(OWNER, group), "")
        }
        assertEquals(expectedMetadata, metadata())
        val after = snapshot()
        assertEquals(before - setOf("private_binders", "private_binder_sync_outbox", "app_metadata"),
            after - setOf("private_binders", "private_binder_sync_outbox", "app_metadata"))
        assertEquals(2, after.getValue("private_binders").size)
        assertEquals(1, after.getValue("private_binder_sync_outbox").size)
        assertEquals(expectedMetadata.size, after.getValue("app_metadata").size)
    }

    private fun metadata(): Map<String, String> = database.openHelper.readableDatabase
        .query("SELECT `key`, value FROM app_metadata ORDER BY `key`").use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getString(1))
        } }

    private fun snapshot(): Map<String, List<List<String?>>> = TABLES.associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor -> buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
        } }
    }

    private fun requireOfflineEmulator() {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE == "ranchu")
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
        check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
        check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        val policy = NetworkSecurityPolicy.getInstance()
        check(policy.isCleartextTrafficPermitted("127.0.0.1") && !policy.isCleartextTrafficPermitted)
        check(!policy.isCleartextTrafficPermitted("10.0.2.2") && !policy.isCleartextTrafficPermitted("example.invalid"))
    }

    private suspend fun cleanup() {
        val errors = mutableListOf<Throwable>()
        suspend fun attempt(block: suspend () -> Unit) { try { block() } catch (caught: Throwable) { errors.add(caught) } }
        attempt {
            workManager?.let { wm ->
                wm.cancelAllWork().result.get(10, TimeUnit.SECONDS)
                val ids = workIds()
                for (id in ids) assertTrue(wm.getWorkInfoById(UUID.fromString(id)).get(5, TimeUnit.SECONDS)!!.state.isFinished)
                WorkManagerTestInitHelper.closeWorkDatabase()
            }
            report.put("workManagerClosed", true)
        }
        attempt { workers.shutdown(); check(workers.awaitTermination(10, TimeUnit.SECONDS)) }
        attempt { tasks.shutdown(); check(tasks.awaitTermination(10, TimeUnit.SECONDS)) }
        for (client in clients.asReversed()) {
            attempt { client.auth.signOut() }
            attempt { client.firestore.terminate().bounded() }
            attempt { client.app.delete() }
        }
        attempt { gate?.close() }
        attempt {
            room?.let { db ->
                if (ownsRoom) check(metadata()[MARKER] == "$run|$OWNER|${context.packageName}")
                db.close()
                check(!db.isOpen)
                // Deletion is authorized only by our own exact marker and run-package path.
                if (ownsRoom) {
                    val file = context.getDatabasePath("cardvault.db").canonicalFile
                    check(file.path.startsWith(context.dataDir.canonicalPath + File.separator))
                    check(context.packageName == "com.dominik.cardvault.wmproof.r$run")
                    check(context.deleteDatabase("cardvault.db"))
                    check(listOf("", "-wal", "-shm", "-journal").none { File(file.path + it).exists() })
                    report.put("ownedRoomClosedAndFilesAbsent", true)
                }
            }
        }
        attempt { requireOfflineEmulator(); report.put("offlineAfter", true) }
        report.put("cleanupPassed", errors.isEmpty())
        if (errors.isNotEmpty()) throw AssertionError("Owned WorkManager proof cleanup failed").also { error -> errors.forEach(error::addSuppressed) }
    }

    private suspend fun <T> Task<T>.bounded(): T = withTimeout(15_000) { suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task -> if (continuation.isActive) {
            if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
            else continuation.resumeWithException(task.exception ?: IllegalStateException("Demo SDK task failed"))
        } }
    } }

    private companion object {
        const val OWNER = "sdk-proof-workmanager-private"
        const val OTHER = "sdk-proof-workmanager-other"
        const val BINDER = "wm-proof-binder"
        const val MARKER = "workmanager-proof-fixture-owner"
        const val TIME = 1_700_000_000_000L
        val COLLECTIONS = listOf("collectionEntries", "privateBinders", "privateBinderPlacements", "collectionLayouts", "collectionPriceSelections")
        val TABLES = listOf("catalog_cards", "binders", "collection_entries", "collection_sync_outbox", "private_binders",
            "private_binder_placements", "collection_layout", "collection_price_selections", "private_binder_sync_outbox",
            "raw_price_snapshots", "graded_market_records", "grading_analyses", "app_metadata", "catalog_sync_state")
    }
}
