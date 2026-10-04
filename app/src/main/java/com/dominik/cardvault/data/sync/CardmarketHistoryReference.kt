package com.dominik.cardvault.data.sync

import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * Maintenance may not delete a year of history because the phone's date is wrong.
 * Only the Date header of the successful, fixed HTTPS price-guide request supplies
 * this reference. Agreement with the device clock is an additional fail-closed
 * plausibility check, never a fallback source of destructive time authority.
 * Missing/malformed/stale/skewed headers disable maintenance, not price import.
 */
internal class CardmarketHistoryReference private constructor(val epochMillis: Long) {
    companion object {
        const val METADATA_KEY = "cardmarket_price_history_verified_clock_v1"
        internal const val MAX_CLOCK_DIFFERENCE_MILLIS = 48L * 60 * 60 * 1000
        private val format = DateTimeFormatter.RFC_1123_DATE_TIME.withResolverStyle(ResolverStyle.STRICT)
        private val earliest = Instant.parse("2000-01-01T00:00:00Z").toEpochMilli()

        fun fromPriceGuideResponse(dateHeader: String?, receivedAtEpochMillis: Long): CardmarketHistoryReference? {
            if (dateHeader == null || dateHeader.length !in 20..64 || dateHeader.any { it == '\r' || it == '\n' }) return null
            val sourceTime = runCatching { ZonedDateTime.parse(dateHeader, format).toInstant().toEpochMilli() }.getOrNull()
                ?: return null
            if (sourceTime < earliest) return null
            val difference = runCatching { Math.subtractExact(sourceTime, receivedAtEpochMillis) }.getOrNull() ?: return null
            if (difference !in -MAX_CLOCK_DIFFERENCE_MILLIS..MAX_CLOCK_DIFFERENCE_MILLIS) return null
            // Even a slightly fast provider clock must not shorten the agreed display window.
            return CardmarketHistoryReference(minOf(sourceTime, receivedAtEpochMillis))
        }
    }
}
