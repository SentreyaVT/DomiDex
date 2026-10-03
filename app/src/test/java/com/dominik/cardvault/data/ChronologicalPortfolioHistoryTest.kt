package com.dominik.cardvault.data

import com.dominik.cardvault.data.catalog.isQuarantinedCardmarketIdentity
import com.dominik.cardvault.data.local.CollectionProjection
import com.dominik.cardvault.data.local.OwnedPriceHistoryProjection
import com.dominik.cardvault.model.PortfolioPoint
import java.math.BigInteger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM checks: no Room, SDK, current-price choices, clocks or external price sources. */
class ChronologicalPortfolioHistoryTest {
    @Test fun emptyAndUnreferencedHistoriesRetainEveryDistinctRawTimestamp() {
        val events = listOf(price("unused", 0, 300), price("unused", 1, null), price("unused", 1, 400), price("other", 2, 0))
        assertEquals(emptyList<PortfolioPoint>(), buildChronologicalPortfolioHistory(listOf(row("a")), emptyList()))
        assertEquals(listOf(0.0, 0.0, 0.0), buildChronologicalPortfolioHistory(emptyList(), events).map { it.value })
        assertEquals(naive(emptyList(), events), buildChronologicalPortfolioHistory(emptyList(), events))
        assertEquals(naive(listOf(row("a")), events), buildChronologicalPortfolioHistory(listOf(row("a")), events))
    }

    @Test fun groupedHoldingsFollowIndependentAsynchronousNormalAndReverseSeries() {
        val rows = listOf(row("a", quantity = 2), row("a", "Holo", 3), row("a", "rEvErSe Holo", 4), row("b"))
        val events = listOf(
            price("a", 0, 100, 250),
            price("b", 1, 700),
            price("a", 2, null, 300),
            price("unused", 3, 90_000),
            price("a", 4, 150, -1),
            price("b", 5, 0),
        )
        val actual = buildChronologicalPortfolioHistory(rows, events)
        assertEquals(listOf(15.0, 22.0, 19.0, 19.0, 14.5, 7.5), actual.map { it.value })
        assertEquals(naive(rows, events), actual)
    }

    @Test fun invalidReplacementClearsPreviousValueRatherThanCarryingItForward() {
        val rows = listOf(row("a", quantity = 2), row("a", "Reverse", 3))
        val events = listOf(
            price("a", 0, 100, 200),
            price("a", 1, null, 200),
            price("a", 2, 100, 0),
            price("a", 3, -1, 200),
            price("a", 4, 100, Long.MIN_VALUE),
            price("a", 5, 0, null),
        )
        assertEquals(listOf(8.0, 6.0, 2.0, 6.0, 2.0, 0.0), buildChronologicalPortfolioHistory(rows, events).map { it.value })
        assertEquals(naive(rows, events), buildChronologicalPortfolioHistory(rows, events))
    }

    @Test fun equalTimeEventsAreLastWinsPerProductAndEmitOnlyAfterAllProducts() {
        val rows = listOf(row("a", quantity = 2), row("a", "Reverse", 7), row("b"))
        val first = price("a", 0, 100, 200)
        val last = price("a", 0, 300, null)
        val events = listOf(first, price("b", 0, 700), last, price("b", 1, null), price("b", 1, 900))
        assertEquals(listOf(13.0, 15.0), buildChronologicalPortfolioHistory(rows, events).map { it.value })
        assertEquals(naive(rows, events), buildChronologicalPortfolioHistory(rows, events))
        // Changing only same-product tie order must change the result, never sort by value.
        val reversedTie = listOf(last, price("b", 0, 700), first)
        assertEquals(listOf(23.0), buildChronologicalPortfolioHistory(rows, reversedTie).map { it.value })
    }

    @Test fun quarantineAppliesBeforeGroupingAndDoesNotPoisonLegitimateProductSiblings() {
        val rows = listOf(
            row("shared", quantity = 100).copy(sourceCardId = "swsh11-201", externalProductId = 670816),
            row("shared", "Reverse", 100).copy(sourceCardId = " TCGDEX-SWSH11-SWSH11-201-REVERSE ", externalProductId = 670816),
            row("shared", quantity = 2).copy(sourceCardId = "swsh11-130", externalProductId = 670816),
            row("shared", "Reverse", 3).copy(sourceCardId = "swsh11-130", externalProductId = 670816),
            row("bad-only", quantity = 50).copy(sourceCardId = "sv03-062", externalProductId = 781858),
        )
        val events = listOf(price("shared", 0, 100, 200), price("bad-only", 1, 99_999), price("shared", 2, null, 300))
        assertEquals(listOf(8.0, 8.0, 9.0), buildChronologicalPortfolioHistory(rows, events).map { it.value })
        assertEquals(naive(rows, events), buildChronologicalPortfolioHistory(rows, events))
    }

