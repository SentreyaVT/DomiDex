package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipeLeft
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.catalog.PokemonCatalogMetadataCache
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.model.*
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Real reader/reload/animation; deterministic metadata only, no Room/Firebase/user writes. */
@RunWith(AndroidJUnit4::class)
class BinderRefreshInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun repeatedRefreshShrinksPagesDuringAnimationWithoutLosingOwnedVariants() {
        val references = (1..40).flatMap { number ->
            listOf(reference(number, "Normal"), reference(number, "Reverse Holo"))
        }
        val owned = references.take(2).mapIndexed { index, card -> card.copy(entryId = index + 1L, quantity = 1) }
        val reads = AtomicInteger()
        val completed = AtomicInteger()
        val gate = AtomicReference(CountDownLatch(0))
        val cached = AtomicReference<List<GermanMasterSetCardDefinition>>(emptyList())
        val repository = Proxy.newProxyInstance(CollectionRepository::class.java.classLoader,
            arrayOf(CollectionRepository::class.java)) { proxy, method, args ->
            when (method.name) {
                "equals" -> proxy === args?.get(0)
                "hashCode" -> System.identityHashCode(proxy)
                "toString" -> "ReadOnlyBinderRegressionRepository"
                "binderReferences" -> { reads.incrementAndGet(); references.take(2) }
                "searchCatalog" -> emptyList<CatalogSearchResult>()
                "cacheGermanMasterSetCards" -> {
                    @Suppress("UNCHECKED_CAST")
                    cached.set(args!![1] as List<GermanMasterSetCardDefinition>)
                    completed.incrementAndGet()
                    Unit
                }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as CollectionRepository
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            val json = when {
                url.endsWith("/de/sets") -> {
                    check(gate.get().await(10, TimeUnit.SECONDS)) { "Test source gate timed out" }
                    """[{"id":"binder-regression","name":"Binder Regression","cardCount":{"official":40,"total":40}}]"""
                }
                url.endsWith("/sets/binder-regression") -> """{"cards":[${
                    (1..40).joinToString(",") { """{"id":"binder-regression-$it","name":"Karte $it","localId":"$it"}""" }
                }]}"""
                url.contains("/cards/") -> """{"rarity":"Common","variants_detailed":[
                    {"variantId":"normal","type":"normal","size":"Standard"},
                    {"variantId":"reverse","type":"reverse","size":"Standard"}
                ]}"""
                else -> error("Unexpected source request: $url")
            }
            Buffer().writeUtf8(json)
        }
        compose.setContent {
            MaterialTheme {
                BinderReaderScreen("Binder Regression", owned, 40, null, repository,
                    onBack = {}, visualCatalog = catalog)
            }
        }
        compose.waitUntil(10_000) { completed.get() == 1 }
        compose.waitForIdle()
        repeat(4) { pass ->
            // Previous refresh clamps to page 2; return to the cover without changing data.
            if (pass > 0) repeat(2) { compose.onNodeWithText("‹ Zurück").performClick() }
            repeat(7) { compose.onNodeWithText("Weiter ›").performClick() }
            compose.onNodeWithText("Kartenseite 7 von 9").assertExists()
            val reloadGate = CountDownLatch(1)
            gate.set(reloadGate)
            try {
                // Keep an outgoing page alive as the reference list contracts from 80 to 2.
                compose.mainClock.autoAdvance = false
                compose.onNodeWithText("Weiter ›").performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.onNodeWithContentDescription("Binder aktualisieren").assertIsEnabled().performClick()
                compose.mainClock.advanceTimeByFrame()
                compose.waitUntil(5_000) { reads.get() == pass + 2 }
                compose.mainClock.advanceTimeBy(500)
                compose.onNodeWithText("Rückseite · 2 Sammelplätze").assertExists()
            } finally {
                reloadGate.countDown()
                compose.mainClock.autoAdvance = true
            }
            compose.waitUntil(10_000) { completed.get() == pass + 2 }
            compose.waitForIdle()
            compose.onNodeWithText("Kartenseite 2 von 9").assertExists()
            compose.onNodeWithContentDescription("Binder aktualisieren").assertIsEnabled()
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.onNodeWithText("Kartenseite 3 von 9").assertExists()
            compose.onNodeWithText("‹ Zurück").performClick()
            assertEquals(80, cached.get().size)
            assertTrue(cached.get().groupBy { it.number }.values.all { cards ->
                cards.map { it.finish } == listOf("Normal", "Reverse Holo")
            })
            val slots = arrangeBinderCards("Binder Regression", owned, 40, references)
            assertSame(owned[0], slots[0])
            assertSame(owned[1], slots[1])
            assertEquals(listOf(1, 1), owned.map { it.quantity })
        }
    }

    private fun reference(number: Int, finish: String) = CollectionCard(
        entryId = 0L, id = "binder-regression-$number-$finish", name = "Karte $number",
        set = "Binder Regression", number = number.toString(), language = CardLanguage.GERMAN,
        finish = finish, variant = "Standard", condition = "Nicht im Besitz", quantity = 0,
        currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null,
        notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null, accent = 0,
    )
}
