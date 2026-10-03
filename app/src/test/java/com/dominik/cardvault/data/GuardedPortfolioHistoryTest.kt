package com.dominik.cardvault.data

import com.dominik.cardvault.data.local.OwnedPortfolioDeltaProjection
import com.dominik.cardvault.model.PortfolioPoint
import java.time.Instant
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

/** Pure adapter checks; SQL eligibility, transactions and fallback remain real Room tests. */
class GuardedPortfolioHistoryTest {
    @Test fun safeEmptyChartIsNotTheUnsafeFallbackSignal() {
        assertEquals(emptyList<PortfolioPoint>(), buildGuardedPortfolioHistory(listOf(guard())))
        assertNull(buildGuardedPortfolioHistory(listOf(guard(safe = 0))))
    }

    @Test fun positiveNegativeAndZeroDeltasAccumulateWithoutDroppingAnEvent() {
        val rows = listOf(guard(), event(0L, 250L), event(1L, -400L),
            event(2L, 0L), event(3L, 200L))
        val actual = requireNotNull(buildGuardedPortfolioHistory(rows))
        assertEquals(listOf(2.5, -1.5, -1.5, .5), actual.map { it.value })
        assertEquals(4, actual.size)
    }

    @Test fun preservesEveryOriginalTimestampAcrossUtcMonthBoundary() {
        val before = Instant.parse("2024-01-31T23:59:59.999Z").toEpochMilli()
        val midnight = before + 1L
        val after = midnight + 1L
        val formatter = DateTimeFormatter.ofPattern("MMM")
        val january = java.time.LocalDate.of(2024, 1, 31).format(formatter)
        val february = java.time.LocalDate.of(2024, 2, 1).format(formatter)
        assertEquals(listOf(PortfolioPoint(january, 2.0), PortfolioPoint(february, 2.0),
            PortfolioPoint(february, 1.0)), buildGuardedPortfolioHistory(
            listOf(guard(), event(before, 200L), event(midnight, 0L), event(after, -100L))))
    }

    @Test fun handlesInclusiveProvenIntegerBoundWithoutRealOrEarlyEuroConversion() {
        val bound = 2_000_000_000_000_000_000L
        val actual = requireNotNull(buildGuardedPortfolioHistory(listOf(
            guard(), event(0L, bound), event(1L, -bound), event(2L, -bound))))
        assertEquals(listOf(bound / 100.0, 0.0, -bound / 100.0), actual.map { it.value })
    }

    @Test fun rejectsMissingOrMalformedSnapshotGuard() {
        val cases = listOf(
            emptyList(),
            listOf(guard().copy(rowKind = 1)),
            listOf(guard().copy(isSafe = -1)),
            listOf(guard().copy(isSafe = 2)),
            listOf(guard().copy(effectiveAtEpochMillis = 0L)),
            listOf(guard().copy(deltaValueMinor = 1L)),
        )
        cases.forEach { rows ->
            assertThrows(IllegalStateException::class.java) { buildGuardedPortfolioHistory(rows) }
        }
    }

    @Test fun rejectsUnsafeGuardWithComputedEvents() {
        assertThrows(IllegalStateException::class.java) {
            buildGuardedPortfolioHistory(listOf(guard(safe = 0), event(0L, 0L)))
        }
    }

    @Test fun rejectsMissingTimestampOrInvalidEventMetadata() {
        val invalid = listOf(event(0L, 0L).copy(effectiveAtEpochMillis = null),
            event(0L, 0L).copy(rowKind = 0), event(0L, 0L).copy(rowKind = 2),
            event(0L, 0L).copy(isSafe = 0), event(0L, 0L).copy(isSafe = 2))
        invalid.forEach { row ->
            assertThrows(IllegalStateException::class.java) {
                buildGuardedPortfolioHistory(listOf(guard(), row))
            }
        }
    }

    @Test fun rejectsDuplicateOrDecreasingOriginalTimestamps() {
        listOf(listOf(event(1L, 100L), event(1L, 200L)),
            listOf(event(1L, 100L), event(0L, 200L))).forEach { rows ->
            assertThrows(IllegalStateException::class.java) {
                buildGuardedPortfolioHistory(listOf(guard()) + rows)
            }
        }
    }

    private fun guard(safe: Int = 1) = OwnedPortfolioDeltaProjection(0, safe, null, 0L)
    private fun event(at: Long, delta: Long) = OwnedPortfolioDeltaProjection(1, 1, at, delta)
}
