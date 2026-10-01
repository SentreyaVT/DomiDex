package com.dominik.cardvault.data.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
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
import com.dominik.cardvault.data.local.PrivateBinderEntity
import com.dominik.cardvault.data.local.PrivateBinderPlacementEntity
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_PENDING
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Source
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * Opt-in real SDK/Room proofs of collection checkpoints and private-binder recovery. The transport
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
    private var initialRereadDocument: DocumentSnapshot? = null
    private var continuationCount = 0
    private var diagnosticSession: FirebaseCollectionSession? = null

    @Before fun setup() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Local collection-session SDK proof not requested",
            args.getString("domidexCollectionSessionProof") == "demo-loopback-only")
        runId = checkNotNull(args.getString("domidexCollectionSessionRunId"))
        check(runId.matches(Regex("[0-9a-f]{32}"))) { "Invalid isolated fixture run ID" }
        scenario = when (testName.methodName) {
            "healthyCollectionPushAcknowledgesAndRereads" -> "healthy"
            "staleSessionCannotAcknowledgeCommittedMutation" -> "stale"
            "missingRereadRetainsCheckpoint" -> "missing"
            "malformedRereadRetainsCheckpoint" -> "malformed"
            "confirmedRereadCompletesCheckpoint" -> "confirmed"
            "pendingOverlayRereadRetainsCheckpointUntilActualAck" -> "pending-overlay"
            "lateParentPlacementIsRecoveredWithoutRemoteRewrite" -> "late-parent"
            "sessionHealthyStartConfirmsAccessWithoutRunningWorkers" -> "session-healthy"
            "offlineSessionNeedsExplicitRecheckWithinObservedReconnectWindow" -> "session-offline"
            "usableConnectivitySignalRechecksActualEntitlementWithoutManualSync" -> "session-reconnect"
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
            if (scenario == "late-parent") {
                // Genuine remote orphan recovery: no invented local parent or checkpoint.
                assertFalse(remote().exists())
                assertLateRoomCount("collection_entries", 0)
                assertLateRoomCount("catalog_cards", 0)
                assertLateRoomOutboxesEmpty()
                return@withTimeout
            }
            if (scenario in setOf("missing", "malformed", "confirmed", "pending-overlay")) {
                // Verify the actual independently readable server fixture BEFORE constructing
                // a synthetic process-resume checkpoint. This is not an invented actual ACK.
                initialRereadDocument = assertRereadFixture(remote())
                seedLocalPendingMutation()
                originalEntry = originalEntry.copy(syncState = SYNC_CLEAN, remoteRevision = 1)
                dao.updateCollectionEntry(originalEntry)
                assertEquals(originalEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
                assertEquals(originalMutation, dao.syncOutbox(originalMutation.entryKey))
            } else {
                assertFalse("Host must supply an empty, fresh collection fixture", remote().exists())
                seedLocalPendingMutation()
            }
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

    @Test fun missingRereadRetainsCheckpoint() = runBlocking(Dispatchers.IO) {
        assertResumeCheckpointBehavior(confirmed = false)
    }

    @Test fun malformedRereadRetainsCheckpoint() = runBlocking(Dispatchers.IO) {
        assertResumeCheckpointBehavior(confirmed = false)
    }

    @Test fun confirmedRereadCompletesCheckpoint() = runBlocking(Dispatchers.IO) {
        assertResumeCheckpointBehavior(confirmed = true)
    }

    /**
     * Real SDK optimistic write, not a transaction/snapshot mock. The gate
     * holds the outgoing WriteRequest before server commit, NOT an incoming ACK.
     * The pre-existing CLEAN/PENDING row is an explicit synthetic resume checkpoint.
     */
    @Test fun pendingOverlayRereadRetainsCheckpointUntilActualAck() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            check(scenario == "pending-overlay")
            val seeded = checkNotNull(initialRereadDocument)
            val seedData = checkNotNull(seeded.data)
            val seedDecoded = checkNotNull(RemoteCollectionEntry.from(seeded, owner))
            val before = localSnapshot()
            assertEquals(SYNC_CLEAN, originalEntry.syncState)
            assertEquals(1L, originalEntry.remoteRevision)
            assertEquals(SYNC_PENDING, originalMutation.state)
            assertEquals(0L, originalMutation.expectedRemoteRevision)
            requireActualEntitlement()

            val overlayMutationId = "collection-session-$runId-pending-overlay-write"
            val overlayNotes = "isolated collection pending overlay"
            val overlayFields = mapOf(
                "revision" to 2L, "lastMutationId" to overlayMutationId,
                "quantity" to 3L, "notes" to overlayNotes, "updatedAtClient" to FIXTURE_TIME + 1L,
            )
            val payload = seedData + overlayFields + ("updatedAt" to FieldValue.serverTimestamp())
            val reference = subject.firestore.document("users/$owner/collectionEntries/$ENTRY")
            val transportGate = checkNotNull(gate)
            transportGate.arm(PrivatePullDemoGrpcGate.Kind.OPTIMISTIC_WRITE,
                "${PrivatePullDemoGrpcGate.DATABASE}/documents/users/$owner/collectionEntries/$ENTRY")
            // Do not await set() yet: its real task remains pending at the transport gate.
            val write = reference.set(payload)
            transportGate.awaitHeld()
            assertFalse("The actual WriteStream mutation must still be unacknowledged", write.isComplete)

            suspend fun assertValidPendingServerOverlay() {
                assertFalse("Held WriteStream task completed unexpectedly", write.isComplete)
                val point = reference.get(Source.SERVER).bounded()
                assertEquals(reference.path, point.reference.path)
                assertTrue("Source.SERVER must return the actual existing overlay", point.exists())
                assertFalse("A cache-only response does not prove this boundary", point.metadata.isFromCache)
                assertTrue("A real local SDK mutation is mandatory", point.metadata.hasPendingWrites())
                assertNull(point.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.NONE))
                assertEquals(seedData + overlayFields + ("updatedAt" to null), point.data)
                val decoded = checkNotNull(RemoteCollectionEntry.from(point, owner)) {
                    "A malformed/undecodable overlay would not isolate the pending-writes guard"
                }
                // The real decoder retains valid client time while serverTimestamp is pending.
                assertEquals(seedDecoded.copy(revision = 2L, quantity = 3, notes = overlayNotes,
                    updatedAtEpochMillis = FIXTURE_TIME + 1L), decoded)
                assertEquals("Independent server must still hold the unchanged revision-1 seed",
                    seedData, assertRereadFixture(remote()).data)
            }

            assertValidPendingServerOverlay()
            repeat(2) {
                requireActualEntitlement()
                val result = runPass()
                // Check the transport/decoder preconditions again before claiming a retry proof:
                // a timeout, permission error, completed write, or missing doc is NOT this case.
                assertValidPendingServerOverlay()
                assertEquals("PENDING_OVERLAY_DISCARDED_CHECKPOINT: never accept the unconfirmed SDK overlay",
                    before, localSnapshot())
                assertEquals(originalEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
                assertEquals(originalMutation, dao.syncOutbox(originalMutation.entryKey))
                assertEquals(ListenableWorker.Result.retry(), result)
                assertEquals(0, continuationCount)
            }

            // Only after both protected retries do we send the original real mutation.
            // It obeys unchanged Rules (revision+1 and serverTimestamp), not a rejected-write fixture.
            transportGate.release()
            write.bounded()
            assertTrue(write.isSuccessful)
            val confirmed = remote()
            assertTrue(confirmed.exists())
            val confirmedTimestamp = checkNotNull(confirmed.getTimestamp("updatedAt"))
            assertTrue(confirmedTimestamp >= checkNotNull(seeded.getTimestamp("updatedAt")))
            val expectedRemote = seedData + overlayFields + ("updatedAt" to confirmedTimestamp)
            assertEquals(expectedRemote, confirmed.data)
            val subjectConfirmed = reference.get(Source.SERVER).bounded()
            assertFalse(subjectConfirmed.metadata.isFromCache)
            assertFalse(subjectConfirmed.metadata.hasPendingWrites())
            assertEquals(expectedRemote, subjectConfirmed.data)
            val confirmedDecoded = checkNotNull(RemoteCollectionEntry.from(confirmed, owner))
            assertEquals(seedDecoded.copy(revision = 2L, quantity = 3, notes = overlayNotes,
                updatedAtEpochMillis = confirmedTimestamp.toDate().time), confirmedDecoded)

            requireActualEntitlement()
            assertEquals(ListenableWorker.Result.success(), runPass())
            val expectedEntry = originalEntry.copy(
                quantity = 3, notes = overlayNotes, remoteRevision = 2L,
                updatedAtEpochMillis = confirmedTimestamp.toDate().time,
                finishOverride = "Normal", variantOverride = "Standard",
            )
            assertEquals(expectedEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
            assertNull(dao.syncOutbox(originalMutation.entryKey))
            assertTrue(dao.pendingSyncOutbox(owner, 2).isEmpty())
            assertEquals(0, continuationCount)
            val converged = localSnapshot()
            assertEquals(1, checkNotNull(converged["collection_entries"]).size)
            assertEquals(emptyList<List<String?>>(), converged["collection_sync_outbox"])
            val changedTables = setOf("collection_entries", "collection_sync_outbox")
            assertEquals("No catalog, binder, private-outbox or metadata side effects",
                before.filterKeys { it !in changedTables }, converged.filterKeys { it !in changedTables })
            assertEquals("Reread cannot rewrite the independently committed server payload",
                expectedRemote, remote().data)

            // Fresh authenticated no-op proves no extra server revision, timestamp or local mutation.
            requireActualEntitlement()
            assertEquals(ListenableWorker.Result.success(), runPass())
            assertEquals(converged, localSnapshot())
            assertEquals(expectedRemote, remote().data)
            assertEquals(0, continuationCount)
        }
    }

    private suspend fun assertResumeCheckpointBehavior(confirmed: Boolean) = withTimeout(120_000) {
        check((scenario == "confirmed") == confirmed)
        val seeded = checkNotNull(initialRereadDocument)
        val before = localSnapshot()
        assertEquals(SYNC_CLEAN, originalEntry.syncState)
        assertEquals(1L, originalEntry.remoteRevision)
        assertEquals(SYNC_PENDING, originalMutation.state)
        assertEquals(0L, originalMutation.expectedRemoteRevision)
        // Each call captures a new real auth session. A second retry must not silently
        // acknowledge a checkpoint or rewrite the seeded remote document either.
        repeat(2) {
            requireActualEntitlement()
            val result = runPass()
            val observed = assertRereadFixture(remote())
            assertEquals("Worker must not create or remove a remote document during checkpoint reread", seeded.exists(), observed.exists())
            assertEquals("Worker must preserve every remote field and the exact server timestamp", seeded.data, observed.data)
            if (confirmed) {
                // Same-revision reread does not replace the local timestamp/payload: only
                // the durable pending checkpoint disappears after a confirmed read.
                assertEquals(before + ("collection_sync_outbox" to emptyList<List<String?>>()), localSnapshot())
                assertEquals(originalEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
                assertNull(dao.syncOutbox(originalMutation.entryKey))
                assertEquals(ListenableWorker.Result.success(), result)
            } else {
                assertEquals("UNCONFIRMED_REREAD_DISCARDED_CHECKPOINT: $scenario must retain the full Room snapshot",
                    before, localSnapshot())
                assertEquals(originalEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
                assertEquals(originalMutation, dao.syncOutbox(originalMutation.entryKey))
                assertEquals(ListenableWorker.Result.retry(), result)
            }
            assertEquals(0, continuationCount)
        }
    }

    /** Admin seeds only this local fixture; production decoder and real SDK read are not mocked. */
    private fun assertRereadFixture(snapshot: DocumentSnapshot): DocumentSnapshot = snapshot.also {
        check(scenario in setOf("missing", "malformed", "confirmed", "pending-overlay"))
        assertEquals("users/$owner/collectionEntries/$ENTRY", snapshot.reference.path)
        if (scenario == "missing") {
            assertFalse(snapshot.exists())
            assertNull(snapshot.data)
            assertNull("Actual production decoder must reject the absent document", RemoteCollectionEntry.from(snapshot, owner))
        } else {
            assertTrue(snapshot.exists())
            val timestamp = checkNotNull(snapshot.getTimestamp("updatedAt"))
            assertTrue(timestamp.seconds > 1_700_000_000L)
            val expected = mapOf(
                "syncId" to ENTRY, "ownerUid" to owner, "revision" to 1L,
                "lastMutationId" to "collection-session-$runId-$scenario", "updatedAt" to timestamp,
                "updatedAtClient" to FIXTURE_TIME, "deleted" to false, "deletedAtClient" to null,
                "name" to "Synthetic collection card", "setId" to "synthetic-set", "setName" to "Synthetic collection set",
                "collectorNumber" to "001/001", "language" to "GERMAN", "finish" to "Normal", "variant" to "Standard",
                "rarity" to "UNKNOWN", "quantity" to 2L, "condition" to "Near Mint", "notes" to "isolated collection session proof",
                "acquisitionPriceMinor" to null, "acquisitionCurrency" to null, "acquisitionDateEpochDay" to null,
                "externalProductSource" to null, "externalProductId" to null, "imageUrl" to null, "effectOverride" to null,
                "sourceSetId" to "synthetic-set", "sourceCardId" to "synthetic-set-001", "binderId" to "inbox",
                "binderSetKey" to "tcgdex-synthetic-set", "binderName" to "Synthetic collection set", "binderHeroAsset" to null,
            )
            assertEquals(if (scenario == "malformed") expected - "name" else expected, snapshot.data)
            if (scenario == "malformed") {
                assertFalse(snapshot.contains("name"))
                assertNull("Actual production decoder must reject the missing required name", RemoteCollectionEntry.from(snapshot, owner))
            } else {
                val decoded = checkNotNull(RemoteCollectionEntry.from(snapshot, owner))
                assertEquals(ENTRY, decoded.syncId)
                assertEquals(1L, decoded.revision)
                assertEquals("Synthetic collection card", decoded.name)
            }
        }
    }

    @Test fun lateParentPlacementIsRecoveredWithoutRemoteRewrite() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            check(scenario == "late-parent" && owner == "sdk-proof-collection-late-parent")
            requireActualEntitlement()
            // This is a real SDK-observed subject lease across BOTH worker passes.
            // Each actual worker still creates/verifies its own lease as normal.
            PrivatePullAuthSession.use(subject.auth, owner) { unchangedSubjectSession ->
                val beforeBinders = lateServerRows("privateBinders")
                val beforePlacements = lateServerRows("privateBinderPlacements")
                assertEquals(listOf("late-parent-binder", "late-parent-control"), beforeBinders.keys.toList())
                for ((id, data) in beforeBinders) {
                    assertEquals(lateBinderPayload(id, checkNotNull(data["updatedAt"])), data)
                }
                assertEquals(listOf("late-parent-placement"), beforePlacements.keys.toList())
                val originalPlacement = checkNotNull(beforePlacements["late-parent-placement"])
                assertEquals(latePlacementPayload(checkNotNull(originalPlacement["updatedAt"])), originalPlacement)
                assertTrue(lateServerRows("collectionEntries").isEmpty())
                assertTrue(lateServerRows("collectionLayouts").isEmpty())
                assertTrue(lateServerRows("collectionPriceSelections").isEmpty())
                assertFalse(remote().exists())
                assertNull(dao.getCollectionEntryBySyncId(owner, ENTRY))
                assertNull(dao.privateBinderPlacement("late-parent-placement", owner))
                assertLateRoomCount("private_binders", 0)

                // Genuine first product pass: dependency lookup reaches the empty
                // server. No direct helper application or invented local parent.
                latePrivateWorkerPass()
                unchangedSubjectSession.requireCurrent()
                assertEquals(lateExpectedBinder("late-parent-binder"), dao.privateBinder("late-parent-binder", owner))
                assertEquals(lateExpectedBinder("late-parent-control"), dao.privateBinder("late-parent-control", owner))
                assertNull("A missing server parent must not become a dummy card", dao.getCollectionEntryBySyncId(owner, ENTRY))
                assertNull("Placement must wait for its real parent", dao.privateBinderPlacement("late-parent-placement", owner))
                assertLateRoomCount("collection_entries", 0)
                assertLateRoomCount("catalog_cards", 0)
                assertLateRoomCount("private_binder_placements", 0)
                assertLateRoomCount("private_binders", 2)
                assertLateRoomOutboxesEmpty()
                assertLateCompletedSmallSweep()
                assertEquals(beforeBinders, lateServerRows("privateBinders"))
                assertEquals(beforePlacements, lateServerRows("privateBinderPlacements"))
                assertTrue("First worker must not create the missing parent remotely", lateServerRows("collectionEntries").isEmpty())

                // Separate named SDK/cache, same nominal appAccess-only Google owner.
                // This is a real Rules-authorized create, not Admin or a fabricated ACK.
                val reference = control.firestore.document("users/$owner/collectionEntries/$ENTRY")
                control.firestore.runTransaction { transaction ->
                    check(!transaction.get(reference).exists()) { "Late-parent fixture was not absent" }
                    transaction.set(reference, lateParentPayload(FieldValue.serverTimestamp()))
                }.bounded()
                val confirmedParent = remote()
                assertTrue(confirmedParent.exists())
                val parentTimestamp = checkNotNull(confirmedParent.getTimestamp("updatedAt"))
                assertTrue(parentTimestamp.seconds > 1_700_000_000L)
                assertEquals(lateParentPayload(parentTimestamp), confirmedParent.data)
                assertNotNull("Actual production decoder must accept the delivered parent", RemoteCollectionEntry.from(confirmedParent, owner))
                // Reading via independent control must not have applied anything to Room.
                assertNull(dao.getCollectionEntryBySyncId(owner, ENTRY))
                assertNull(dao.privateBinderPlacement("late-parent-placement", owner))
                unchangedSubjectSession.requireCurrent()

                // No placement edit, new revision, timestamp bump, cursor reset or
                // local insertion in the test between the two genuine worker passes.
                latePrivateWorkerPass()
                unchangedSubjectSession.requireCurrent()
                val parent = checkNotNull(dao.getCollectionEntryBySyncId(owner, ENTRY))
                val serverTime = parentTimestamp.toDate().time
                val expectedCardId = "cloud-${UUID.nameUUIDFromBytes("$owner:$ENTRY".toByteArray(StandardCharsets.UTF_8))}"
                assertEquals(CollectionEntryEntity(
                    id = parent.id, cardId = expectedCardId, quantity = 2, condition = "Near Mint",
                    acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null,
                    notes = "isolated collection session proof", frontPhotoUri = null, backPhotoUri = null,
                    createdAtEpochMillis = serverTime, updatedAtEpochMillis = serverTime,
                    externalProductSource = null, externalProductId = null, binderId = "inbox",
                    ownerUid = owner, syncId = ENTRY, syncState = SYNC_CLEAN, remoteRevision = 1,
                    deletedAtEpochMillis = null, finishOverride = "Normal", variantOverride = "Standard",
                    effectOverride = null, sourceSetId = "synthetic-set", sourceCardId = "synthetic-set-001",
                ), parent)
                assertTrue(parent.id > 0)
                assertEquals(PrivateBinderPlacementEntity(
                    id = "late-parent-placement", ownerUid = owner, binderId = "late-parent-binder",
                    collectionEntryId = parent.id, slotPosition = 4, holoEffectId = "rainbow", holoIntensity = 0.5f,
                    createdAtEpochMillis = FIXTURE_TIME, updatedAtEpochMillis = FIXTURE_TIME,
                    syncState = SYNC_CLEAN, remoteRevision = 1, deletedAtEpochMillis = null,
                ), dao.privateBinderPlacement("late-parent-placement", owner))
                assertEquals(lateExpectedBinder("late-parent-binder"), dao.privateBinder("late-parent-binder", owner))
                assertEquals(lateExpectedBinder("late-parent-control"), dao.privateBinder("late-parent-control", owner))
                assertLateRoomCount("collection_entries", 1)
                assertLateRoomCount("catalog_cards", 1)
                assertLateRoomCount("private_binder_placements", 1)
                assertLateRoomCount("private_binders", 2)
                assertLateRoomOutboxesEmpty()
                assertLateCompletedSmallSweep()
                assertEquals(beforeBinders, lateServerRows("privateBinders"))
                assertEquals(beforePlacements, lateServerRows("privateBinderPlacements"))
                assertEquals(mapOf(ENTRY to checkNotNull(confirmedParent.data)), lateServerRows("collectionEntries"))
                assertTrue(lateServerRows("collectionLayouts").isEmpty())
                assertTrue(lateServerRows("collectionPriceSelections").isEmpty())
                assertEquals(0, continuationCount)
            }
        }
    }

    private suspend fun latePrivateWorkerPass() {
        var opens = 0
        val appends = mutableListOf<String>()
        val shares = mutableListOf<Pair<String, String>>()
        val result = withTimeout(45_000) {
            runPrivateBinderSync(owner, null, subject.auth, subject.firestore,
                database = { opens++; database },
                appendPrivate = { uid -> appends.add(uid); Unit },
                continueSharing = { uid, binder -> shares.add(uid to binder); Unit })
        }
        assertTrue("Actual worker must access our isolated Room", opens > 0)
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("Missing parent alone must not schedule a hot continuation", appends.isEmpty())
        assertTrue(shares.isEmpty())
    }

    private suspend fun lateServerRows(collection: String): Map<String, Map<String, Any>> {
        check(collection in setOf("privateBinders", "privateBinderPlacements", "collectionEntries",
            "collectionLayouts", "collectionPriceSelections"))
        // Independent reader, bounded even if the host accidentally supplies extra data.
        val snapshot = control.firestore.collection("users/$owner/$collection")
            .orderBy(FieldPath.documentId()).limit(8).get(Source.SERVER).bounded()
        assertFalse(snapshot.metadata.isFromCache)
        assertFalse(snapshot.metadata.hasPendingWrites())
        return snapshot.documents.associate { document ->
            assertFalse(document.metadata.isFromCache)
            assertFalse(document.metadata.hasPendingWrites())
            val timestamp = checkNotNull(document.getTimestamp("updatedAt"))
            assertTrue(timestamp.seconds > 1_700_000_000L)
            document.id to checkNotNull(document.data)
        }
    }

    private fun assertLateRoomCount(table: String, expected: Int) {
        check(table in setOf("catalog_cards", "collection_entries", "private_binders",
            "private_binder_placements", "collection_sync_outbox", "private_binder_sync_outbox"))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM `$table`").use {
            check(it.moveToFirst())
            assertEquals(table, expected, it.getInt(0))
        }
    }

    private fun assertLateRoomOutboxesEmpty() {
        assertLateRoomCount("collection_sync_outbox", 0)
        assertLateRoomCount("private_binder_sync_outbox", 0)
    }

    private suspend fun assertLateCompletedSmallSweep() {
        // This small 3-record fixture visits each group exactly once. These asserts
        // are not a general claim that every FULL-pull milestone has empty cursors.
        assertEquals("rr1|0|0", dao.metadata(PrivateFullPullMetadataStore.phaseKey(owner)))
        for (group in 0..3) assertEquals("", dao.metadata(PrivateFullPullMetadataStore.cursorKey(owner, group)))
    }

    private fun lateExpectedBinder(id: String) = PrivateBinderEntity(
        id = id, ownerUid = owner, name = "Synthetic $id", description = null,
        coverStyle = "DOMIDEX", minimumPageCount = 1, createdAtEpochMillis = FIXTURE_TIME,
        updatedAtEpochMillis = FIXTURE_TIME, lastOpenedAtEpochMillis = null,
        syncState = SYNC_CLEAN, remoteRevision = 1, deletedAtEpochMillis = null,
    )

    private fun lateBinderPayload(id: String, timestamp: Any) = mapOf(
        "binderId" to id, "ownerUid" to owner, "name" to "Synthetic $id", "description" to null,
        "coverStyle" to "DOMIDEX", "minimumPageCount" to 1L, "createdAtClient" to FIXTURE_TIME,
        "updatedAtClient" to FIXTURE_TIME, "lastOpenedAtClient" to null, "deleted" to false,
        "revision" to 1L, "lastMutationId" to "late-parent-$runId-$id", "updatedAt" to timestamp,
    )

    private fun latePlacementPayload(timestamp: Any) = mapOf(
        "placementId" to "late-parent-placement", "ownerUid" to owner,
        "binderId" to "late-parent-binder", "entrySyncId" to ENTRY, "slotPosition" to 4L,
        "holoEffectId" to "rainbow", "holoIntensity" to 0.5,
        "createdAtClient" to FIXTURE_TIME, "updatedAtClient" to FIXTURE_TIME, "deleted" to false,
        "revision" to 1L, "lastMutationId" to "late-parent-$runId-placement", "updatedAt" to timestamp,
    )

    private fun lateParentPayload(timestamp: Any) = mapOf(
        "syncId" to ENTRY, "ownerUid" to owner, "revision" to 1L,
        "lastMutationId" to "collection-session-$runId-late-parent", "updatedAt" to timestamp,
        "updatedAtClient" to FIXTURE_TIME, "deleted" to false, "deletedAtClient" to null,
        "name" to "Synthetic collection card", "setId" to "synthetic-set", "setName" to "Synthetic collection set",
        "collectorNumber" to "001/001", "language" to "GERMAN", "finish" to "Normal", "variant" to "Standard",
        "rarity" to "UNKNOWN", "quantity" to 2L, "condition" to "Near Mint", "notes" to "isolated collection session proof",
        "acquisitionPriceMinor" to null, "acquisitionCurrency" to null, "acquisitionDateEpochDay" to null,
        "externalProductSource" to null, "externalProductId" to null, "imageUrl" to null, "effectOverride" to null,
        "sourceSetId" to "synthetic-set", "sourceCardId" to "synthetic-set-001", "binderId" to "inbox",
        "binderSetKey" to "tcgdex-synthetic-set", "binderName" to "Synthetic collection set", "binderHeroAsset" to null,
    )

    @Test fun sessionHealthyStartConfirmsAccessWithoutRunningWorkers() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            check(scenario == "session-healthy")
            requireActualEntitlement()
            assertDiagnosticServerEmpty()
            val before = localSnapshot()
            val effects = SessionEffectRecorder()
            val session = newDiagnosticSession(effects)
            val authEvents = Channel<String?>(Channel.UNLIMITED)
            val monitor = FirebaseAuth.IdTokenListener { changed ->
                authEvents.trySend(canonicalCollectionOwnerUid(changed.currentUser))
            }
            try {
                startWithInitialAuthCallbackFence(session, monitor, authEvents)
                awaitDiagnosticActivation(session, effects)
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()
                assertEquals(owner, session.ownerUid.value)
                assertEquals(owner, session.cloudState.value.ownerUid)
                assertTrue(session.cloudState.value.accessConfirmed)
                // PENDING is legitimate: the seam records scheduling but deliberately runs no worker.
                assertTrue(session.cloudState.value.status in setOf(CollectionCloudStatus.LOCAL_ONLY,
                    CollectionCloudStatus.PENDING, CollectionCloudStatus.ACTIVE))
                closeDiagnosticSession(session, monitor, effects)
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()
            } finally {
                subject.auth.removeIdTokenListener(monitor)
                session.close()
                diagnosticSession = null
                // The actual Auth SDK may have posted a callback before removal; do not close its channel.
            }
        }
    }

    @Test fun offlineSessionNeedsExplicitRecheckWithinObservedReconnectWindow() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            check(scenario == "session-offline")
            // A genuine online entitlement before the experiment excludes missing claims/Rules as cause.
            requireActualEntitlement()
            assertDiagnosticServerEmpty()
            val before = localSnapshot()
            val effects = SessionEffectRecorder()
            subject.firestore.disableNetwork().bounded()
            val session = newDiagnosticSession(effects)
            val authEvents = Channel<String?>(Channel.UNLIMITED)
            val monitor = FirebaseAuth.IdTokenListener { changed ->
                authEvents.trySend(canonicalCollectionOwnerUid(changed.currentUser))
            }
            try {
                startWithInitialAuthCallbackFence(session, monitor, authEvents)
                // start() invokes handleIdTokenState once itself and receives one actual initial SDK
                // IdToken callback. LOCAL_ONLY alone could be the first attempt; generation >= 2 is
                // the explicit fence against enabling transport before the second/current attempt.
                withTimeout(25_000) {
                    session.cloudState.first { state ->
                        diagnosticGeneration(session) >= 2L && state.ownerUid == owner &&
                            state.status == CollectionCloudStatus.LOCAL_ONLY && !state.accessConfirmed
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue("Initial SDK callbacks must be consumed before the reconnect experiment",
                    authEvents.tryReceive().isFailure)
                val settledGeneration = diagnosticGeneration(session)
                assertTrue(settledGeneration >= 2L)
                assertEquals(CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, owner), session.cloudState.value)
                assertTrue(effects.snapshot().isEmpty())
                assertDiagnosticCheckpoint(before)

                subject.firestore.enableNetwork().bounded()
                // Real Source.SERVER, not a manufactured connectivity event or a cached snapshot.
                val access = subject.firestore.document("accountAccess/$owner").get(Source.SERVER).bounded()
                assertFalse(access.metadata.isFromCache)
                assertFalse(access.metadata.hasPendingWrites())
                assertFalse(access.exists())
                requireActualEntitlement()
                assertDiagnosticServerEmpty()
                assertEquals("RECONNECT_CHARACTERIZATION_NOT_REPRODUCED: a new session generation ran",
                    settledGeneration, diagnosticGeneration(session))
                assertTrue("RECONNECT_CHARACTERIZATION_NOT_REPRODUCED: Auth emitted a new token event",
                    authEvents.tryReceive().isFailure)

                val observationStarted = SystemClock.elapsedRealtime()
                val observedActivation = withTimeoutOrNull(2_000) {
                    session.cloudState.first { state ->
                        state != CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, owner)
                    }
                }
                val observedMillis = SystemClock.elapsedRealtime() - observationStarted
                assertNull("RECONNECT_CHARACTERIZATION_NOT_REPRODUCED: state changed in bounded " +
                    "${observedMillis}ms observation (not a permanent-block or latency claim)", observedActivation)
                assertEquals(settledGeneration, diagnosticGeneration(session))
                assertTrue("Auth activity makes this particular no-token-event characterization inconclusive",
                    authEvents.tryReceive().isFailure)
                assertTrue(effects.snapshot().isEmpty())
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()

                // Positive control IN THIS SAME CASE: actual production entry point, actual verification,
                // actual listeners, scheduling requests observed, but no worker delivery/execution.
                session.requestSync()
                awaitDiagnosticActivation(session, effects)
                assertTrue(diagnosticGeneration(session) > settledGeneration)
                assertEquals(owner, session.ownerUid.value)
                assertTrue(session.cloudState.value.accessConfirmed)
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()
                closeDiagnosticSession(session, monitor, effects)
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()
            } finally {
                subject.auth.removeIdTokenListener(monitor)
                session.close()
                diagnosticSession = null
                // No global network toggle to restore: only this named SDK was disabled. Existing
                // @After terminates/deletes that SDK even if a preceding assertion/enable failed.
            }
        }
    }

    @Test fun usableConnectivitySignalRechecksActualEntitlementWithoutManualSync() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            check(scenario == "session-reconnect")
            requireActualEntitlement()
            assertDiagnosticServerEmpty()
            val before = localSnapshot()
            val effects = SessionEffectRecorder()
            val connectivity = RecordedConnectivitySource()
            subject.firestore.disableNetwork().bounded()
            val session = newConnectivityDiagnosticSession(effects, connectivity)
            val authEvents = Channel<String?>(Channel.UNLIMITED)
            val monitor = FirebaseAuth.IdTokenListener { changed ->
                authEvents.trySend(canonicalCollectionOwnerUid(changed.currentUser))
            }
            try {
                startWithInitialAuthCallbackFence(session, monitor, authEvents)
                withTimeout(25_000) {
                    session.cloudState.first { state ->
                        diagnosticGeneration(session) >= 2L && state.ownerUid == owner &&
                            state.status == CollectionCloudStatus.LOCAL_ONLY && !state.accessConfirmed
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                assertTrue("Initial real Auth events must be consumed before the connectivity experiment",
                    authEvents.tryReceive().isFailure)
                val settledGeneration = diagnosticGeneration(session)
                assertTrue(settledGeneration >= 2L)
                assertEquals(CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, owner), session.cloudState.value)
                assertEquals(ConnectivityObservation(1, 0, true), connectivity.observation())
                assertTrue(effects.snapshot().isEmpty())
                assertDiagnosticCheckpoint(before)

                subject.firestore.enableNetwork().bounded()
                // Independent actual SDK transport confirmation, not the synthetic connectivity source.
                val access = subject.firestore.document("accountAccess/$owner").get(Source.SERVER).bounded()
                assertFalse(access.metadata.isFromCache)
                assertFalse(access.metadata.hasPendingWrites())
                assertFalse(access.exists())
                requireActualEntitlement()
                assertDiagnosticServerEmpty()
                assertEquals("An unrelated auth/session generation would invalidate this trigger proof",
                    settledGeneration, diagnosticGeneration(session))
                assertTrue("An unrelated real token event would invalidate this trigger proof",
                    authEvents.tryReceive().isFailure)
                assertEquals(CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, owner), session.cloudState.value)
                assertTrue(effects.snapshot().isEmpty())

                withContext(Dispatchers.Main.immediate) {
                    connectivity.emit(false)
                    connectivity.emit(false)
                }
                assertEquals("Unusable connectivity must not initiate another entitlement attempt",
                    settledGeneration, diagnosticGeneration(session))
                assertTrue(effects.snapshot().isEmpty())
                assertEquals(CollectionCloudState(CollectionCloudStatus.LOCAL_ONLY, owner), session.cloudState.value)

                // Only this signal is synthetic. The production Session owns the attempt, real token,
                // actual Source.SERVER access read, listeners and all four recorded scheduling effects.
                // There is intentionally NO requestSync() call anywhere in this method or its helpers.
                withContext(Dispatchers.Main.immediate) {
                    connectivity.emit(true)
                    connectivity.emit(true)
                    connectivity.emit(false)
                    connectivity.emit(true)
                    connectivity.emit(true)
                }
                awaitDiagnosticActivation(session, effects)
                assertEquals("One recoverable failure plus a signal burst must produce one successful recheck",
                    settledGeneration + 1L, diagnosticGeneration(session))
                assertTrue("The recovery must not be explained by an actual new token event",
                    authEvents.tryReceive().isFailure)
                assertEquals(owner, session.ownerUid.value)
                assertEquals(owner, session.cloudState.value.ownerUid)
                assertTrue(session.cloudState.value.accessConfirmed)
                val activatedEffects = effects.snapshot()
                assertEquals(mapOf(
                    SessionEffect("collection", owner, true) to 1,
                    SessionEffect("private", owner, true) to 1,
                    SessionEffect("ensure-periodic", owner) to 1,
                    SessionEffect("scan-knowledge") to 1,
                ), activatedEffects.groupingBy { it }.eachCount())
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()

                val activatedGeneration = diagnosticGeneration(session)
                withContext(Dispatchers.Main.immediate) {
                    repeat(3) {
                        connectivity.emit(false)
                        connectivity.emit(true)
                        connectivity.emit(true)
                    }
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                // A bounded negative observation, not a production response-time guarantee.
                val unexpectedRecheck = withTimeoutOrNull(2_000) {
                    session.cloudState.first { state ->
                        diagnosticGeneration(session) != activatedGeneration || state.ownerUid != owner ||
                            !state.accessConfirmed
                    }
                }
                assertNull("An already entitled session must not restart for the connectivity burst", unexpectedRecheck)
                assertEquals(activatedGeneration, diagnosticGeneration(session))
                assertEquals(activatedEffects, effects.snapshot())
                assertTrue(authEvents.tryReceive().isFailure)
                assertEquals(ConnectivityObservation(1, 0, true), connectivity.observation())
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()

                subject.auth.removeIdTokenListener(monitor)
                session.close()
                // Retain diagnosticSession until finally/@After as a cleanup fallback even after close.
                assertEquals(ConnectivityObservation(1, 1, false), connectivity.observation())
                val closedGeneration = diagnosticGeneration(session)
                val closedState = session.cloudState.value
                val closedEffects = effects.snapshot()
                withContext(Dispatchers.Main.immediate) {
                    // Deliberately call the captured observer after disposal. This is not filtered
                    // by the fake: the real Session itself must reject late platform-style callbacks.
                    connectivity.emitRetainedAfterClose(false)
                    connectivity.emitRetainedAfterClose(true)
                    connectivity.emitRetainedAfterClose(true)
                    session.start() // Existing closed Session must not register a second observer.
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                val revived = withTimeoutOrNull(2_000) {
                    session.cloudState.first { state ->
                        diagnosticGeneration(session) != closedGeneration || state != closedState
                    }
                }
                assertNull("A released connectivity observer must not revive the closed Session", revived)
                assertEquals(closedGeneration, diagnosticGeneration(session))
                assertEquals(closedEffects, effects.snapshot())
                assertEquals(ConnectivityObservation(1, 1, false), connectivity.observation())
                assertDiagnosticCheckpoint(before)
                assertDiagnosticServerEmpty()
            } finally {
                subject.auth.removeIdTokenListener(monitor)
                session.close()
                diagnosticSession = null
                // Independent cleanup postcondition also detects duplicate releases from repeated close.
                assertEquals(ConnectivityObservation(1, 1, false), connectivity.observation())
                // Actual named SDK termination/Room cleanup remains the unchanged @After path.
                // Do not close the monitor channel: a genuine Auth callback may already be posted.
            }
        }
    }

    private fun newConnectivityDiagnosticSession(
        effects: SessionEffectRecorder,
        connectivity: CollectionConnectivitySource,
    ): FirebaseCollectionSession {
        check(diagnosticSession == null)
        return FirebaseCollectionSession(database = database, auth = subject.auth,
            firestore = subject.firestore, scheduling = effects, connectivity = connectivity).also {
            diagnosticSession = it
            assertEquals(0L, diagnosticGeneration(it))
        }
    }

    private data class ConnectivityObservation(val registrations: Int, val closeCalls: Int, val active: Boolean)

    /** Only OS connectivity delivery is substituted; auth, entitlement, Tasks and snapshots are real. */
    private class RecordedConnectivitySource : CollectionConnectivitySource {
        private val lock = Any()
        private var registrations = 0
        private var closeCalls = 0
        private var active = false
        private var retained: ((Boolean) -> Unit)? = null

        override fun observe(onUsableChanged: (Boolean) -> Unit): AutoCloseable {
            synchronized(lock) {
                registrations++
                check(registrations == 1 && !active) { "Unexpected duplicate connectivity subscription" }
                active = true
                retained = onUsableChanged
            }
            // A source may immediately report its initial state; this also exercises registration
            // while Session.start() is executing. Never invoke a consumer under the fake's lock.
            onUsableChanged(false)
            return AutoCloseable {
                synchronized(lock) {
                    closeCalls++
                    active = false
                    // Keep the actual callback, enabling the explicit late-delivery proof above.
                }
            }
        }

        fun observation(): ConnectivityObservation = synchronized(lock) {
            ConnectivityObservation(registrations, closeCalls, active)
        }

        fun emit(usable: Boolean) {
            val callback = synchronized(lock) {
                check(active) { "Use the explicit late-delivery helper after release" }
                checkNotNull(retained)
            }
            callback(usable)
        }

        fun emitRetainedAfterClose(usable: Boolean) {
            val callback = synchronized(lock) {
                check(!active && closeCalls == 1) { "Late delivery requires one actual observer release" }
                checkNotNull(retained)
            }
            callback(usable)
        }
    }

    private fun newDiagnosticSession(effects: SessionEffectRecorder): FirebaseCollectionSession {
        check(diagnosticSession == null)
        return FirebaseCollectionSession(database = database, auth = subject.auth,
            firestore = subject.firestore, scheduling = effects).also {
            diagnosticSession = it
            assertEquals(0L, diagnosticGeneration(it))
        }
    }

    private suspend fun startWithInitialAuthCallbackFence(
        session: FirebaseCollectionSession,
        monitor: FirebaseAuth.IdTokenListener,
        authEvents: Channel<String?>,
    ) {
        withContext(Dispatchers.Main.immediate) {
            session.start()
            // This observer's actual SDK initial event is registered after Session's initial event.
            subject.auth.addIdTokenListener(monitor)
        }
        awaitOwnerEvent(authEvents, owner)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Read-only inspection, never a fabricated token, injected completion or altered generation.
        // The StateFlow waiter additionally checks >=2, so no SDK-posting-order assumption is needed
        // to accept LOCAL_ONLY from the initial direct invocation alone.
        assertTrue("Actual Session initial IdToken callback must already have run",
            diagnosticGeneration(session) >= 2L)
    }

    private fun diagnosticGeneration(session: FirebaseCollectionSession): Long =
        FirebaseCollectionSession::class.java.getDeclaredField("entitlementGeneration").let { field ->
            field.isAccessible = true
            field.getLong(session)
        }

    private suspend fun awaitDiagnosticActivation(session: FirebaseCollectionSession, effects: SessionEffectRecorder) {
        withTimeout(25_000) {
            session.cloudState.first { it.ownerUid == owner && it.accessConfirmed && diagnosticGeneration(session) >= 2L }
            effects.awaitActivation(owner)
        }
        assertTrue(session.cloudState.value.accessConfirmed)
        effects.assertOnlyExpectedActivation(owner)
    }

    private suspend fun assertDiagnosticCheckpoint(before: Map<String, List<List<String?>>>) {
        assertEquals("Session observer must not ACK or mutate the pending local fixture", before, localSnapshot())
        assertEquals(originalEntry, dao.getCollectionEntryBySyncId(owner, ENTRY))
        assertEquals(originalMutation, dao.syncOutbox(originalMutation.entryKey))
        assertEquals(listOf(originalMutation), dao.pendingSyncOutbox(owner, 2))
        assertEquals(0, continuationCount)
    }

    private suspend fun assertDiagnosticServerEmpty() {
        for (collection in listOf("collectionEntries", "privateBinders", "privateBinderPlacements",
            "collectionLayouts", "collectionPriceSelections")) {
            assertEquals("Session diagnostic fixture must stay empty remotely: $collection",
                emptyMap<String, Map<String, Any>>(), lateServerRows(collection))
        }
        val access = control.firestore.document("accountAccess/$owner").get(Source.SERVER).bounded()
        assertFalse(access.metadata.isFromCache)
        assertFalse(access.metadata.hasPendingWrites())
        assertFalse(access.exists())
    }

    private fun closeDiagnosticSession(session: FirebaseCollectionSession,
        monitor: FirebaseAuth.IdTokenListener, effects: SessionEffectRecorder) {
        subject.auth.removeIdTokenListener(monitor)
        session.close()
        diagnosticSession = null
        val before = effects.snapshot()
        session.requestSync() // Closed session must not revive or enqueue work.
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(before, effects.snapshot())
    }

    private data class SessionEffect(val kind: String, val ownerUid: String? = null, val userInitiated: Boolean? = null)

    /** Recorder substitutes ONLY scheduling delivery; no Firebase/Task/entitlement callback is mocked. */
    private class SessionEffectRecorder : CollectionSessionScheduling {
        private val lock = Any()
        private val recorded = mutableListOf<SessionEffect>()
        private var overflow = false
        private val wake = Channel<Unit>(Channel.CONFLATED)
        private fun record(event: SessionEffect) {
            synchronized(lock) {
                if (recorded.size < 64) recorded.add(event) else overflow = true
            }
            wake.trySend(Unit)
        }
        fun snapshot(): List<SessionEffect> = synchronized(lock) {
            check(!overflow) { "Unexpected scheduling storm in bounded fixture" }
            recorded.toList()
        }
        private fun expected(owner: String) = setOf(
            SessionEffect("collection", owner, true), SessionEffect("private", owner, true),
            SessionEffect("ensure-periodic", owner), SessionEffect("scan-knowledge"),
        )
        suspend fun awaitActivation(owner: String) {
            while (!snapshot().containsAll(expected(owner))) wake.receive()
        }
        fun assertOnlyExpectedActivation(owner: String) {
            val found = snapshot()
            assertTrue(found.containsAll(expected(owner)))
            assertTrue("No foreign owner, unexpected cancellation or altered scheduling flags: $found",
                found.all { it in expected(owner) })
        }
        override fun scheduleCollection(ownerUid: String, userInitiated: Boolean) = record(SessionEffect("collection", ownerUid, userInitiated))
        override fun schedulePrivateBinder(ownerUid: String, userInitiated: Boolean) = record(SessionEffect("private", ownerUid, userInitiated))
        override fun ensurePrivateBinderPeriodic(ownerUid: String) = record(SessionEffect("ensure-periodic", ownerUid))
        override fun cancelPrivateBinderPeriodic(ownerUid: String) = record(SessionEffect("cancel-periodic", ownerUid))
        override fun cancelAllPrivateBinderPeriodic() = record(SessionEffect("cancel-all-periodic"))
        override fun scheduleScanKnowledge() = record(SessionEffect("scan-knowledge"))
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
            attempt { diagnosticSession?.close(); diagnosticSession = null }
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
