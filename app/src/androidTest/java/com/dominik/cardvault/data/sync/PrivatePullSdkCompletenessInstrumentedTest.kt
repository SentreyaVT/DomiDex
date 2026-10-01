package com.dominik.cardvault.data.sync

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import android.security.NetworkSecurityPolicy
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.QuerySnapshot
import com.google.firebase.firestore.Source
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

/**
 * Opt-in actual SDK proof, not a Worker/Room E2E test and not a production benchmark.
 * Requires separately prepared local demo fixtures; see docs/PRIVATE_PULL_SDK_PROOF.md.
 * The ordinary offline instrumentation suite SKIPS these three network-to-loopback tests.
 */
@RunWith(AndroidJUnit4::class)
class PrivatePullSdkCompletenessInstrumentedTest {
    @get:Rule val testName = TestName()
    private data class Client(val app: FirebaseApp, val auth: FirebaseAuth, val db: FirebaseFirestore)
    private val clients = mutableListOf<Client>()
    private val apps = mutableListOf<FirebaseApp>()
    private val appNames = mutableSetOf<String>()
    private val auths = mutableListOf<FirebaseAuth>()
    private val databases = mutableListOf<FirebaseFirestore>()
    private var gate: PrivatePullDemoGrpcGate? = null
    private lateinit var subject: Client
    private lateinit var control: Client
    private lateinit var owner: String
    private lateinit var originalA: DocumentSnapshot
    private lateinit var upper: Timestamp

