package com.dominik.cardvault.data.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import android.security.NetworkSecurityPolicy
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import com.dominik.cardvault.auth.canUseFirebaseAppBackend
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentVerifiedFirebaseEntitlementAccount
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_PENDING
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Opt-in, real SDK/Room proof of the actual CollectionSyncWorker pass. The transport
 * gate holds an actual CommitRequest, not an invented ACK or an injected owner predicate.
 * A separate named SDK client and the host Admin readback prove the server commit.
 * This does not exercise WorkManager delivery or any production project/account.
 */
@RunWith(AndroidJUnit4::class)
class CollectionSyncSessionSdkInstrumentedTest {
    @get:Rule val testName = TestName()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var room: CardVaultDatabase? = null
    private val database get() = checkNotNull(room)
    private val dao get() = database.cardVaultDao()
    private data class Client(val auth: FirebaseAuth, val firestore: FirebaseFirestore)
    private val apps = mutableListOf<FirebaseApp>()
    private val appNames = mutableListOf<String>()
    private val auths = mutableListOf<FirebaseAuth>()
    private val firestores = mutableListOf<FirebaseFirestore>()
    private var gate: PrivatePullDemoGrpcGate? = null
    private lateinit var subject: Client
    private lateinit var control: Client
    private lateinit var scenario: String
    private lateinit var owner: String
    private lateinit var runId: String
    private lateinit var originalEntry: CollectionEntryEntity
    private lateinit var originalMutation: CollectionSyncOutboxEntity
    private var continuationCount = 0

