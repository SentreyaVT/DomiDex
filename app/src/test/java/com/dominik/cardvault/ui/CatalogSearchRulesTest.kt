package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogSearchRulesTest {
    @Test fun findsByNameInsideSelectedSet() {
        assertTrue(matchesCardInSelectedSet("Morpeko ex", "117", "morpeko"))
    }

    @Test fun printedFractionNarrowsToCardNumber() {
        assertTrue(matchesCardInSelectedSet("Morpeko ex", "117", "117/094"))
        assertFalse(matchesCardInSelectedSet("Morpeko ex", "055", "117/094"))
    }

    @Test fun acceptsCommonSetCodeNumberWriting() {
        assertTrue(matchesCardInSelectedSet("Morpeko ex", "PBL117", "PBL 117"))
    }
}
