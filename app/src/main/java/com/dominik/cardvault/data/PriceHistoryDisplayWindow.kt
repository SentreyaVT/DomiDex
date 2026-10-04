package com.dominik.cardvault.data

import com.dominik.cardvault.model.PriceHistoryWindowPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow

/**
 * Read-only display clock, refreshed while observed (including an app left open overnight).
 * This device clock is never a trusted timestamp for destructive storage maintenance.
 * The application shares one state so cards, binders and portfolio use the same boundary.
 */
internal fun priceHistoryDisplayWindow(
    nowEpochMillis: () -> Long = System::currentTimeMillis,
    awaitRefresh: suspend (Long) -> Unit = { delay(it) },
): Flow<Long> = flow {
    while (true) {
        emit(PriceHistoryWindowPolicy.startEpochMillis(nowEpochMillis()))
        awaitRefresh(PRICE_HISTORY_DISPLAY_REFRESH_MILLIS)
    }
}.distinctUntilChanged()

internal const val PRICE_HISTORY_DISPLAY_REFRESH_MILLIS = 60 * 60 * 1000L
