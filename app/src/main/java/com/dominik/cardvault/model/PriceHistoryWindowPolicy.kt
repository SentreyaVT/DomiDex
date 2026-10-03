package com.dominik.cardvault.model

import java.time.Instant
import java.time.ZoneOffset

/** Shared display/retention boundary: twelve calendar months, never a fixed day count. */
internal object PriceHistoryWindowPolicy {
    /**
     * The caller supplies one reference time for the whole operation. UTC avoids device-zone
     * and DST differences; the original time of day and milliseconds are retained. Calendar
     * subtraction clamps February 29 to February 28 in a non-leap year. This pure calculation
     * neither validates the clock's trustworthiness nor authorizes deletion of any data.
     */
    fun startEpochMillis(nowEpochMillis: Long): Long = Instant.ofEpochMilli(nowEpochMillis)
        .atZone(ZoneOffset.UTC)
        .minusMonths(12)
        .toInstant()
        .toEpochMilli()
}
