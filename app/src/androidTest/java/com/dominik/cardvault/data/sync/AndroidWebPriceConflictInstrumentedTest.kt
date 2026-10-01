package com.dominik.cardvault.data.sync

import android.content.Context
import android.content.pm.ApplicationInfo
import android.database.sqlite.SQLiteDatabase
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
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.selectionOrNull
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CardPriceMetric
import com.dominik.cardvault.model.CardPriceSelection
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
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
 * Independent opt-in proof: real Repository/worker ACK, intervening real Web save,
 * held stale-base conflict, and an explicit real conflict resolver + worker ACK.
 * The host runs separate fresh cloud/local fixtures. This does not start the
 * Settings session facade, WorkManager, default Firebase app, or production backend.
 */
@RunWith(AndroidJUnit4::class)
class AndroidWebPriceConflictInstrumentedTest {
    @get:Rule val testName = TestName()
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private var room: CardVaultDatabase? = null
    private val database get() = checkNotNull(room)
    private val dao get() = database.cardVaultDao()
    private var app: FirebaseApp? = null
    private var appName: String? = null
    private var namedAuth: FirebaseAuth? = null
    private var namedFirestore: FirebaseFirestore? = null
    private val auth get() = checkNotNull(namedAuth)
    private val firestore get() = checkNotNull(namedFirestore)
    private var gate: PrivatePullDemoGrpcGate? = null
    private lateinit var runId: String
    private lateinit var resolution: String
    private lateinit var databaseName: String
    private lateinit var databaseFile: File

