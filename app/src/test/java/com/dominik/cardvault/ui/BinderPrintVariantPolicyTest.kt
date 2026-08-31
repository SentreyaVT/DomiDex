package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import org.junit.Assert.assertEquals
import org.junit.Test

class BinderPrintVariantPolicyTest {
    private val set = PokemonSet("sv-test", "Testset", "Test", 100, 120, "2026-01-01", null, null)
    private val threePrints = listOf(
        PokemonPrintVariant("normal", "Normal", "Standard"),
        PokemonPrintVariant("holo", "Holo", "Holo"),
        PokemonPrintVariant("reverse", "Reverse Holo", "Reverse Holo"),
    )

    @Test fun ordinaryMainSetCardNeverGetsThreeSlots() {
        val variants = card("Pikachu", "025", "Common").binderPrintVariants()
        assertEquals(listOf("Normal", "Reverse Holo"), variants.map(PokemonPrintVariant::finish))
    }

    @Test fun exCardGetsOnlyOneSlot() {
        assertEquals(1, card("Glurak ex", "006", "Double Rare").binderPrintVariants().size)
    }

    @Test fun specialOrAboveMainSetCardGetsOnlyOneSlot() {
        assertEquals(1, card("Trainerkarte", "070", "ACE SPEC Rare").binderPrintVariants().size)
        assertEquals(1, card("Illustration", "117", "Illustration Rare").binderPrintVariants().size)
    }

    private fun card(name: String, number: String, rarity: String) = PokemonVisualCard(
        id = "sv-test-$number",
        name = name,
        number = number,
        rarity = rarity,
        smallImageUrl = "",
        largeImageUrl = "",
        set = set,
        printVariants = threePrints,
    )
}
