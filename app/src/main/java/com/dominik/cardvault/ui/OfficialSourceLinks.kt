package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CollectionCard
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/** User-initiated links only. CardVault never downloads or parses these pages. */
object OfficialSourceLinks {
    const val PSA_AUCTION_PRICES = "https://www.psacard.com/auctionprices"

    fun cardmarketProduct(productId: Long): String =
        "https://www.cardmarket.com/en/Pokemon/Products/Singles/CardVault-Product?idProduct=$productId"

    fun cardmarketSearch(card: CollectionCard): String {
        val query = listOf(card.name, card.set, card.number).filter { it.isNotBlank() }.joinToString(" ")
        return "https://www.cardmarket.com/en/Pokemon/Products/Singles?searchString=${encode(query)}"
    }

    fun psaSearchText(card: CollectionCard): String =
        listOf("Pokemon", card.name, card.set, card.number).filter { it.isNotBlank() }.joinToString(" ")

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
}
