package com.dominik.cardvault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.local.PrivateBinderEntity
import com.dominik.cardvault.model.BinderTarget
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.ManualCardInput
import com.dominik.cardvault.model.PrivateBinderSummary
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PrivateBinderRepositoryInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            CardVaultDatabase::class.java,
        ).build()
        repository = RoomCollectionRepository(database, MutableStateFlow("private-owner"))
    }

    @After fun tearDown() = database.close()

    @Test fun privateMembershipUsesStableSlotsAndNeverDuplicatesOwnershipOrPortfolio() = runBlocking {
        seedCardmarketProduct(42L, "Mew", 345L)
        seedCardmarketProduct(43L, "Pikachu", 200L)
        val firstBinder = repository.createPrivateBinder("Lieblingskarten", "Eigene Reihenfolge")
        val secondBinder = repository.createPrivateBinder("Tauschmappe")

        repository.setPrivateBinderMinimumPageCount(firstBinder, 4)
        assertEquals(4, repository.privateBinders.first().first { it.id == firstBinder }.minimumPageCount)

        val firstAdd = repository.addManualCard(cardInput(42L, "Mew", "001", "sv-test-001"), BinderTarget.PrivateBinder(firstBinder))
        assertTrue(firstAdd.ownershipCreated)
        assertFalse(firstAdd.quantityIncremented)
        assertTrue(firstAdd.privateAssignmentCreated)
        assertEquals(false, repository.setBinderVisibility.first()[firstAdd.entryId])
        assertEquals(1, repository.portfolio.first().totalCards)
        assertEquals(3.45, repository.portfolio.first().totalValue, .001)

        val existingAssignment = repository.addManualCard(
            cardInput(42L, "Mew", "001", "sv-test-001"),
            BinderTarget.PrivateBinder(secondBinder),
        )
        assertFalse(existingAssignment.ownershipCreated)
        assertFalse(existingAssignment.quantityIncremented)
        assertTrue(existingAssignment.privateAssignmentCreated)
        assertEquals(firstAdd.entryId, existingAssignment.entryId)
        assertEquals(1, repository.portfolio.first().totalCards)
        assertEquals(3.45, repository.portfolio.first().totalValue, .001)

        val duplicateAssignment = repository.addManualCard(
            cardInput(42L, "Mew", "001", "sv-test-001"),
            BinderTarget.PrivateBinder(secondBinder),
        )
        assertFalse(duplicateAssignment.privateAssignmentCreated)
        assertEquals(1, repository.portfolio.first().totalCards)

        assertTrue(repository.unassignFromPrivateBinder(secondBinder, firstAdd.entryId))
        val reassigned = repository.assignToPrivateBinder(secondBinder, firstAdd.entryId)
        assertTrue(reassigned != duplicateAssignment.privatePlacementId)
        assertEquals(1, repository.portfolio.first().totalCards)

        val secondAdd = repository.addManualCard(cardInput(43L, "Pikachu", "002", "sv-test-002"), BinderTarget.PrivateBinder(firstBinder))
        val initial = repository.privateBinderContents(firstBinder).first()
        assertEquals(listOf(0L, 1L), initial.map { it.position })
        repository.movePrivateBinderCard(firstBinder, secondAdd.privatePlacementId!!, 0L)
        val swapped = repository.privateBinderContents(firstBinder).first()
        assertEquals("Pikachu", swapped.first { it.position == 0L }.card.name)
        assertEquals("Mew", swapped.first { it.position == 1L }.card.name)

        assertEquals(2, repository.portfolio.first().totalCards)
        assertEquals(5.45, repository.portfolio.first().totalValue, .001)
        assertEquals(5.45, repository.privateBinders.first().first { it.id == firstBinder }.marketValueEur, .001)

        repository.deletePrivateBinder(firstBinder)
        assertTrue(repository.privateBinders.first().none { it.id == firstBinder })
        assertEquals(2, repository.portfolio.first().totalCards)
        assertEquals(5.45, repository.portfolio.first().totalValue, .001)
        assertEquals(listOf("Mew"), repository.privateBinderContents(secondBinder).first().map { it.card.name })

        repository.setVisibleInSetBinder(firstAdd.entryId, true)
        assertEquals(true, repository.setBinderVisibility.first()[firstAdd.entryId])
    }

    @Test fun targetSelectionChangesOnlyDisplayStateAndPreservesLayoutCasRevision() = runBlocking {
        seedCardmarketProduct(45L, "Glurak", 600L)
        val binderId = repository.createPrivateBinder("Zielbinder")
        val added = repository.addManualCard(cardInput(45L, "Glurak", "004", "sv-test-004"))
        val card = repository.portfolio.first().cards.single()
        val dao = database.cardVaultDao()
        val entrySyncId = requireNotNull(dao.getCollectionEntry(card.entryId, "private-owner")).syncId
        val now = System.currentTimeMillis()
        dao.upsertCollectionLayout(
            com.dominik.cardvault.data.local.CollectionLayoutEntity(
                collectionEntryId = card.entryId,
                ownerUid = "private-owner",
                visibleInSetBinder = false,
                updatedAtEpochMillis = now,
                syncState = com.dominik.cardvault.data.local.SYNC_CLEAN,
                remoteRevision = 7L,
            ),
        )

        repository.applyBinderTargetSelection(card.entryId, BinderTarget.MatchingSetBinder)
        assertEquals(1, repository.portfolio.first().totalCards)
        assertEquals(added, com.dominik.cardvault.model.AddCardOutcome.CREATED)
        assertEquals(true, repository.setBinderVisibility.first()[card.entryId])
        assertEquals(7L, dao.collectionLayout(card.entryId, "private-owner")?.remoteRevision)
        assertEquals(7L, dao.privateBinderSyncOutbox("private-owner:LAYOUT:$entrySyncId")?.expectedRemoteRevision)

        repository.applyBinderTargetSelection(card.entryId, BinderTarget.PrivateBinder(binderId))
        assertEquals(1, repository.portfolio.first().totalCards)
        assertEquals(false, repository.setBinderVisibility.first()[card.entryId])
        assertEquals(listOf(card.entryId), repository.privateBinderContents(binderId).first().map { it.card.entryId })
        assertEquals(7L, dao.collectionLayout(card.entryId, "private-owner")?.remoteRevision)
        assertEquals(7L, dao.privateBinderSyncOutbox("private-owner:LAYOUT:$entrySyncId")?.expectedRemoteRevision)

        repository.addManualCard(cardInput(45L, "Glurak", "004", "sv-test-004"), BinderTarget.MatchingSetBinder)
        assertEquals(2, repository.portfolio.first().totalCards)
        assertEquals(true, repository.setBinderVisibility.first()[card.entryId])
        assertEquals(7L, dao.collectionLayout(card.entryId, "private-owner")?.remoteRevision)
        assertEquals(7L, dao.privateBinderSyncOutbox("private-owner:LAYOUT:$entrySyncId")?.expectedRemoteRevision)
    }

    @Test fun privateBinderPrimaryKeyIsOwnerScoped() = runBlocking {
        val now = System.currentTimeMillis()
        val id = "shared-local-id"
        database.cardVaultDao().insertPrivateBinder(
            PrivateBinderEntity(id = id, ownerUid = "owner-a", name = "A", createdAtEpochMillis = now, updatedAtEpochMillis = now),
        )
        database.cardVaultDao().insertPrivateBinder(
            PrivateBinderEntity(id = id, ownerUid = "owner-b", name = "B", createdAtEpochMillis = now, updatedAtEpochMillis = now),
        )
        assertEquals("A", database.cardVaultDao().privateBinder(id, "owner-a")?.name)
        assertEquals("B", database.cardVaultDao().privateBinder(id, "owner-b")?.name)

        // Keep one real Room subscription through the switch; the binder ID is identical.
        val owner = MutableStateFlow("owner-a")
        val scopedRepository = RoomCollectionRepository(database, owner)
        val summaries = Channel<PrivateBinderSummary>(Channel.UNLIMITED)
        val observer = launch {
            scopedRepository.privateBinders.collect { rows -> rows.forEach { summaries.send(it) } }
        }
        try {
            val first = withTimeout(5_000) { summaries.receiveAsFlow().first { it.name == "A" } }
            assertEquals(id, first.id)
            assertEquals("owner-a", first.ownerUid)
            owner.value = "owner-b"
            val switched = withTimeout(5_000) { summaries.receiveAsFlow().first { it.name == "B" } }
            assertEquals(id, switched.id)
            assertEquals("owner-b", switched.ownerUid)
            // An already open reader must retain the owner of its original summary.
            assertEquals("owner-a", first.ownerUid)
            owner.value = "owner-a"
            val returned = withTimeout(5_000) { summaries.receiveAsFlow().first { it.name == "A" } }
            assertEquals("owner-a", returned.ownerUid)
        } finally {
            observer.cancelAndJoin()
            summaries.close()
        }
    }

    @Test fun privateBinderAppearanceIsPlacementScopedDurableAndQueuedForSync() = runBlocking {
        seedCardmarketProduct(46L, "Nachtara", 750L)
        val binderId = repository.createPrivateBinder("Holo-Auswahl")
        assertEquals("DOMIDEX", repository.privateBinders.first().single().coverStyle)

        repository.setPrivateBinderCoverTheme(binderId, "nebula-v1")
        val binder = database.cardVaultDao().privateBinder(binderId, "private-owner")!!
        assertEquals("nebula-v1", binder.coverStyle)
        assertEquals("nebula-v1", repository.privateBinders.first().single().coverStyle)
        assertEquals(
            binder.remoteRevision,
            database.cardVaultDao().privateBinderSyncOutbox("private-owner:BINDER:$binderId")?.expectedRemoteRevision,
        )
        assertTrue(runCatching { repository.setPrivateBinderCoverTheme(binderId, "kein stabiler Wert") }.isFailure)
        assertEquals("nebula-v1", database.cardVaultDao().privateBinder(binderId, "private-owner")?.coverStyle)

        val added = repository.addManualCard(
            cardInput(46L, "Nachtara", "005", "sv-test-005"),
            BinderTarget.PrivateBinder(binderId),
        )
        val placementId = requireNotNull(added.privatePlacementId)
        repository.setPrivateBinderPlacementHolo(binderId, placementId, "reverse-holo", 0.65f)
        val rendered = repository.privateBinderContents(binderId).first().single()
        assertEquals("reverse-holo", rendered.holoEffectId)
        assertEquals(0.65f, rendered.holoIntensity, 0f)
        val stored = database.cardVaultDao().privateBinderPlacement(placementId, "private-owner")!!
        assertEquals("reverse-holo", stored.holoEffectId)
        assertEquals(0.65f, stored.holoIntensity, 0f)
        val projection = database.cardVaultDao().privateBinderPlacementSyncProjection("private-owner", placementId)!!
        assertEquals("reverse-holo", projection.holoEffectId)
        assertEquals(0.65f, projection.holoIntensity, 0f)
        assertEquals(
            stored.remoteRevision,
            database.cardVaultDao().privateBinderSyncOutbox("private-owner:PLACEMENT:$placementId")?.expectedRemoteRevision,
        )
        assertTrue(runCatching {
            repository.setPrivateBinderPlacementHolo(binderId, placementId, "reverse holo", 0.5f)
        }.isFailure)
        assertTrue(runCatching {
            repository.setPrivateBinderPlacementHolo(binderId, placementId, "reverse-holo", 1.1f)
        }.isFailure)

        repository.movePrivateBinderCard(binderId, placementId, 9L)
        assertEquals("reverse-holo", repository.privateBinderContents(binderId).first().single().holoEffectId)
        repository.setPrivateBinderPlacementHolo(binderId, placementId, null, 0.9f)
        val cleared = repository.privateBinderContents(binderId).first().single()
        assertEquals(null, cleared.holoEffectId)
        assertEquals(0f, cleared.holoIntensity, 0f)
    }

    @Test fun guestPrivateBinderClaimCopiesCompositeKeysBeforeRemovingGuestRows() = runBlocking {
        seedCardmarketProduct(44L, "Evoli", 450L)
        val owner = MutableStateFlow(GUEST_OWNER_UID)
        val guestRepository = RoomCollectionRepository(database, owner, verifyGuestClaimOwner = { owner.value })
        val binderId = guestRepository.createPrivateBinder("Gastbinder")
        val added = guestRepository.addManualCard(
            cardInput(44L, "Evoli", "003", "sv-test-003"),
            BinderTarget.PrivateBinder(binderId),
        )

        owner.value = "claimed-owner"
        assertEquals(1, guestRepository.claimGuestCollectionForCurrentUser())
        assertEquals("Gastbinder", database.cardVaultDao().privateBinder(binderId, "claimed-owner")?.name)
        assertEquals(null, database.cardVaultDao().privateBinder(binderId, GUEST_OWNER_UID))
        assertEquals(listOf(added.entryId), guestRepository.privateBinderContents(binderId).first().map { it.card.entryId })
        assertEquals(1, guestRepository.portfolio.first().totalCards)
        assertEquals(4.50, guestRepository.portfolio.first().totalValue, .001)
    }

    private suspend fun seedCardmarketProduct(productId: Long, name: String, trendMinor: Long) {
        val now = System.currentTimeMillis()
        val cardId = "cm-private-$productId"
        database.cardVaultDao().insertCatalogCard(
            CatalogCardEntity(
                id = cardId,
                tcg = "POKEMON",
                setId = "9000",
                setName = "Cardmarket Test",
                name = name,
                collectorNumber = "",
                language = "UNKNOWN",
                finish = "UNKNOWN",
                variant = "Cardmarket-Produkt",
                createdAtEpochMillis = now,
                externalSource = "CARDMARKET_PUBLIC_DOWNLOAD",
                externalProductId = productId,
            ),
        )
        database.cardVaultDao().insertRawPrices(
            listOf(
                RawPriceSnapshotEntity(
                    cardId = cardId,
                    source = "CARDMARKET_PUBLIC_DOWNLOAD",
                    currency = "EUR",
                    lowValueMinor = null,
                    averageValueMinor = null,
                    trendValueMinor = trendMinor,
                    marketValueMinor = trendMinor,
                    fetchedAtEpochMillis = now,
                    effectiveAtEpochMillis = now,
                ),
            ),
        )
    }

    private fun cardInput(productId: Long, name: String, number: String, sourceCardId: String) = ManualCardInput(
        name = name,
        setName = "Testset",
        collectorNumber = "$number/100",
        language = CardLanguage.GERMAN,
        finish = "Normal",
        variant = "Standard",
        quantity = 1,
        condition = "Nicht geprüft",
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        externalProductId = productId,
        cardmarketExpansionId = "9000",
        sourceSetId = "sv-test",
        sourceCardId = sourceCardId,
    )
}
