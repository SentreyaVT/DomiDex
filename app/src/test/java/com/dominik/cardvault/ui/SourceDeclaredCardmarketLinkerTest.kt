package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.data.catalog.supplementalThirtyYearsCards
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.CollectionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking

/** All prices and auxiliary product ids are deterministic local fixtures, never live quotes. */
class SourceDeclaredCardmarketLinkerTest {
    @Test fun rgbMewUsesItsExactColorProductWithoutBorrowingNormalOrAnotherColorPrice() {
        val set = PokemonSet("30th", "30 Jahre", "Mega-Entwicklung", 128, 161, "", null, "30C")
        val rgbCards = supplementalThirtyYearsCards(set)
        val candidates = listOf(
            product(907672L, "Mew [Psychic | 30C]", "6601", 1.0),
            product(909509L, "Mew [Psychic | 30C]", "6601", null),
            product(909510L, "Mew [Psychic | 30C]", "6601", null),
            product(909511L, "Mew [Psychic | 30C]", "6601", null),
        )
        val expectedProducts = mapOf("R/RGB" to 909509L, "G/RGB" to 909510L, "B/RGB" to 909511L)
        for (card in rgbCards) {
            val linked = visualCardmarketProducts(card, rgbCards, candidates, "6601").single()
            assertEquals(expectedProducts[card.number], linked.productId)
            assertNull(linked.trendPriceEur)
            val selected = catalogResultForVisualCard(card, linked)
            assertEquals(card.id, selected.sourceCardId)
            assertEquals(card.number, selected.collectorNumber)
            assertNull(selected.trendPriceEur)
            assertTrue(selected.hasMarketProduct)
            val otherColors = candidates.filterNot { it.productId == expectedProducts[card.number] }
                .map { it.copy(trendPriceEur = 2000.0) }
            assertTrue(visualCardmarketProducts(card, rgbCards, otherColors, "6601").isEmpty())
        }
    }

    @Test fun anniversaryMewPromoUsesItsOwnVerifiedIdAndCanRemainHonestlyUnpriced() {
        val set = PokemonSet("mep", "MEP Black Star Promos", "Mega-Entwicklung", 0, 105, "", null, "MEP")
        val cards = supplementalThirtyYearsCards(set)
        val mew = cards.single { it.number == "105" }
        val linked = visualCardmarketProducts(mew, cards, listOf(
            product(907672L, "Mew [Psychic | 30C]", "6601", 1.0),
            product(895621L, "Mew [Psychic | MEP]", "6232", null),
        )).single()
        assertEquals(895621L, linked.productId)
        assertEquals("105", linked.collectorNumber)
        assertNull(linked.trendPriceEur)
        assertEquals(mew.id, catalogResultForVisualCard(mew, linked).sourceCardId)
    }

    private val paldea = PokemonVisualCard(
        "sv08.5-137", "Freunde aus Paldea", "137", "Ultra Rare", "small", "large",
        PokemonSet("sv08.5", "Prismatische Entwicklungen", "Test", 131, 180, "", null, null),
        listOf(PokemonPrintVariant("full-art", "Holo", "normal", 805531L)),
        marketSearchName = "Friends in Paldea",
    )

    @Test fun paldea137UsesDeclaredFullArtIdDespiteAbsentOrConflictingExpansionInference() {
        val candidates = listOf(
            product(805503L, "Friends in Paldea", "5944", 0.25),
            product(42L, "Friends in Paldea (Full Art)", "other-set", 99.0),
            product(805531L, "Friends in Paldea (Full Art)", "5944", 24.39),
        )

        listOf(null, "other-set").forEach { inferredExpansion ->
            // An exact source id requires neither a full set list nor a collector-number guess.
            val linked = visualCardmarketProducts(paldea, emptyList(), candidates, inferredExpansion).single()
            assertEquals(805531L, linked.productId)
            assertEquals("5944", linked.expansionId)
            assertEquals(paldea.id, linked.sourceCardId)
            assertEquals(paldea.set.id, linked.sourceSetId)
            assertEquals(paldea.set.name, linked.visualSetName)
            assertEquals("137", linked.collectorNumber)
            assertEquals("large", linked.imageUrl)
        }
    }