    @Before fun setup() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Local Android/Web conflict proof not requested",
            args.getString("domidexAndroidWebPriceConflictProof") == "demo-loopback-only")
        runId = checkNotNull(args.getString("domidexPriceConflictRunId"))
        resolution = checkNotNull(args.getString("domidexPriceConflictResolution"))
        check(runId.matches(Regex("[0-9a-f]{32}"))) { "Invalid isolated fixture run ID" }
        check(resolution in setOf("cloud", "local")) { "Unreviewed conflict resolution" }
        check(testName.methodName in METHODS) { "Unreviewed proof phase" }
        withTimeout(60_000) {
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(Build.HARDWARE == "ranchu") { "Only the isolated Android emulator is permitted" }
            check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
            check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
            check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            databaseName = "domidex-price-conflict-$resolution-$runId.db"
            databaseFile = context.getDatabasePath(databaseName)
            check(databaseFile.name == databaseName && databaseFile.canonicalFile.parentFile ==
                context.getDatabasePath("fixed-proof-parent").canonicalFile.parentFile)
            if (testName.methodName == "cleanupOwnedFixture") return@withTimeout
            if (testName.methodName == "androidCreatesAndAcknowledgesPriceChoice") {
                check(ownedFiles().none(File::exists)) { "Phase A requires a fresh, non-reused Room fixture" }
                room = Room.databaseBuilder(context, CardVaultDatabase::class.java, databaseName).build()
                dao.putMetadata(AppMetadataEntity(OWNER_KEY, ownerMarker()))
                dao.putMetadata(AppMetadataEntity(PHASE_KEY, "created"))
            } else {
                verifyOwnerOnDisk()
                room = Room.databaseBuilder(context, CardVaultDatabase::class.java, databaseName).build()
                assertEquals(ownerMarker(), dao.metadata(OWNER_KEY))
                assertEquals(if (testName.methodName == "androidHoldsStaleBaseConflict") "A" else "B", dao.metadata(PHASE_KEY))
            }
            configureNamedClient()
        }
    }

    private suspend fun configureNamedClient() {
        val policy = NetworkSecurityPolicy.getInstance()
        check(policy.isCleartextTrafficPermitted("127.0.0.1") && !policy.isCleartextTrafficPermitted)
        check(!policy.isCleartextTrafficPermitted("10.0.2.2") && !policy.isCleartextTrafficPermitted("example.invalid"))
        for (port in listOf(PrivatePullDemoGrpcGate.AUTH_PORT, PrivatePullDemoGrpcGate.FIRESTORE_PORT)) {
            Socket().use { it.connect(InetSocketAddress(PrivatePullDemoGrpcGate.HOST, port), 3_000) }
        }
        val unique = UUID.randomUUID().toString()
        appName = "android-web-price-conflict-$unique"
        app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(PrivatePullDemoGrpcGate.PROJECT)
            .setApplicationId("1:1234567890:android:0000000000000000000000")
            .setApiKey("demo-android-web-price-conflict-$unique").build(), checkNotNull(appName))
        namedAuth = FirebaseAuth.getInstance(checkNotNull(app)).also {
            it.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.AUTH_PORT)
        }
        namedFirestore = FirebaseFirestore.getInstance(checkNotNull(app)).also {
            it.firestoreSettings = FirebaseFirestoreSettings.Builder()
                .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
            it.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.FIRESTORE_PORT)
        }
        check(checkNotNull(app).options.projectId == PrivatePullDemoGrpcGate.PROJECT)
        check(firestore.firestoreSettings.host == "127.0.0.1:8187" && !firestore.firestoreSettings.isSslEnabled)
        gate = PrivatePullDemoGrpcGate().also { it.verifyForeignDatabaseFailsBeforeStart() }
        val credential = GoogleAuthProvider.getCredential(
            """{"sub":"price-conflict-google","email":"price-conflict@example.invalid","email_verified":true}""", null)
        val user = checkNotNull(auth.signInWithCredential(credential).bounded().user)
        assertEquals(OWNER, user.uid)
        assertEquals(EMAIL, user.email)
        assertTrue(user.isEmailVerified)
        assertEquals(GOOGLE_SUB, user.providerData.single { it.providerId == "google.com" }.uid)
        assertEquals(OWNER, canonicalCollectionOwnerUid(user))
        val token = user.getIdToken(false).bounded()
        assertEquals(true, token.claims["appAccess"])
        assertTrue(listOf("admin", "pretester").all { it !in token.claims })
        assertEquals("google.com", (token.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider"))
        requireEntitlement()
    }

    @Test fun androidCreatesAndAcknowledgesPriceChoice() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            val parentSnapshot = parentSnapshot()
            checkParentIdentity(parentSnapshot)
            assertFalse(priceSnapshot().exists())
            withVerifiedSession { session ->
                applyRemoteDocumentToRoom(database, OWNER, parentSnapshot, session::isCurrent)
            }
            val parent = checkNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
            assertEquals(SYNC_CLEAN, parent.syncState)
            assertEquals(1L, parent.remoteRevision)
            assertNull(parent.deletedAtEpochMillis)
            dao.putMetadata(AppMetadataEntity(PARENT_KEY, parent.toString()))
            dao.putMetadata(AppMetadataEntity(SERVER_PARENT_KEY, parentSnapshot.data!!.toSortedMap().toString()))
            seedProductCache()
            var scheduled = 0
            val repository = repository { scheduled++ }
            repository.setPriceSelection(parent.id, originalChoice())
            assertEquals(1, scheduled)
            val mutation = dao.pendingPrivateBinderSyncOutbox(OWNER, 2).single()
            assertMutation(mutation, 0)
            assertEquals(originalChoice(), dao.priceSelection(OWNER, ENTRY)?.selectionOrNull())
            assertEquals(SYNC_PENDING, dao.priceSelection(OWNER, ENTRY)?.syncState)
            assertEquals(CollectionCloudQueueState(pending = true, error = false), readCollectionCloudQueueState(dao, OWNER))
            dao.putMetadata(AppMetadataEntity(FIRST_MUTATION_KEY, mutation.mutationId))
            actualWorkerPass()
            val remote = priceSnapshot()
            assertEquals(mutation.mutationId, remote.getString("lastMutationId"))
            assertCleanChoice(remote, originalChoice(), 1)
            assertResolvedQueue()
            assertParentUnchanged(parentSnapshot)
            assertNoFabricatedTrend(repository, 5.32)
            dao.putMetadata(AppMetadataEntity(PHASE_KEY, "A"))
        }
    }

    @Test fun androidHoldsStaleBaseConflict() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            val baseline = checkNotNull(dao.priceSelection(OWNER, ENTRY))
            assertEquals(originalChoice(), baseline.selectionOrNull())
            assertEquals(1L, baseline.remoteRevision)
            assertEquals(SYNC_CLEAN, baseline.syncState)
            assertResolvedQueue()
            val parent = parentSnapshot()
            assertParentUnchanged(parent)
            val web = priceSnapshot()
            assertRemoteChoice(web, webChoice(web), 2)
            assertNotEquals(dao.metadata(FIRST_MUTATION_KEY), web.getString("lastMutationId"))
            dao.putMetadata(AppMetadataEntity(SERVER_WEB_KEY, web.data!!.toSortedMap().toString()))

            var scheduled = 0
            val repository = repository { scheduled++ }
            repository.setPriceSelection(baseline.collectionEntryId, localChoice())
            assertEquals(1, scheduled)
            val pending = checkNotNull(dao.priceSelection(OWNER, ENTRY))
            val mutation = dao.pendingPrivateBinderSyncOutbox(OWNER, 2).single()
            assertMutation(mutation, 1)
            assertNotEquals(dao.metadata(FIRST_MUTATION_KEY), mutation.mutationId)
            assertEquals(localChoice(), pending.selectionOrNull())
            assertEquals(1L, pending.remoteRevision)
            assertEquals(SYNC_PENDING, pending.syncState)
            assertEquals(CollectionCloudQueueState(pending = true, error = false), readCollectionCloudQueueState(dao, OWNER))
            val expectedSelection = pending.copy(syncState = SYNC_CONFLICT)
            val expectedMutation = mutation.copy(lastError = "price-selection-conflict:2")

            repeat(2) {
                actualWorkerPass()
                assertEquals("The real stale-base transaction must not change any server field", web.data, priceSnapshot().data)
                assertHeldConflict(expectedSelection, expectedMutation)
                assertParentUnchanged(parent)
                assertNoFabricatedTrend(repository, 5.00)
            }
            dao.putMetadata(AppMetadataEntity(HELD_SELECTION_KEY, expectedSelection.toString()))
            dao.putMetadata(AppMetadataEntity(HELD_MUTATION_KEY, expectedMutation.toString()))
            dao.putMetadata(AppMetadataEntity(PHASE_KEY, "B"))
        }
    }

    @Test fun androidResolvesExplicitConflict() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            val local = checkNotNull(dao.priceSelection(OWNER, ENTRY))
            val conflict = dao.conflictedPriceSelectionOutbox(OWNER).single()
            assertEquals(dao.metadata(HELD_SELECTION_KEY), local.toString())
            assertEquals(dao.metadata(HELD_MUTATION_KEY), conflict.toString())
            assertEquals(localChoice(), local.selectionOrNull())
            assertEquals(1L, local.remoteRevision)
            assertMutation(conflict, 1, "price-selection-conflict:2")
            assertHeldConflict(local, conflict)
            val parent = parentSnapshot()
            assertParentUnchanged(parent)
            val web = priceSnapshot()
            assertEquals(dao.metadata(SERVER_WEB_KEY), web.data!!.toSortedMap().toString())
            assertRemoteChoice(web, webChoice(web), 2)

            if (resolution == "cloud") {
                withVerifiedSession { session ->
                    val confirmed = confirmed(firestore.document(choicePath()).get(Source.SERVER).awaitPrivateBinderTask(session))
                    assertEquals(web.data, confirmed.data)
                    assertTrue(keepCloudPriceSelectionConflict(database, conflict, confirmed.data.orEmpty(),
                        confirmed.exists() && !confirmed.metadata.isFromCache && !confirmed.metadata.hasPendingWrites(), session::isCurrent))
                    session.requireCurrent()
                }
                assertCleanChoice(priceSnapshot(), webChoice(web), 2)
                assertResolvedQueue()
                actualWorkerPass()
                assertEquals("Explicit cloud choice is local-only and must not rewrite the server", web.data, priceSnapshot().data)
                assertCleanChoice(priceSnapshot(), webChoice(web), 2)
                assertNoFabricatedTrend(repository(), 4.50)
            } else {
                val newMutation = UUID.randomUUID().toString()
                val resolvedAt = System.currentTimeMillis()
                withVerifiedSession { session ->
                    assertTrue(keepLocalPriceSelectionConflict(database, conflict, newMutation, resolvedAt, session::isCurrent))
                    session.requireCurrent()
                }
                assertEquals(local.copy(remoteRevision = 2, syncState = SYNC_PENDING, updatedAtEpochMillis = resolvedAt),
                    dao.priceSelection(OWNER, ENTRY))
                val rebased = conflict.copy(mutationId = newMutation, expectedRemoteRevision = 2,
                    updatedAtEpochMillis = resolvedAt, createdAtEpochMillis = resolvedAt, lastError = null)
                assertEquals(rebased, dao.pendingPrivateBinderSyncOutbox(OWNER, 2).single())
                assertTrue(dao.conflictedPriceSelectionOutbox(OWNER).isEmpty())
                assertEquals(CollectionCloudQueueState(pending = true, error = false), readCollectionCloudQueueState(dao, OWNER))
                assertEquals(CollectionCloudStatus.PENDING, queueStatus(serverSnapshot = true))
                assertEquals("Rebasing locally must not write before the actual worker pass", web.data, priceSnapshot().data)
                actualWorkerPass()
                val remote = priceSnapshot()
                assertEquals(newMutation, remote.getString("lastMutationId"))
                assertNotEquals(conflict.mutationId, remote.getString("lastMutationId"))
                assertNotEquals(web.getString("lastMutationId"), remote.getString("lastMutationId"))
                assertEquals(resolvedAt, remote.getLong("updatedAtClient"))
                assertCleanChoice(remote, localChoice(), 3)
                assertNoFabricatedTrend(repository(), 5.00)
            }
            assertResolvedQueue()
            assertParentUnchanged(parent)
            dao.putMetadata(AppMetadataEntity(PHASE_KEY, "C"))
        }
    }

    private suspend fun requireEntitlement() {
        val account = checkNotNull(auth.currentVerifiedFirebaseEntitlementAccount(firestore, forceRefresh = false))
        assertEquals(OWNER, account.uid)
        assertEquals(OWNER, canonicalCollectionOwnerUid(auth.currentUser))
        assertTrue(canUseFirebaseAppBackend(OWNER, canonicalCollectionOwnerUid(auth.currentUser), account.entitlements))
        assertFalse(account.entitlements.admin)
        assertFalse(account.entitlements.pretester)
    }

    private suspend fun <T> withVerifiedSession(action: suspend (PrivatePullAuthSession) -> T): T {
        requireEntitlement()
        return PrivatePullAuthSession.use(auth, OWNER) { session ->
            session.requireCurrent()
            requireEntitlement()
            session.requireCurrent()
            action(session).also { session.requireCurrent() }
        }
    }

    private suspend fun actualWorkerPass() {
        val appends = mutableListOf<String>()
        val sharing = mutableListOf<Pair<String, String>>()
        var opens = 0
        val result = withTimeout(60_000) {
            runPrivateBinderSync(OWNER, null, auth, firestore, { opens++; database }, { uid -> appends.add(uid); Unit },
                { uid, binder -> sharing.add(uid to binder) })
        }
        assertTrue("The real authenticated worker pass must open the isolated Room database", opens > 0)
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("A held conflict must not cause background continuation", appends.isEmpty())
        assertTrue(sharing.isEmpty())
    }

    private fun assertMutation(mutation: PrivateBinderSyncOutboxEntity, revision: Long, error: String? = null) {
        assertEquals("$OWNER:$PRIVATE_SYNC_PRICE_SELECTION:$ENTRY", mutation.entryKey)
        assertEquals(OWNER, mutation.ownerUid)
        assertEquals(PRIVATE_SYNC_PRICE_SELECTION, mutation.recordType)
        assertEquals(ENTRY, mutation.recordId)
        assertEquals(PRIVATE_SYNC_UPSERT, mutation.operation)
        assertEquals(revision, mutation.expectedRemoteRevision)
        assertEquals(error, mutation.lastError)
        assertTrue(mutation.mutationId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
    }

    private suspend fun assertHeldConflict(selection: CollectionPriceSelectionEntity, mutation: PrivateBinderSyncOutboxEntity) {
        assertEquals(SYNC_CONFLICT, selection.syncState)
        assertEquals(1L, selection.remoteRevision)
        assertEquals(localChoice(), selection.selectionOrNull())
        assertMutation(mutation, 1, "price-selection-conflict:2")
        assertEquals(selection, dao.priceSelection(OWNER, ENTRY))
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
        assertEquals(listOf(mutation), dao.conflictedPriceSelectionOutbox(OWNER))
        assertTrue(dao.pendingPrivateBinderSyncOutbox(OWNER).isEmpty())
        assertEquals(1, repository().syncConflictCount.first())
        assertEquals(0, countOutbox("collection_sync_outbox"))
        assertEquals(1, countOutbox("private_binder_sync_outbox"))
        assertEquals(CollectionCloudQueueState(pending = false, error = true), readCollectionCloudQueueState(dao, OWNER))
        for (server in listOf(false, true)) assertEquals(CollectionCloudStatus.ERROR, queueStatus(server))
    }

    private suspend fun queueStatus(serverSnapshot: Boolean): CollectionCloudStatus {
        val queue = readCollectionCloudQueueState(dao, OWNER)
        return collectionCloudStatus(checking = false, accessConfirmed = true, denied = false,
            serverSnapshot = serverSnapshot, pending = queue.pending, error = queue.error)
    }

    private suspend fun assertResolvedQueue() {
        assertEquals(0, countOutbox("collection_sync_outbox"))
        assertEquals(0, countOutbox("private_binder_sync_outbox"))
        assertTrue(dao.conflictedPriceSelectionOutbox(OWNER).isEmpty())
        assertEquals(0, repository().syncConflictCount.first())
        assertEquals(CollectionCloudQueueState(pending = false, error = false), readCollectionCloudQueueState(dao, OWNER))
        assertEquals(CollectionCloudStatus.ACTIVE, queueStatus(serverSnapshot = true))
        assertEquals(CollectionCloudStatus.LOCAL_ONLY, queueStatus(serverSnapshot = false))
    }

    private fun countOutbox(table: String): Int {
        require(table in setOf("collection_sync_outbox", "private_binder_sync_outbox"))
        return database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            check(it.moveToFirst()); it.getInt(0)
        }
    }

    private suspend fun assertRemoteChoice(remote: DocumentSnapshot, expected: CardPriceSelection, revision: Long) {
        assertTrue(remote.exists())
        val parent = checkNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        val data = checkNotNull(remote.data)
        assertEquals(PRICE_SELECTION_VALUE_FIELDS + setOf("schemaVersion", "ownerUid", "entrySyncId", "revision",
            "lastMutationId", "updatedAt", "updatedAtClient", "cleared"), data.keys)
        val decoded = checkNotNull(parsePriceSelectionSidecar(data, OWNER, ENTRY, parent.id))
        assertEquals(expected, decoded.selectionOrNull())
        assertEquals(false, decoded.cleared)
        assertEquals(revision, decoded.remoteRevision)
        assertEquals(SYNC_CLEAN, decoded.syncState)
        assertNotNull(remote.getTimestamp("updatedAt"))
        assertTrue(checkNotNull(remote.getTimestamp("updatedAt")).seconds > 1_700_000_000)
        val payload = decoded.priceSelectionPayload(checkNotNull(remote.getString("lastMutationId")))
        payload.forEach { (key, value) -> assertEquals("Exact server field $key", value, remote.get(key)) }
    }

    private suspend fun assertCleanChoice(remote: DocumentSnapshot, expected: CardPriceSelection, revision: Long) {
        assertRemoteChoice(remote, expected, revision)
        val saved = checkNotNull(dao.priceSelection(OWNER, ENTRY))
        val parsed = checkNotNull(parsePriceSelectionSidecar(checkNotNull(remote.data), OWNER, ENTRY, saved.collectionEntryId))
        assertEquals(parsed, saved)
        assertEquals(expected, saved.selectionOrNull())
        assertEquals(OWNER, saved.ownerUid)
        assertEquals(ENTRY, saved.entrySyncId)
    }

    private suspend fun assertParentUnchanged(before: DocumentSnapshot) {
        val local = checkNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        assertEquals(dao.metadata(PARENT_KEY), local.toString())
        assertEquals(dao.metadata(SERVER_PARENT_KEY), before.data!!.toSortedMap().toString())
        assertEquals(before.data, parentSnapshot().data)
        assertEquals(1L, local.remoteRevision)
        assertEquals(SYNC_CLEAN, local.syncState)
    }

    private suspend fun assertNoFabricatedTrend(repository: RoomCollectionRepository, effective: Double) {
        val row = dao.observeCollection(OWNER).first().single()
        assertNull(row.marketValueMinor)
        assertNull(row.priceUpdatedAtEpochMillis)
        val card = repository.portfolio.first().cards.single()
        assertEquals(0.0, card.currentValueEur, 0.0)
        assertNull(card.priceUpdatedAtEpochMillis)
        assertEquals(effective, checkNotNull(card.effectiveValueEur), 0.0001)
        assertTrue(repository.priceHistory(card).first().isEmpty())
    }

    private fun repository(onSyncNeeded: () -> Unit = { error("Readback must not queue new work") }) =
        RoomCollectionRepository(database, MutableStateFlow(OWNER), onSyncNeeded = onSyncNeeded)

    private suspend fun seedProductCache() {
        dao.insertCatalogCard(CatalogCardEntity("cm-pokemon-42", "POKEMON", "6443", "Source product",
            "Rowlet", "", "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 1_000,
            externalSource = CardmarketSyncContract.SOURCE, externalProductId = 42))
        dao.insertRawPrices(listOf(RawPriceSnapshotEntity(cardId = "cm-pokemon-42", source = CardmarketSyncContract.SOURCE,
            currency = "EUR", lowValueMinor = 450, averageValueMinor = 532, trendValueMinor = null, marketValueMinor = null,
            average1DayValueMinor = 500, average7DayValueMinor = 510, average30DayValueMinor = 520,
            holoAverageValueMinor = 800, holoLowValueMinor = 700, holoTrendValueMinor = null,
            fetchedAtEpochMillis = 1_000, effectiveAtEpochMillis = 1_000)))
    }

    private fun originalChoice() = CardPriceSelection("me03", "me03-090", "090/088", "Holo", "Holo",
        CardLanguage.GERMAN, 42, CardPriceMetric.NORMAL_AVG_SELL, 532, "EUR", 1_000, 2_000)
    private fun localChoice() = originalChoice().copy(metric = CardPriceMetric.NORMAL_AVG_1D, amountMinor = 500, selectedAt = 3_000)
    private fun webChoice(remote: DocumentSnapshot): CardPriceSelection {
        val at = checkNotNull(remote.getLong("selectedAt"))
        assertTrue(at > 3_000)
        assertEquals(at, remote.getLong("updatedAtClient"))
        return originalChoice().copy(metric = CardPriceMetric.NORMAL_LOW, amountMinor = 450, selectedAt = at)
    }

    private fun checkParentIdentity(parent: DocumentSnapshot) {
        assertTrue(parent.exists())
        for ((field, expected) in mapOf("ownerUid" to OWNER, "syncId" to ENTRY, "sourceSetId" to "me03",
            "sourceCardId" to "me03-090", "collectorNumber" to "090/088", "language" to "GERMAN",
            "finish" to "Holo", "variant" to "Holo", "externalProductSource" to CardmarketSyncContract.SOURCE)) {
            assertEquals(field, expected, parent.getString(field))
        }
        assertEquals(42L, parent.getLong("externalProductId"))
        assertEquals(1L, parent.getLong("revision"))
        assertEquals(false, parent.getBoolean("deleted"))
    }

    private fun choicePath() = "users/$OWNER/collectionPriceSelections/$ENTRY"
    private suspend fun parentSnapshot() = confirmed(firestore.document("users/$OWNER/collectionEntries/$ENTRY").get(Source.SERVER).bounded())
    private suspend fun priceSnapshot() = confirmed(firestore.document(choicePath()).get(Source.SERVER).bounded())
    private fun confirmed(snapshot: DocumentSnapshot): DocumentSnapshot = snapshot.also {
        assertFalse(it.metadata.isFromCache); assertFalse(it.metadata.hasPendingWrites())
    }

    @Test fun cleanupOwnedFixture() = runBlocking(Dispatchers.IO) {
        withTimeout(10_000) {
            check(room == null && app == null && namedAuth == null && namedFirestore == null)
            if (ownedFiles().none(File::exists)) return@withTimeout
            verifyOwnerOnDisk()
            assertTrue("Remove only the verified run-scoped Room fixture", context.deleteDatabase(databaseName))
            assertTrue(ownedFiles().none(File::exists))
        }
    }

    private fun ownerMarker() = "v1|$OWNER|$resolution|$runId"
    private fun ownedFiles() = listOf(databaseFile, File(databaseFile.path + "-wal"), File(databaseFile.path + "-shm"), File(databaseFile.path + "-journal"))
    private fun verifyOwnerOnDisk() {
        check(databaseFile.isFile) { "No owned primary fixture database; refusing cleanup or reopen" }
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            sqlite.rawQuery("SELECT value FROM app_metadata WHERE `key` = ?", arrayOf(OWNER_KEY)).use {
                check(it.moveToFirst() && it.getString(0) == ownerMarker() && !it.moveToNext()) { "Fixture ownership mismatch" }
            }
        }
    }

    @After fun closeResources() = runBlocking(Dispatchers.IO) {
        var failure: Throwable? = null
        suspend fun attempt(action: suspend () -> Unit) {
            try { action() } catch (caught: Throwable) { if (failure == null) failure = caught else failure?.addSuppressed(caught) }
        }
        try {
            attempt { gate?.cancelCalls() }
            attempt { namedFirestore?.terminate()?.bounded() }
            attempt { namedAuth?.signOut() }
            attempt { app?.delete() }
            attempt { room?.close() }
        } finally {
            attempt { gate?.close() }
            attempt { appName?.let { name -> check(FirebaseApp.getApps(context).none { it.name == name }) } }
            gate = null; namedFirestore = null; namedAuth = null; app = null; room = null
        }
        failure?.let { throw AssertionError("Isolated Android/Web conflict cleanup failed", it) }
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
        private const val OWNER = "price-conflict-owner"
        private const val EMAIL = "price-conflict@example.invalid"
        private const val GOOGLE_SUB = "price-conflict-google"
        private const val ENTRY = "entry"
        private const val OWNER_KEY = "android-web-price-conflict-owner"
        private const val PHASE_KEY = "android-web-price-conflict-phase"
        private const val PARENT_KEY = "android-web-price-conflict-parent"
        private const val SERVER_PARENT_KEY = "android-web-price-conflict-server-parent"
        private const val FIRST_MUTATION_KEY = "android-web-price-conflict-first-mutation"
        private const val SERVER_WEB_KEY = "android-web-price-conflict-web"
        private const val HELD_SELECTION_KEY = "android-web-price-conflict-held-selection"
        private const val HELD_MUTATION_KEY = "android-web-price-conflict-held-mutation"
        private val METHODS = setOf("androidCreatesAndAcknowledgesPriceChoice", "androidHoldsStaleBaseConflict",
            "androidResolvesExplicitConflict", "cleanupOwnedFixture")
    }
}
