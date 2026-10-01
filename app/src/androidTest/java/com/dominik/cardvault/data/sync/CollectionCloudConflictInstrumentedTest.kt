package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Exact production two-phase Room helpers; synthetic data, no SDK/Worker E2E claim. */
@RunWith(AndroidJUnit4::class)
class CollectionCloudConflictInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()
    private var generation = 1
    private val current = { generation == 1 }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }
    @After fun close() = database.close()

    @Test fun activeTwoPhasesRetainConflictUntilFreshReadThenApplyAllFields() = runBlocking {
        seed()
        val before = row()
        assertTrue(open())
        assertEquals(before.copy(syncState = SYNC_CLEAN, remoteRevision = 3), row())
        assertEquals(conflict(), dao.syncOutbox(KEY))
        assertEquals(1, finish())
        val saved = row()
        assertEquals(6, saved.quantity)
        assertEquals("remote", saved.notes)
        assertEquals("Good", saved.condition)
        assertEquals(222L, saved.acquisitionPriceMinor)
        assertEquals("EUR", saved.acquisitionCurrency)
        assertEquals(19000L, saved.acquisitionDateEpochDay)
        assertEquals("cardmarket", saved.externalProductSource)
        assertEquals(42L, saved.externalProductId)
        assertEquals("set", saved.sourceSetId)
        assertEquals("set-1", saved.sourceCardId)
        assertEquals("binder", saved.binderId)
        assertEquals("Holo", saved.finishOverride)
        assertEquals("Standard", saved.variantOverride)
        assertEquals("galaxy", saved.effectOverride)
        assertEquals(30L, saved.updatedAtEpochMillis)
        assertNull(saved.deletedAtEpochMillis)
        assertNull(dao.syncOutbox(KEY))
    }

    @Test fun staleBeforeFirstPhaseCannotOpenLocalConflict() = runBlocking {
        seed(); val before = row(); generation++
        assertTrue(runCatching { open() }.exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(before, row()); assertEquals(conflict(), dao.syncOutbox(KEY))
    }

    @Test fun accountRoundTripBetweenReadsCannotAcknowledgeCloudChoice() = runBlocking {
        seed(); assertTrue(open()); val opened = row(); generation += 2
        assertTrue(runCatching { finish() }.exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(opened, row()); assertEquals(conflict(), dao.syncOutbox(KEY))
    }

    @Test fun lateAccountChangeRollsBackFirstPhaseWrites() = runBlocking {
        seed(); val before = row(); var checks = 0
        assertTrue(runCatching {
            openCollectionCloudConflict(database, OWNER, conflict(), remote()) { ++checks == 1 }
        }.exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(before, row()); assertEquals(conflict(), dao.syncOutbox(KEY))
    }

    @Test fun lateAccountChangeRollsBackSecondPhasePayloadAndAcknowledgement() = runBlocking {
        seed(); open(); val opened = row(); var checks = 0
        assertTrue(runCatching {
            finishCollectionCloudConflict(database, OWNER, conflict(), remote()) { ++checks == 1 }
        }.exceptionOrNull() is PrivatePullAccountChangedException)
        assertEquals(opened, row()); assertEquals(conflict(), dao.syncOutbox(KEY))
    }

    @Test fun cancellationAtEitherCommitBoundaryDoesNotPartiallyWrite() = runBlocking {
        seed(); val original = row()
        for (finish in listOf(false, true)) {
            if (finish) open()
            val before = row(); var checks = 0
            val guard = { if (++checks > 1) throw CancellationException("synthetic") else true }
            val failure = runCatching {
                if (finish) finishCollectionCloudConflict(database, OWNER, conflict(), remote(), guard)
                else openCollectionCloudConflict(database, OWNER, conflict(), remote(), guard)
            }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(before, row()); assertEquals(conflict(), dao.syncOutbox(KEY))
        }
        assertEquals(original.notes, row().notes)
    }

    @Test fun offlineBetweenReadsKeepsFirstLegitimateCommitAndDurableConflict() = runBlocking {
        seed(); open(); val opened = row()
        assertNull(readCollectionCloudConflict(current) { throw IllegalStateException("synthetic offline") })
        assertEquals(opened, row()); assertEquals(SYNC_CLEAN, row().syncState)
        assertEquals(conflict(), dao.syncOutbox(KEY))
        assertTrue(open()) // Explicit retry tolerates the intentionally retained CLEAN presentation.
        assertEquals(1, finish())
    }

    @Test fun newerListenerRevisionFourSurvivesFreshReadRevisionThree() = runBlocking {
        seed(); open()
        val newer = row().copy(quantity = 9, notes = "newer listener", remoteRevision = 4, updatedAtEpochMillis = 40)
        dao.updateCollectionEntry(newer)
        assertEquals(1, finish())
        assertEquals(newer, row()); assertNull(dao.syncOutbox(KEY))
    }

    @Test fun firstPhaseNeverDecreasesAlreadyNewerRevision() = runBlocking {
        seed(); dao.updateCollectionEntry(row().copy(remoteRevision = 4))
        assertTrue(open()); assertEquals(4L, row().remoteRevision)
        assertEquals(1, finish()); assertEquals(4L, row().remoteRevision)
        assertEquals("local", row().notes)
    }

    @Test fun successorBeforeFirstPhaseIsNotOpenedOrRemoved() = runBlocking {
        seed(); val before = row()
        val next = conflict().copy(mutationId = "successor", state = SYNC_PENDING)
        dao.upsertSyncOutbox(next)
        assertFalse(open()); assertEquals(0, finish())
        assertEquals(before, row()); assertEquals(next, dao.syncOutbox(KEY))
    }

    @Test fun successorBetweenReadsCannotBeAcknowledgedByOldAction() = runBlocking {
        seed(); open()
        val next = conflict().copy(mutationId = "successor", state = SYNC_PENDING)
        val local = row().copy(notes = "new edit", syncState = SYNC_PENDING)
        dao.upsertSyncOutbox(next); dao.updateCollectionEntry(local)
        assertEquals(0, finish()); assertEquals(local, row()); assertEquals(next, dao.syncOutbox(KEY))
    }

    @Test fun absentOrNonConflictOutboxDoesNotApplyRemotePayload() = runBlocking {
        seed(); val before = row()
        dao.deleteSyncOutboxIfCurrent(KEY, conflict().mutationId)
        assertFalse(open()); assertEquals(0, finish()); assertEquals(before, row())
        dao.upsertSyncOutbox(conflict().copy(state = SYNC_ERROR))
        assertFalse(open()); assertEquals(0, finish()); assertEquals(before, row())
    }

    @Test fun explicitCloudTombstoneAppliesWithoutResurrectingOrRemovingCollectionIdentity() = runBlocking {
        seed()
        val tombstone = remote().copy(deletedAtEpochMillis = 30)
        assertTrue(open(tombstone)); assertEquals(1, finish(tombstone))
        assertEquals(30L, row().deletedAtEpochMillis)
        assertEquals(ENTRY, row().syncId); assertEquals(OWNER, row().ownerUid)
        assertNull(dao.syncOutbox(KEY))
    }

    @Test fun foreignOwnerCannotOpenOrFinishThisConflict() = runBlocking {
        seed(); val before = row()
        assertFalse(openCollectionCloudConflict(database, "foreign", conflict(), remote(), { true }))
        assertEquals(0, finishCollectionCloudConflict(database, "foreign", conflict(), remote(), { true }))
        assertEquals(before, row()); assertEquals(conflict(), dao.syncOutbox(KEY))
    }

    private suspend fun open(remote: RemoteCollectionEntry = remote()) =
        openCollectionCloudConflict(database, OWNER, conflict(), remote, current)
    private suspend fun finish(remote: RemoteCollectionEntry = remote()) =
        finishCollectionCloudConflict(database, OWNER, conflict(), remote, current)
    private suspend fun row() = dao.getCollectionEntryBySyncId(OWNER, ENTRY)!!
    private suspend fun seed() {
        dao.insertCatalogCard(CatalogCardEntity("card", "POKEMON", "set", "Synthetic set", "Synthetic card", "1",
            "GERMAN", "Normal", "Standard", 1))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "card", quantity = 1, condition = "Near Mint",
            acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null,
            notes = "local", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1, ownerUid = OWNER, syncId = ENTRY, syncState = SYNC_CONFLICT, remoteRevision = 1))
        dao.upsertSyncOutbox(conflict())
    }
    private fun conflict() = CollectionSyncOutboxEntity(KEY, "conflict", OWNER, ENTRY, "UPSERT", 1,
        SYNC_CONFLICT, 20, "remote-revision-3")
    private fun remote() = RemoteCollectionEntry(syncId = ENTRY, revision = 3, updatedAtEpochMillis = 30,
        deletedAtEpochMillis = null, name = "Synthetic card", setId = "set", setName = "Synthetic set",
        collectorNumber = "1", language = "GERMAN", finish = "Holo", variant = "Standard", rarity = "RARE",
        quantity = 6, condition = "Good", notes = "remote", acquisitionPriceMinor = 222,
        acquisitionCurrency = "EUR", acquisitionDateEpochDay = 19000, externalProductSource = "cardmarket",
        externalProductId = 42, imageUrl = null, effectOverride = "galaxy", sourceSetId = "set", sourceCardId = "set-1",
        binderId = "binder", binderSetKey = "set", binderName = "Synthetic", binderHeroAsset = null)

    companion object { private const val OWNER = "cloud-conflict-A"; private const val ENTRY = "entry"; private const val KEY = "$OWNER:$ENTRY" }
}
