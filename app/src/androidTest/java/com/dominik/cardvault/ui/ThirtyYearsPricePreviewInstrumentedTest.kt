package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

/** Actual preview/add/owned flows against isolated Room and a fully offline source loader. */
@RunWith(AndroidJUnit4::class)
class ThirtyYearsPricePreviewInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val sync = MutableStateFlow(CatalogSyncStatus("IDLE", null, null, 0, 0, null))
    private val anniversary = PokemonSet("30th", "30 Jahre Pokémon", "", 128, 161, "", null, "30TH")
    private val promos = PokemonSet("mep", "Mega-Entwicklung Promos", "", 110, 110, "", null, "MEP")
    private val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
        check(url.substringAfterLast('/') in setOf("30th-001", "30th-065", "30th-129", "30th-151", "30th-158")) {
            "Unexpected offline catalog request: $url"
        }
        // The current English source sends lowercase size/type and no Cardmarket identity.
        Buffer().writeUtf8("""{"variants_detailed":[{"variantId":"generated","type":"normal","size":"standard"}]}""")
    }

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
        repository = RoomCollectionRepository(database, MutableStateFlow("isolated-thirty-years-price-owner"))
    }

    @After fun close() = database.close()

    @Test fun newlyMappedExeggcutePricesGreyPreviewThenAddedAndReopenedCard() {
        val source = PokemonVisualCard("30th-001", "Owei", "001", null, "", "", anniversary,
            marketSearchName = "Exeggcute")
        val detailed = runBlocking { catalog.withDeclaredPrintVariants(source) }
        val printing = detailed.binderPrintVariants().single()
        assertEquals("Normal", printing.finish)
        assertEquals("standard", printing.variant)
        assertEquals(907607L, printing.cardmarketProductId)
        assertPricedPreviewAddAndReopen(source, printing.finish, printing.variant, 907607L, "6601", 567L)
    }

    @Test fun lowercaseGeneratedMewVariantPricesGreyPreviewThenAddedAndReopenedCard() {
        val source = PokemonVisualCard("30th-065", "Mew", "065", null, "", "", anniversary,
            marketSearchName = "Mew")
        val detailed = runBlocking { catalog.withDeclaredPrintVariants(source) }
        assertEquals("generated", detailed.printVariants.single().id)
        assertEquals("Normal", detailed.printVariants.single().finish)
        assertEquals("standard", detailed.printVariants.single().variant)
        assertEquals(907672L, detailed.printVariants.single().cardmarketProductId)
        // An older cached reference may capitalize Standard; matching is case-insensitive.
        val cachedProducts = binderReferenceCardmarketProducts(
            reference(source, "Normal", "Standard"), detailed, listOf(detailed),
            listOf(CatalogSearchResult(907672L, "Mew [Psychic | 30]", "6601", 2.34, "EUR")),
        )
        assertEquals(907672L, cachedProducts.single().productId)
        val printing = detailed.binderPrintVariants().single()
        assertPricedPreviewAddAndReopen(source, printing.finish, printing.variant, 907672L, "6601", 234L)
    }

    @Test fun supplementalPromo094RetainsItsSourcePrintingPriceAfterAdding() {
        val source = promo("mep-094")
        val detailed = runBlocking { catalog.withDeclaredPrintVariants(source) }
        assertEquals("Holo", detailed.printVariants.single().finish)
        assertEquals(895609L, detailed.printVariants.single().cardmarketProductId)
        val printing = detailed.binderPrintVariants().single()
        assertPricedPreviewAddAndReopen(source, printing.finish, printing.variant, 895609L, "6232", 456L)
    }

    @Test fun firstIllustrationAbovePrintedTotalPricesAndAddsToItsOwnSlot() {
        val source = PokemonVisualCard("30th-129", "Alola-Kokowei", "129", "Illustration Rare", "", "", anniversary)
        assertPricedPreviewAddAndReopen(source, "Normal", "standard", 907737L, "6601", 432L)
    }

    @Test fun mewtwoSar151DoesNotUseItsBaseOrFuturisticProduct() {
        seedPrice(907671L, "Mewtwo ex", "6601", 111L)
        seedPrice(907761L, "Mewtwo ex", "6601", 222L)
        val source = PokemonVisualCard("30th-151", "Mewtu-ex", "151", "Special Illustration Rare", "", "", anniversary)
        assertPricedPreviewAddAndReopen(source, "Normal", "standard", 908353L, "6601", 12345L)
    }

    @Test fun mewFuturistic158DoesNotUseItsBaseSarOrRgbProduct() {
        seedPrice(907673L, "Mew ex", "6601", 111L)
        seedPrice(908354L, "Mew ex", "6601", 222L)
        seedPrice(909509L, "Mew", "6601", 333L)
        val source = PokemonVisualCard("30th-158", "Mew-ex", "158", "Futuristic Rare", "", "", anniversary)
        assertPricedPreviewAddAndReopen(source, "Normal", "standard", 907762L, "6601", 8765L)
    }

    @Test fun reopeningPreviouslyUnlinkedSarRepairsOnlyItsProductAndPreservesOwnedData() {
        val source = PokemonVisualCard("30th-151", "Mewtu-ex", "151", "Special Illustration Rare", "", "", anniversary)
        val reference = reference(source, "Normal", "standard")
        val owned = runBlocking {
            repository.addManualCard(reference.input(908353L, "6601").copy(
                externalProductId = null, quantity = 2, notes = "Unverändert behalten",
            ))
            withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 }.cards.single() }
        }
        assertNull(owned.externalProductId)
        seedPrice(907671L, "Mewtwo ex", "6601", 111L)
        seedPrice(907761L, "Mewtwo ex", "6601", 222L)
        seedPrice(908353L, "Mewtwo ex", "6601", 12345L)
        compose.setContent { MaterialTheme {
            CardPreviewDialog(owned.copy(effectOverride = "NONE", imageUrl = null), repository,
                listOf(source), catalog, onDismiss = {}, onEdit = null)
        } }
        waitForText(euro(12345L))
        val repaired = runBlocking {
            withTimeout(10_000) { repository.portfolio.first { it.cards.singleOrNull()?.externalProductId == 908353L } }
        }
        val card = repaired.cards.single()
        assertEquals(owned.entryId, card.entryId)
        assertEquals(owned.binderId, card.binderId)
        assertEquals(owned.sourceCardId, card.sourceCardId)
        assertEquals(owned.number, card.number)
        assertEquals(owned.finish, card.finish)
        assertEquals(owned.variant, card.variant)
        assertEquals(2, card.quantity)
        assertEquals("Unverändert behalten", card.notes)
        assertEquals(246.90, repaired.totalValue, .001)
    }

    @Test fun promo105WithNullTrendNeverBorrowsAnotherMewPriceInPreviewOrOwnedCard() {
        val source = promo("mep-105")
        val printing = source.binderPrintVariants().single()
        val reference = reference(source, printing.finish, printing.variant)
        seedPrice(895621L, "Mew [Psychic| MEP]", "6232", null)
        seedPrice(907672L, "Mew [Psychic | 30]", "6601", 999L)
        val shown = mutableStateOf(reference)
        val ownedMode = mutableStateOf(false)
        val unpricedSelection = AtomicReference<CatalogSearchResult?>()
        compose.setContent { MaterialTheme {
            key(ownedMode.value) {
                CardPreviewDialog(shown.value, repository, listOf(source), catalog,
                    onDismiss = {}, onEdit = null,
                    onAdd = if (ownedMode.value) null else { product -> unpricedSelection.set(product) })
            }
        } }
        waitForText("Preis nicht verfügbar")
        // Release 27 no longer blocks ownership just because the exact product
        // has no trend. It must remain honestly unpriced and never borrow Mew's value.
        compose.onNodeWithText("In diesen Binder hinzufügen").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(895621L, unpricedSelection.get()!!.productId)
            assertNull(unpricedSelection.get()!!.trendPriceEur)
            assertNull(unpricedSelection.get()!!.confirmedPriceSelection)
        }
        compose.onNodeWithText(euro(999L)).assertDoesNotExist()

        // Existing owned copies can carry the exact product even when its guide has no trend.
        val owned = runBlocking {
            repository.addManualCard(reference.input(895621L, "6232"))
            withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 }.cards.single() }
        }
        assertEquals(895621L, owned.externalProductId)
        assertEquals("mep-105", owned.sourceCardId)
        assertEquals(0.0, owned.currentValueEur, .001)
        assertNull(owned.priceUpdatedAtEpochMillis)
        val portfolio = runBlocking { withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 } } }
        assertEquals(0.0, portfolio.totalValue, .001)
        compose.runOnIdle {
            shown.value = owned.copy(effectOverride = "NONE", imageUrl = null)
            ownedMode.value = true
        }
        waitForText("Preis nicht verfügbar")
        compose.onNodeWithText(euro(999L)).assertDoesNotExist()
        compose.onNodeWithText("In diesen Binder hinzufügen").assertDoesNotExist()
    }

    private fun assertPricedPreviewAddAndReopen(
        source: PokemonVisualCard,
        finish: String,
        variant: String,
        productId: Long,
        expansionId: String,
        valueMinor: Long,
    ) {
        val reference = reference(source, finish, variant)
        runBlocking {
            repository.cacheGermanMasterSetCards(GermanMasterSetDefinition(source.set.id, source.set.name), listOf(
                GermanMasterSetCardDefinition(reference.id, source.name, source.number, null, finish, variant),
            ))
        }
        val shown = mutableStateOf(reference)
        val ownedMode = mutableStateOf(false)
        val added = AtomicReference<CollectionCard?>()
        val selectedProduct = AtomicReference<CatalogSearchResult?>()
        val uiRepository = object : CollectionRepository by repository { override val catalogSyncStatus = sync }
        compose.setContent { MaterialTheme {
            val scope = rememberCoroutineScope()
            key(ownedMode.value) {
                val add: ((CatalogSearchResult) -> Unit)? = if (ownedMode.value) null else { product ->
                    selectedProduct.set(product)
                    scope.launch {
                        repository.addManualCard(reference.input(product.productId, product.expansionId))
                        added.set(withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 }.cards.single() })
                    }
                }
                CardPreviewDialog(shown.value, uiRepository, listOf(source), catalog,
                    onDismiss = {}, onEdit = null, onAdd = add)
            }
        } }
        waitForText("Preis nicht verfügbar")
        compose.onNodeWithText("In diesen Binder hinzufügen").assertIsNotEnabled()
        seedPrice(productId, source.marketSearchName ?: source.name, expansionId, valueMinor)
        sync.value = CatalogSyncStatus("SUCCESS", 2_000L, null, 1, 1, null)
        waitForText(euro(valueMinor))
        compose.onNodeWithText("In diesen Binder hinzufügen").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { added.get() != null }
        val owned = added.get()!!
        assertEquals(productId, selectedProduct.get()!!.productId)
        assertEquals(productId, owned.externalProductId)
        assertEquals(source.set.id, owned.sourceSetId)
        assertEquals(source.id, owned.sourceCardId)
        assertEquals(source.number, owned.number)
        assertEquals(finish, owned.finish)
        assertEquals(variant, owned.variant)
        assertEquals(1, owned.quantity)
        assertEquals(valueMinor / 100.0, owned.currentValueEur, .001)
        assertNotNull(owned.priceUpdatedAtEpochMillis)
        val portfolio = runBlocking { withTimeout(10_000) { repository.portfolio.first { it.cards.size == 1 } } }
        assertEquals(valueMinor / 100.0, portfolio.totalValue, .001)
        compose.runOnIdle {
            shown.value = owned.copy(effectOverride = "NONE", imageUrl = null)
            ownedMode.value = true
        }
        waitForText(euro(valueMinor))
        compose.onNodeWithText("In diesen Binder hinzufügen").assertDoesNotExist()
        compose.onNodeWithText("Preis nicht verfügbar").assertDoesNotExist()
    }

    private fun promo(id: String): PokemonVisualCard = supplementalThirtyYearsCards(promos).single { it.id == id }

    private fun reference(source: PokemonVisualCard, finish: String, variant: String) = CollectionCard(
        entryId = 0L, id = "${source.id}-${finish.lowercase()}", name = source.name, set = source.set.name,
        number = source.number, language = CardLanguage.GERMAN, finish = finish, variant = variant,
        condition = "Nicht im Besitz", quantity = 0, currentValueEur = 0.0, changePercent = 0.0,
        priceUpdatedAtEpochMillis = null, notes = "", acquisitionPriceEur = null,
        acquisitionDateEpochDay = null, accent = 0L, effectOverride = "NONE",
        sourceSetId = source.set.id, sourceCardId = source.id, marketSearchName = source.marketSearchName,
    )

    private fun CollectionCard.input(productId: Long, expansionId: String) = ManualCardInput(
        name, set, number, language, finish, variant, 1, "Nicht geprüft", "", null, null,
        externalProductId = productId, effectOverride = "NONE", cardmarketExpansionId = expansionId,
        sourceSetId = sourceSetId, sourceCardId = sourceCardId,
    )

    private fun seedPrice(productId: Long, name: String, expansionId: String, trendMinor: Long?) = runBlocking {
        val dao = database.cardVaultDao()
        dao.insertCatalogCard(CatalogCardEntity("cm-pokemon-$productId", "POKEMON", expansionId,
            "Cardmarket-Erweiterung $expansionId", name, "", "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", 1_000L,
            externalSource = CardmarketSyncContract.SOURCE, externalProductId = productId))
        // Deliberately isolated fixture values; no live download or user's stored price is used.
        dao.insertRawPrices(listOf(RawPriceSnapshotEntity(cardId = "cm-pokemon-$productId",
            source = CardmarketSyncContract.SOURCE, currency = "EUR", lowValueMinor = null,
            averageValueMinor = null, trendValueMinor = trendMinor, marketValueMinor = trendMinor,
            holoTrendValueMinor = 0L, fetchedAtEpochMillis = 2_000L, effectiveAtEpochMillis = 2_000L)))
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }

    private fun euro(valueMinor: Long): String = NumberFormat.getCurrencyInstance(Locale.GERMANY).format(valueMinor / 100.0)
}
