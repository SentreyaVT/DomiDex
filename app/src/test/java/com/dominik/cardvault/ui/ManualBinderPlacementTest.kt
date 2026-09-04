package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ManualBinderPlacementTest {
    private val sourceSet = PokemonSet("test-set", "Katalogname", "Test", 100, 100, "", "https://example.test/logo.png", null)

    @Test fun uniqueSourceSetWinsOverADifferentSetWithTheDisplayName() {
        val displayNameMatch = sourceSet.copy(id = "another-set", name = "Test set")
        val owned = card("001").copy(sourceSetId = " TEST-SET ")
        val legacy = card("002").copy(sourceSetId = null, sourceCardId = null)

        assertSame(sourceSet, resolveBinderSourceSet("Test set", listOf(owned, legacy), listOf(displayNameMatch, sourceSet)))
    }

    @Test fun missingOrAmbiguousSourceSetUsesTheDisplayNameFallback() {
        val displayNameMatch = sourceSet.copy(id = "another-set", name = "Test set")
        val unknownSource = card("001").copy(sourceSetId = "unavailable")
        val ambiguous = listOf(card("001"), card("002").copy(sourceSetId = "another-set"))
        val sets = listOf(sourceSet, displayNameMatch)

        assertSame(displayNameMatch, resolveBinderSourceSet("Test set", listOf(unknownSource), sets))
        assertSame(displayNameMatch, resolveBinderSourceSet("Test set", ambiguous, sets))
        assertNull(resolveBinderSourceSet("", emptyList(), sets))
    }

    @Test fun missingOwnedImageUsesItsExactReferenceWithoutChangingTheSavedCard() {
        val reference = card("001").copy(imageUrl = "https://example.test/card.webp")
        listOf(null, "", " ").forEach { missingImage ->
            val owned = reference.copy(entryId = 11, imageUrl = missingImage, quantity = 2, notes = "Keep notes")

            val displayed = arrange(listOf(owned), listOf(reference)).single()

            assertEquals(owned.copy(imageUrl = reference.imageUrl), displayed)
            assertEquals(missingImage, owned.imageUrl)
        }
    }

    @Test fun existingOwnedImageIsNeverReplacedByTheReference() {
        val reference = card("001").copy(imageUrl = "https://example.test/reference.webp")
        val owned = reference.copy(entryId = 11, imageUrl = "https://example.test/owned.webp")

        assertSame(owned, arrange(listOf(owned), listOf(reference)).single())
    }

    @Test fun legacyMissingEditionMetadataIsNotANewSpecialEdition() {
        val reference = card(number = "001")
        val owned = reference.copy(entryId = 11, variant = "UNKNOWN")
        val nextCard = card(number = "002")
        assertEquals(listOf(owned, nextCard), arrange(listOf(owned), listOf(reference, nextCard)))
    }

    @Test fun sourceIdentityKeepsFormattedNumberInItsExactSlot() {
        val reference = card(number = "001", finish = "Holo")
        val owned = reference.copy(entryId = 11, number = "SVP001/999", set = "Translated set")

        val slots = arrange(listOf(owned), listOf(reference, card(number = "002")))

        assertEquals(2, slots.size)
        assertSame(owned, slots[0])
    }

    @Test fun sharedMarketProductDoesNotMoveReverseIntoNormalSlot() {
        val normal = card(number = "001").copy(externalProductId = 99)
        val reverse = normal.copy(finish = "Reverse Holo", variant = "Reverse Holo")
        val ownedNormal = normal.copy(entryId = 11)
        val ownedReverse = reverse.copy(entryId = 12)

        val slots = arrange(listOf(ownedReverse, ownedNormal), listOf(normal, reverse, card(number = "002")))

        assertEquals(3, slots.size)
        assertSame(ownedNormal, slots[0])
        assertSame(ownedReverse, slots[1])
    }

    @Test fun omittedConfirmedPrintingStaysBetweenItsNormalAndReverseSlots() {
        val normal = card(number = "001")
        val reverse = normal.copy(finish = "Reverse Holo", variant = "Reverse Holo")
        val ownedHolo = normal.copy(entryId = 11, finish = "Holo", variant = "Holo")
        val nextCard = card(number = "002")

        val slots = arrange(listOf(ownedHolo), listOf(normal, reverse, nextCard))

        assertEquals(4, slots.size)
        assertSame(normal, slots[0])
        assertSame(ownedHolo, slots[1])
        assertSame(reverse, slots[2])
        assertSame(nextCard, slots[3])
    }

    @Test fun additionalConditionStaysBesideTheSameCardBeforeTheNextNumber() {
        val reference = card(number = "001")
        val firstCopy = reference.copy(entryId = 11, condition = "Mint")
        val secondCopy = reference.copy(entryId = 12, condition = "Played")
        val nextCard = card(number = "002")

        val slots = arrange(listOf(firstCopy, secondCopy), listOf(reference, nextCard))

        assertEquals(listOf(firstCopy, secondCopy, nextCard), slots)
    }

    @Test fun specialEditionKeepsTheOrdinarySlotAndStaysAtTheSameNumber() {
        val reference = card(number = "001")
        val firstEdition = reference.copy(entryId = 11, variant = "1. Edition")
        val nextCard = card(number = "002")

        val slots = arrange(listOf(firstEdition), listOf(reference, nextCard))

        assertEquals(listOf(reference, firstEdition, nextCard), slots)
    }

    @Test fun conflictingSourceCardCannotStealOrJoinAKnownSlotEvenWithSamePriceId() {
        val reference = card(number = "001").copy(externalProductId = 99, imageUrl = "https://example.test/reference.webp")
        val conflicting = reference.copy(entryId = 11, sourceCardId = "different-card", imageUrl = null)
        val nextCard = card(number = "002")

        val slots = arrange(listOf(conflicting), listOf(reference, nextCard))

        assertEquals(listOf(reference, nextCard, conflicting), slots)
    }

    @Test fun conflictingSourceSetCannotStealOrJoinAKnownSlot() {
        val reference = card(number = "001")
        val conflicting = reference.copy(entryId = 11, sourceSetId = "another-set")
        val nextCard = card(number = "002")

        assertEquals(listOf(reference, nextCard, conflicting), arrange(listOf(conflicting), listOf(reference, nextCard)))
    }

    @Test fun legacyNumberMatchStillRequiresTheSameSet() {
        val reference = card(number = "001")
        val otherSet = reference.copy(entryId = 11, set = "Other set", sourceSetId = null, sourceCardId = null)
        val nextCard = card(number = "002")

        assertEquals(listOf(reference, nextCard, otherSet), arrange(listOf(otherSet), listOf(reference, nextCard)))
    }

    @Test fun sourceConfirmedCopyGetsTheSlotBeforeLegacyNumberOnlyCopy() {
        val reference = card(number = "001")
        val legacy = reference.copy(entryId = 11, sourceSetId = null, sourceCardId = null)
        val sourceConfirmed = reference.copy(entryId = 12, number = "001/100")
        val nextCard = card(number = "002")

        assertEquals(listOf(sourceConfirmed, legacy, nextCard), arrange(listOf(legacy, sourceConfirmed), listOf(reference, nextCard)))
    }

    @Test fun unverifiedCardWithoutNumberRemainsVisibleInOverflow() {
        val reference = card(number = "001")
        val unknown = reference.copy(entryId = 11, number = "", sourceSetId = null, sourceCardId = null)
        val nextCard = card(number = "002")

        assertEquals(listOf(reference, nextCard, unknown), arrange(listOf(unknown), listOf(reference, nextCard)))
    }

    @Test fun legacyMarketReferenceWithoutNumberStillRequiresTheRightFinish() {
        val normal = card(number = "").copy(sourceSetId = null, sourceCardId = null, externalProductId = 99)
        val reverse = normal.copy(finish = "Reverse Holo", variant = "Reverse Holo")
        val ownedReverse = reverse.copy(entryId = 11)

        assertEquals(listOf(normal, ownedReverse), arrange(listOf(ownedReverse), listOf(normal, reverse)))
    }

    private fun arrange(owned: List<CollectionCard>, references: List<CollectionCard>) =
        arrangeBinderCards("Test set", owned, capacity = 100, references = references)

    private fun card(number: String, finish: String = "Normal") = CollectionCard(
        entryId = 0,
        id = "test-set-$number-$finish",
        name = "Test card",
        set = "Test set",
        number = number,
        language = CardLanguage.GERMAN,
        finish = finish,
        variant = if (finish == "Normal") "Standard" else finish,
        condition = "Not owned",
        quantity = 0,
        currentValueEur = 0.0,
        changePercent = 0.0,
        priceUpdatedAtEpochMillis = null,
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        accent = 0,
        sourceSetId = "test-set",
        sourceCardId = "test-set-$number",
    )
}
