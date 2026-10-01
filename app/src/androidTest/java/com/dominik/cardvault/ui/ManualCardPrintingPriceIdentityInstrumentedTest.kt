package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Real add-dialog + isolated Room. No account, source-catalogue or network access. */
@RunWith(AndroidJUnit4::class)
class ManualCardPrintingPriceIdentityInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val submitted = AtomicReference<ManualCardInput?>()
    private val saved = AtomicReference<AddCardOutcome?>()
    private val selectedNormal = CatalogSearchResult(
        productId = 42L, name = "Isolierte Testkarte", expansionId = "isolated-expansion",
        trendPriceEur = null, currency = "EUR", visualSetName = "Isoliertes Testset",
        collectorNumber = "090/088", finish = "Normal", variant = "Standard",
        sourceSetId = "isolated-set", sourceCardId = "isolated-set-090", hasMarketProduct = true,
    )

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java,
        ).build()
        repository = RoomCollectionRepository(database)
        runBlocking {
            database.cardVaultDao().insertCatalogCard(CatalogCardEntity(
                id = "cm-pokemon-42", tcg = "POKEMON", setId = selectedNormal.expansionId,
                setName = "Isolated product set", name = "Isolated exact product", collectorNumber = "",
                language = "UNKNOWN", finish = "UNKNOWN", variant = "Cardmarket-Produkt",
                createdAtEpochMillis = 1_000L, externalSource = CardmarketSyncContract.SOURCE,
                externalProductId = 42L,
            ))
            database.cardVaultDao().insertRawPrices(listOf(RawPriceSnapshotEntity(
                cardId = "cm-pokemon-42", source = CardmarketSyncContract.SOURCE, currency = "EUR",
                lowValueMinor = 200L, averageValueMinor = 532L, trendValueMinor = null,
                marketValueMinor = null, holoTrendValueMinor = null, holoLowValueMinor = 900L,
                fetchedAtEpochMillis = 1_000L, effectiveAtEpochMillis = 1_000L,
            )))
        }
    }

    @After fun close() = database.close()

    @Test fun changingNormalToReverseClearsProductAndConfirmedAlternativeButKeepsSource() {
        openAndConfirmNormalAlternative()
        scrollDialogTo(hasText("Druckart"))
        // The first row controls the physical finish; the second is display-only Holo effects.
        compose.onAllNodesWithText("Reverse Holo")[0].performScrollTo().performClick()
        compose.waitForIdle()
        val card = saveAndRead()
        assertDetachedPrinting(card, "Reverse Holo", "Standard")
    }

    @Test fun changingStandardToStampClearsProductAndConfirmedAlternativeButKeepsSource() {
        openAndConfirmNormalAlternative()
        val variantField = hasSetTextAction() and hasText("Variante")
        scrollDialogTo(variantField)
        compose.onNode(variantField).performTextReplacement("Prerelease-Stempel")
        compose.waitForIdle()
        val card = saveAndRead()
        assertDetachedPrinting(card, "Normal", "Prerelease-Stempel")
    }

    @Test fun unchangedNormalPrintingRetainsExactProductAndExplicitAlternative() {
        openAndConfirmNormalAlternative()
        val card = saveAndRead()
        assertSource(card, "Normal", "Standard")
        val input = requireNotNull(submitted.get())
        assertEquals(42L, input.externalProductId)
        assertEquals(42L, card.externalProductId)
        assertEquals(CardPriceMetric.NORMAL_AVG_SELL, card.priceSelection?.metric)
        assertEquals(532L, card.priceSelection?.amountMinor)
        assertEquals(input.priceSelection, card.priceSelection)
        assertTrue(requireNotNull(card.priceSelection).matches(card))
        // A confirmed comparison value must never masquerade as the real Cardmarket trend.
        assertNull(card.trendValueEur)
        assertEquals(0.0, card.currentValueEur, .001)
        assertEquals(5.32, requireNotNull(card.effectiveValueEur), .001)
    }

    private fun openAndConfirmNormalAlternative() {
        val uiRepository = object : CollectionRepository by repository {
            override suspend fun searchCatalog(query: String, limit: Int): List<CatalogSearchResult> =
                error("An exact source printing must not fall back to a name lookup")

            override suspend fun addManualCard(input: ManualCardInput, target: BinderTarget): TargetedAddCardResult {
                submitted.set(input)
                return repository.addManualCard(input, target)
            }
        }
        compose.setContent { MaterialTheme {
            ManualCardDialog(uiRepository, selectedNormal, onDismiss = {}, onSaved = saved::set)
        } }
        scrollDialogTo(hasText("Cardmarket-Trendpreis"))
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("Passendes Preisprodukt für diese Karte").fetchSemanticsNodes().isNotEmpty()
        }
        scrollDialogTo(hasText("Kein Trendpreis – Vergleichswert wählen"))
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onAllNodes(radio)[0].performScrollTo().assertIsNotSelected().performClick()
        compose.onNodeWithText("Als Vergleichswert bestätigen").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("Gespeicherter Vergleichswert: Cardmarket-Durchschnitt", substring = true).assertExists()
        assertNull("Confirming the alternative must not save a collection entry yet", submitted.get())
    }

    private fun scrollDialogTo(matcher: SemanticsMatcher) {
        compose.onNode(
            hasScrollToNodeAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
        ).performScrollToNode(matcher)
    }

    private fun saveAndRead(): CollectionCard {
        compose.onNodeWithText("In diesen Binder hinzufügen").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { saved.get() != null }
        assertEquals(AddCardOutcome.CREATED, saved.get())
        return runBlocking {
            withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 }.cards.single() }
        }
    }

    private fun assertDetachedPrinting(card: CollectionCard, finish: String, variant: String) {
        assertSource(card, finish, variant)
        val input = requireNotNull(submitted.get())
        assertNull("Dialog must not submit the previous printing's product", input.externalProductId)
        assertNull("Dialog must not submit the previous printing's expansion", input.cardmarketExpansionId)
        assertNull("Dialog must clear the confirmed comparison price", input.priceSelection)
        assertNull(card.externalProductId)
        assertNull(card.priceSelection)
        assertNull(card.trendValueEur)
        assertEquals(0.0, card.currentValueEur, .001)
    }

    private fun assertSource(card: CollectionCard, finish: String, variant: String) {
        val input = requireNotNull(submitted.get())
        assertEquals(selectedNormal.sourceSetId, input.sourceSetId)
        assertEquals(selectedNormal.sourceCardId, input.sourceCardId)
        assertEquals(selectedNormal.collectorNumber, input.collectorNumber)
        assertEquals(finish, input.finish)
        assertEquals(variant, input.variant)
        assertEquals(selectedNormal.sourceSetId, card.sourceSetId)
        assertEquals(selectedNormal.sourceCardId, card.sourceCardId)
        assertEquals(selectedNormal.collectorNumber, card.number)
        assertEquals(finish, card.finish)
        assertEquals(variant, card.variant)
    }
}
