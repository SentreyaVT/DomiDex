package com.dominik.cardvault.data.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Production Map/Room seam; synthetic data only, no SDK snapshots, Firebase or WorkManager. */
@RunWith(AndroidJUnit4::class)
class PrivateRemoteApplyInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private val dao get() = database.cardVaultDao()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val completed = PrivateRemoteApplyOutcome.Completed
    private val deferred = PrivateRemoteApplyOutcome.DeferredLocal

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java).build()
    }
    @After fun teardown() = database.close()

    @Test fun allFourKindsProtectTheirOwnOutboxWithoutChangingPayload() = runBlocking {
        val entry = seedOwner()
        for (type in TYPES) {
            val id = if (type == PRIVATE_SYNC_BINDER) BINDER else if (type == PRIVATE_SYNC_PLACEMENT) "placement" else ENTRY
            val mutation = outbox(type, id)
            dao.upsertPrivateBinderSyncOutbox(mutation)
            val binderBefore = dao.privateBinder(BINDER, OWNER)
            assertEquals(deferred, apply(type, id))
            assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
            assertEquals(binderBefore, dao.privateBinder(BINDER, OWNER))
            assertNull(dao.privateBinderPlacement("placement", OWNER))
            assertNull(dao.collectionLayout(entry.id, OWNER))
            assertNull(dao.priceSelection(OWNER, ENTRY))
        }
    }

    @Test fun pendingOwnTargetAfterCurrentOutboxRemovalCanAcceptRemoteWins() = runBlocking {
        seedOwner()
        val local = requireNotNull(dao.privateBinder(BINDER, OWNER)).copy(syncState = SYNC_PENDING, name = "local pending")
        dao.updatePrivateBinder(local)
        val mutation = outbox(PRIVATE_SYNC_BINDER, BINDER)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        val outcome = database.withTransaction {
            dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
            applyPrivateRemoteRecord(dao, OWNER, PRIVATE_SYNC_BINDER, BINDER,
                record(PRIVATE_SYNC_BINDER, BINDER, mapOf("name" to "confirmed remote")), true)
        }
        assertEquals(completed, outcome)
        assertEquals("confirmed remote", dao.privateBinder(BINDER, OWNER)?.name)
        assertEquals(SYNC_CLEAN, dao.privateBinder(BINDER, OWNER)?.syncState)
        assertNull(dao.privateBinderSyncOutbox(mutation.entryKey))
    }

    @Test fun bothMembershipTieDirectionsKeepOtherPendingMutationAndPayload() = runBlocking {
        val entry = seedOwner()
        for ((existingId, incomingId) in listOf("a-local" to "z-remote", "z-local" to "a-remote")) {
            val local = placement(existingId, entry.id, SYNC_PENDING)
            dao.upsertPrivateBinderPlacement(local)
            val mutation = outbox(PRIVATE_SYNC_PLACEMENT, existingId)
            dao.upsertPrivateBinderSyncOutbox(mutation)
            assertEquals(deferred, apply(PRIVATE_SYNC_PLACEMENT, incomingId))
            assertEquals(local, dao.privateBinderPlacement(existingId, OWNER))
            assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
            assertNull(dao.privateBinderPlacement(incomingId, OWNER))
            dao.deletePrivateBinderSyncOutbox(mutation.entryKey)
            dao.updatePrivateBinderPlacement(local.copy(deletedAtEpochMillis = 99))
        }
    }

    @Test fun terminalMembershipWithoutOutboxAndOlderPendingDuplicateAreProtected() = runBlocking {
        val entry = seedOwner()
        val terminal = placement("z-terminal", entry.id, SYNC_ERROR)
        dao.insertPrivateBinderPlacement(terminal)
        assertEquals(deferred, apply(PRIVATE_SYNC_PLACEMENT, "a-incoming"))
        assertEquals(terminal, dao.privateBinderPlacement(terminal.id, OWNER))
        assertNull(dao.privateBinderPlacement("a-incoming", OWNER))
        // The prior LIMIT 1 membership query can select the newer CLEAN duplicate instead.
        val pending = terminal.copy(syncState = SYNC_PENDING, updatedAtEpochMillis = 1)
        dao.updatePrivateBinderPlacement(pending)
        dao.insertPrivateBinderPlacement(placement("z-newer-clean", entry.id, SYNC_CLEAN).copy(updatedAtEpochMillis = 100))
        val mutation = outbox(PRIVATE_SYNC_PLACEMENT, pending.id)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        assertEquals(deferred, apply(PRIVATE_SYNC_PLACEMENT, "a-incoming"))
        assertEquals(pending, dao.privateBinderPlacement(pending.id, OWNER))
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
    }

    @Test fun terminalCollectionParentDefersAllChildKindsUntilExplicitlyResolved() = runBlocking {
        val entry = seedOwner()
        for (state in listOf(SYNC_ERROR, SYNC_CONFLICT)) {
            dao.updateCollectionEntry(entry.copy(syncState = state))
            assertEquals(deferred, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
            assertEquals(deferred, apply(PRIVATE_SYNC_LAYOUT, ENTRY))
            assertEquals(deferred, apply(PRIVATE_SYNC_PRICE_SELECTION, ENTRY))
            assertNull(dao.privateBinderPlacement("placement", OWNER))
            assertNull(dao.collectionLayout(entry.id, OWNER))
            assertNull(dao.priceSelection(OWNER, ENTRY))
        }
        dao.updateCollectionEntry(entry.copy(syncState = SYNC_CLEAN))
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
        assertEquals(completed, apply(PRIVATE_SYNC_LAYOUT, ENTRY))
        assertEquals(completed, apply(PRIVATE_SYNC_PRICE_SELECTION, ENTRY))
    }

    @Test fun localDeletedParentsAreNotTreatedAsConfirmedRemoteDeletion() = runBlocking {
        val entry = seedOwner()
        val originalBinder = requireNotNull(dao.privateBinder(BINDER, OWNER))
        val deletedBinder = originalBinder.copy(syncState = SYNC_PENDING, deletedAtEpochMillis = 10)
        dao.updatePrivateBinder(deletedBinder)
        val binderMutation = outbox(PRIVATE_SYNC_BINDER, BINDER)
        dao.upsertPrivateBinderSyncOutbox(binderMutation)
        assertEquals(deferred, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
        assertEquals(deletedBinder, dao.privateBinder(BINDER, OWNER))
        dao.deletePrivateBinderSyncOutbox(binderMutation.entryKey)
        dao.updatePrivateBinder(originalBinder)
        dao.updateCollectionEntry(entry.copy(deletedAtEpochMillis = 10, syncState = SYNC_PENDING))
        assertEquals(deferred, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
        assertEquals(deferred, apply(PRIVATE_SYNC_LAYOUT, ENTRY))
        assertEquals(deferred, apply(PRIVATE_SYNC_PRICE_SELECTION, ENTRY))
        // Explicit resolution, not this apply helper, restores the parent.
        dao.updateCollectionEntry(entry)
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
    }

    @Test fun cleanDeletedParentIsCompletedButNotRecreatedAndLivePendingParentIsAllowed() = runBlocking {
        val entry = seedOwner()
        dao.updateCollectionEntry(entry.copy(deletedAtEpochMillis = 10, syncState = SYNC_CLEAN))
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
        assertNull(dao.privateBinderPlacement("placement", OWNER))
        dao.updateCollectionEntry(entry.copy(syncState = SYNC_PENDING))
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
        assertNotNull(dao.privateBinderPlacement("placement", OWNER))
    }

    @Test fun missingParentsReturnTypedDependenciesWithoutFabricatingRows() = runBlocking {
        assertEquals(PrivateRemoteApplyOutcome.RetryDependency(PrivateRemoteDependencyType.BINDER, BINDER),
            apply(PRIVATE_SYNC_PLACEMENT, "placement"))
        assertEquals(PrivateRemoteApplyOutcome.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, ENTRY),
            apply(PRIVATE_SYNC_LAYOUT, ENTRY))
        assertEquals(PrivateRemoteApplyOutcome.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, ENTRY),
            apply(PRIVATE_SYNC_PRICE_SELECTION, ENTRY))
        assertNull(dao.privateBinder(BINDER, OWNER))
        assertNull(dao.getCollectionEntryBySyncId(OWNER, ENTRY))
        seedOwner()
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "placement"))
    }

    @Test fun unknownBinderAndPlacementTombstonesRetainRevisionAndRejectOlderLiveSnapshots() = runBlocking {
        for (type in listOf(PRIVATE_SYNC_BINDER, PRIVATE_SYNC_PLACEMENT)) {
            val id = "unknown-${type.lowercase()}"
            assertEquals(completed, apply(type, id, mapOf("deleted" to true, "revision" to 9L)))
            val receipt = dao.metadata(privateRemoteTombstoneReceiptKey(OWNER, type, id))
            assertNotNull(receipt)
            assertEquals(completed, apply(type, id, mapOf("revision" to 8L)))
            assertEquals(completed, apply(type, id, mapOf("revision" to 9L)))
            assertEquals(completed, apply(type, id, mapOf("deleted" to true, "revision" to 3L)))
            assertEquals(receipt, dao.metadata(privateRemoteTombstoneReceiptKey(OWNER, type, id)))
            assertNull(dao.privateBinder(id, OWNER))
            assertNull(dao.privateBinderPlacement(id, OWNER))
        }
    }

    @Test fun receiptSurvivesRoomReopenWithoutCreatingMissingParents() = runBlocking {
        val name = "private-remote-apply-fixture-${UUID.randomUUID()}.db"
        var disk = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
        try {
            disk.withTransaction {
                assertEquals(completed, applyPrivateRemoteRecord(disk.cardVaultDao(), OWNER, PRIVATE_SYNC_PLACEMENT, "deleted",
                    record(PRIVATE_SYNC_PLACEMENT, "deleted", mapOf("deleted" to true, "revision" to 5L)), true))
            }
            disk.close()
            disk = Room.databaseBuilder(context, CardVaultDatabase::class.java, name).build()
            disk.withTransaction {
                assertEquals(completed, applyPrivateRemoteRecord(disk.cardVaultDao(), OWNER, PRIVATE_SYNC_PLACEMENT, "deleted",
                    record(PRIVATE_SYNC_PLACEMENT, "deleted", mapOf("revision" to 4L)), true))
            }
            assertNull(disk.cardVaultDao().privateBinderPlacement("deleted", OWNER))
            assertNull(disk.cardVaultDao().privateBinder(BINDER, OWNER))
            assertNotNull(disk.cardVaultDao().metadata(privateRemoteTombstoneReceiptKey(OWNER, PRIVATE_SYNC_PLACEMENT, "deleted")))
        } finally {
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun sameDocumentIdsAndReceiptsAreOwnerAndRecordTypeIsolated() = runBlocking {
        seedOwner()
        val otherEntry = seedOwner(OTHER_OWNER)
        val foreignBinder = requireNotNull(dao.privateBinder(BINDER, OTHER_OWNER))
        assertEquals(completed, apply(PRIVATE_SYNC_BINDER, BINDER, mapOf("name" to "owner A changed")))
        assertEquals(foreignBinder, dao.privateBinder(BINDER, OTHER_OWNER))
        assertEquals(completed, apply(PRIVATE_SYNC_BINDER, "same", mapOf("deleted" to true, "revision" to 8L)))
        assertEquals(completed, apply(PRIVATE_SYNC_BINDER, "same", owner = OTHER_OWNER))
        assertNotNull(dao.privateBinder("same", OTHER_OWNER))
        assertNull(dao.privateBinder("same", OWNER))
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "same"))
        assertNotNull(dao.privateBinderPlacement("same", OWNER))
        assertNull(dao.metadata(privateRemoteTombstoneReceiptKey(OTHER_OWNER, PRIVATE_SYNC_BINDER, "same")))
        assertNull(dao.metadata(privateRemoteTombstoneReceiptKey(OWNER, PRIVATE_SYNC_PLACEMENT, "same")))
        assertEquals(otherEntry, dao.getCollectionEntryBySyncId(OTHER_OWNER, ENTRY))
    }

    @Test fun invalidAndUnconfirmedTombstonesCannotCreateReceipts() = runBlocking {
        for (type in listOf(PRIVATE_SYNC_BINDER, PRIVATE_SYNC_PLACEMENT)) {
            val id = "invalid"
            for (bad in listOf(mapOf("ownerUid" to OTHER_OWNER), mapOf("revision" to 2.5), mapOf("updatedAtClient" to 0L))) {
                assertEquals(PrivateRemoteApplyOutcome.Invalid, apply(type, id, bad + ("deleted" to true)))
            }
            assertEquals(deferred, apply(type, id, mapOf("deleted" to true), confirmed = false))
            assertNull(dao.metadata(privateRemoteTombstoneReceiptKey(OWNER, type, id)))
        }
    }

    @Test fun corruptReceiptThrowsAndRollsBackTentativeOutboxRemoval() = runBlocking {
        seedOwner()
        val mutation = outbox(PRIVATE_SYNC_BINDER, BINDER)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        val key = privateRemoteTombstoneReceiptKey(OWNER, PRIVATE_SYNC_BINDER, BINDER)
        dao.putMetadata(AppMetadataEntity(key, "corrupt-receipt"))
        val before = dao.privateBinder(BINDER, OWNER)
        val failure = runCatching {
            database.withTransaction {
                dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                applyPrivateRemoteRecord(dao, OWNER, PRIVATE_SYNC_BINDER, BINDER, record(PRIVATE_SYNC_BINDER, BINDER), true)
            }
        }.exceptionOrNull()
        assertTrue(failure is PrivateRemoteReceiptException)
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
        assertEquals(before, dao.privateBinder(BINDER, OWNER))
        assertEquals("corrupt-receipt", dao.metadata(key))
    }

    @Test fun interruptedTransactionRollsBackEntityReceiptAndOutboxTogether() = runBlocking {
        seedOwner()
        val mutation = outbox(PRIVATE_SYNC_BINDER, BINDER)
        dao.upsertPrivateBinderSyncOutbox(mutation)
        val before = dao.privateBinder(BINDER, OWNER)
        assertNotNull(runCatching {
            database.withTransaction {
                dao.deletePrivateBinderSyncOutboxIfCurrent(mutation.entryKey, mutation.mutationId)
                applyPrivateRemoteRecord(dao, OWNER, PRIVATE_SYNC_BINDER, BINDER, record(PRIVATE_SYNC_BINDER, BINDER), true)
                applyPrivateRemoteRecord(dao, OWNER, PRIVATE_SYNC_PLACEMENT, "deleted",
                    record(PRIVATE_SYNC_PLACEMENT, "deleted", mapOf("deleted" to true)), true)
                error("synthetic interruption")
            }
        }.exceptionOrNull())
        assertEquals(before, dao.privateBinder(BINDER, OWNER))
        assertEquals(mutation, dao.privateBinderSyncOutbox(mutation.entryKey))
        assertNull(dao.metadata(privateRemoteTombstoneReceiptKey(OWNER, PRIVATE_SYNC_PLACEMENT, "deleted")))
    }

    @Test fun cleanMembershipTieAndFirstFreeSlotPolicyRemainUnchanged() = runBlocking {
        val entry = seedOwner()
        dao.insertPrivateBinderPlacement(placement("z-loser", entry.id, SYNC_CLEAN))
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "a-winner"))
        assertNotNull(dao.privateBinderPlacement("z-loser", OWNER)?.deletedAtEpochMillis)
        assertEquals(PRIVATE_SYNC_DELETE, dao.privateBinderSyncOutbox("$OWNER:$PRIVATE_SYNC_PLACEMENT:z-loser")?.operation)
        assertNull(dao.privateBinderPlacement("a-winner", OWNER)?.deletedAtEpochMillis)
        val second = newEntry("second")
        assertEquals(completed, apply(PRIVATE_SYNC_PLACEMENT, "another", mapOf("entrySyncId" to second.syncId, "slotPosition" to 0L)))
        assertEquals(1L, dao.privateBinderPlacement("another", OWNER)?.slotPosition)
        assertEquals(PRIVATE_SYNC_UPSERT, dao.privateBinderSyncOutbox("$OWNER:$PRIVATE_SYNC_PLACEMENT:another")?.operation)
    }

    @Test fun clearedPriceSelectionRetainsRevisionWithoutChangingCollectionIdentity() = runBlocking {
        val entry = seedOwner()
        assertEquals(completed, apply(PRIVATE_SYNC_PRICE_SELECTION, ENTRY, mapOf("revision" to 7L)))
        val saved = requireNotNull(dao.priceSelection(OWNER, ENTRY))
        assertTrue(saved.cleared)
        assertEquals(7L, saved.remoteRevision)
        assertEquals(completed, apply(PRIVATE_SYNC_PRICE_SELECTION, ENTRY, mapOf("revision" to 6L)))
        assertEquals(saved, dao.priceSelection(OWNER, ENTRY))
        assertEquals(entry, dao.getCollectionEntryBySyncId(OWNER, ENTRY))
    }

    private suspend fun apply(type: String, id: String, extra: Map<String, Any?> = emptyMap(),
                              owner: String = OWNER, confirmed: Boolean = true) = database.withTransaction {
        applyPrivateRemoteRecord(dao, owner, type, id, record(type, id, extra, owner), confirmed)
    }

    private fun record(type: String, id: String, extra: Map<String, Any?> = emptyMap(), owner: String = OWNER): Map<String, Any?> {
        val common = mapOf("ownerUid" to owner, "revision" to 3L, "updatedAtClient" to 30L,
            "createdAtClient" to 1L, "lastMutationId" to "remote-mutation", "deleted" to false)
        val fields: Map<String, Any?> = when (type) {
            PRIVATE_SYNC_BINDER -> mapOf("binderId" to id, "name" to "remote binder", "description" to null,
                "coverStyle" to "DOMIDEX", "minimumPageCount" to 1L, "lastOpenedAtClient" to null)
            PRIVATE_SYNC_PLACEMENT -> mapOf("placementId" to id, "binderId" to BINDER, "entrySyncId" to ENTRY,
                "slotPosition" to 0L, "holoEffectId" to "galaxy", "holoIntensity" to 0.5)
            PRIVATE_SYNC_LAYOUT -> mapOf("entrySyncId" to id, "visibleInSetBinder" to false)
            PRIVATE_SYNC_PRICE_SELECTION -> mapOf("entrySyncId" to id, "schemaVersion" to 1L, "cleared" to true) +
                PRICE_SELECTION_VALUE_FIELDS.associateWith { null }
            else -> emptyMap()
        }
        return common + fields + extra
    }

    private suspend fun seedOwner(owner: String = OWNER): CollectionEntryEntity {
        dao.insertCatalogCards(listOf(CatalogCardEntity(CARD, "POKEMON", "synthetic-set", "Synthetic set",
            "Synthetic card", "1", "GERMAN", "Normal", "Standard", 1L)))
        val entry = newEntry(ENTRY, owner)
        dao.upsertPrivateBinder(PrivateBinderEntity(BINDER, owner, "local binder", createdAtEpochMillis = 1,
            updatedAtEpochMillis = 1, syncState = SYNC_CLEAN, remoteRevision = 1))
        return entry
    }

    private suspend fun newEntry(syncId: String, owner: String = OWNER): CollectionEntryEntity {
        val row = CollectionEntryEntity(cardId = CARD, quantity = 1, condition = "Near Mint", acquisitionPriceMinor = null,
            acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "synthetic", frontPhotoUri = null,
            backPhotoUri = null, createdAtEpochMillis = 1, updatedAtEpochMillis = 1,
            ownerUid = owner, syncId = syncId, syncState = SYNC_CLEAN, remoteRevision = 1)
        return row.copy(id = dao.insertCollectionEntry(row))
    }

    private fun placement(id: String, entryId: Long, state: String) = PrivateBinderPlacementEntity(
        id, OWNER, BINDER, entryId, 0, createdAtEpochMillis = 1, updatedAtEpochMillis = 1, syncState = state, remoteRevision = 1,
    )
    private fun outbox(type: String, id: String) = PrivateBinderSyncOutboxEntity(
        "$OWNER:$type:$id", "local-mutation-$id", OWNER, type, id, PRIVATE_SYNC_UPSERT,
        expectedRemoteRevision = 1, updatedAtEpochMillis = 1, createdAtEpochMillis = 1,
    )

    companion object {
        private const val OWNER = "private-apply-fixture-A"
        private const val OTHER_OWNER = "private-apply-fixture-B"
        private const val BINDER = "binder"
        private const val ENTRY = "entry"
        private const val CARD = "synthetic-private-apply-card"
        private val TYPES = listOf(PRIVATE_SYNC_BINDER, PRIVATE_SYNC_PLACEMENT, PRIVATE_SYNC_LAYOUT, PRIVATE_SYNC_PRICE_SELECTION)
    }
}
