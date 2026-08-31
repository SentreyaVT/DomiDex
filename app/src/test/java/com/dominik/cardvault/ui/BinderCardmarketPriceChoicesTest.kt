package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Test

class BinderCardmarketPriceChoicesTest {

    private val card = CollectionCard(
        entryId = 9L,
        id = "tcgdex-test-001",
        name = "Glurak ex",
        set = "Obsidianflammen",
        number = "199/197",
        language = CardLanguage.GERMAN,
        finish = "Holo",
        variant = "Standard",
        condition = "Near Mint",
        quantity = 1,
        currentValueEur = 134.99,
        changePercent = 0.0,
        priceUpdatedAtEpochMillis = 1L,
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        accent = 0L,
        externalProductId = 20L,
    )

    @Test
    fun keepsOnlyRealPricedProductsAndPutsCurrentLinkFirst() {
        val choices = binderCardmarketPriceChoices(
            card,
            listOf(
                product(10L, "Glurak ex", 120.0),
                product(20L, "Glurak ex (Full Art)", 134.99),
                product(30L, "Glurak ex (ohne Preis)", null),
                product(40L, "Lokale Referenz", 99.0, hasMarketProduct = false),
                product(20L, "Duplikat", 134.99),
            ),
        )

        assertEquals(listOf(20L, 10L), choices.map { it.productId })
    }

    private fun product(
        id: Long,
        name: String,
        price: Double?,
        hasMarketProduct: Boolean = true,
    ) = CatalogSearchResult(
        productId = id,
        name = name,
        expansionId = "123",
        trendPriceEur = price,
        currency = "EUR",
        visualSetName = "Obsidianflammen",
        collectorNumber = "199/197",
        hasMarketProduct = hasMarketProduct,
    )
}
