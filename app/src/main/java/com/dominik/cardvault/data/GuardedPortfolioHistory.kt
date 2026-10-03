package com.dominik.cardvault.data

import com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection
import com.dominik.cardvault.model.PortfolioPoint
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * null requests the unchanged full-history path; emptyList is a valid empty safe chart.
 * Pre-window events still initialize the total and undergo every validation. Only emission
 * is bounded: the window never invents an observation at its boundary.
 */
internal fun buildGuardedPortfolioHistory(
    rows: List<OwnedPortfolioDeltaProjection>,
    windowStartEpochMillis: Long = Long.MIN_VALUE,
): List<PortfolioPoint>? {
    val guard = checkNotNull(rows.firstOrNull()) { "Portfolio query must return its snapshot guard" }
    check(guard.rowKind == 0 && guard.effectiveAtEpochMillis == null && guard.deltaValueMinor == 0L)
    check(guard.isSafe == 0 || guard.isSafe == 1)
    if (guard.isSafe == 0) {
        check(rows.size == 1) { "Unsafe snapshots may not carry computed deltas" }
        return null
    }
    val points = ArrayList<PortfolioPoint>(rows.size - 1)
    var totalMinor = 0L
    var previousTimestamp: Long? = null
    for (index in 1 until rows.size) {
        val row = rows[index]
        check(row.rowKind == 1 && row.isSafe == 1)
        val timestamp = checkNotNull(row.effectiveAtEpochMillis)
        check(previousTimestamp == null || timestamp > previousTimestamp)
        totalMinor += row.deltaValueMinor
        if (timestamp >= windowStartEpochMillis) {
            points += PortfolioPoint(
                Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate()
                    .format(DateTimeFormatter.ofPattern("MMM")),
                totalMinor / 100.0,
            )
        }
        previousTimestamp = timestamp
    }
    return points
}
