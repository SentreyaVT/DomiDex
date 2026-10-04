package com.dominik.cardvault.data

import com.dominik.cardvault.model.PriceHistoryWindowPolicy
import java.time.Instant
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class PriceHistoryDisplayWindowTest {
    @Test fun emitsImmediatelyAndUsesAnExactCalendarWindow() = runBlocking { withTimeout(5_000) {
        val now = Instant.parse("2025-02-28T12:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2024-02-28T12:00:00Z").toEpochMilli(),
            priceHistoryDisplayWindow(nowEpochMillis = { now }).first())
    } }

    @Test fun longLivedObserverRefreshesAndCancellationStopsClockReads() = runBlocking { withTimeout(5_000) {
        val start = Instant.parse("2026-10-04T23:30:00Z").toEpochMilli()
        val values = mutableListOf<Long>()
        var reads = 0
        var now = start
        val waiting = Channel<Long>(Channel.UNLIMITED)
        val refresh = Channel<Unit>(Channel.UNLIMITED)
        val job = launch { priceHistoryDisplayWindow(
            nowEpochMillis = { reads++; now },
            awaitRefresh = { waiting.send(it); refresh.receive() },
        ).collect { values += it } }
        try {
            assertEquals(PRICE_HISTORY_DISPLAY_REFRESH_MILLIS, waiting.receive())
            assertEquals(listOf(PriceHistoryWindowPolicy.startEpochMillis(start)), values)
            now += PRICE_HISTORY_DISPLAY_REFRESH_MILLIS
            refresh.send(Unit)
            assertEquals(PRICE_HISTORY_DISPLAY_REFRESH_MILLIS, waiting.receive())
            assertEquals(listOf(PriceHistoryWindowPolicy.startEpochMillis(start),
                PriceHistoryWindowPolicy.startEpochMillis(now)), values)
            job.cancelAndJoin()
            now += 3 * PRICE_HISTORY_DISPLAY_REFRESH_MILLIS
            repeat(3) { refresh.send(Unit) }
            assertEquals(2, reads)
        } finally {
            job.cancelAndJoin()
            waiting.close()
            refresh.close()
        }
    } }

    @Test fun recollectingReadsFreshClockAndDoesNotRetainAnOldCutoff() = runBlocking { withTimeout(5_000) {
        var now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
        val window = priceHistoryDisplayWindow(nowEpochMillis = { now })
        assertEquals(PriceHistoryWindowPolicy.startEpochMillis(now), window.first())
        now += 86_400_000L
        assertEquals(PriceHistoryWindowPolicy.startEpochMillis(now), window.first())
    } }

    @Test fun repeatedClockDoesNotEmitDuplicateBoundaries() = runBlocking { withTimeout(5_000) {
        val now = Instant.parse("2026-10-04T12:00:00Z").toEpochMilli()
        val values = mutableListOf<Long>()
        val waiting = Channel<Unit>(Channel.UNLIMITED)
        val refresh = Channel<Unit>(Channel.UNLIMITED)
        val job = launch { priceHistoryDisplayWindow(
            nowEpochMillis = { now },
            awaitRefresh = { waiting.send(Unit); refresh.receive() },
        ).collect { values += it } }
        try {
            waiting.receive()
            repeat(3) { refresh.send(Unit); waiting.receive() }
            assertEquals(listOf(PriceHistoryWindowPolicy.startEpochMillis(now)), values)
        } finally {
            job.cancelAndJoin()
            waiting.close()
            refresh.close()
        }
    } }
}
