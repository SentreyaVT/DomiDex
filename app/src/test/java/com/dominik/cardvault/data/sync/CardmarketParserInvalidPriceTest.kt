package com.dominik.cardvault.data.sync

import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CardmarketParserInvalidPriceTest {
    @Test fun nanAfterValidRecordRejectsWholeGuideForNormalAndReverse() {
        rejectsWholeGuide("NaN", NumberFormatException::class.java)
    }

    @Test fun infinityAfterValidRecordRejectsWholeGuideForNormalAndReverse() {
        rejectsWholeGuide("Infinity", NumberFormatException::class.java)
    }

    @Test fun centLongOverflowAfterValidRecordRejectsWholeGuideForNormalAndReverse() {
        // Exact cents are Long.MAX_VALUE + 1; no floating-point rounding is involved.
        rejectsWholeGuide("92233720368547758.08", ArithmeticException::class.java)
    }

    private fun rejectsWholeGuide(value: String, expectedFailure: Class<out Throwable>) {
        listOf("trend", "trend-holo").forEach { field ->
            // The identical JSON shape accepts a decimal string. NaN/Infinity below
            // are also quoted JSON strings, not syntactically invalid bare literals.
            val valid = CardmarketParsers.prices(Buffer().writeUtf8(guide(field, "5.32"))).records
            assertEquals(listOf(41L, 42L), valid.map { it.productId })
            assertEquals(532L, valid.first().trendMinor)
            assertEquals(914L, valid.first().holoTrendMinor)
            assertEquals(532L, if (field == "trend") valid.last().trendMinor else valid.last().holoTrendMinor)

            val result = runCatching {
                CardmarketParsers.prices(Buffer().writeUtf8(guide(field, value)))
            }
            val failure = result.exceptionOrNull()
            assertTrue("$field=$value must fail at numeric conversion, got $failure",
                expectedFailure.isInstance(failure))
            assertNull("$field=$value must not return the already parsed valid prefix", result.getOrNull())
        }
    }

    private fun guide(field: String, quotedNumericValue: String) = """
        {"version":1,"createdAt":"2026-09-22T02:48:57+0200","priceGuides":[
          {"idProduct":41,"idCategory":52,"trend":5.32,"trend-holo":9.14},
          {"idProduct":42,"idCategory":52,"$field":"$quotedNumericValue"}
        ]}
    """.trimIndent()
}
