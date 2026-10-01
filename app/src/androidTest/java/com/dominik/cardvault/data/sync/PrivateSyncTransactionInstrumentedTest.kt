package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.supervisorScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Actual Room rollback checks; synthetic metadata, no Firebase or real accounts. */
@RunWith(AndroidJUnit4::class)
class PrivateSyncTransactionInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val generation = AtomicLong(1)
    private val closed = AtomicBoolean(false)
    private var owner: String? = "synthetic-a"
    private val session = PrivatePullAuthSession(PrivatePullSessionLease("synthetic-a", 1), generation, closed) { owner }
    private val dao get() = database.cardVaultDao()

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }
    @After fun close() = database.close()

    @Test fun validSessionCommitsAndReturnsResult() = runBlocking {
        val result = database.withPrivateSyncSession({ session.isCurrent() }) {
            dao.putMetadata(AppMetadataEntity("synthetic-payload", "committed"))
            12
        }
        assertEquals(12, result)
        assertEquals("committed", dao.metadata("synthetic-payload"))
    }

    @Test fun alreadyStaleSessionDoesNotEnterCallback() = runBlocking {
        owner = null
        var entered = false
        expectStale {
            database.withPrivateSyncSession({ session.isCurrent() }) { entered = true }
        }
        assertFalse(entered)
    }

    @Test fun accountChangeDuringWritesRollsPayloadAndCursorBack() = runBlocking {
        dao.putMetadata(AppMetadataEntity("synthetic-cursor", "old"))
        expectStale {
            database.withPrivateSyncSession({ session.isCurrent() }) {
                dao.putMetadata(AppMetadataEntity("synthetic-payload", "new"))
                dao.putMetadata(AppMetadataEntity("synthetic-cursor", "new"))
                owner = "synthetic-b"
                generation.incrementAndGet()
            }
        }
        assertNull(dao.metadata("synthetic-payload"))
        assertEquals("old", dao.metadata("synthetic-cursor"))
    }

    @Test fun accountRoundTripStillRollsBack() = runBlocking {
        expectStale {
            database.withPrivateSyncSession({ session.isCurrent() }) {
                dao.putMetadata(AppMetadataEntity("synthetic-payload", "new"))
                owner = "synthetic-b"
                generation.incrementAndGet()
                owner = "synthetic-a"
                generation.incrementAndGet()
            }
        }
        assertNull(dao.metadata("synthetic-payload"))
    }

    @Test fun closedSessionDuringWriteRollsBack() = runBlocking {
        expectStale {
            database.withPrivateSyncSession({ session.isCurrent() }) {
                dao.putMetadata(AppMetadataEntity("synthetic-payload", "new"))
                closed.set(true)
            }
        }
        assertNull(dao.metadata("synthetic-payload"))
    }

    @Test fun cancellationAfterWritesPreservesPriorPayloadCursorAndOutbox() = runBlocking {
        dao.putMetadata(AppMetadataEntity("synthetic-payload", "old"))
        dao.putMetadata(AppMetadataEntity("synthetic-cursor", "old"))
        dao.putMetadata(AppMetadataEntity("synthetic-outbox", "pending"))
        val written = CompletableDeferred<Unit>()
        val job = launch {
            database.withPrivateSyncSession({ session.isCurrent() }) {
                dao.putMetadata(AppMetadataEntity("synthetic-payload", "new"))
                dao.putMetadata(AppMetadataEntity("synthetic-cursor", "new"))
                dao.deleteMetadata("synthetic-outbox")
                written.complete(Unit)
                awaitCancellation()
            }
        }
        written.await()
        job.cancelAndJoin()
        assertEquals("old", dao.metadata("synthetic-payload"))
        assertEquals("old", dao.metadata("synthetic-cursor"))
        assertEquals("pending", dao.metadata("synthetic-outbox"))
    }

    @Test fun completedNestedRoomTransactionRollsBackWithStaleOuterSession() = runBlocking {
        expectStale {
            database.withPrivateSyncSession({ session.isCurrent() }) {
                database.withTransaction {
                    dao.putMetadata(AppMetadataEntity("synthetic-nested-payload", "new"))
                }
                generation.incrementAndGet()
            }
        }
        assertNull(dao.metadata("synthetic-nested-payload"))
    }

    @Test fun completedSdkTaskReturnsOnlyForCurrentLease() = runBlocking {
        assertEquals(42, Tasks.forResult(42).awaitPrivateBinderTask(session))
        owner = null
        expectStale { Tasks.forResult(43).awaitPrivateBinderTask(session) }
    }

    @Test fun sdkTaskCompletionAfterAccountRoundTripCannotApplyResult() = runBlocking {
        supervisorScope {
            val completion = TaskCompletionSource<Int>()
            val awaiting = async(start = CoroutineStart.UNDISPATCHED) {
                completion.task.awaitPrivateBinderTask(session)
            }
            generation.addAndGet(2) // Both observed events; current UID is again A.
            completion.setResult(42)
            expectStale { awaiting.await() }
        }
    }

    @Test fun cancelledSdkAwaitDoesNotApplyALaterRemoteCompletion() = runBlocking {
        val completion = TaskCompletionSource<Int>()
        var applied = false
        val awaiting = launch(start = CoroutineStart.UNDISPATCHED) {
            completion.task.awaitPrivateBinderTask(session)
            applied = true
        }
        awaiting.cancelAndJoin()
        completion.setResult(42)
        assertTrue(awaiting.isCancelled)
        assertFalse(applied)
    }

    @Test fun missingCachedOrPendingDependencyDoesNotEnterRoomApply() = runBlocking {
        for (exists in listOf(false, true)) {
            for (cached in listOf(false, true)) {
                for (pending in listOf(false, true)) {
                    if (exists && !cached && !pending) continue
                    var entered = false
                    applyConfirmedPrivateDependency(exists, cached, pending) {
                        entered = true
                        database.withPrivateSyncSession(session::isCurrent) {
                            dao.putMetadata(AppMetadataEntity("synthetic-dependency", "unconfirmed"))
                        }
                    }
                    assertFalse("exists=$exists cached=$cached pending=$pending", entered)
                    assertNull(dao.metadata("synthetic-dependency"))
                }
            }
        }
    }

    @Test fun confirmedDependencyCanApplyUnderCurrentSession() = runBlocking {
        applyConfirmedPrivateDependency(exists = true, isFromCache = false, hasPendingWrites = false) {
            database.withPrivateSyncSession(session::isCurrent) {
                dao.putMetadata(AppMetadataEntity("synthetic-dependency", "confirmed"))
            }
        }
        assertEquals("confirmed", dao.metadata("synthetic-dependency"))
    }

    private suspend fun expectStale(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected stale account session")
        } catch (_: PrivatePullAccountChangedException) { /* Expected; Room must already have rolled back. */ }
    }
}