    @Test fun existingNullSourceFallbackIsNotSilentlyChangedIntoBlankSourceFallback() {
        val original = row("shared").copy(cardId = "swsh11-201", sourceCardId = null, externalProductId = 670816)
        val rows = listOf(original, original.copy(sourceCardId = "", quantity = 2), original.copy(sourceCardId = "   ", quantity = 3), original.copy(externalProductId = null, quantity = 4))
        val events = listOf(price("shared", 0, 100))
        assertEquals(listOf(9.0), buildChronologicalPortfolioHistory(rows, events).map { it.value })
        assertEquals(naive(rows, events), buildChronologicalPortfolioHistory(rows, events))
    }

    @Test fun quantitiesUseLongAndOverflowMatchesWrappingPerRowArithmetic() {
        val rows = listOf(
            row("a", quantity = Int.MAX_VALUE), row("a", "Holo", Int.MAX_VALUE),
            row("a", "Reverse", Int.MIN_VALUE), row("a", "Reverse", -1),
            row("b", quantity = -2), row("b", "Reverse", 0),
        )
        val events = listOf(
            price("a", 0, 100, 100),
            price("b", 0, Long.MAX_VALUE),
            price("a", 1, Long.MAX_VALUE, Long.MAX_VALUE - 1),
            price("b", 2, 1),
            price("a", 3, 1, Long.MIN_VALUE),
            price("a", 4, null, null),
        )
        val actual = buildChronologicalPortfolioHistory(rows, events)
        // BigInteger only in the independent oracle: low 64 bits model JVM Long wraparound.
        assertEquals(naive(rows, events), actual)
        val allPositive = rows.take(2)
        assertEquals(listOf(4_294_967_294.0), buildChronologicalPortfolioHistory(allPositive, listOf(price("a", 0, 100))).map { it.value })
        assertEquals(-0.02, actual.last().value, 0.0)
    }

    @Test fun seededSmallHistoriesMatchIndependentNaiveOracleAcrossGroupingAndReplacements() {
        val random = Random(0x27_10_01)
        val quantities = intArrayOf(0, 1, 2, 7, -1, -3, Int.MAX_VALUE, Int.MIN_VALUE)
        val values = listOf(null, -1L, 0L, 1L, 100L, 300L, Long.MIN_VALUE, Long.MAX_VALUE, Long.MAX_VALUE - 1)
        val finishes = listOf("Normal", "Holo", "REVERSE HOLO", "special reverse printing", "")
        repeat(400) { scenario ->
            val rows = List(random.nextInt(0, 18)) { index ->
                row("p${random.nextInt(0, 5)}", finishes[random.nextInt(finishes.size)], quantities[random.nextInt(quantities.size)])
                    .copy(entryId = index.toLong(), sourceCardId = if (random.nextInt(4) == 0) "swsh11-201" else "healthy-$index", externalProductId = 670816)
            }
            // Stable sort retains the randomly generated order within one timestamp.
            val events = List(random.nextInt(0, 45)) {
                price("p${random.nextInt(0, 7)}", random.nextInt(0, 9).toLong(), values[random.nextInt(values.size)], values[random.nextInt(values.size)])
            }.sortedBy { it.effectiveAtEpochMillis }
            val expected = naive(rows, events)
            assertEquals("scenario=$scenario", expected, buildChronologicalPortfolioHistory(rows, events))
            // Long addition modulo 2^64 is permutation-invariant; grouping may not change it.
            assertEquals("reversed rows scenario=$scenario", expected, buildChronologicalPortfolioHistory(rows.reversed(), events))
        }
    }

    @Test fun windowKeepsAsynchronousProductAndFinishStateBeforeItsInclusiveBoundary() {
        val rows = listOf(row("a", quantity = 2), row("a", "Reverse", 3), row("b"))
        val events = listOf(
            price("a", 0, 100, 200),
            price("b", 1, 700),
            price("a", 2, null, 300),
            price("b", 3, 800),
            price("a", 4, 150, -1),
        )
        val actual = buildChronologicalPortfolioHistory(rows, events, windowStartEpochMillis = 3L)
        assertEquals(listOf(17.0, 11.0), actual.map { it.value })
        assertEquals(naive(rows, events).drop(3), actual)
    }

