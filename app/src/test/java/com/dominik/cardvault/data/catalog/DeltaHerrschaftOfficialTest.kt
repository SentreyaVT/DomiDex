package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaHerrschaftOfficialTest {
    private val sourceSet = PokemonSet(
        id = "me06", name = "Delta-Herrschaft", series = "Mega-Entwicklung",
        printedTotal = 103, total = 141, releaseDate = "2026-11-06", logoUrl = null, ptcgoCode = null,
    )

    @Test fun officialMetadataProvidesTheStableDlrScannerIdentity() {
        val set = DeltaHerrschaftOfficial.officialize(sourceSet)
        assertEquals(DeltaHerrschaftOfficial.germanSetName, set.name)
        assertEquals("DLR", PokemonSetRegistry.metadata(set).setCode)
        assertTrue(PokemonSetRegistry.isVerifiedPrintedCode("DLR"))
        assertEquals("6704", set.cardmarketExpansionId)
        assertEquals(103, set.printedTotal)
        assertEquals(141, set.total)
    }

    @Test fun officialChecklistCorrectionsAndAboveMainRaritiesWinOverTransportMetadata() {
        fun card(number: String, name: String = "upstream", rarity: String? = "Selten") = PokemonVisualCard(
            id = "me06-$number", name = name, number = number, rarity = rarity,
            smallImageUrl = "", largeImageUrl = "", set = sourceSet, sourceLocalId = number,
        )
        assertEquals("Legendäre Lavabröhe", DeltaHerrschaftOfficial.officialize(card("094")).name)
        assertEquals("Sprudelnde Wasser-Energie", DeltaHerrschaftOfficial.officialize(card("132")).name)
        assertEquals("Illustration Rare", DeltaHerrschaftOfficial.officialize(card("104")).rarity)
        assertEquals("Ultra Rare", DeltaHerrschaftOfficial.officialize(card("118")).rarity)
        assertEquals("Special Illustration Rare", DeltaHerrschaftOfficial.officialize(card("135")).rarity)
        assertEquals("Mega Hyper Rare", DeltaHerrschaftOfficial.officialize(card("141")).rarity)
    }

    @Test fun all141OfficialSlotsAreRequiredBeforeTheDeltaCatalogueCanReplaceACache() {
        fun card(number: Int) = PokemonVisualCard(
            id = "me06-${number.toString().padStart(3, '0')}", name = "Karte $number",
            number = number.toString().padStart(3, '0'), rarity = null, smallImageUrl = "", largeImageUrl = "",
            set = sourceSet, sourceLocalId = number.toString().padStart(3, '0'),
        )
        fun completeCard(number: Int) = card(number).copy(smallImageUrl = "https://example.test/$number-low", largeImageUrl = "https://example.test/$number-high")
        assertEquals(141, DeltaHerrschaftOfficial.requireComplete((1..141).map(::completeCard)).size)
        try {
            DeltaHerrschaftOfficial.requireComplete((1..140).map(::completeCard))
            throw AssertionError("Unvollständiger Katalog wurde akzeptiert")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("unvollständig"))
        }
    }

    @Test fun aFullDlrFeedWithOneMissingArtworkIsNotAcceptedAsCompleteMasterSet() {
        fun card(number: Int) = PokemonVisualCard(
            id = "me06-${number.toString().padStart(3, '0')}", name = "Karte $number",
            number = number.toString().padStart(3, '0'), rarity = null,
            smallImageUrl = "https://example.test/$number-low", largeImageUrl = "https://example.test/$number-high",
            set = sourceSet, sourceLocalId = number.toString().padStart(3, '0'),
        )
        try {
            DeltaHerrschaftOfficial.requireComplete((1..141).map(::card).map { if (it.sourceLocalId == "077") it.copy(largeImageUrl = "") else it })
            throw AssertionError("DLR mit fehlender Bildquelle wurde akzeptiert")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message.orEmpty().contains("Bildquelle"))
        }
    }
}
