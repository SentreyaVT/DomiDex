package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogAuditRegressionTest {
    private val set = PokemonSet("bw2", "Aufstreben der Mächtigen", "SW", 98, 98, "", null, null)

    @Test fun exactUnavailableGermanAssetsUseOnlySameCardEnglishSource() {
        listOf(
            "bw2-9" to "bw/bw2/9", "swsh12-047" to "swsh/swsh12/047",
            "sm1-94" to "sm/sm1/94", "swsh10-162" to "swsh/swsh10/162",
            "sv02-269" to "sv/sv02/269",
        ).forEach { (id, asset) ->
            val de = visual(id, "https://assets.tcgdex.net/de/$asset/high.webp")
            val en = de.copy(name = "English", largeImageUrl = "https://assets.tcgdex.net/en/$asset/high.webp")
            val merged = mergeLocalizedSetCards(listOf(de), listOf(en)).single()
            assertEquals(de.id, merged.id)
            assertEquals(de.name, merged.name)
            assertEquals(en.largeImageUrl, merged.largeImageUrl)
            assertEquals(de.largeImageUrl, mergeLocalizedSetCards(listOf(de), emptyList()).single().largeImageUrl)
        }
    }

    @Test fun correctedUpstreamImageAndDifferentCardNeverGetReplaced() {
        val corrected = visual("bw2-9", "https://assets.tcgdex.net/de/bw/bw2/9-fixed/high.webp")
        val other = visual("bw2-10", "https://assets.tcgdex.net/en/bw/bw2/10/high.webp")
        assertEquals(corrected.largeImageUrl, mergeLocalizedSetCards(listOf(corrected), listOf(other)).first().largeImageUrl)
    }

    @Test fun sameSetEnglishLogoPrecedesUnavailableUniversalSymbolButNeverGermanLogo() {
        val symbol = set.copy(logoUrl = "https://assets.tcgdex.net/univ/bw/bw2/symbol.png")
        val english = set.copy(name = "English", logoUrl = "https://assets.tcgdex.net/en/bw/bw2/logo.png")
        val merged = fillMissingSetLogos(listOf(symbol), listOf(english)).single()
        assertEquals(english.logoUrl, merged.logoUrl)
        assertEquals(symbol.name, merged.name)
        val german = set.copy(logoUrl = "https://assets.tcgdex.net/de/bw/bw2/logo.png")
        assertEquals(german, fillMissingSetLogos(listOf(german), listOf(english)).single())
        assertEquals(symbol, fillMissingSetLogos(listOf(symbol), listOf(english.copy(id = "another-set"))).single())
    }

    @Test fun regularMaintainedProductCannotLeakToUndeclaredSpecialPrinting() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { _ -> Buffer().writeUtf8("""{
            "variants_detailed":[
              {"variantId":"normal","type":"normal","size":"Standard"},
              {"variantId":"stamped","type":"holo","size":"Standard","stamp":["Event"]},
              {"variantId":"verified-stamped","type":"holo","size":"Standard","stamp":["Event"],"thirdParty":{"cardmarket":12345}}
            ]}
        """) }
        val card = visual("30th-065", "").copy(set = set.copy(id = "30th"))
        val variants = catalog.withDeclaredPrintVariants(card).printVariants.associateBy { it.id }
        assertEquals(907672L, variants.getValue("normal").cardmarketProductId)
        assertNull(variants.getValue("stamped").cardmarketProductId)
        assertEquals(12345L, variants.getValue("verified-stamped").cardmarketProductId)
    }

    private fun visual(id: String, image: String) = PokemonVisualCard(
        id, "Deutscher Kartenname", id.substringAfterLast('-'), null, image, image, set,
    )
}
