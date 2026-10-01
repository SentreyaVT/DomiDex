package com.dominik.cardvault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic Room data only; these checks never authenticate, sync or create public links. */
@RunWith(AndroidJUnit4::class)
class PrivateBinderSharingSyncInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()
    private val owner = "sharing-fixture-owner"
    private val binderId = "selected"

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }

    @After fun tearDown() = database.close()

    @Test fun missingOrDeletedBinderCannotAppearReadyAndEmptyBinderNeedsAcknowledgement() = runBlocking {
        assertEquals(-1, state())
        val binder = binder()
        dao.insertPrivateBinder(binder.copy(remoteRevision = 0))
        assertEquals(0, state())
        dao.updatePrivateBinder(binder)
        assertEquals(1, state())
        dao.updatePrivateBinder(binder.copy(deletedAtEpochMillis = 2))
        assertEquals(-1, state())
    }

    @Test fun unrelatedOwnersBindersAndLayoutOutboxesDoNotBlockSharing() = runBlocking {
        dao.insertPrivateBinder(binder())
        dao.insertPrivateBinder(binder().copy(id = "unrelated", syncState = SYNC_PENDING, remoteRevision = 0))
        dao.insertPrivateBinder(binder().copy(ownerUid = "another-owner", syncState = SYNC_PENDING, remoteRevision = 0))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("BINDER", "unrelated"))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("BINDER", binderId, "another-owner"))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("LAYOUT", "entry"))
        dao.upsertSyncOutbox(collectionOutbox("unrelated-entry"))
        assertEquals(1, state())

        val pending = privateOutbox("BINDER", binderId)
        dao.upsertPrivateBinderSyncOutbox(pending)
        assertEquals(0, state())
        dao.deletePrivateBinderSyncOutbox(pending.entryKey)
        assertEquals(1, state())
    }

    @Test fun selectedPlacementAndReferencedCardMustBothBeAcknowledgedIncludingOutboxes() = runBlocking {
        dao.insertPrivateBinder(binder())
        val entry = insertEntry()
        val placement = placement(entry.id)
        dao.insertPrivateBinderPlacement(placement.copy(remoteRevision = 0))
        assertEquals(0, state())
        dao.updatePrivateBinderPlacement(placement)
        assertEquals(1, state())

        dao.updateCollectionEntry(entry.copy(syncState = SYNC_PENDING))
        assertEquals(0, state())
        dao.updateCollectionEntry(entry.copy(remoteRevision = 0))
        assertEquals(0, state())
        dao.updateCollectionEntry(entry)
        val pending = collectionOutbox(entry.syncId).copy(state = SYNC_CONFLICT)
        dao.upsertSyncOutbox(pending)
        assertEquals(0, state())
        dao.deleteSyncOutboxIfCurrent(pending.entryKey, pending.mutationId)
        assertEquals(1, state())

        val placementOutbox = privateOutbox("PLACEMENT", placement.id)
        dao.upsertPrivateBinderSyncOutbox(placementOutbox)
        assertEquals(0, state())
        dao.deletePrivateBinderSyncOutbox(placementOutbox.entryKey)
        assertEquals(1, state())

        dao.updateCollectionEntry(entry.copy(ownerUid = "another-owner"))
        assertEquals(0, state())
    }

    @Test fun removedPlacementMustFinishSyncButItsFormerCardNoLongerBlocksSharing() = runBlocking {
        dao.insertPrivateBinder(binder())
        val entry = insertEntry()
        val placement = placement(entry.id).copy(deletedAtEpochMillis = 2)
        dao.insertPrivateBinderPlacement(placement.copy(syncState = SYNC_PENDING))
        dao.updateCollectionEntry(entry.copy(syncState = SYNC_PENDING, deletedAtEpochMillis = 2))
        assertEquals(0, state())
        dao.updatePrivateBinderPlacement(placement)
        assertEquals(1, state())
    }

    @Test fun priorityCollectionQueryIncludesOnlyLiveReferencedPendingCardsOfExactOwnerAndBinder() = runBlocking {
        dao.insertPrivateBinder(binder())
        dao.insertPrivateBinder(binder().copy(id = "unrelated"))
        dao.insertPrivateBinder(binder().copy(ownerUid = "another-owner"))
        val entry = insertEntry()
        dao.insertPrivateBinderPlacement(placement(entry.id))
        dao.upsertSyncOutbox(collectionOutbox(entry.syncId))

        suspend fun otherEntry(syncId: String, targetBinder: String, uid: String = owner, removed: Boolean = false) {
            val id = dao.insertCollectionEntry(entry.copy(id = 0, syncId = syncId, ownerUid = uid))
            dao.insertPrivateBinderPlacement(placement(id).copy(id = "p-$syncId", binderId = targetBinder,
                ownerUid = uid, slotPosition = 1, deletedAtEpochMillis = if (removed) 2 else null))
            dao.upsertSyncOutbox(collectionOutbox(syncId).copy(entryKey = "$uid:$syncId", ownerUid = uid))
        }
        otherEntry("unrelated-card", "unrelated")
        otherEntry("foreign-card", binderId, "another-owner")
        otherEntry("removed-card", binderId, removed = true)
        assertEquals(listOf(entry.syncId), dao.pendingSharingCollectionOutbox(owner, binderId).map { it.syncId })
        assertEquals(3, dao.pendingSyncOutbox(owner).size)
        assertEquals(1, dao.pendingSyncOutbox("another-owner").size)
        dao.upsertSyncOutbox(collectionOutbox(entry.syncId).copy(state = SYNC_CONFLICT))
        assertEquals(emptyList<CollectionSyncOutboxEntity>(), dao.pendingSharingCollectionOutbox(owner, binderId))
    }

    @Test fun priorityPrivateQuerySendsBinderFirstIncludesTombstonesAndExcludesUnrelatedLayoutOrOwner() = runBlocking {
        dao.insertPrivateBinder(binder())
        val entry = insertEntry()
        dao.insertPrivateBinderPlacement(placement(entry.id).copy(deletedAtEpochMillis = 2))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("PLACEMENT", "placement"))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("BINDER", binderId).copy(createdAtEpochMillis = 100))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("BINDER", "unrelated"))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("BINDER", binderId, "another-owner"))
        dao.upsertPrivateBinderSyncOutbox(privateOutbox("LAYOUT", "entry"))
        assertEquals(listOf("BINDER", "PLACEMENT"), dao.pendingSharingPrivateBinderOutbox(owner, binderId).map { it.recordType })
        assertEquals(listOf(binderId), dao.pendingSharingPrivateBinderOutbox(owner, binderId, limit = 1).map { it.recordId })
        assertEquals(4, dao.pendingPrivateBinderSyncOutbox(owner).size)
        assertEquals(1, dao.pendingPrivateBinderSyncOutbox("another-owner").size)
    }

    private suspend fun state() = dao.observePrivateBinderSharingSyncState(owner, binderId).first()

    private fun binder() = PrivateBinderEntity(
        id = binderId, ownerUid = owner, name = "Fixture", createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1, syncState = SYNC_CLEAN, remoteRevision = 1,
    )

    private suspend fun insertEntry(): CollectionEntryEntity {
        dao.insertCatalogCard(CatalogCardEntity("fixture-card", "pokemon", "fixture-set", "Fixture", "Pikachu", "1", "DE", "Normal", "Standard", 1))
        val entry = CollectionEntryEntity(
            cardId = "fixture-card", quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
            acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "", frontPhotoUri = null,
            backPhotoUri = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            ownerUid = owner, syncId = "entry", syncState = SYNC_CLEAN, remoteRevision = 1,
        )
        return entry.copy(id = dao.insertCollectionEntry(entry))
    }

    private fun placement(entryId: Long) = PrivateBinderPlacementEntity(
        id = "placement", ownerUid = owner, binderId = binderId, collectionEntryId = entryId,
        slotPosition = 0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
        syncState = SYNC_CLEAN, remoteRevision = 1,
    )

    private fun privateOutbox(type: String, id: String, uid: String = owner) = PrivateBinderSyncOutboxEntity(
        entryKey = "$uid:$type:$id", mutationId = "fixture-mutation", ownerUid = uid, recordType = type,
        recordId = id, operation = "UPSERT", expectedRemoteRevision = 1,
        updatedAtEpochMillis = 1, createdAtEpochMillis = 1,
    )

    private fun collectionOutbox(syncId: String) = CollectionSyncOutboxEntity(
        entryKey = "$owner:$syncId", mutationId = "fixture-mutation", ownerUid = owner, syncId = syncId,
        operation = "UPSERT", expectedRemoteRevision = 1, createdAtEpochMillis = 1,
    )
}
