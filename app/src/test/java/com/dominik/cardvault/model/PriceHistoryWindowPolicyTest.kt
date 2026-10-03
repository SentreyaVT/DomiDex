package com.dominik.cardvault.model

import java.time.Instant
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PriceHistoryWindowPolicyTest {
    @Test fun subtractsTwelveCalendarMonthsAndPreservesTimeAndMilliseconds() {
        assertWindow("2026-10-04T12:34:56.789Z", "2025-10-04T12:34:56.789Z")
        assertWindow("2025-03-31T23:59:59.999Z", "2024-03-31T23:59:59.999Z")
    }

    @Test fun leapDayClampsToLastValidDayInPreviousYear() {
        assertWindow("2024-02-29T06:07:08.009Z", "2023-02-28T06:07:08.009Z")
    }

    @Test fun windowCanContain366DaysRatherThanAlways365() {
        val now = epoch("2025-02-28T12:00:00Z")
        val start = PriceHistoryWindowPolicy.startEpochMillis(now)
        assertEquals(epoch("2024-02-28T12:00:00Z"), start)
        assertEquals(366L * 86_400_000L, now - start)
    }

    @Test fun offsetInputUsesUtcCalendarEvenWhenLocalDateWouldDiffer() {
        val now = OffsetDateTime.parse("2025-03-01T00:30:15.123+02:00").toInstant().toEpochMilli()
        assertEquals(epoch("2024-02-28T22:30:15.123Z"), PriceHistoryWindowPolicy.startEpochMillis(now))
    }

    @Test fun callerTimeIsFullyInjectedAndExactMillisecondBoundaryIsPreserved() {
        val before = epoch("2025-03-01T00:00:00Z") - 1L
        assertEquals(epoch("2024-02-28T23:59:59.999Z"), PriceHistoryWindowPolicy.startEpochMillis(before))
        assertEquals(epoch("2024-03-01T00:00:00Z"), PriceHistoryWindowPolicy.startEpochMillis(before + 1L))
        assertWindow("1970-01-01T00:00:00Z", "1969-01-01T00:00:00Z")
    }

    @Test fun unrepresentableEarlierEpochFailsInsteadOfWrappingTheBoundary() {
        assertThrows(ArithmeticException::class.java) { PriceHistoryWindowPolicy.startEpochMillis(Long.MIN_VALUE) }
    }

    private fun assertWindow(now: String, expected: String) {
        assertEquals(epoch(expected), PriceHistoryWindowPolicy.startEpochMillis(epoch(now)))
    }

    private fun epoch(value: String) = Instant.parse(value).toEpochMilli()
}
