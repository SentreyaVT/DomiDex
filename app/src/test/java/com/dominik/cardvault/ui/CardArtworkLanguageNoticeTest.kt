package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.additionalEnglishArtworkUrls
import com.dominik.cardvault.data.catalog.additionalPokemonCardArtworkOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardArtworkLanguageNoticeTest {
    @Test fun allNewEnglishFallbacksAreLabelledButGermanFilesAreNot() {
        additionalEnglishArtworkUrls.forEach { url ->
            assertEquals(url, "Ersatzbild auf Englisch", cardArtworkLanguageNotice(url))
            assertEquals(url, "Ersatzbild auf Englisch", cardArtworkLanguageNotice("$url?size=preview"))
            assertNull(cardArtworkLanguageNotice(url.replace("https://", "http://")))
            assertNull(cardArtworkLanguageNotice("$url#other-image"))
        }
        additionalPokemonCardArtworkOverrides().forEach { entry ->
            val image = requireNotNull(entry.replacement)
            listOf(image.smallImageUrl, image.largeImageUrl).filterNot { it in additionalEnglishArtworkUrls }.forEach {
                assertNull(it, cardArtworkLanguageNotice(it))
            }
        }
    }

    @Test fun knownEnglishTcgdexCardImagesAreLabelled() {
        listOf("low.webp", "high.webp", "high.png", "low.jpg").forEach { file ->
            assertEquals("Ersatzbild auf Englisch",
                cardArtworkLanguageNotice("https://assets.tcgdex.net/en/swsh/swsh9/TG16/$file"))
        }
    }

    @Test fun publishedPokemonTcgImagePathsAreLabelled() {
        listOf("base1/4.png", "sm75/78_hires.png", "swsh45sv/SV001.png").forEach { path ->
            assertEquals("Ersatzbild auf Englisch", cardArtworkLanguageNotice("https://images.pokemontcg.io/$path"))
        }
    }

    @Test fun onlyReviewedEnglishSerebiiSupplementImagesAreLabelled() {
        listOf("megapromos/96.jpg", "megapromos/97.jpg", "megapromos/98.jpg", "megapromos/104.jpg",
            "megapromos/105.jpg", "megapromos/106.jpg", "30thcelebration/rgb1.jpg",
            "30thcelebration/rgb2.jpg", "30thcelebration/rgb3.jpg").forEach { path ->
            assertEquals("Ersatzbild auf Englisch", cardArtworkLanguageNotice("https://www.serebii.net/card/$path"))
        }
        assertNull(cardArtworkLanguageNotice("https://www.serebii.net/card/unknown-japanese-set/1.jpg"))
        assertNull(cardArtworkLanguageNotice("https://www.serebii.net/card/megapromos/96.shtml"))
    }

    @Test fun germanAndOtherKnownLanguagesHaveNoEnglishHint() {
        listOf("de", "fr", "ja").forEach { language ->
            assertNull(cardArtworkLanguageNotice("https://assets.tcgdex.net/$language/swsh/swsh9/TG16/high.webp"))
        }
        assertNull(cardArtworkLanguageNotice("https://assets.pokemon.com/static-assets/content-assets/cms2-de-de/img/cards/web/MEP/MEP_DE_94.png"))
    }

    @Test fun privatePhotosAndUnknownHostsAreNotLabelled() {
        listOf("content://photos/en/card/1", "file:///private/en/photo.jpg", "https://example.org/en/card.jpg",
            "https://assets.pokemon.com/unknown.png", "data:image/png;base64,AA==").forEach {
            assertNull(cardArtworkLanguageNotice(it))
        }
    }

    @Test fun deceptiveHostsAndUserInfoAreRejected() {
        listOf("https://assets.tcgdex.net.evil.example/en/swsh/swsh9/TG16/high.webp",
            "https://evil.example/?image=https://images.pokemontcg.io/base1/4.png",
            "https://assets.tcgdex.net@evil.example/en/swsh/swsh9/TG16/high.webp",
            "https://evil@assets.tcgdex.net/en/swsh/swsh9/TG16/high.webp",
            "https://@images.pokemontcg.io/base1/4.png",
            "https://www.serebii.net.evil.example/card/megapromos/96.jpg").forEach {
            assertNull(cardArtworkLanguageNotice(it))
        }
    }

    @Test fun malformedAndRelativeUrlsAreRejected() {
        listOf(null, "", " ", "not a URL", "/en/swsh/swsh9/TG16/high.webp",
            "//images.pokemontcg.io/base1/4.png", "https://[broken/en/image.png").forEach {
            assertNull(cardArtworkLanguageNotice(it))
        }
    }

    @Test fun onlyHttpsAndStandardPortAreRecognized() {
        assertNull(cardArtworkLanguageNotice("http://images.pokemontcg.io/base1/4.png"))
        assertNull(cardArtworkLanguageNotice("https://images.pokemontcg.io:444/base1/4.png"))
        assertEquals("Ersatzbild auf Englisch", cardArtworkLanguageNotice("HTTPS://IMAGES.POKEMONTCG.IO:443/base1/4.png"))
    }

    @Test fun encodedOrNormalizedPathsAreNotGuessed() {
        listOf("https://assets.tcgdex.net/%65n/swsh/swsh9/TG16/high.webp",
            "https://assets.tcgdex.net/en/../swsh/swsh9/TG16/high.webp",
            "https://images.pokemontcg.io/base1/%34.png",
            "https://images.pokemontcg.io/base1/4.png#other-image",
            "https://images.pokemontcg.io/base1/4.html",
            "https://images.pokemontcg.io/base1/4.png/extra").forEach {
            assertNull(cardArtworkLanguageNotice(it))
        }
    }

    @Test fun sourceQueryDoesNotChangeAnExactImagePath() {
        assertEquals("Ersatzbild auf Englisch", cardArtworkLanguageNotice("https://images.pokemontcg.io/base1/4.png?size=small"))
    }
}
