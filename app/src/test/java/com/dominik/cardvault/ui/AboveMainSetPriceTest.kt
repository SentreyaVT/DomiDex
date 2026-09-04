package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import com.squareup.moshi.JsonReader
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Real, dated public-source snapshot; product IDs are independently confirmed by TCGdex details. */
class AboveMainSetPriceTest {
    @Suppress("UNCHECKED_CAST")
    private val fixture = JsonReader.of(Buffer().writeUtf8(
        javaClass.getResource("/prices/above-main-set-2026-09-04.json")!!.readText(),
    )).readJsonValue() as Map<String, Any?>

    @Suppress("UNCHECKED_CAST")
    private fun rows(key: String) = fixture[key] as List<Map<String, Any?>>
    private fun Map<String, Any?>.text(key: String) = getValue(key) as String
    private fun Map<String, Any?>.id(key: String) = (getValue(key) as Number).toLong()
    private fun Map<String, Any?>.price(key: String) = (get(key) as? Number)?.toDouble()

    private val set = PokemonSet("me03", "Optimale Ordnung", "Mega", 88, 124, "", null, "POR", "6443")
    private val cards = rows("cards").map { row ->
        PokemonVisualCard(row.text("id"), row.text("name"), row.text("number"), null, "", "", set,
            marketSearchName = row.text("marketName"))
    }
    private val products = rows("products").map { row ->
        val price = rows("prices").single { it.id("productId") == row.id("id") }
        CatalogSearchResult(row.id("id"), row.text("name"), row.text("expansionId"), price.price("trend"), "EUR",
            finish = "UNKNOWN", holoTrendPriceEur = price.price("reverseTrend")?.takeIf { it > 0 })
    }

    @Test fun mainAndSecretPrintingsResolveTheirOwnVerifiedProductNotTheMainSetReprint() {
        rows("cases").forEach { case ->
            val card = cards.single { it.id == case.text("id") }
            // Both localId and the full printed numerator/denominator must work above 088.
            listOf(card, card.copy(number = "${card.number}/088")).forEach { selected ->
                val product = visualCardmarketProducts(selected, cards, products).single()
                assertEquals(card.id, case.id("productId"), product.productId)
                assertEquals(card.id, product.sourceCardId)
                assertEquals("me03", product.sourceSetId)
                assertEquals(selected.number, product.collectorNumber)
                assertTrue(product.trendPriceEur!! > 0)
            }
        }
    }

    @Test fun aboveMainSetHoloPriceSurvivesManualAddAndGreyBinderPreview() {
        rows("cases").filter { it.text("number").toInt() > 88 }.forEach { case ->
            val card = cards.single { it.id == case.text("id") }.copy(
                rarity = case.text("rarity"), printVariants = listOf(PokemonPrintVariant("holo", "Holo", "Holo")),
            )
            val market = visualCardmarketProducts(card, cards, products).single()
            val selected = catalogResultForVisualCard(card, market)
            assertEquals(case.id("productId"), selected.productId)
            assertEquals("Holo", selected.finish)
            assertEquals(market.trendPriceEur, selected.trendPriceEur)

            val preview = binderReferenceCardmarketProducts(reference(card, "Holo"), card, cards, products).single()
            assertEquals("Missing price for ${card.id}", market.trendPriceEur, preview.trendPriceEur)
            assertEquals(case.id("productId"), preview.productId)
            assertEquals(card.id, preview.sourceCardId)
            assertEquals("Holo", preview.finish)
            assertEquals("Holo", preview.variant)
        }
    }

    @Test fun normalHoloAndReverseUseTheirRespectiveSourceFieldsWithoutFallback() {
        val card = cards.single { it.number == "010" }
        val product = products.single { it.productId == 877422L }
        assertEquals(0.05, product.realTrendFor("Normal")!!, 0.0)
        assertEquals(0.05, product.realTrendFor("Holo")!!, 0.0)
        assertEquals(0.09, product.realTrendFor("Reverse Holo")!!, 0.0)
        assertEquals(0.09, binderReferenceCardmarketProducts(reference(card, "Reverse Holo"), card, cards, products).single().trendPriceEur!!, 0.0)
        assertNull(product.copy(holoTrendPriceEur = null).realTrendFor("Reverse Holo"))
        assertNull(product.copy(holoTrendPriceEur = 0.0).realTrendFor("Reverse Holo"))
        assertNull(product.copy(trendPriceEur = null).realTrendFor("Holo"))
    }

    @Test fun missingSecretProductOrWrongSetNeverFallsBackToAPricedMainSetCard() {
        val secret = cards.single { it.number == "090" }
        assertTrue(visualCardmarketProducts(secret, cards, products.filterNot { it.productId == 877507L }).isEmpty())
        assertTrue(visualCardmarketProducts(secret, cards.filterNot { it.id == secret.id }, products).isEmpty())
        assertTrue(visualCardmarketProducts(secret, cards, products.map { it.copy(expansionId = "another-set") }).isEmpty())
        assertTrue(binderReferenceCardmarketProducts(reference(secret, "Holo").copy(sourceCardId = "me03-010"), secret, cards, products).isEmpty())
    }

    private fun reference(card: PokemonVisualCard, finish: String) = CollectionCard(
        entryId = 0, id = card.id, name = card.name, set = card.set.name, number = card.number,
        language = CardLanguage.GERMAN, finish = finish, variant = finish, condition = "Nicht im Besitz",
        quantity = 0, currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null,
        notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null, accent = 0,
        sourceSetId = card.set.id, sourceCardId = card.id, marketExpansionId = "6443",
    )
}
