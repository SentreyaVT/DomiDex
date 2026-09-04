package com.dominik.cardvault.ui

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.model.AddCardOutcome
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.GermanMasterSetCardDefinition
import com.dominik.cardvault.model.GermanMasterSetDefinition
import com.dominik.cardvault.model.ManualCardInput
import com.dominik.cardvault.model.PortfolioSummary
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real cache/add/observe/reload path in a disposable database; no installed collection or Firebase. */
@RunWith(AndroidJUnit4::class)
class BinderSlotOwnershipInstrumentedTest {
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val set = GermanMasterSetDefinition("binder-slot-regression", "Binder Slot Regression")
    private val sourceCardId = "${set.sourceId}-001"
    // A source-declared Holo/Reverse card has no canonical Normal cache row. The first
    // owned add consequently creates a second catalogue ID for that exact printing.
    private val definitions = listOf(
        GermanMasterSetCardDefinition("$sourceCardId-holo", "Karte 1", "001", null, "Holo"),
        GermanMasterSetCardDefinition("$sourceCardId-reverse", "Karte 1", "001", null, "Reverse Holo"),
        GermanMasterSetCardDefinition("${set.sourceId}-002", "Karte 2", "002", null, "Normal"),
    )

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).build()
        repository = RoomCollectionRepository(database)
    }

    @After fun closeDatabase() = database.close()

    @Test fun holoAddQuantityRemoveAndReloadKeepTheOriginalSlots() = runBlocking {
        verifyOwnershipChanges("Holo", "Reverse Holo")
    }

    @Test fun reverseAddQuantityRemoveAndReloadKeepTheOriginalSlots() = runBlocking {
        verifyOwnershipChanges("Reverse Holo", "Holo")
    }

    private suspend fun verifyOwnershipChanges(firstFinish: String, otherFinish: String) = coroutineScope {
        val emissions = Channel<PortfolioSummary>(Channel.UNLIMITED)
        // Keep this subscription alive across writes, as the reader's parent does.
        val observer = launch(start = CoroutineStart.UNDISPATCHED) {
            repository.portfolio.collect { emissions.send(it) }
        }
        try {
            val empty = emissions.awaitCards { it.isEmpty() }
            repository.cacheGermanMasterSetCards(set, definitions)
            val references = repository.binderReferences(set.name)
            val original = arrangeBinderCards(set.name, empty, 2, references).filterNotNull()
            assertEquals(3, original.size)
            assertTrue(original.all { it.entryId == 0L && it.quantity == 0 })
            val identities = original.map(::identity)
            val firstReference = original.single { it.finish == firstFinish }
            val otherReference = original.single { it.finish == otherFinish }
            val normalReference = original.single { it.finish == "Normal" }
            val firstInput = firstReference.previewInput()

            assertEquals(AddCardOutcome.CREATED, repository.addManualCard(firstInput))
            val first = emissions.awaitCards { it.size == 1 && it.single().quantity == 1 }
            val firstEntry = first.single()
            assertEquals("tcgdex-${set.sourceId}-$sourceCardId", firstEntry.id)
            assertEquals(set.sourceId, firstEntry.sourceSetId)
            assertEquals(sourceCardId, firstEntry.sourceCardId)
            assertEquals("tcgdex-${set.sourceId}", firstEntry.binderId)
            assertEquals("001", firstEntry.number)
            assertEquals(CardLanguage.GERMAN, firstEntry.language)
            assertEquals(firstFinish, firstEntry.finish)
            assertEquals("Standard", firstEntry.variant)
            assertEquals(42L, firstEntry.externalProductId)
            // Verify the actual repository fixture reproduces the two physical IDs.
            val duplicatedReferences = repository.binderReferences(set.name).filter {
                it.sourceCardId == sourceCardId && it.finish == firstFinish
            }
            assertEquals(2, duplicatedReferences.size)
            assertEquals(2, duplicatedReferences.map { it.id }.distinct().size)
            assertSlots(first, identities)

            assertEquals(AddCardOutcome.QUANTITY_INCREMENTED, repository.addManualCard(firstInput))
            val doubled = emissions.awaitCards { it.size == 1 && it.single().quantity == 2 }
            assertEquals(firstEntry.entryId, doubled.single().entryId)
            assertSlots(doubled, identities)

            assertEquals(AddCardOutcome.CREATED, repository.addManualCard(otherReference.previewInput()))
            val both = emissions.awaitCards { it.size == 2 }
            assertEquals(2, both.single { it.finish == firstFinish }.quantity)
            assertEquals(1, both.single { it.finish == otherFinish }.quantity)
            assertEquals(2, both.map { it.entryId }.distinct().size)
            assertSlots(both, identities)
            repeat(3) {
                repository.cacheGermanMasterSetCards(set, definitions)
                assertSlots(both, identities)
            }

            assertEquals(AddCardOutcome.CREATED, repository.addManualCard(normalReference.previewInput(43L)))
            val all = emissions.awaitCards { it.size == 3 }
            assertEquals(4, all.sumOf { it.quantity })
            assertSlots(all, identities)

            repository.deleteEntry(firstEntry.entryId)
            val remaining = emissions.awaitCards { cards ->
                cards.size == 2 && cards.none { it.entryId == firstEntry.entryId }
            }
            assertEquals(1, remaining.single { it.finish == otherFinish }.quantity)
            assertEquals(1, remaining.single { it.finish == "Normal" }.quantity)
            assertSlots(remaining, identities)
            repeat(3) {
                repository.cacheGermanMasterSetCards(set, definitions)
                assertSlots(remaining, identities)
            }

            remaining.forEach { repository.deleteEntry(it.entryId) }
            assertSlots(emissions.awaitCards { it.isEmpty() }, identities)
        } finally {
            observer.cancelAndJoin()
            emissions.close()
        }
    }

    private suspend fun assertSlots(cards: List<CollectionCard>, identities: List<List<String?>>) {
        val slots = arrangeBinderCards(set.name, cards, 2, repository.binderReferences(set.name)).filterNotNull()
        assertEquals("Slot identities and positions must survive writes and reference reloads", identities, slots.map(::identity))
        assertEquals("Each owned row is rendered exactly once", cards.size, slots.count { it.entryId > 0L })
        slots.forEach { slot ->
            val owned = cards.singleOrNull { identity(it) == identity(slot) }
            assertEquals(owned?.entryId ?: 0L, slot.entryId)
            assertEquals(owned?.quantity ?: 0, slot.quantity)
            if (owned != null) assertEquals(owned.id, slot.id)
        }
    }

    private fun identity(card: CollectionCard): List<String?> = listOf(
        card.sourceSetId, card.sourceCardId, card.number, card.language.name, card.finish, card.variant,
    )

    /** The Binder preview's mapping: source identity comes from the slot, price link from the product. */
    private fun CollectionCard.previewInput(productId: Long = 42L) = ManualCardInput(
        name = name, setName = set, collectorNumber = number, language = language,
        finish = finish, variant = variant, quantity = 1, condition = "Nicht geprüft",
        notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null,
        externalProductId = productId, imageUrl = imageUrl, cardmarketExpansionId = "6209",
        sourceSetId = sourceSetId, sourceCardId = sourceCardId,
    )

    private suspend fun Channel<PortfolioSummary>.awaitCards(
        predicate: (List<CollectionCard>) -> Boolean,
    ): List<CollectionCard> = withTimeout(10_000L) {
        var cards = receive().cards
        while (!predicate(cards)) cards = receive().cards
        cards
    }
}
