package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.catalog.*
import com.dominik.cardvault.data.local.*
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/** Real Compose add-dialog and Room flows; isolated data, no user account or Firebase writes. */
@RunWith(AndroidJUnit4::class)
class BinderPricePreviewInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val sync = MutableStateFlow(CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    private val priceText = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.GERMANY).format(1.11)
    private val set = PokemonSet("sv08.5", "Prismatische Entwicklungen", "", 131, 180, "", null, "PRE")
    private val source = PokemonVisualCard("sv08.5-137", "Freunde aus Paldea", "137", "Ultra Rare", "", "", set,
        marketSearchName = "Friends in Paldea")
    private val reference = CollectionCard(
        entryId = 0L, id = "${source.id}-holo", name = source.name, set = set.name,
        number = source.number, language = CardLanguage.GERMAN, finish = "Holo", variant = "Standard",
        condition = "Nicht im Besitz", quantity = 0, currentValueEur = 0.0, changePercent = 0.0,
        // The emulator's live tilt sensor never idles; this price/slot test does not test visual effects.
        priceUpdatedAtEpochMillis = null, notes = "", acquisitionPriceEur = null, effectOverride = "NONE",
        acquisitionDateEpochDay = null, accent = 0L, sourceSetId = set.id, sourceCardId = source.id,
    )
    private val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
        Buffer().writeUtf8(when {
          url.endsWith("/sets") -> """[{"id":"sv08.5","name":"Prismatische Entwicklungen","cardCount":{"official":131,"total":180}}]"""
          url.endsWith("/sets/sv08.5") -> """{"cards":[{"id":"sv08.5-137","name":"Freunde aus Paldea","localId":"137"}]}"""
          else -> """{"rarity":"Ultra Rare","variants_detailed":[
          {"variantId":"holo","type":"Holo","size":"Standard","thirdParty":{"cardmarket":805531}}
        ]}"""
        })
    }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
        repository = RoomCollectionRepository(database, MutableStateFlow("isolated-preview-owner"))
        runBlocking {
            repository.cacheGermanMasterSetCards(GermanMasterSetDefinition(set.id, set.name), listOf(
                GermanMasterSetCardDefinition("${source.id}-holo", source.name, "137", null, "Holo"),
                GermanMasterSetCardDefinition("sv08.5-138-holo", "Nächste Karte", "138", null, "Holo"),
            ))
        }
    }

    @After fun close() = database.close()

    @Test fun greyFullArtPreviewUpdatesAfterPriceSyncAndAddsTheOfficialSlot() {
        val added = AtomicReference<CollectionCard?>()
        val uiRepository = object : CollectionRepository by repository { override val catalogSyncStatus = sync }
        compose.setContent {
            MaterialTheme {
                val scope = rememberCoroutineScope()
                val portfolio by repository.portfolio.collectAsState(initial = PortfolioSummary(emptyList(), emptyList()))
                added.set(portfolio.cards.singleOrNull())
                CardPreviewDialog(reference, uiRepository, listOf(source), catalog,
                    onDismiss = {}, onEdit = null, onAdd = { product ->
                        scope.launch { repository.addManualCard(reference.input(product.productId)) }
                    })
            }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Preis nicht verfügbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("In diesen Binder hinzufügen").assertIsNotEnabled()
        seedPrice()
        sync.value = CatalogSyncStatus("SUCCESS", 2_000L, null, 1, 1, null)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(priceText).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Ziel-Binder").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Offizieller Sammelplatz 137 · Holo").assertExists()
        compose.onNodeWithText("In diesen Binder hinzufügen").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { added.get() != null }
        assertOwnedOfficialSlot(added.get()!!)
    }

    @Test fun setSelectionPreservesPrintingAndFills137Before138RatherThanAppending() {
        seedPrice()
        val saved = AtomicReference<AddCardOutcome?>()
        compose.setContent { MaterialTheme {
            var selection by remember { mutableStateOf<CatalogSearchResult?>(null) }
            if (selection == null) CatalogProductPicker(repository, onDismiss = {}, visualCatalog = catalog) { selection = it }
            else ManualCardDialog(repository, selection, onDismiss = {}, onSaved = { saved.set(it) })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(set.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(set.name).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText(source.name).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(source.name).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Zum Binder hinzufügen · $priceText").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Zum Binder hinzufügen · $priceText").performClick()
        compose.onNodeWithText("Ziel-Binder").assertExists()
        compose.onNodeWithText("Offizieller Sammelplatz 137 · Holo").assertExists()
        compose.onNodeWithText("In diesen Binder hinzufügen").performClick()
        compose.waitUntil(10_000) { saved.get() != null }
        val card = runBlocking { withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 }.cards.single() } }
        assertOwnedOfficialSlot(card)
    }

    @Test fun noReversePriceIsBorrowedFromAnAvailableHoloPrinting() {
        seedPrice()
        compose.setContent { MaterialTheme {
            CardPreviewDialog(reference.copy(finish = "Reverse Holo"), repository, listOf(source), catalog,
                onDismiss = {}, onEdit = null, onAdd = { error("Wrong printing must not be addable with Holo price") })
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Preis nicht verfügbar").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("In diesen Binder hinzufügen").assertIsNotEnabled()
        compose.onNodeWithText(priceText).assertDoesNotExist()
    }

    @Test fun reopeningOwnedFullArtKeepsItsExplicitPriceNotAMainSetNamesake() {
        seedPrice()
        runBlocking {
            val dao = database.cardVaultDao()
            dao.insertCatalogCard(CatalogCardEntity("cm-pokemon-805503", "POKEMON", "5944", "Prismatic Evolutions",
                "Friends in Paldea", "", "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 1_000L,
                externalSource = CardmarketSyncContract.SOURCE, externalProductId = 805503L))
            dao.insertRawPrices(listOf(RawPriceSnapshotEntity(cardId = "cm-pokemon-805503", source = CardmarketSyncContract.SOURCE,
                currency = "EUR", lowValueMinor = null, averageValueMinor = null, trendValueMinor = 5L,
                marketValueMinor = 5L, holoTrendValueMinor = 8L, fetchedAtEpochMillis = 2_000L, effectiveAtEpochMillis = 2_000L)))
        }
        val owned = reference.copy(entryId = 1L, quantity = 1, externalProductId = 805531L,
            marketSearchName = "Friends in Paldea", marketExpansionId = "5944")
        compose.setContent { MaterialTheme {
            CardPreviewDialog(owned, repository, onDismiss = {}, onEdit = null)
        } }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(priceText).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(java.text.NumberFormat.getCurrencyInstance(java.util.Locale.GERMANY).format(.05)).assertDoesNotExist()
    }

    private fun assertOwnedOfficialSlot(card: CollectionCard) = runBlocking {
        assertEquals(source.id, card.sourceCardId)
        assertEquals(set.id, card.sourceSetId)
        assertEquals("137", card.number)
        assertEquals("Holo", card.finish)
        assertEquals("Standard", card.variant)
        assertEquals(805531L, card.externalProductId)
        assertEquals(1.11, card.currentValueEur, .001)
        val portfolio = withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 } }
        assertEquals(1.11, portfolio.totalValue, .001)
        repeat(3) {
            val slots = arrangeBinderCards(set.name, portfolio.cards, set.total, repository.binderReferences(set.name)).filterNotNull()
            assertEquals(listOf("137", "138"), slots.map { it.number })
            assertTrue(slots[0].entryId > 0L)
            assertEquals(1, slots[0].quantity)
            assertEquals(0L, slots[1].entryId)
        }
    }

    private fun seedPrice() = runBlocking {
        val dao = database.cardVaultDao()
        dao.insertCatalogCard(CatalogCardEntity("cm-pokemon-805531", "POKEMON", "5944", "Prismatic Evolutions",
            "Friends in Paldea (Full Art)", "", "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 1_000L,
            externalSource = CardmarketSyncContract.SOURCE, externalProductId = 805531L))
        // Public guide snapshot 2026-09-04, used only in this isolated fixture.
        dao.insertRawPrices(listOf(RawPriceSnapshotEntity(cardId = "cm-pokemon-805531", source = CardmarketSyncContract.SOURCE,
            currency = "EUR", lowValueMinor = null, averageValueMinor = null, trendValueMinor = 111L,
            marketValueMinor = 111L, holoTrendValueMinor = 0L, fetchedAtEpochMillis = 2_000L, effectiveAtEpochMillis = 2_000L)))
    }

    private fun CollectionCard.input(product: Long) = ManualCardInput(name, set, number, language, finish, variant,
        1, "Nicht geprüft", "", null, null, product, null, cardmarketExpansionId = "5944",
        sourceSetId = sourceSetId, sourceCardId = sourceCardId)
}