    @Before fun setup() {
        assumeTrue("Local SDK proof not requested", InstrumentationRegistry.getArguments()
            .getString("domidexPrivatePullSdkProof") == "demo-loopback-only")
        val context = ApplicationProvider.getApplicationContext<Context>()
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
        check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
        check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null) {
            "An external/default Android network is active; keep the emulator offline"
        }
        val networkPolicy = NetworkSecurityPolicy.getInstance()
        check(networkPolicy.isCleartextTrafficPermitted("127.0.0.1")) { "Local emulator HTTP must be explicitly allowed" }
        check(!networkPolicy.isCleartextTrafficPermitted) { "Global cleartext HTTP must remain disabled" }
        check(!networkPolicy.isCleartextTrafficPermitted("example.invalid")) { "Non-loopback HTTP must remain disabled" }
        check(!networkPolicy.isCleartextTrafficPermitted("10.0.2.2")) { "Emulator host-alias HTTP must remain disabled" }
        // These bounded probes use only the loopback reverse ports supplied by the operator.
        for (port in listOf(PrivatePullDemoGrpcGate.FIRESTORE_PORT, PrivatePullDemoGrpcGate.AUTH_PORT)) {
            Socket().use { it.connect(InetSocketAddress(PrivatePullDemoGrpcGate.HOST, port), 3_000) }
        }
        owner = when (testName.methodName) {
            "actualTransactionCommitDoesNotHideUncommittedDocument" -> "sdk-proof-commit"
            "syntheticOptimisticWriteCanHideDocumentUntilSuccessfulAck" -> "sdk-proof-write-ack"
            "syntheticRejectedOverlayIsNotRecoveredByInclusiveTimestampAlone" -> "sdk-proof-write-reject"
            else -> error("Unreviewed demo fixture")
        }
        // Construct/configure named clients before any Firestore operation. No default app lookup.
        subject = client(context, "subject")
        control = client(context, "control")
        gate = PrivatePullDemoGrpcGate()
        checkNotNull(gate).verifyForeignDatabaseFailsBeforeStart()
        for (client in clients) {
            val result = client.auth.signInWithEmailAndPassword("$owner@example.invalid", DEMO_PASSWORD).bounded()
            check(result.user?.uid == owner) { "Demo fixture identity mismatch" }
            check(client.auth.currentUser?.getIdToken(false)?.bounded()?.claims?.get("appAccess") == true)
        }
        val seed = query(control).get(Source.SERVER).bounded()
        assertConfirmed(seed, listOf("a", "b"))
        originalA = checkNotNull(seed.documents.firstOrNull { it.id == "a" })
        upper = checkNotNull(seed.documents.first { it.id == "b" }.getTimestamp("updatedAt"))
        assertEquals("Persisted synthetic A timestamp", Timestamp(1_700_000_000L, 1_000), originalA.getTimestamp("updatedAt"))
        assertEquals("Persisted synthetic B timestamp", Timestamp(1_700_000_001L, 2_000), upper)
        check(originalA.getLong("revision") == 1L)
        assertConfirmed(query(subject).get(Source.SERVER).bounded(), listOf("a", "b"))
    }

    private fun client(context: Context, suffix: String): Client {
        val instanceId = UUID.randomUUID().toString()
        val app = FirebaseApp.initializeApp(context, FirebaseOptions.Builder()
            .setProjectId(PrivatePullDemoGrpcGate.PROJECT)
            .setApplicationId("1:1234567890:android:0000000000000000000000")
            // Auth's emulator registry is API-key scoped and outlives FirebaseApp.delete().
            // Never reuse a synthetic key whose old named app may already be deleted.
            .setApiKey("demo-private-pull-$instanceId").build(), "private-pull-sdk-$suffix-$instanceId")
        apps.add(app) // Register each acquired resource before the next possibly failing operation.
        appNames.add(app.name) // FirebaseApp.getName() is invalid after delete().
        val auth = FirebaseAuth.getInstance(app)
        auths.add(auth)
        auth.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.AUTH_PORT)
        val db = FirebaseFirestore.getInstance(app)
        databases.add(db)
        db.firestoreSettings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build()).build()
        db.useEmulator(PrivatePullDemoGrpcGate.HOST, PrivatePullDemoGrpcGate.FIRESTORE_PORT)
        check(app.options.projectId == PrivatePullDemoGrpcGate.PROJECT)
        check(db.firestoreSettings.host == "127.0.0.1:8187" && !db.firestoreSettings.isSslEnabled)
        return Client(app, auth, db).also(clients::add)
    }

    private fun query(client: Client): Query = client.db.collection("users").document(owner)
        .collection("privateBinders").orderBy("updatedAt").orderBy(FieldPath.documentId())

    private fun boundedQuery(client: Client): Query = query(client).endAt(upper, "b").limit(200)
    private fun a(client: Client) = client.db.collection("users").document(owner).collection("privateBinders").document("a")
    private fun payload(revision: Long): Map<String, Any?> = checkNotNull(originalA.data).toMutableMap().apply {
        put("revision", revision); put("name", "Controlled pending change")
        put("lastMutationId", "sdk-proof-mutation"); put("updatedAt", FieldValue.serverTimestamp())
    }

    @Test fun actualTransactionCommitDoesNotHideUncommittedDocument() {
        val gate = checkNotNull(gate)
        gate.arm(PrivatePullDemoGrpcGate.Kind.TRANSACTION_COMMIT, "${PrivatePullDemoGrpcGate.DATABASE}/documents/users/$owner/privateBinders/a")
        // Same actual SDK primitive used by the production private-sync worker.
        val write = subject.db.runTransaction { transaction ->
            check(transaction.get(a(subject)).getLong("revision") == 1L)
            transaction.set(a(subject), payload(2L))
        }
        gate.awaitHeld()
        assertFalse(write.isComplete)
        // MemoryCache's eager GC may evict an unlistened get() result. A fresh
        // server point read still includes local overlays, without assuming retention.
        val heldPoint = a(subject).get(Source.SERVER).bounded()
        assertTrue(heldPoint.exists())
        assertFalse(heldPoint.metadata.isFromCache)
        assertFalse(heldPoint.metadata.hasPendingWrites())
        assertEquals(originalA.getTimestamp("updatedAt"), heldPoint.getTimestamp("updatedAt"))
        assertConfirmed(boundedQuery(subject).get(Source.SERVER).bounded(), listOf("a", "b"))
        assertConfirmed(boundedQuery(control).get(Source.SERVER).bounded(), listOf("a", "b"))
        assertEquals(originalA.getTimestamp("updatedAt"), a(control).get(Source.SERVER).bounded().getTimestamp("updatedAt"))
        gate.release(); write.bounded()
        assertTrue(checkNotNull(a(control).get(Source.SERVER).bounded().getTimestamp("updatedAt")) > upper)
        assertConfirmed(query(subject).startAt(upper).get(Source.SERVER).bounded(), listOf("b", "a"))
    }

    @Test fun syntheticOptimisticWriteCanHideDocumentUntilSuccessfulAck() {
        val gate = checkNotNull(gate)
        gate.arm(PrivatePullDemoGrpcGate.Kind.OPTIMISTIC_WRITE, "${PrivatePullDemoGrpcGate.DATABASE}/documents/users/$owner/privateBinders/a")
        // Deliberately different from the current worker: optimistic set/Write queue.
        val write = a(subject).set(payload(2L))
        gate.awaitHeld()
        assertFalse(write.isComplete)
        val local = a(subject).get(Source.CACHE).bounded()
        assertTrue(local.metadata.hasPendingWrites())
        assertNull(local.getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.NONE))
        assertConfirmed(boundedQuery(control).get(Source.SERVER).bounded(), listOf("a", "b"))
        // This expected omission is the SDK counterexample, not a product correctness PASS.
        assertConfirmed(boundedQuery(subject).get(Source.SERVER).bounded(), listOf("b"))
        gate.release(); write.bounded()
        assertEquals(2L, a(control).get(Source.SERVER).bounded().getLong("revision"))
        assertConfirmed(query(subject).startAt(upper).get(Source.SERVER).bounded(), listOf("b", "a"))
    }

    @Test fun syntheticRejectedOverlayIsNotRecoveredByInclusiveTimestampAlone() {
        val gate = checkNotNull(gate)
        gate.arm(PrivatePullDemoGrpcGate.Kind.OPTIMISTIC_WRITE, "${PrivatePullDemoGrpcGate.DATABASE}/documents/users/$owner/privateBinders/a")
        // Invalid successor revision: the unchanged project Rules, not a fake SDK error, reject it.
        val write = a(subject).set(payload(1L))
        gate.awaitHeld()
        assertTrue(a(subject).get(Source.CACHE).bounded().metadata.hasPendingWrites())
        assertConfirmed(boundedQuery(control).get(Source.SERVER).bounded(), listOf("a", "b"))
        assertConfirmed(boundedQuery(subject).get(Source.SERVER).bounded(), listOf("b"))
        val firstTimestamp = checkNotNull(originalA.getTimestamp("updatedAt"))
        assertConfirmed(query(control).endAt(firstTimestamp, "a").get(Source.SERVER).bounded(), listOf("a"))
        assertConfirmed(query(subject).endAt(firstTimestamp, "a").get(Source.SERVER).bounded(), emptyList())
        gate.release()
        try { write.bounded(); fail("Unchanged Rules must reject stale revision") }
        catch (failure: ExecutionException) {
            assertEquals(FirebaseFirestoreException.Code.PERMISSION_DENIED, (failure.cause as? FirebaseFirestoreException)?.code)
        }
        assertEquals(firstTimestamp, a(control).get(Source.SERVER).bounded().getTimestamp("updatedAt"))
        assertConfirmed(query(subject).startAt(upper).get(Source.SERVER).bounded(), listOf("b"))
        assertConfirmed(boundedQuery(subject).get(Source.SERVER).bounded(), listOf("a", "b"))
        // No real cursor/Room data was advanced: only the proposed query sequence was exercised.
    }

    private fun assertConfirmed(snapshot: QuerySnapshot, ids: List<String>) {
        assertFalse("Expected actual server response", snapshot.metadata.isFromCache)
        assertFalse("Snapshot reports no pending writes for its returned result", snapshot.metadata.hasPendingWrites())
        assertEquals(ids, snapshot.documents.map { it.id })
        snapshot.documents.forEach { assertFalse(it.metadata.isFromCache); assertFalse(it.metadata.hasPendingWrites()) }
    }

    @After fun cleanup() {
        var failure: Throwable? = null
        fun attempt(action: () -> Unit) { try { action() } catch (caught: Throwable) { if (failure == null) failure = caught else failure?.addSuppressed(caught) } }
        try {
            attempt { gate?.cancelCalls() }
            databases.asReversed().forEach { db -> attempt { db.terminate().bounded() } }
            auths.asReversed().forEach { auth -> attempt { auth.signOut() } }
            apps.asReversed().forEach { app -> attempt { app.delete() } }
        } finally {
            attempt { gate?.close() }
            if (appNames.isNotEmpty()) {
                attempt { check(FirebaseApp.getApps(ApplicationProvider.getApplicationContext()).none { it.name in appNames }) { "Named test app cleanup failed" } }
            }
            clients.clear(); apps.clear(); auths.clear(); databases.clear(); appNames.clear(); gate = null
        }
        failure?.let { throw AssertionError("Demo SDK cleanup failed", it) }
    }

    private fun <T> Task<T>.bounded(): T = Tasks.await(this, 20, TimeUnit.SECONDS)

    companion object {
        // Only deliberately created local Auth-emulator fixtures use this non-secret value.
        const val DEMO_PASSWORD = "Demo-only-private-pull-proof-2026"
    }
}
