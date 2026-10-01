package com.dominik.cardvault.data.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Actual isolated Room aggregation; no Firebase, authenticated session or Worker E2E claim. */
@RunWith(AndroidJUnit4::class)
class CollectionCloudQueueStateInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }
    @After fun close() = database.close()

    @Test fun isolatedPriceSelectionConflictIsErrorEvenWithNoPendingRows() = runBlocking {
        val mutation = seedPriceSelection(OWNER)
        assertTrue(database.withPrivateSyncSession({ true }) { markPriceSelectionConflict(dao, mutation, 3) })
        val selection = dao.priceSelection(OWNER, ENTRY)
        val conflict = dao.privateBinderSyncOutbox(mutation.entryKey)
        assertEquals(SYNC_CONFLICT, selection?.syncState)
        assertEquals(1, dao.conflictedPriceSelectionOutbox(OWNER).size)
        assertTrue(dao.pendingPrivateBinderSyncOutbox(OWNER).isEmpty())
        assertTrue(dao.pendingSyncOutbox(OWNER).isEmpty())
        assertTrue(dao.erroredSyncOutbox(OWNER).isEmpty())
        assertTrue(dao.conflictedSyncOutbox(OWNER).isEmpty())

        val queue = readCollectionCloudQueueState(dao, OWNER)
        assertFalse(queue.pending)
        assertEquals("Status reading must not resolve or mutate the choice", selection, dao.priceSelection(OWNER, ENTRY))
        assertEquals(conflict, dao.privateBinderSyncOutbox(mutation.entryKey))
        for (serverSnapshot in listOf(true, false)) {
            assertEquals("An unresolved price conflict cannot look synced or merely local",
                CollectionCloudStatus.ERROR, status(queue, serverSnapshot))
        }
    }

    @Test fun absentOutboxesLeaveServerAndLocalOnlyStatusUnchanged() = runBlocking {
        val queue = readCollectionCloudQueueState(dao, OWNER)
        assertEquals(CollectionCloudQueueState(pending = false, error = false), queue)
        assertEquals(CollectionCloudStatus.ACTIVE, status(queue, true))
        assertEquals(CollectionCloudStatus.LOCAL_ONLY, status(queue, false))
    }

    @Test fun regularCollectionAndPrivateMutationsRemainPending() = runBlocking {
        dao.upsertSyncOutbox(collectionMutation(OWNER, SYNC_PENDING))
        assertEquals(CollectionCloudStatus.PENDING, status(readCollectionCloudQueueState(dao, OWNER)))
        for (type in listOf(PRIVATE_SYNC_BINDER, PRIVATE_SYNC_PLACEMENT, PRIVATE_SYNC_LAYOUT, PRIVATE_SYNC_PRICE_SELECTION)) {
            val uid = "queue-$type"
            val mutation = if (type == PRIVATE_SYNC_PRICE_SELECTION) seedPriceSelection(uid) else privateMutation(uid, type)
            // Retriable generic errors still belong to the existing pending policy.
            dao.upsertPrivateBinderSyncOutbox(mutation.copy(lastError = "network-error"))
            val queue = readCollectionCloudQueueState(dao, uid)
            assertTrue(queue.pending)
            assertFalse(queue.error)
            assertEquals(CollectionCloudStatus.PENDING, status(queue))
        }
    }

    @Test fun foreignPendingErrorsAndPriceConflictsDoNotAffectThisOwner() = runBlocking {
        val foreign = "queue-foreign"
        for (state in listOf(SYNC_PENDING, SYNC_ERROR, SYNC_CONFLICT)) {
            dao.upsertSyncOutbox(collectionMutation(foreign, state))
        }
        dao.upsertPrivateBinderSyncOutbox(privateMutation(foreign, PRIVATE_SYNC_BINDER))
        val price = seedPriceSelection(foreign)
        assertTrue(database.withPrivateSyncSession({ true }) { markPriceSelectionConflict(dao, price, 3) })
        assertEquals(1, dao.conflictedPriceSelectionOutbox(foreign).size)

        val queue = readCollectionCloudQueueState(dao, OWNER)
        assertEquals(CollectionCloudQueueState(pending = false, error = false), queue)
        assertEquals(CollectionCloudStatus.ACTIVE, status(queue))
    }

    @Test fun existingCollectionErrorsAndConflictsTakePrecedenceOverPending() = runBlocking {
        for (state in listOf(SYNC_ERROR, SYNC_CONFLICT)) {
            val uid = "queue-$state"
            dao.upsertSyncOutbox(collectionMutation(uid, state))
            dao.upsertPrivateBinderSyncOutbox(privateMutation(uid, PRIVATE_SYNC_BINDER))
            val queue = readCollectionCloudQueueState(dao, uid)
            assertTrue(queue.pending)
            assertTrue(queue.error)
            assertEquals(CollectionCloudStatus.ERROR, status(queue))
        }
    }

    @Test fun explicitLocalResolutionChangesErrorToPendingWithoutRefreshingThePriceSource() = runBlocking {
        val conflict = seedConflict()
        val selection = dao.priceSelection(OWNER, ENTRY)!!
        val parent = dao.getCollectionEntryBySyncId(OWNER, ENTRY)
        assertEquals(CollectionCloudStatus.ERROR, status(readCollectionCloudQueueState(dao, OWNER)))

        assertTrue(keepLocalPriceSelectionConflict(database, conflict, "explicit-local", 99) { true })
        assertEquals(selection.copy(syncState = SYNC_PENDING, remoteRevision = 3, updatedAtEpochMillis = 99),
            dao.priceSelection(OWNER, ENTRY))
        assertEquals(conflict.copy(mutationId = "explicit-local", expectedRemoteRevision = 3,
            updatedAtEpochMillis = 99, createdAtEpochMillis = 99, lastError = null),
            dao.privateBinderSyncOutbox(conflict.entryKey))
        assertEquals(parent, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        val queue = readCollectionCloudQueueState(dao, OWNER)
        assertEquals(CollectionCloudQueueState(pending = true, error = false), queue)
        assertEquals(CollectionCloudStatus.PENDING, status(queue))
    }

    @Test fun explicitConfirmedCloudResolutionClearsTheQueueButStillNeedsAServerSnapshotForActive() = runBlocking {
        val conflict = seedConflict()
        val selection = dao.priceSelection(OWNER, ENTRY)!!
        val parent = dao.getCollectionEntryBySyncId(OWNER, ENTRY)
        assertEquals(CollectionCloudStatus.ERROR, status(readCollectionCloudQueueState(dao, OWNER)))

        assertTrue(keepCloudPriceSelectionConflict(database, conflict, remote(), true) { true })
        assertEquals(selection.copy(amountMinor = 999, syncState = SYNC_CLEAN, remoteRevision = 3,
            updatedAtEpochMillis = 25), dao.priceSelection(OWNER, ENTRY))
        assertNull(dao.privateBinderSyncOutbox(conflict.entryKey))
        assertEquals(parent, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        val queue = readCollectionCloudQueueState(dao, OWNER)
        assertEquals(CollectionCloudQueueState(pending = false, error = false), queue)
        assertEquals(CollectionCloudStatus.ACTIVE, status(queue, true))
        assertEquals(CollectionCloudStatus.LOCAL_ONLY, status(queue, false))
    }

    @Test fun unconfirmedCloudAndStaleActionsCannotClearTheCurrentError() = runBlocking {
        val conflict = seedConflict()
        val selection = dao.priceSelection(OWNER, ENTRY)
        assertFalse(keepCloudPriceSelectionConflict(database, conflict, remote(), false) { true })
        assertEquals(conflict, dao.privateBinderSyncOutbox(conflict.entryKey))
        assertEquals(selection, dao.priceSelection(OWNER, ENTRY))
        assertEquals(CollectionCloudStatus.ERROR, status(readCollectionCloudQueueState(dao, OWNER)))

        val newerConflict = conflict.copy(mutationId = "newer-conflict")
        dao.upsertPrivateBinderSyncOutbox(newerConflict)
        assertFalse(keepLocalPriceSelectionConflict(database, conflict, "stale-local", 99) { true })
        assertFalse(keepCloudPriceSelectionConflict(database, conflict, remote(), true) { true })
        assertEquals(newerConflict, dao.privateBinderSyncOutbox(conflict.entryKey))
        assertEquals(selection, dao.priceSelection(OWNER, ENTRY))
        val queue = readCollectionCloudQueueState(dao, OWNER)
        assertEquals(CollectionCloudQueueState(pending = false, error = true), queue)
        assertEquals(CollectionCloudStatus.ERROR, status(queue))
    }

    @Test fun conflictMarkerUsesTheExistingExactTypeAndGlobPolicy() = runBlocking {
        val cases = listOf(
            Triple(PRIVATE_SYNC_PRICE_SELECTION, "price-selection-conflict:garbage", true),
            Triple(PRIVATE_SYNC_PRICE_SELECTION, "price-selection-conflict:", true),
            Triple(PRIVATE_SYNC_PRICE_SELECTION, "price-selection-conflicts:3", false),
            Triple(PRIVATE_SYNC_PRICE_SELECTION, "PRICE-SELECTION-CONFLICT:3", false),
            Triple(PRIVATE_SYNC_BINDER, "price-selection-conflict:3", false),
            Triple(PRIVATE_SYNC_PLACEMENT, "price-selection-conflict:3", false),
            Triple(PRIVATE_SYNC_LAYOUT, "price-selection-conflict:3", false),
        )
        for ((index, case) in cases.withIndex()) {
            val (type, marker, blocked) = case
            val uid = "marker-owner-$index"
            val mutation = if (type == PRIVATE_SYNC_PRICE_SELECTION) seedPriceSelection(uid) else privateMutation(uid, type)
            dao.upsertPrivateBinderSyncOutbox(mutation.copy(lastError = marker))
            val queue = readCollectionCloudQueueState(dao, uid)
            assertEquals("Marker $marker on $type", CollectionCloudQueueState(pending = !blocked, error = blocked), queue)
            assertEquals(if (blocked) CollectionCloudStatus.ERROR else CollectionCloudStatus.PENDING, status(queue))
            assertEquals(marker, dao.privateBinderSyncOutbox(mutation.entryKey)?.lastError)
        }
    }

    private fun status(queue: CollectionCloudQueueState, serverSnapshot: Boolean = true) =
        collectionCloudStatus(false, true, false, serverSnapshot, queue.pending, queue.error)

    private fun collectionMutation(uid: String, state: String) = CollectionSyncOutboxEntity(
        "$uid:entry-$state", "collection-$state", uid, "entry-$state", "UPSERT", 1,
        state = state, createdAtEpochMillis = 20,
    )

    private fun privateMutation(uid: String, type: String) = PrivateBinderSyncOutboxEntity(
        "$uid:$type:$ENTRY", "private-$type", uid, type, ENTRY, PRIVATE_SYNC_UPSERT, 1, 20, 20,
    )

    private suspend fun seedConflict(): PrivateBinderSyncOutboxEntity {
        val mutation = seedPriceSelection(OWNER)
        assertTrue(database.withPrivateSyncSession({ true }) { markPriceSelectionConflict(dao, mutation, 3) })
        return dao.privateBinderSyncOutbox(mutation.entryKey)!!
    }

    private fun remote(): Map<String, Any?> = mapOf(
        "ownerUid" to OWNER, "entrySyncId" to ENTRY, "schemaVersion" to 1L,
        "revision" to 3L, "updatedAtClient" to 25L, "lastMutationId" to "remote", "cleared" to false,
        "sourceSetId" to "synthetic-set", "sourceCardId" to "synthetic-set-1", "collectorNumber" to "1",
        "finish" to "Normal", "variant" to "Standard", "language" to "GERMAN", "productId" to 42L,
        "metric" to "normal_low", "amountMinor" to 999L, "currency" to "EUR", "sourceAt" to 10L, "selectedAt" to 15L,
    )

    private suspend fun seedPriceSelection(uid: String): PrivateBinderSyncOutboxEntity {
        dao.insertCatalogCards(listOf(CatalogCardEntity("card", "POKEMON", "synthetic-set", "Synthetic",
            "Synthetic card", "1", "GERMAN", "Normal", "Standard", 1)))
        dao.upsertBinders(listOf(BinderEntity("inbox", "inbox", "Synthetic", createdAtEpochMillis = 1, updatedAtEpochMillis = 1)))
        val id = dao.insertCollectionEntry(CollectionEntryEntity(cardId = "card", quantity = 1,
            condition = "Near Mint", acquisitionPriceMinor = null, acquisitionCurrency = null,
            acquisitionDateEpochDay = null, notes = "", frontPhotoUri = null, backPhotoUri = null,
            createdAtEpochMillis = 1, updatedAtEpochMillis = 1, ownerUid = uid, syncId = ENTRY,
            syncState = SYNC_CLEAN, remoteRevision = 1, sourceSetId = "synthetic-set", sourceCardId = "synthetic-set-1",
            externalProductSource = CardmarketSyncContract.SOURCE, externalProductId = 42))
        dao.upsertPriceSelection(CollectionPriceSelectionEntity(id, uid, ENTRY, cleared = false,
            sourceSetId = "synthetic-set", sourceCardId = "synthetic-set-1", collectorNumber = "1",
            finish = "Normal", variant = "Standard", language = "GERMAN", productId = 42,
            metric = "normal_low", amountMinor = 532, currency = "EUR", sourceAt = 10, selectedAt = 15,
            updatedAtEpochMillis = 20, syncState = SYNC_PENDING, remoteRevision = 1))
        return privateMutation(uid, PRIVATE_SYNC_PRICE_SELECTION).also { dao.upsertPrivateBinderSyncOutbox(it) }
    }

    companion object {
        private const val OWNER = "cloud-queue-A"
        private const val ENTRY = "entry"
    }
}