    @Test fun missingDeclaredFullArtIdNeverFallsBackToNormalOrAnotherSet() {
        val namesakes = listOf(
            product(805503L, "Friends in Paldea", "5944", 0.25),
            product(42L, "Friends in Paldea (Full Art)", "other-set", 99.0),
        )

        assertTrue(visualCardmarketProducts(paldea, listOf(paldea), namesakes, "5944").isEmpty())
        assertTrue(binderReferenceCardmarketProducts(reference(paldea), paldea, listOf(paldea), namesakes).isEmpty())
    }

    @Test fun anUnpricedDeclaredProductDoesNotBorrowTheNormalPrintingPrice() {
        val linked = binderReferenceCardmarketProducts(reference(paldea), paldea, listOf(paldea), listOf(
            product(805503L, "Friends in Paldea", "5944", 10.0),
            product(805531L, "Friends in Paldea (Full Art)", "5944", null),
        )).single()

        assertEquals(805531L, linked.productId)
        assertNull(linked.trendPriceEur)
    }

    @Test fun declaredIdentityAlsoResolvesAnotherSetAndPreservesTheRegularHoloPrice() {
        val rowlet = paldea.copy(
            id = "me03-090", name = "Bauz", number = "090", marketSearchName = "Rowlet",
            set = PokemonSet("me03", "Optimale Ordnung", "Test", 88, 124, "", null, null),
            printVariants = listOf(PokemonPrintVariant("illustration", "Holo", "Holo", 877507L)),
        )
        val linked = binderReferenceCardmarketProducts(reference(rowlet), rowlet, listOf(rowlet), listOf(
            product(7L, "Rowlet", "6443", 0.25),
            product(877507L, "Rowlet [Find a Friend | Tackle]", "6443", 7.25, 18.0),
        )).single()

        assertEquals(877507L, linked.productId)
        assertEquals("me03", linked.sourceSetId)
        assertEquals("me03-090", linked.sourceCardId)
        assertEquals("090", linked.collectorNumber)
        assertEquals("Bauz", linked.name)
        assertEquals("Holo", linked.finish)
        assertEquals(7.25, linked.trendPriceEur!!, .001)
    }

    @Test fun matchingFinishAndEditionSelectOnlyTheirOwnDeclaredProduct() {
        val source = paldea.copy(printVariants = listOf(
            PokemonPrintVariant("normal", "Normal", "Standard", 900001L),
            PokemonPrintVariant("reverse", "Reverse Holo", "Reverse Holo", 900002L),
            PokemonPrintVariant("edition", "Normal", "1. Edition", 900003L),
        ))
        val candidates = listOf(
            product(900001L, "Normal product", "5944", 1.0, 2.0),
            product(900002L, "Reverse product", "5944", 3.0, 4.0),
            product(900003L, "First edition product", "5944", 5.0, 6.0),
        )
        val expectedPrices = listOf(1.0, 4.0, 5.0)

        source.printVariants.forEachIndexed { index, printing ->
            val linked = binderReferenceCardmarketProducts(reference(source, printing), source, listOf(source), candidates).single()
            assertEquals(printing.cardmarketProductId, linked.productId)
            assertEquals(printing.finish, linked.finish)
            assertEquals(printing.variant, linked.variant)
            assertEquals(expectedPrices[index], linked.trendPriceEur!!, .001)
        }
    }

    @Test fun undeclaredFinishOrEditionCannotBorrowAnotherPrintingId() {
        val source = paldea.copy(printVariants = listOf(
            PokemonPrintVariant("normal", "Normal", "Standard", 900001L),
            PokemonPrintVariant("unlinked-reverse", "Reverse Holo", "Reverse Holo"),
        ))
        val candidates = listOf(product(900001L, "Friends in Paldea", "5944", 1.0, 2.0))

        listOf(
            PokemonPrintVariant("unlinked-reverse", "Reverse Holo", "Reverse Holo"),
            PokemonPrintVariant("missing-edition", "Normal", "1. Edition"),
        ).forEach { printing ->
            assertTrue(binderReferenceCardmarketProducts(reference(source, printing), source, listOf(source), candidates).isEmpty())
        }
    }

