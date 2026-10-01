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
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
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
 * Opt-in Android repository -> actual worker pass -> Web -> actual worker pull proof.
 * The host executes A, a real Web save, B, a real Web clear, C, then owned cleanup.
 * No WorkManager delivery/constraints, production backend or physical-device claim.
 */
@RunWith(AndroidJUnit4::class)
class AndroidWebPriceRoundTripInstrumentedTest {
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
    private lateinit var databaseName: String
    private lateinit var databaseFile: File

    @Before fun setup() = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Local Android/Web proof not requested", args.getString("domidexAndroidWebPriceProof") == "demo-loopback-only")
        runId = checkNotNull(args.getString("domidexPriceRunId"))
        check(runId.matches(Regex("[0-9a-f]{32}"))) { "Invalid isolated fixture run ID" }
        check(testName.methodName in METHODS) { "Unreviewed proof phase" }
        withTimeout(60_000) {
            check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            check(Build.HARDWARE == "ranchu") { "Only the isolated Android emulator is permitted" }
            check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
            check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
            check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
            databaseName = "domidex-price-roundtrip-$runId.db"
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
                assertEquals(if (testName.methodName == "androidPullsWebPriceChoice") "A" else "B", dao.metadata(PHASE_KEY))
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
        appName = "android-web-price-$unique"
        app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(PrivatePullDemoGrpcGate.PROJECT)
            .setApplicationId("1:1234567890:android:0000000000000000000000")
            .setApiKey("demo-android-web-price-$unique").build(), checkNotNull(appName))
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
        // Deliberately synthetic credential, understood only by the local Auth emulator.
        val credential = GoogleAuthProvider.getCredential(
            """{"sub":"price-roundtrip-google","email":"price-roundtrip@example.invalid","email_verified":true}""", null)
        val user = checkNotNull(auth.signInWithCredential(credential).bounded().user)
        assertEquals(OWNER, user.uid)
        assertEquals(EMAIL, user.email)
        assertTrue(user.isEmailVerified)
        assertEquals(GOOGLE_SUB, user.providerData.single { it.providerId == "google.com" }.uid)
        assertEquals(OWNER, canonicalCollectionOwnerUid(user))
        val token = user.getIdToken(false).bounded()
        assertEquals(true, token.claims["appAccess"])
        assertTrue("No admin or pretester claim is granted", listOf("admin", "pretester").all { it !in token.claims })
        assertEquals("google.com", (token.claims["firebase"] as? Map<*, *>)?.get("sign_in_provider"))
    }

    @Test fun androidCreatesAndAcknowledgesPriceChoice() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            val parentSnapshot = parentSnapshot()
            checkParentIdentity(parentSnapshot)
            assertFalse(priceSnapshot().exists())
            applyRemoteDocumentToRoom(database, OWNER, parentSnapshot) { canonicalCollectionOwnerUid(auth.currentUser) == OWNER }
            val parent = checkNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
            assertEquals(SYNC_CLEAN, parent.syncState)
            assertEquals(1L, parent.remoteRevision)
            assertNull(parent.deletedAtEpochMillis)
            dao.putMetadata(AppMetadataEntity(PARENT_KEY, parent.toString()))
            dao.putMetadata(AppMetadataEntity(SERVER_PARENT_KEY, parentSnapshot.data!!.toSortedMap().toString()))
            seedProductCache()
            var scheduled = 0
            val repository = RoomCollectionRepository(database, MutableStateFlow(OWNER), onSyncNeeded = { scheduled++ })
            repository.setPriceSelection(parent.id, originalChoice())
            assertEquals(1, scheduled)
            val mutation = dao.pendingPrivateBinderSyncOutbox(OWNER, 2).single()
            assertEquals(PRIVATE_SYNC_PRICE_SELECTION, mutation.recordType)
            assertEquals(ENTRY, mutation.recordId)
            assertEquals(0L, mutation.expectedRemoteRevision)
            assertEquals(originalChoice(), dao.priceSelection(OWNER, ENTRY)?.selectionOrNull())
            assertEquals(SYNC_PENDING, dao.priceSelection(OWNER, ENTRY)?.syncState)
            assertTrue(dao.pendingSyncOutbox(OWNER).isEmpty())
            dao.putMetadata(AppMetadataEntity(MUTATION_KEY, mutation.mutationId))

            actualWorkerPass()
            val remote = priceSnapshot()
            assertEquals(1L, remote.getLong("revision"))
            assertEquals(mutation.mutationId, remote.getString("lastMutationId"))
            assertChoice(remote, originalChoice(), 1)
            assertOutboxesEmpty()
            assertParentUnchanged(parentSnapshot)
            assertNoFabricatedTrend(repository, 5.32)
            dao.putMetadata(AppMetadataEntity(PHASE_KEY, "A"))
        }
    }

    @Test fun androidPullsWebPriceChoice() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            assertEquals(originalChoice(), dao.priceSelection(OWNER, ENTRY)?.selectionOrNull())
            assertEquals(1L, dao.priceSelection(OWNER, ENTRY)?.remoteRevision)
            val parent = parentSnapshot()
            assertParentUnchanged(parent)
            val remote = priceSnapshot()
            assertEquals(2L, remote.getLong("revision"))
            assertNotEquals(dao.metadata(MUTATION_KEY), remote.getString("lastMutationId"))
            val selectedAt = checkNotNull(remote.getLong("selectedAt")).also { check(it > 0) }
            val expected = originalChoice().copy(metric = CardPriceMetric.NORMAL_LOW, amountMinor = 450, selectedAt = selectedAt)
            actualWorkerPass()
            assertChoice(priceSnapshot(), expected, 2)
            assertEquals(remote.data, priceSnapshot().data)
            assertOutboxesEmpty()
            assertParentUnchanged(parent)
            assertNoFabricatedTrend(repository(), 4.50)
            dao.putMetadata(AppMetadataEntity(PHASE_KEY, "B"))
        }
    }

    @Test fun androidPullsWebClear() = runBlocking(Dispatchers.IO) {
        withTimeout(120_000) {
            assertEquals(2L, dao.priceSelection(OWNER, ENTRY)?.remoteRevision)
            assertFalse(checkNotNull(dao.priceSelection(OWNER, ENTRY)).cleared)
            val parent = parentSnapshot()
            assertParentUnchanged(parent)
            val remote = priceSnapshot()
            assertEquals(3L, remote.getLong("revision"))
            assertEquals(true, remote.getBoolean("cleared"))
            assertTrue(PRICE_SELECTION_VALUE_FIELDS.all { remote.contains(it) && remote.get(it) == null })
            actualWorkerPass()
            val saved = checkNotNull(dao.priceSelection(OWNER, ENTRY))
            assertTrue(saved.cleared)
            assertEquals(SYNC_CLEAN, saved.syncState)
            assertEquals(3L, saved.remoteRevision)
            assertEquals(remote.getLong("updatedAtClient"), saved.updatedAtEpochMillis)
            assertTrue(listOf(saved.sourceSetId, saved.sourceCardId, saved.collectorNumber, saved.finish, saved.variant,
                saved.language, saved.productId, saved.metric, saved.amountMinor, saved.currency, saved.sourceAt, saved.selectedAt).all { it == null })
            assertEquals(remote.data, priceSnapshot().data)
            assertOutboxesEmpty()
            assertParentUnchanged(parent)
            assertNoFabricatedTrend(repository(), null)
            dao.putMetadata(AppMetadataEntity(PHASE_KEY, "C"))
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
        assertTrue("The real authenticated pass must open the isolated Room database", opens > 0)
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("Small fixture needs no background continuation", appends.isEmpty())
        assertTrue(sharing.isEmpty())
    }

    private suspend fun assertChoice(remote: DocumentSnapshot, expected: CardPriceSelection, revision: Long) {
        assertTrue(remote.exists())
        assertEquals(OWNER, remote.getString("ownerUid"))
        assertEquals(ENTRY, remote.getString("entrySyncId"))
        assertEquals(false, remote.getBoolean("cleared"))
        assertEquals(revision, remote.getLong("revision"))
        val saved = checkNotNull(dao.priceSelection(OWNER, ENTRY))
        assertEquals(OWNER, saved.ownerUid)
        assertEquals(ENTRY, saved.entrySyncId)
        assertEquals(SYNC_CLEAN, saved.syncState)
        assertEquals(revision, saved.remoteRevision)
        assertEquals(expected, saved.selectionOrNull())
        assertEquals(remote.getLong("updatedAtClient"), saved.updatedAtEpochMillis)
        val encoded = saved.priceSelectionPayload(checkNotNull(remote.getString("lastMutationId")))
        encoded.forEach { (key, value) -> assertEquals("Server/Room field $key", value, remote.get(key)) }
    }

    private suspend fun assertParentUnchanged(before: DocumentSnapshot) {
        val local = checkNotNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        assertEquals(dao.metadata(PARENT_KEY), local.toString())
        assertEquals(dao.metadata(SERVER_PARENT_KEY), before.data!!.toSortedMap().toString())
        assertEquals(before.data, parentSnapshot().data)
        assertEquals(1L, local.remoteRevision)
        assertEquals(SYNC_CLEAN, local.syncState)
    }

    private suspend fun assertOutboxesEmpty() {
        for (table in listOf("collection_sync_outbox", "private_binder_sync_outbox")) {
            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table WHERE ownerUid = ?", arrayOf(OWNER)).use {
                check(it.moveToFirst()); assertEquals("No remaining $table rows", 0, it.getInt(0))
            }
        }
    }

    private suspend fun assertNoFabricatedTrend(repository: RoomCollectionRepository, effective: Double?) {
        val row = dao.observeCollection(OWNER).first().single()
        assertNull(row.marketValueMinor)
        assertNull(row.priceUpdatedAtEpochMillis)
        val card = repository.portfolio.first().cards.single()
        assertEquals(0.0, card.currentValueEur, 0.0)
        assertNull(card.priceUpdatedAtEpochMillis)
        if (effective == null) assertNull(card.effectiveValueEur)
        else assertEquals(effective, checkNotNull(card.effectiveValueEur), 0.0001)
        assertTrue(repository.priceHistory(card).first().isEmpty())
    }

    private fun repository() = RoomCollectionRepository(database, MutableStateFlow(OWNER),
        onSyncNeeded = { error("Readback must not queue new work") })

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

    private suspend fun parentSnapshot() = confirmed(firestore.document("users/$OWNER/collectionEntries/$ENTRY").get(Source.SERVER).bounded())
    private suspend fun priceSnapshot() = confirmed(firestore.document("users/$OWNER/collectionPriceSelections/$ENTRY").get(Source.SERVER).bounded())
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

    private fun ownerMarker() = "v1|$OWNER|$runId"
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
        failure?.let { throw AssertionError("Isolated Android/Web proof cleanup failed", it) }
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
        private const val OWNER = "price-roundtrip-owner"
        private const val EMAIL = "price-roundtrip@example.invalid"
        private const val GOOGLE_SUB = "price-roundtrip-google"
        private const val ENTRY = "entry"
        private const val OWNER_KEY = "android-web-price-proof-owner"
        private const val PHASE_KEY = "android-web-price-proof-phase"
        private const val PARENT_KEY = "android-web-price-proof-parent"
        private const val SERVER_PARENT_KEY = "android-web-price-proof-server-parent"
        private const val MUTATION_KEY = "android-web-price-proof-mutation"
        private val METHODS = setOf("androidCreatesAndAcknowledgesPriceChoice", "androidPullsWebPriceChoice", "androidPullsWebClear", "cleanupOwnedFixture")
    }
}