    @Test fun windowKeepsHiddenNullNegativeResetsAndRealUnreferencedZeroEvents() {
        val rows = listOf(row("a", quantity = 2), row("a", "Reverse", 3))
        val events = listOf(price("a", 0, 100, 200), price("a", 1, -1, null),
            price("unused", 5, 999), price("a", 6, 0, -1))
        assertEquals(listOf(0.0, 0.0), buildChronologicalPortfolioHistory(rows, events, 5L).map { it.value })
        assertEquals(emptyList<PortfolioPoint>(), buildChronologicalPortfolioHistory(rows, events, 7L))
        assertEquals(emptyList<PortfolioPoint>(), buildChronologicalPortfolioHistory(rows, emptyList(), 5L))
    }

    @Test fun windowGroupsEveryBoundaryTieBeforeEmittingAndDoesNotInventGapPoints() {
        val rows = listOf(row("a", quantity = 2), row("b"))
        val old = Instant.parse("2023-12-01T00:00:00Z").toEpochMilli()
        val observed = Instant.parse("2024-02-01T00:00:00Z").toEpochMilli()
        val events = listOf(price("a", old, 100), price("b", old, 700),
            price("a", observed, 200), price("b", observed, 900), price("a", observed, 300))
        val month = java.time.LocalDate.of(2024, 2, 1).format(DateTimeFormatter.ofPattern("MMM"))
        val expected = listOf(PortfolioPoint(month, 15.0))
        assertEquals(expected, buildChronologicalPortfolioHistory(rows, events, observed))
        assertEquals(expected, buildChronologicalPortfolioHistory(rows, events,
            Instant.parse("2024-01-01T00:00:00Z").toEpochMilli()))
    }

    @Test fun windowPreservesWrappingFallbackAndQuarantineAgainstIndependentAsOfOracle() {
        val rows = listOf(
            row("a", quantity = Int.MAX_VALUE), row("a", "Reverse", Int.MIN_VALUE),
            row("b", quantity = -2),
            row("a", quantity = 300).copy(sourceCardId = "swsh11-201", externalProductId = 670816),
        )
        val events = listOf(price("a", 0, Long.MAX_VALUE, Long.MAX_VALUE - 1),
            price("b", 1, Long.MAX_VALUE), price("a", 2, 1, Long.MIN_VALUE),
            price("b", 3, 1), price("unused", 4, null))
        val expected = naive(rows, events).drop(2)
        assertEquals(expected, buildChronologicalPortfolioHistory(rows, events, 2L))
        assertEquals(expected, buildChronologicalPortfolioHistory(rows.reversed(), events, 2L))
    }

    /** Deliberately independent: every original row scans the entire event list per date. */
    private fun naive(rows: List<CollectionProjection>, events: List<OwnedPriceHistoryProjection>): List<PortfolioPoint> =
        events.map { it.effectiveAtEpochMillis }.distinct().map { timestamp ->
            var mathematicalTotal = BigInteger.ZERO
            for (row in rows) {
                if (isQuarantinedCardmarketIdentity(row.sourceCardId ?: row.cardId, row.externalProductId)) continue
                val last = events.lastOrNull { it.cardId == row.priceCardId && it.effectiveAtEpochMillis <= timestamp }
                val value = if (row.finish.contains("reverse", ignoreCase = true)) last?.holoTrendValueMinor else last?.trendValueMinor
                if (value != null && value > 0L) {
                    mathematicalTotal += BigInteger.valueOf(value) * BigInteger.valueOf(row.quantity.toLong())
                }
            }
            PortfolioPoint(
                Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC).toLocalDate().format(DateTimeFormatter.ofPattern("MMM")),
                mathematicalTotal.toLong() / 100.0,
            )
        }

    private fun price(id: String, timestamp: Long, normal: Long?, reverse: Long? = null) =
        OwnedPriceHistoryProjection(id, timestamp, normal, reverse)

    private fun row(id: String, finish: String = "Normal", quantity: Int = 1) = CollectionProjection(
        entryId = 1,
        cardId = "owned-$id",
        name = "Fixture",
        setName = "Fixture set",
        collectorNumber = "001",
        language = "GERMAN",
        finish = finish,
        variant = "Standard",
        quantity = quantity,
        condition = "Near Mint",
        acquisitionPriceMinor = null,
        acquisitionCurrency = null,
        acquisitionDateEpochDay = null,
        notes = "",
        marketValueMinor = 999_999,
        priceCurrency = "EUR",
        priceUpdatedAtEpochMillis = 123,
        externalProductSource = "CARDMARKET_PUBLIC_DOWNLOAD",
        externalProductId = 42,
        priceCardId = id,
        imageUrl = null,
        binderId = "fixture",
        rarity = "UNKNOWN",
        effectOverride = null,
        sourceSetId = "fixture",
        sourceCardId = null,
    )
}
