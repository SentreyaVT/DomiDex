package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonSetRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardRarityResolverTest {
    private val prismatic = PokemonSet("sv08.5", "Prismatische Entwicklungen", "Deutsch · TCGdex", 131, 180, "2025-01-17", null, null)

    @Test fun preAndSvpResolveOnlyToTheirOwnSourceSets() {
        val promo = PokemonSet("svp", "SVP Black Star Promos", "Scarlet & Violet", 225, 225, "2023-01-01", null, null)
        assertEquals(prismatic, PokemonSetRegistry.resolve("PRE", listOf(prismatic)))
        assertEquals(null, PokemonSetRegistry.resolve("SVP", listOf(prismatic)))
        assertEquals(promo, PokemonSetRegistry.resolve("SVP", listOf(prismatic, promo)))
    }

    @Test fun aboveMainSetIsDerivedFromTheSetSpecificMainSize() {
        assertTrue(PokemonSetRegistry.isAboveMainSet("143/131", prismatic))
        assertFalse(PokemonSetRegistry.isAboveMainSet("131/131", prismatic))
    }

    @Test fun aboveMainDoesNotInventASpecificRarity() {
        val result = CardRarityResolver.resolve(null, "143/132", prismatic.copy(printedTotal = 132))
        assertTrue(result.aboveMainSet)
        assertEquals(CardRarityType.UNKNOWN, result.type)
    }

    @Test fun sourceMetadataMapsGoldAndSilverClassesSeparately() {
        assertEquals(CardRarityType.ILLUSTRATION_RARE, CardRarityResolver.fromMetadata("Illustration Rare", "143/132", 132).type)
        assertEquals(CardRarityType.SPECIAL_ILLUSTRATION_RARE, CardRarityResolver.fromMetadata("Special Illustration Rare", "144/132", 132).type)
        assertEquals(CardRarityType.HYPER_RARE, CardRarityResolver.fromMetadata("Hyper Rare", "145/132", 132).type)
        assertEquals(CardRarityType.ULTRA_RARE, CardRarityResolver.fromMetadata("Ultra Rare", "140/132", 132).type)
        assertEquals(CardRarityType.SPECIAL_ILLUSTRATION_RARE, CardRarityResolver.fromMetadata("Selten, besondere Illustration", "223/197", 197).type)
        assertEquals(CardRarityType.ULTRA_RARE, CardRarityResolver.fromMetadata("Ultra Selten", "143/131", 131).type)
    }
}
