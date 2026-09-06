package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.runBlocking
import okio.Buffer
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PokemonSetSeriesIdentityTest {
    @Test fun setDetailAddsStablePocketSeriesIdentityWithoutChangingDisplayIdentity() = runBlocking {
        val catalog = PokemonVisualCatalog(PokemonCatalogMetadataCache()) { url ->
            when (url) {
                "https://api.tcgdex.net/v2/de/sets" -> Buffer().writeUtf8("""[{
                    "id":"A1","name":"Unschlagbare Gene","logo":"https://assets.example/A1/logo",
                    "cardCount":{"official":226,"total":286}
                }]""")
                "https://api.tcgdex.net/v2/de/sets/A1" -> Buffer().writeUtf8("""{
                    "id":"A1","name":"Unschlagbare Gene","serie":{"id":"tcgp","name":"Pokémon Pocket"},
                    "cards":[{"id":"A1-001","localId":"001","name":"Bisasam"}]
                }""")
                "https://api.tcgdex.net/v2/en/sets/A1" -> throw IOException("English set detail unavailable")
                else -> error("Unexpected URL: $url")
            }
        }

        val indexedSet = catalog.sets().single()
        assertNull(indexedSet.seriesId)

        val detailedCard = catalog.cards(indexedSet).single()

        assertEquals("A1", detailedCard.set.id)
        assertEquals("Unschlagbare Gene", detailedCard.set.name)
        assertEquals("tcgp", detailedCard.set.seriesId)
        assertTrue(detailedCard.set.isPokemonPocketSet())
        assertFalse(indexedSet.copy(seriesId = "sv").isPokemonPocketSet())
    }
}
