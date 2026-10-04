package com.dominik.cardvault.data.sync

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CardmarketHistoryReferenceTest {
    private val source = Instant.parse("2026-10-03T23:00:00Z").toEpochMilli()
    private val header = "Sat, 03 Oct 2026 23:00:00 GMT"

    @Test fun usesVerifiedResponseTimeInsteadOfALaterPhoneTime() {
        assertEquals(source, CardmarketHistoryReference.fromPriceGuideResponse(header, source + 60_000)!!.epochMillis)
    }

    @Test fun aSlightlyFastSourceCannotDeleteMoreThanTheCurrentWindow() {
        assertEquals(source - 60_000, CardmarketHistoryReference.fromPriceGuideResponse(header, source - 60_000)!!.epochMillis)
    }

    @Test fun malformedAbsentContradictoryOrUnboundedHeadersDisableMaintenance() {
        listOf(null, "", "not a date", "Sat, 31 Feb 2026 23:00:00 GMT", "Sun, 03 Oct 2026 23:00:00 GMT",
            "$header\r\nX-Test: injected", " ".repeat(100), "Thu, 01 Jan 1970 00:00:00 GMT").forEach { value ->
            assertNull(value, CardmarketHistoryReference.fromPriceGuideResponse(value, source))
        }
    }

    @Test fun clockSkewFailsClosedInBothDirectionsAndAtOverflow() {
        val max = CardmarketHistoryReference.MAX_CLOCK_DIFFERENCE_MILLIS
        assertEquals(source, CardmarketHistoryReference.fromPriceGuideResponse(header, source + max)!!.epochMillis)
        assertEquals(source - max, CardmarketHistoryReference.fromPriceGuideResponse(header, source - max)!!.epochMillis)
        listOf(source + max + 1, source - max - 1, Long.MIN_VALUE, Long.MAX_VALUE).forEach { deviceTime ->
            assertNull(CardmarketHistoryReference.fromPriceGuideResponse(header, deviceTime))
        }
    }
}
