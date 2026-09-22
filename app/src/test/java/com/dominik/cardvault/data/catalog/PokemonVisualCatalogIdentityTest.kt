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

    @Test fun `classic collection historic numbers resolve to virtual source positions`() {
        assertEquals("1", collectorIdentityForSourceLookup("004/102", "30th-c"))
        assertEquals("17", collectorIdentityForSourceLookup("089/149", "30th-c"))
        assertEquals("29", collectorIdentityForSourceLookup("149/147", "30th-c"))
        assertEquals("89/149", classic30thPrintedCollectorForLocalId("017"))
        assertEquals(null, classic30thLocalIdForPrintedCollector("089/128"))
    }

    @Test fun `all thirty classic aliases are unique and round trip`() {
        val printed = listOf(
            "4/102", "5/109", "11/113", "11/101", "18/132", "19/109", "25/111", "33/181",
            "41/122", "43/146", "47/127", "50/185", "57/111", "58/102", "69/132", "85/124",
            "89/149", "94/102", "99/102", "100/102", "101/101", "106/106", "106/160", "106/105",
            "108/115", "269/264", "123/172", "138/202", "149/147", "203/193",
        )

        assertEquals(30, printed.toSet().size)
        printed.forEachIndexed { index, collector ->
            val local = (index + 1).toString()
            assertEquals(local, classic30thLocalIdForPrintedCollector(collector))
            assertEquals(collector, classic30thPrintedCollectorForLocalId(local.padStart(3, '0')))
        }
    }

    @Test fun `verified anniversary price collisions have exact Cardmarket products`() {
        assertEquals(907630L, verifiedCardmarketProductId("30th-023"))
        assertEquals(907659L, verifiedCardmarketProductId("30th-052"))
        assertEquals(907672L, verifiedCardmarketProductId("30th-065"))
        assertEquals(907956L, verifiedCardmarketProductId("30th-c-021"))
        assertEquals(907950L, verifiedCardmarketProductId("30th-c-022"))
        assertEquals(907945L, verifiedCardmarketProductId("30th-c-029"))
        assertEquals(null, verifiedCardmarketProductId("30th-001"))
    }
}
