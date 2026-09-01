package com.dominik.cardvault.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManualSetLookupInstrumentedTest {
    @Test fun selectedSourceSetAndNumberFindTheExactCard() = runBlocking {
        val results = PokemonVisualCatalog().findExact("sv08.5", "068/131")

        assertTrue("Selected PRE set and number returned no card", results.isNotEmpty())
        assertTrue(results.all { it.set.id.equals("sv08.5", ignoreCase = true) })
        assertEquals("68", results.first().number.filter(Char::isDigit).trimStart('0'))
    }
}
