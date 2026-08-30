package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Test

class CardEffectResolverTest {
    private fun card(finish: String = "Normal", variant: String = "Standard") = CollectionCard(
        entryId = 1, id = "card-1", name = "Testkarte", set = "Test", number = "001/100",
        language = CardLanguage.GERMAN, finish = finish, variant = variant, condition = "Near Mint",
        quantity = 1, currentValueEur = 0.0, changePercent = 0.0, priceUpdatedAtEpochMillis = null,
        notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null, accent = 0L,
    )

    @Test fun reverseFinishUsesBodyZone() {
        val effect = CardEffectResolver.resolve(card(finish = "Reverse Holo"))
        assertEquals(CardEffectProfile.REVERSE_HOLO, effect.profile)
        assertEquals(setOf(CardEffectZone.BODY), effect.zones)
    }

    @Test fun metadataUsesFullArtBeforeGenericHolo() {
        assertEquals(CardEffectProfile.FULL_ART, CardEffectResolver.resolve(card(finish = "Holo", variant = "Full Art")).profile)
    }

    @Test fun variantMetadataSelectsExWithoutUsingTheCardName() {
        assertEquals(CardEffectProfile.EX, CardEffectResolver.resolve(card(finish = "Normal", variant = "Pokémon ex")).profile)
    }

    @Test fun sourceDeclaredSpecialIllustrationRareGetsItsOwnProfile() {
        assertEquals(
            CardEffectProfile.SPECIAL_ILLUSTRATION_RARE,
            CardEffectResolver.resolve(card(finish = "Holo", variant = "Full Art").copy(rarity = "Special Illustration Rare")).profile,
        )
    }

    @Test fun explicitUserChoiceOverridesTheAutomaticVisualOnly() {
        val effect = CardEffectResolver.resolve(card(finish = "Normal").copy(rarity = "Double Rare", effectOverride = "REVERSE_HOLO"))

        assertEquals(CardEffectProfile.REVERSE_HOLO, effect.profile)
    }

    @Test fun everyProfileHasAStableSafeMask() {
        assertEquals(emptySet<CardEffectZone>(), CardEffectResolver.configFor(CardEffectProfile.NONE).zones)
        assertEquals(setOf(CardEffectZone.ARTWORK, CardEffectZone.BORDER), CardEffectResolver.configFor(CardEffectProfile.HOLO).zones)
        assertEquals(setOf(CardEffectZone.BODY), CardEffectResolver.configFor(CardEffectProfile.REVERSE_HOLO).zones)
        assertEquals(setOf(CardEffectZone.FULL_CARD), CardEffectResolver.configFor(CardEffectProfile.ILLUSTRATION_RARE).zones)
        assertEquals(setOf(CardEffectZone.FULL_CARD), CardEffectResolver.configFor(CardEffectProfile.SPECIAL_ILLUSTRATION_RARE).zones)
        assertEquals(setOf(CardEffectZone.FULL_CARD), CardEffectResolver.configFor(CardEffectProfile.HYPER_RARE).zones)
        assertEquals(setOf(CardEffectZone.FULL_CARD), CardEffectResolver.configFor(CardEffectProfile.ULTRA_RARE).zones)
        assertEquals(setOf(CardEffectZone.FULL_CARD), CardEffectResolver.configFor(CardEffectProfile.EX).zones)
        assertEquals(setOf(CardEffectZone.FULL_CARD), CardEffectResolver.configFor(CardEffectProfile.FULL_ART).zones)
        assertEquals(setOf(CardEffectZone.FULL_CARD), CardEffectResolver.configFor(CardEffectProfile.SPECIAL).zones)
    }
}
