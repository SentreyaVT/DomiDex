package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Test

class PokemonVisualCatalogIdentityTest {
    @Test fun `registered promo prefix becomes numeric source local id only in its own set`() {
        assertEquals("173", collectorIdentityForSourceLookup("SVP173", "svp"))
        assertEquals("SVP173", collectorIdentityForSourceLookup("SVP173", "sv08.5"))
    }

    @Test fun `ordinary collector identity remains unchanged`() {
        assertEquals("117", collectorIdentityForSourceLookup("117/084", "sv08.5"))
    }
}
