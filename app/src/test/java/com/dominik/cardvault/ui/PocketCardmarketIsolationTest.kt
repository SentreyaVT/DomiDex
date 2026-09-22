package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class PocketCardmarketIsolationTest {
    @Test fun `known pocket set cannot infer or accept a physical expansion`() {
        val cards = (1..5).map { index ->
            visual(
                set = set("A1", "Unschlagbare Gene", "tcgp"),
                id = "A1-${index.toString().padStart(3, '0')}",
                name = "Pocket $index",
            )
        }
        val candidates = cards.associate { card ->
            card.marketSearchName!! to listOf(product(card.marketSearchName, "6381", 9.99))
        }

        assertNull(resolveCardmarketExpansionId(cards, candidates))
        assertTrue(visualCardmarketProducts(cards.first(), cards, candidates.getValue("Pocket 1"), "6381").isEmpty())
    }

    @Test fun `future pocket set skips even an accidentally declared physical product`() = runBlocking {
        val card = visual(
            set = set("C9", "Zukünftiges Pocket-Set", "tcgp"),
            id = "C9-001",
            name = "Future Pocket",
            printing = PokemonPrintVariant("normal", "Normal", "Standard", 900001L),
        )
        val physical = product("Future Pocket", "900", 42.0, 900001L)

        assertTrue(visualCardmarketProducts(card, listOf(card), listOf(physical), "900").isEmpty())
        assertTrue(loadVisualCardmarketCandidates(card, repositoryThatMustNotBeCalled()).isEmpty())
    }

    @Test fun `physical set with the same uppercase id keeps normal Cardmarket resolution`() {
        val physicalSet = set("A1", "Physisches Testset", "physical")
        val declared = visual(
            set = physicalSet,
            id = "A1-001",
            name = "Physical One",
            printing = PokemonPrintVariant("normal", "Normal", "Standard", 900001L),
        )
        assertEquals(
            900001L,
            visualCardmarketProducts(declared, listOf(declared), listOf(product("Physical One", "900", 1.25, 900001L))).single().productId,
        )

        val inferredCards = (1..3).map { index ->
            visual(physicalSet, "A1-10$index", "Physical $index")
        }
        val candidates = inferredCards.associate { card ->
            card.marketSearchName!! to listOf(product(card.marketSearchName, "900", 1.0))
        }
        assertNull(resolveCardmarketExpansionId(inferredCards, candidates))
    }

    private fun set(id: String, name: String, seriesId: String) = PokemonSet(
        id = id,
        name = name,
        series = "Test",
        printedTotal = 100,
        total = 120,
        releaseDate = "",
        logoUrl = null,
        ptcgoCode = null,
        cardmarketExpansionId = null,
        seriesId = seriesId,
    )

    private fun visual(
        set: PokemonSet,
        id: String,
        name: String,
        printing: PokemonPrintVariant = PokemonPrintVariant("normal", "Normal", "Standard"),
    ) = PokemonVisualCard(
        id = id,
        name = name,
        number = id.substringAfterLast('-'),
        rarity = null,
        smallImageUrl = "",
        largeImageUrl = "",
        set = set,
        printVariants = listOf(printing),
        marketSearchName = name,
    )

    private fun product(name: String, expansion: String, price: Double, id: Long = kotlin.math.abs(name.hashCode().toLong())) =
        CatalogSearchResult(id, name, expansion, price, "EUR")

    private fun repositoryThatMustNotBeCalled(): CollectionRepository = Proxy.newProxyInstance(
        CollectionRepository::class.java.classLoader,
        arrayOf(CollectionRepository::class.java),
    ) { _, method, _ -> error("Pocket lookup called repository.${method.name}") } as CollectionRepository
}
