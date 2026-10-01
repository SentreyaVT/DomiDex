package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.model.*
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

/** Offline UI only: never accesses Firebase, a real account, or live prices. */
@RunWith(AndroidJUnit4::class)
class CardPriceAlternativePickerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val card = CollectionCard(
        entryId = 1, id = "me03-090", name = "Testkarte", set = "Testset", number = "090/088",
        language = CardLanguage.GERMAN, finish = "Holo", variant = "Standard", condition = "Near Mint",
        quantity = 1, currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null,
        notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null, accent = 0,
        sourceSetId = "me03", sourceCardId = "me03-090", externalProductId = 42,
    )
    private val product = CatalogSearchResult(42, "Testkarte", "test", null, "EUR", priceAlternatives = listOf(
        CardPriceAlternative(CardPriceMetric.NORMAL_AVG_SELL, 42, 532, "EUR", 1_000),
        CardPriceAlternative(CardPriceMetric.NORMAL_LOW, 42, 200, "EUR", 1_000),
        CardPriceAlternative(CardPriceMetric.REVERSE_HOLO_LOW, 42, 900, "EUR", 1_000),
    ))

    @Test fun radioStartsEmptyAndOnlyExplicitConfirmationSavesExactIdentity() {
        var confirmed: CardPriceSelection? = null
        compose.setContent { MaterialTheme {
            CardPriceAlternativePicker(card, product, null, true, { confirmed = it })
        } }
        compose.onAllNodes(isSelectable()).assertCountEquals(2)
        compose.onAllNodes(isSelectable())[0].assertIsNotSelected()
        compose.onNodeWithText("Als Vergleichswert bestätigen").assertIsNotEnabled()
        compose.onAllNodes(isSelectable())[0].performClick()
        compose.runOnIdle { assertNull(confirmed) }
        compose.onNodeWithText("Als Vergleichswert bestätigen").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(confirmed!!.matches(card))
            assertEquals(CardPriceMetric.NORMAL_AVG_SELL, confirmed!!.metric)
            assertEquals(532L, confirmed!!.amountMinor)
        }
    }

    @Test fun realTrendSuppressesAlternativePickerEntirely() {
        compose.setContent { MaterialTheme {
            CardPriceAlternativePicker(card.copy(currentValueEur = 5.0, priceUpdatedAtEpochMillis = 1_000),
                product, null, true, { error("Unexpected selection") })
        } }
        compose.onNodeWithText("Als Vergleichswert bestätigen").assertDoesNotExist()
    }

    @Test fun reverseShowsOnlyReverseMetricAndDoesNotPreselectSavedValue() {
        val reverse = card.copy(finish = "Reverse Holo", variant = "Reverse Holo")
        val saved = product.priceAlternatives.last().toSelection(reverse, 2_000)
        var cleared = false
        compose.setContent { MaterialTheme {
            CardPriceAlternativePicker(reverse, product, saved, true,
                { error("Must not silently confirm") }, { cleared = true })
        } }
        compose.onAllNodes(isSelectable()).assertCountEquals(1)
        compose.onAllNodes(isSelectable())[0].assertIsNotSelected()
        compose.onNodeWithText("Als Vergleichswert bestätigen").assertIsNotEnabled()
        compose.onNodeWithText("Vergleichswert entfernen").performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }

    @Test fun savedSelectionRemainsClearableWithoutCurrentProduct() {
        assertSavedSelectionCanBeCleared(product = null)
    }

    @Test fun savedSelectionRemainsClearableAfterAlternativesDisappear() {
        assertSavedSelectionCanBeCleared(product = product.copy(priceAlternatives = emptyList()))
    }

    @Test fun laterRealTrendSuppressesCandidatesButDoesNotHideSavedSelectionRemoval() {
        assertSavedSelectionCanBeCleared(card = card.copy(currentValueEur = 8.0, priceUpdatedAtEpochMillis = 3_000))
        compose.onNodeWithText("Der verfügbare Trendpreis wird für die Bewertung verwendet.").assertExists()
    }

    @Test fun changedIdentityKeepsRemovalButLabelsSavedSelectionAsInactive() {
        assertSavedSelectionCanBeCleared(card = card.copy(number = "091/088"), product = null)
        compose.onNodeWithText("Diese gespeicherte Auswahl passt nicht mehr zur Kartenidentität und wird nicht für die Bewertung verwendet.").assertExists()
    }

    @Test fun savedSelectionClearRespectsInProgressState() {
        compose.setContent { MaterialTheme {
            CardPriceAlternativePicker(card, null, product.priceAlternatives.first().toSelection(card, 2_000),
                false, { error("Unexpected selection") }, { error("Disabled clear must not run") })
        } }
        compose.onNodeWithText("Vergleichswert entfernen").assertIsNotEnabled()
    }

    @Test fun ownedPreviewClearsSavedSnapshotWithoutProductAndWithoutWaitingForAnotherEmission() {
        val saved = product.priceAlternatives.first().toSelection(card, 2_000)
        val owned = card.copy(priceSelection = saved, effectOverride = "NONE")
        val clears = AtomicInteger()
        val repository = Proxy.newProxyInstance(CollectionRepository::class.java.classLoader,
            arrayOf(CollectionRepository::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "OfflinePriceSelectionRemovalRepository"
                "getCatalogSyncStatus" -> flowOf(CatalogSyncStatus("IDLE", null, null, 0, 0, null))
                "priceHistory" -> flowOf(emptyList<CardPricePoint>())
                "catalogProducts" -> emptyList<CatalogSearchResult>()
                "clearPriceSelection" -> {
                    assertEquals(owned.entryId, args!![0])
                    clears.incrementAndGet()
                    Unit
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as CollectionRepository
        val offlineCatalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { error("No source request expected") }
        compose.setContent { MaterialTheme {
            // Keep the old snapshot as input: successful removal must update this open dialog immediately.
            CardPreviewDialog(owned, repository, visualCatalog = offlineCatalog, onDismiss = {}, onEdit = null)
        } }
        compose.onNodeWithText("Vergleichswert entfernen").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(5_000) { clears.get() == 1 }
        compose.onNodeWithText("Vergleichswert entfernen").assertDoesNotExist()
        compose.onNodeWithText("Preis nicht verfügbar").performScrollTo().assertIsDisplayed()
    }

    private fun assertSavedSelectionCanBeCleared(
        card: CollectionCard = this.card,
        product: CatalogSearchResult? = this.product,
    ) {
        val saved = this.product.priceAlternatives.first().toSelection(this.card, 2_000)
        var cleared = false
        compose.setContent { MaterialTheme {
            CardPriceAlternativePicker(card, product, saved, true,
                { error("No replacement should be offered") }, { cleared = true })
        } }
        compose.onAllNodes(isSelectable()).assertCountEquals(0)
        compose.onNodeWithText("Als Vergleichswert bestätigen").assertDoesNotExist()
        compose.onNodeWithText("Vergleichswert entfernen").assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(cleared) }
    }
}
