package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSourceLinksTest {
    private val card = CollectionCard(
        entryId = 1, id = "me5-117", name = "Morpeko ex", set = "Pitch Black",
        number = "117/094", language = CardLanguage.GERMAN, finish = "Holo",
        variant = "Special Illustration Rare", condition = "Nahezu neuwertig", quantity = 1,
        currentValueEur = 67.07, changePercent = 0.0, priceUpdatedAtEpochMillis = 1,
        notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null,
        accent = 0, externalProductId = 888660,
    )

    @Test fun productLinkUsesOnlyOfficialHostAndConfirmedId() {
        assertEquals("https://www.cardmarket.com/en/Pokemon/Products/Singles/CardVault-Product?idProduct=888660", OfficialSourceLinks.cardmarketProduct(888660))
    }

    @Test fun fallbackSearchContainsNameSetAndPrintedNumber() {
        val url = OfficialSourceLinks.cardmarketSearch(card)
        assertTrue(url.startsWith("https://www.cardmarket.com/en/Pokemon/Products/Singles?"))
        assertTrue(url.contains("Morpeko%20ex%20Pitch%20Black%20117%2F094"))
    }

    @Test fun psaResearchTextKeepsExactIdentityVisible() {
        assertEquals("Pokemon Morpeko ex Pitch Black 117/094", OfficialSourceLinks.psaSearchText(card))
        assertEquals("https://www.psacard.com/auctionprices", OfficialSourceLinks.PSA_AUCTION_PRICES)
    }
}
