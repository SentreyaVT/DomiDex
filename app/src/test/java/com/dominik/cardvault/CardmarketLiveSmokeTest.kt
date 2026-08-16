package com.dominik.cardvault

import com.dominik.cardvault.data.sync.CardmarketParsers
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import okio.buffer
import okio.source
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URL

class CardmarketLiveSmokeTest {
    @Test fun officialDownloadsRemainParseableWhenExplicitlyEnabled() {
        assumeTrue("Opt-in only; regular tests stay offline", System.getProperty("cardvault.liveSmoke") == "true")
        URL(CardmarketSyncContract.PRODUCT_URL).openStream().source().buffer().use { assertTrue(CardmarketParsers.products(it).records.isNotEmpty()) }
        URL(CardmarketSyncContract.PRICE_URL).openStream().source().buffer().use { assertTrue(CardmarketParsers.prices(it).records.isNotEmpty()) }
    }
}
