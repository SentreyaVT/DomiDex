package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Test

class SpecialPrintBinderIdentityTest {
    private val set = PokemonSet("sv03.5", "151", "Karmesin & Purpur", 165, 207, "", null, "MEW")
    private val card = PokemonVisualCard(
        id = "sv03.5-001",
        name = "Bisasam",
        number = "001",
        rarity = "Häufig",
        smallImageUrl = "",
        largeImageUrl = "",
        set = set,
        printVariants = listOf(
            PokemonPrintVariant("regular", "Normal", "Standard", 733596L),
            PokemonPrintVariant("reverse", "Reverse Holo", "Standard", 733596L),
            PokemonPrintVariant("set-logo", "Normal", "Standard · Stempel: Set-Logo", 720365L),
            PokemonPrintVariant("cosmos", "Reverse Holo", "Standard · Folie: Kosmos", 794908L),
        ),
        marketSearchName = "Bulbasaur",
    )

    @Test fun `special products do not create duplicate canonical binder slots`() {
        assertEquals(
            listOf("regular", "reverse"),
            card.binderPrintVariants().map(PokemonPrintVariant::id),
        )
    }

    @Test fun `declared Bisasam options retain four exact identities and standard default`() {
        val options = visualCardmarketProducts(card, listOf(card), sourceProducts())

        assertEquals(
            listOf(
                "733596|Normal|Standard|0.11",
                "733596|Reverse Holo|Standard|0.31",
                "720365|Normal|Standard · Stempel: Set-Logo|69.77",
                "794908|Reverse Holo|Standard · Folie: Kosmos|13.63",
            ),
            options.map { "${it.productId}|${it.finish}|${it.variant}|${it.trendPriceEur}" },
        )
        val preferred = preferredVisualCardmarketProduct(card, options)!!
        assertEquals(733596L, preferred.productId)
        assertEquals("Normal", preferred.finish)
        assertEquals("Standard", preferred.variant)
        assertEquals(0.11, preferred.trendPriceEur!!, 0.0001)
    }

    @Test fun `shared product id and Cosmos remain exact for scanner and add preview`() {
        val options = visualCardmarketProducts(card, listOf(card), sourceProducts())
        val reverse = visualCardmarketProductForPrinting(card.printVariants[1], options)!!
        val cosmos = visualCardmarketProductForPrinting(card.printVariants[3], options)!!

        assertEquals(0.31, reverse.trendPriceEur!!, 0.0001)
        assertEquals("Reverse Holo", reverse.finish)
        assertEquals(13.63, cosmos.trendPriceEur!!, 0.0001)
        val selected = catalogResultForVisualCard(card, cosmos)
        assertEquals("Standard · Folie: Kosmos", selected.variant)
        assertEquals(13.63, selected.trendPriceEur!!, 0.0001)
    }

    @Test fun `brief card refuses ambiguous same name products instead of choosing a stamp`() {
        val brief = card.copy(printVariants = listOf(PokemonPrintVariant("normal", "Normal", "Standard")))

        assertEquals(emptyList<CatalogSearchResult>(), visualCardmarketProducts(brief, listOf(brief), sourceProducts(), "5169"))
    }

    @Test fun `manual set logo selection retains exact product and variant identity`() {
        val selected = catalogResultForVisualCard(card, product(720365L, 24.50))

        assertEquals(720365L, selected.productId)
        assertEquals("Normal", selected.finish)
        assertEquals("Standard · Stempel: Set-Logo", selected.variant)
        assertEquals(24.50, selected.trendPriceEur!!, 0.0001)
        assertEquals("sv03.5", selected.sourceSetId)
        assertEquals("sv03.5-001", selected.sourceCardId)
    }

    @Test fun `manual cosmos selection retains exact reverse price identity`() {
        val selected = catalogResultForVisualCard(card, product(794908L, 5.00, 31.75))

        assertEquals(794908L, selected.productId)
        assertEquals("Reverse Holo", selected.finish)
        assertEquals("Standard · Folie: Kosmos", selected.variant)
        assertEquals(31.75, selected.trendPriceEur!!, 0.0001)
    }

    private fun product(id: Long, regularPrice: Double, reversePrice: Double? = null) = CatalogSearchResult(
        productId = id,
        name = "Bulbasaur",
        expansionId = "5169",
        trendPriceEur = regularPrice,
        currency = "EUR",
        holoTrendPriceEur = reversePrice,
    )

    private fun sourceProducts() = listOf(
        product(720365L, 69.77),
        product(733596L, 0.11, 0.31),
        product(794908L, 0.0, 13.63),
    )
}