    @Test fun declaredProductIdDoesNotOverrideAConflictingBinderSourceIdentity() {
        val candidates = listOf(product(805531L, "Friends in Paldea (Full Art)", "5944", 24.39))
        val slot = reference(paldea)

        assertTrue(binderReferenceCardmarketProducts(slot.copy(sourceSetId = "other-set"), paldea, listOf(paldea), candidates).isEmpty())
        assertTrue(binderReferenceCardmarketProducts(slot.copy(sourceCardId = "sv08.5-001"), paldea, listOf(paldea), candidates).isEmpty())
    }

    @Test fun printedCollectorMustAgreeIncludingAnExplicitDenominator() {
        val slot = reference(paldea)
        val candidates = listOf(product(805531L, "Friends in Paldea", "5944", 24.39))
        assertTrue(binderReferenceCardmarketProducts(slot.copy(number = "138/131"), paldea, listOf(paldea), candidates).isEmpty())
        assertTrue(binderReferenceCardmarketProducts(slot.copy(number = "137/132"), paldea, listOf(paldea), candidates).isEmpty())
        assertEquals(805531L, binderReferenceCardmarketProducts(slot.copy(number = "0137/0131"), paldea, listOf(paldea), candidates).single().productId)
        val classic = paldea.copy(number = "89/149", set = paldea.set.copy(id = "30th-c", printedTotal = 0), id = "30th-c-017")
        assertTrue(referenceMatchesSourceCard(reference(classic), classic))
        assertFalse(referenceMatchesSourceCard(reference(classic).copy(number = "89/148"), classic))
    }

    @Test fun oneCharacterCardNameLoadsItsDeclaredProductWithoutNameSearch() = runBlocking {
        val n = paldea.copy(
            id = "30th-c-021", name = "N", marketSearchName = "N",
            set = PokemonSet("30th-c", "30 Jahre: Klassische Sammlung", "Mega-Entwicklung", 0, 30, "", null, "30C"),
            printVariants = listOf(PokemonPrintVariant("normal", "Normal", "Standard", 907956L)),
        )
        var nameSearchCalled = false
        val repository = Proxy.newProxyInstance(
            CollectionRepository::class.java.classLoader,
            arrayOf(CollectionRepository::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "catalogProducts" -> listOf(product(907956L, "N", "6601", 14.56))
                "searchCatalog" -> { nameSearchCalled = true; emptyList<CatalogSearchResult>() }
                else -> error("Unexpected repository call: ${method.name}")
            }
        } as CollectionRepository

        val loaded = loadVisualCardmarketCandidates(n, repository)

        assertEquals(listOf(907956L), loaded.map { it.productId })
        assertFalse(nameSearchCalled)
    }

    private fun product(id: Long, name: String, expansion: String, trend: Double?, reverse: Double? = null) =
        CatalogSearchResult(id, name, expansion, trend, "EUR", holoTrendPriceEur = reverse)

    private fun reference(card: PokemonVisualCard, printing: PokemonPrintVariant = card.printVariants.first()) = CollectionCard(
        entryId = 0L, id = "${card.id}-${printing.id}", name = card.name, set = card.set.name,
        number = card.number, language = CardLanguage.GERMAN, finish = printing.finish, variant = printing.variant,
        condition = "Nicht im Besitz", quantity = 0, currentValueEur = 0.0, changePercent = 0.0,
        priceUpdatedAtEpochMillis = null, notes = "", acquisitionPriceEur = null,
        acquisitionDateEpochDay = null, accent = 0L, marketSearchName = card.marketSearchName,
        sourceSetId = card.set.id, sourceCardId = card.id,
    )
}