    @Before fun setup() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Local collection-session SDK proof not requested",
            args.getString("domidexCollectionSessionProof") == "demo-loopback-only")
        runId = checkNotNull(args.getString("domidexCollectionSessionRunId"))
        check(runId.matches(Regex("[0-9a-f]{32}"))) { "Invalid isolated fixture run ID" }
        scenario = when (testName.methodName) {
            "healthyCollectionPushAcknowledgesAndRereads" -> "healthy"
            "staleSessionCannotAcknowledgeCommittedMutation" -> "stale"
            else -> error("Unreviewed collection-session proof method")
        }
        owner = "sdk-proof-collection-$scenario"
        withTimeout(60_000) {
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(Build.HARDWARE == "ranchu") { "Only the isolated Android emulator is permitted" }
            check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
            check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
            check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            val policy = NetworkSecurityPolicy.getInstance()
            check(policy.isCleartextTrafficPermitted("127.0.0.1") && !policy.isCleartextTrafficPermitted)
            check(!policy.isCleartextTrafficPermitted("10.0.2.2") && !policy.isCleartextTrafficPermitted("example.invalid"))
            for (port in listOf(PrivatePullDemoGrpcGate.AUTH_PORT, PrivatePullDemoGrpcGate.FIRESTORE_PORT)) {
                Socket().use { it.connect(InetSocketAddress(PrivatePullDemoGrpcGate.HOST, port), 3_000) }
            }
            room = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java).build()
            gate = PrivatePullDemoGrpcGate().also { it.verifyForeignDatabaseFailsBeforeStart() }
            subject = client("subject")
            control = client("control")
            signIn(subject.auth, owner)
            signIn(control.auth, owner)
            assertFalse("Host must supply an empty, fresh collection fixture", remote().exists())
            seedLocalPendingMutation()
        }
    }

    private fun client(label: String): Client {
        val unique = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(PrivatePullDemoGrpcGate.PROJECT)
            .setApplicationId("1:1234567890:android:0000000000000000000000")
            // Auth's emulator registry is API-key scoped and survives FirebaseApp.delete().
            .setApiKey("demo-collection-session-$unique").build(), "collection-session-$label-$unique")
        apps.add(app)
        appNames.add(app.name)
        val auth = FirebaseAuth.getInstance(app).also { auths.add(it) }
        auth.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.AUTH_PORT)
        val firestore = FirebaseFirestore.getInstance(app).also { firestores.add(it) }
        firestore.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        firestore.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.FIRESTORE_PORT)
        check(app.options.projectId == PrivatePullDemoGrpcGate.PROJECT)
        check(firestore.firestoreSettings.host == "127.0.0.1:8187" && !firestore.firestoreSettings.isSslEnabled)
        return Client(auth, firestore)
    }

    private suspend fun signIn(auth: FirebaseAuth, expectedOwner: String) {
        check(expectedOwner in setOf(owner, OTHER_OWNER))
        val credential = GoogleAuthProvider.getCredential(
            """{"sub":"$expectedOwner-google","email":"$expectedOwner@example.invalid","email_verified":true}""", null)
        val user = checkNotNull(auth.signInWithCredential(credential).bounded().user)
        assertEquals(expectedOwner, user.uid)
        assertEquals("$expectedOwner@example.invalid", user.email)
        assertTrue(user.isEmailVerified)
        assertEquals("$expectedOwner-google", user.providerData.single { it.providerId == "google.com" }.uid)
        assertEquals(expectedOwner, canonicalCollectionOwnerUid(user))
        val token = user.getIdToken(false).bounded()
        assertEquals(true, token.claims["appAccess"])
        assertTrue(listOf("admin", "pretester").all { it !in token.claims })
        assertEquals("google.com", (token.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider"))
    }

    private suspend fun seedLocalPendingMutation() {
        dao.insertCatalogCard(CatalogCardEntity(
            id = "synthetic-collection-card", tcg = "POKEMON", setId = "synthetic-set",
            setName = "Synthetic collection set", name = "Synthetic collection card",
            collectorNumber = "001/001", language = "GERMAN", finish = "Normal", variant = "Standard",
            createdAtEpochMillis = FIXTURE_TIME,
        ))
        val entry = CollectionEntryEntity(
            cardId = "synthetic-collection-card", quantity = 2, condition = "Near Mint",
            acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null,
            notes = "isolated collection session proof", frontPhotoUri = null, backPhotoUri = null,
            createdAtEpochMillis = FIXTURE_TIME, updatedAtEpochMillis = FIXTURE_TIME,
            ownerUid = owner, syncId = ENTRY, syncState = SYNC_PENDING, remoteRevision = 0,
            sourceSetId = "synthetic-set", sourceCardId = "synthetic-set-001",
        )
        originalEntry = entry.copy(id = dao.insertCollectionEntry(entry))
        originalMutation = CollectionSyncOutboxEntity(
            entryKey = "$owner:$ENTRY", mutationId = "collection-session-$runId-$scenario",
            ownerUid = owner, syncId = ENTRY, operation = "UPSERT", expectedRemoteRevision = 0,
            state = SYNC_PENDING, createdAtEpochMillis = FIXTURE_TIME,
        )
        dao.upsertSyncOutbox(originalMutation)
        assertEquals(originalEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
        assertEquals(originalMutation, dao.syncOutbox(originalMutation.entryKey))
    }

    @Test fun healthyCollectionPushAcknowledgesAndRereads() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            requireActualEntitlement()
            assertEquals(ListenableWorker.Result.success(), runPass())
            assertFullyAcknowledged()
            val committed = assertCommittedRemote()
            // A fresh no-op run must not make a second mutation or change server timestamp.
            assertEquals(ListenableWorker.Result.success(), runPass())
            assertFullyAcknowledged()
            assertEquals(committed.data, assertCommittedRemote().data)
        }
    }

    @Test fun staleSessionCannotAcknowledgeCommittedMutation() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            requireActualEntitlement()
            val before = localSnapshot()
            val events = Channel<String?>(Channel.UNLIMITED)
            val listener = FirebaseAuth.IdTokenListener { changed ->
                check(events.trySend(canonicalCollectionOwnerUid(changed.currentUser)).isSuccess)
            }
            subject.auth.addIdTokenListener(listener)
            try {
                awaitOwnerEvent(events, owner)
                val transportGate = checkNotNull(gate)
                transportGate.arm(PrivatePullDemoGrpcGate.Kind.TRANSACTION_COMMIT,
                    "${PrivatePullDemoGrpcGate.DATABASE}/documents/users/$owner/collectionEntries/$ENTRY")
                supervisorScope {
                    val oldRun = async { runPass() }
                    try {
                        transportGate.awaitHeld()
                        assertFalse("Actual worker commit must still be held", oldRun.isCompleted)
                        assertFalse("An unsent held Commit is not a committed server document", remote().exists())
                        signIn(subject.auth, OTHER_OWNER)
                        awaitOwnerEvent(events, OTHER_OWNER)
                        signIn(subject.auth, owner)
                        awaitOwnerEvent(events, owner)
                        assertEquals(owner, canonicalCollectionOwnerUid(subject.auth.currentUser))
                        assertFalse(oldRun.isCompleted)
                        transportGate.release()
                        val result = oldRun.await()
                        // Must precede the expected RED assertion: an SDK cancellation/no-write
                        // is NOT the counterexample and cannot be mistaken for this proof.
                        val committed = assertCommittedRemote()
                        assertEquals("STALE_SESSION_ACKED_COMMITTED_MUTATION: observed A->B->A must retain all Room rows",
                            before, localSnapshot())
                        assertEquals(originalEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
                        assertEquals(originalMutation, dao.syncOutbox(originalMutation.entryKey))
                        assertEquals(ListenableWorker.Result.retry(), result)
                        assertEquals(0, continuationCount)

                        // No fabricated ACK/rebase: the actual new authenticated worker retries
                        // the same mutation, recognizes lastMutationId, and rereads the server.
                        requireActualEntitlement()
                        assertEquals(ListenableWorker.Result.success(), runPass())
                        assertFullyAcknowledged()
                        assertEquals("Fresh retry must not increment revision or rewrite timestamp",
                            committed.data, assertCommittedRemote().data)
                    } finally {
                        oldRun.cancelAndJoin()
                    }
                }
            } finally {
                subject.auth.removeIdTokenListener(listener)
                // Do not close the channel: an already posted listener callback may still run.
            }
        }
    }

    private suspend fun awaitOwnerEvent(events: Channel<String?>, expected: String) = withTimeout(20_000) {
        while (events.receive() != expected) { /* Await the actual SDK listener event, not currentUid alone. */ }
    }

    private suspend fun requireActualEntitlement() {
        val account = checkNotNull(subject.auth.currentVerifiedFirebaseEntitlementAccount(subject.firestore, forceRefresh = false))
        assertEquals(owner, account.uid)
        assertTrue(canUseFirebaseAppBackend(owner, canonicalCollectionOwnerUid(subject.auth.currentUser), account.entitlements))
    }

    private suspend fun runPass(): ListenableWorker.Result = runCollectionSync(
        ownerUid = owner, sharingBinderId = null, auth = subject.auth, firestore = subject.firestore,
        database = { database }, appendCollection = { scheduledOwner ->
            assertEquals(owner, scheduledOwner)
            continuationCount++
        },
    )

    private suspend fun assertFullyAcknowledged() {
        assertEquals(originalEntry.copy(syncState = SYNC_CLEAN, remoteRevision = 1), dao.getCollectionEntryBySyncId(owner, ENTRY))
        assertNull(dao.syncOutbox(originalMutation.entryKey))
        assertTrue(dao.pendingSyncOutbox(owner, 2).isEmpty())
        assertEquals(0, continuationCount)
    }

    private suspend fun remote(): DocumentSnapshot = control.firestore
        .document("users/$owner/collectionEntries/$ENTRY").get(Source.SERVER).bounded().also {
            assertFalse("Independent SDK read must come from server", it.metadata.isFromCache)
            assertFalse("Independent SDK read must have no local writes", it.metadata.hasPendingWrites())
        }

    private suspend fun assertCommittedRemote(): DocumentSnapshot = remote().also { snapshot ->
        assertTrue("Actual server commit required; SDK cancellation is not the stale-ACK counterexample", snapshot.exists())
        val data = checkNotNull(snapshot.data)
        val timestamp = checkNotNull(snapshot.getTimestamp("updatedAt"))
        assertTrue(timestamp.seconds > 0)
        assertEquals(mapOf(
            "syncId" to ENTRY, "ownerUid" to owner, "revision" to 1L,
            "lastMutationId" to originalMutation.mutationId, "updatedAt" to timestamp,
            "updatedAtClient" to FIXTURE_TIME, "deleted" to false, "deletedAtClient" to null,
            "name" to "Synthetic collection card", "setId" to "synthetic-set", "setName" to "Synthetic collection set",
            "collectorNumber" to "001/001", "language" to "GERMAN", "finish" to "Normal", "variant" to "Standard",
            "rarity" to "UNKNOWN", "quantity" to 2L, "condition" to "Near Mint", "notes" to "isolated collection session proof",
            "acquisitionPriceMinor" to null, "acquisitionCurrency" to null, "acquisitionDateEpochDay" to null,
            "externalProductSource" to null, "externalProductId" to null, "imageUrl" to null, "effectOverride" to null,
            "sourceSetId" to "synthetic-set", "sourceCardId" to "synthetic-set-001", "binderId" to "inbox",
            "binderSetKey" to "tcgdex-synthetic-set", "binderName" to "Synthetic collection set", "binderHeroAsset" to null,
        ), data)
    }

    private fun localSnapshot(): Map<String, List<List<String?>>> = listOf(
        "catalog_cards", "collection_entries", "collection_sync_outbox", "binders",
        "private_binders", "private_binder_placements", "private_binder_sync_outbox", "app_metadata",
    ).associateWith { table ->
        database.openHelper.readableDatabase.query("SELECT * FROM `$table` ORDER BY rowid").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add((0 until cursor.columnCount).map { if (cursor.isNull(it)) null else cursor.getString(it) })
            }
        }
    }

    @After fun cleanup() = runBlocking(Dispatchers.IO) {
        var failure: Throwable? = null
        suspend fun attempt(action: suspend () -> Unit) {
            try { action() } catch (caught: Throwable) { if (failure == null) failure = caught else failure?.addSuppressed(caught) }
        }
        try {
            attempt { gate?.cancelCalls() }
            firestores.asReversed().forEach { firestore -> attempt { firestore.terminate().bounded() } }
            auths.asReversed().forEach { auth -> attempt { auth.signOut() } }
            apps.asReversed().forEach { app -> attempt { app.delete() } }
            attempt { room?.close(); check(room?.isOpen != true) }
        } finally {
            attempt { gate?.close() }
            attempt { check(FirebaseApp.getApps(context).none { it.name in appNames }) { "Named test app cleanup failed" } }
            firestores.clear(); auths.clear(); apps.clear(); appNames.clear(); gate = null; room = null
        }
        failure?.let { throw AssertionError("Collection session SDK fixture cleanup failed", it) }
        Unit
    }

    private suspend fun <T> Task<T>.bounded(): T = withTimeout(20_000) {
        suspendCancellableCoroutine { continuation ->
            addOnCompleteListener { task ->
                if (continuation.isActive) {
                    if (task.isSuccessful) continuation.resume(task.result)
                    else continuation.resumeWithException(task.exception ?: IllegalStateException("Local emulator task failed"))
                }
            }
        }
    }

    companion object {
        private const val OTHER_OWNER = "sdk-proof-collection-other"
        private const val ENTRY = "entry"
        private const val FIXTURE_TIME = 1_700_000_000_000L
    }
}
