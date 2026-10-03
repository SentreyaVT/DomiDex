package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.*
import org.junit.Test

class CollectionCardArtworkTest {
    @Test fun newMissingPromoRepairIsDisplayOnlyAndPreservesTheWholeOwnedCard() {
        val stored = card().copy(name = "Meganie", set = "MEP Black Star Promos", number = "001",
            sourceSetId = "mep", sourceCardId = "mep-001")
        val before = stored.copy()
        assertTrue(stored.displayImageUrl.orEmpty().contains("/MEP/MEP_001_R_DE"))
        assertEquals(before, stored)
        assertNull(stored.imageUrl)
        assertNull(stored.copy(sourceSetId = "other").displayImageUrl)
        assertNull(stored.copy(language = CardLanguage.JAPANESE).displayImageUrl)
        listOf("content://user/my-meganium", "https://example.org/own.png").forEach { url ->
            assertEquals(url, stored.copy(imageUrl = url).displayImageUrl)
        }
    }

    private fun card(image: String? = null, language: CardLanguage = CardLanguage.GERMAN) = CollectionCard(
        entryId = 41, id = "owned-card", name = "Hisui-Voltobal", set = "Zenit der Könige Galar-Galerie",
        number = "GG01/GG70", language = language, finish = "Holo", variant = "Standard",
        condition = "Nahezu neuwertig", quantity = 3, currentValueEur = 8.5, changePercent = 2.1,
        priceUpdatedAtEpochMillis = 1234, notes = "Private Notiz", acquisitionPriceEur = 5.0,
        acquisitionDateEpochDay = 19000, accent = 0, binderId = "private-binder", externalProductId = 42,
        imageUrl = image, sourceSetId = "swsh12.5gg", sourceCardId = "swsh12.5gg-GG01",
    )

    @Test fun repairUsesExactSourceIdNotPrintedNumberOrNameAndNeverChangesStoredCard() {
        val original = card()
        val before = original.copy()
        assertEquals("https://assets.tcgdex.net/de/swsh/swsh12.5/GG01/high.webp", original.displayImageUrl)
        assertEquals(before, original)
        assertNull(original.imageUrl)
        assertEquals(original.displayImageUrl, original.copy(name = "not a matching name", number = "999/999").displayImageUrl)
    }

    @Test fun missingOrContradictorySourceIdentityNeverBorrowsGalleryArt() {
        assertNull(card().copy(sourceCardId = null).displayImageUrl)
        assertNull(card().copy(sourceSetId = null).displayImageUrl)
        assertNull(card().copy(sourceSetId = "swsh12.5").displayImageUrl)
        assertNull(card().copy(sourceCardId = "swsh12.5gg-GG999").displayImageUrl)
    }

    @Test fun userPhotosAndOtherValidImageUrlsRemainByteForByte() {
        listOf("content://user/photo/42", "file:///private/own.jpg", "https://example.org/my-card.png").forEach {
            assertEquals(it, card(it).displayImageUrl)
        }
    }

    @Test fun otherLanguageCardsDoNotAcquireGermanArtwork() {
        CardLanguage.entries.filterNot { it == CardLanguage.GERMAN }.forEach { language ->
            assertNull(card(language = language).displayImageUrl)
            assertEquals("kept", card("kept", language).displayImageUrl)
        }
    }

    @Test fun auditedEnglishImageUpgradeIsGermanDisplayOnlyAndPreservesEveryStoredField() {
        val english = "https://assets.tcgdex.net/en/bw/dv1/1/high.webp"
        val german = "https://assets.tcgdex.net/de/bw/dv1/1/high.webp"
        val source = card(english).copy(name = "Dratini", set = "Drachengruft", number = "1/20",
            sourceSetId = "dv1", sourceCardId = "dv1-1")
        CardLanguage.entries.forEach { language ->
            val stored = source.copy(language = language)
            val before = stored.copy()
            assertEquals(if (language == CardLanguage.GERMAN) german else english, stored.displayImageUrl)
            assertEquals(before, stored)
            assertEquals(english, stored.imageUrl)
        }
        assertEquals(english, source.copy(sourceCardId = "dv1-01").displayImageUrl)
        assertEquals(english, source.copy(sourceSetId = "dv2").displayImageUrl)
        listOf("$english?user-corrected=1", "file:///private/dratini.png", "content://user/dratini").forEach { image ->
            assertEquals(image, source.copy(imageUrl = image).displayImageUrl)
        }
    }
}
