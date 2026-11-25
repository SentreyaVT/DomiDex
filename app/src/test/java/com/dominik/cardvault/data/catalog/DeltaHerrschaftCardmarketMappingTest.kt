package com.dominik.cardvault.data.catalog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeltaHerrschaftCardmarketMappingTest {
    @Test fun mappingCoversEveryDlrSlotWithoutTreatingUnknownPrintingsAsVerified() {
        val records = DeltaHerrschaftCardmarketMapping.records()
        assertEquals(141, records.size)
        assertEquals(62, records.count { it.status == DlrCardmarketMappingStatus.VERIFIED })
        assertEquals(69, records.count { it.status == DlrCardmarketMappingStatus.NEEDS_REVIEW })
        assertEquals(10, records.count { it.status == DlrCardmarketMappingStatus.UNMATCHED })
        assertEquals(62, records.mapNotNull { it.productId }.distinct().size)
        assertTrue(records.all { it.sourceCardId.matches(Regex("me06-\\d{3}")) })
    }

    @Test fun onlyTheReviewedNormalStandardPrintingCanReceiveADlrProduct() {
        assertEquals(918810L, DeltaHerrschaftCardmarketMapping.verifiedProductId("me06-001", "Normal", "Standard"))
        assertEquals(918878L, DeltaHerrschaftCardmarketMapping.verifiedProductId("me06-055", "Normal", "Standard"))
        assertNull(DeltaHerrschaftCardmarketMapping.verifiedProductId("me06-001", "Reverse", "Standard"))
        assertNull(DeltaHerrschaftCardmarketMapping.verifiedProductId("me06-003", "Normal", "Standard"))
    }
}
