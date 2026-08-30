package com.dominik.cardvault

import com.dominik.cardvault.data.sync.CardmarketParsers
import com.dominik.cardvault.data.sync.guideValueMinor
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class CardmarketParsersTest {
    @Test fun parsesOfficialProductEnvelopeAndIgnoresUnknownFields() {
        val json = """{"version":1,"createdAt":"2026-08-16T00:49:33Z","products":[{"idProduct":273532,"name":"Weedle [Multiply]","idCategory":51,"idExpansion":1585,"idMetacard":340471,"future":"ignored"}]}"""
        val result = CardmarketParsers.products(Buffer().writeUtf8(json))
        assertEquals("2026-08-16T00:49:33Z", result.createdAt)
        assertEquals(273532L, result.records.single().id)
        assertEquals(1585L, result.records.single().expansionId)
    }

    @Test fun parsesPricesToExactEuroCentsIncludingNullableHoloFields() {
        val json = """{"version":1,"createdAt":"2026-08-16T00:49:33Z","priceGuides":[{"idProduct":271439,"idCategory":52,"avg":99.99,"low":85,"trend":344.42,"avg1":null,"avg7":1.2,"avg30":1.235,"avg-holo":null,"low-holo":null,"trend-holo":3.93}]}"""
        val price = CardmarketParsers.prices(Buffer().writeUtf8(json)).records.single()
        assertEquals(9999L, price.averageMinor)
        assertEquals(34442L, price.trendMinor)
        assertEquals(124L, price.average30Minor)
        assertNull(price.holoAverageMinor)
        assertEquals(393L, price.holoTrendMinor)
    }

    @Test fun priceGuideUsesOnlyTrendWithoutAverageOrLowFallback() {
        val prices = CardmarketParsers.prices(Buffer().writeUtf8("""{"version":1,"priceGuides":[
            {"idProduct":10,"idCategory":52,"avg":12.5,"low":8.0,"trend":null,"avg7":13.25,"avg30":14.0},
            {"idProduct":11,"idCategory":52,"avg":12.5,"low":8.0,"trend":15.0,"avg7":13.25,"avg30":14.0}
        ]}""")).records
        assertNull(prices.first().guideValueMinor())
        assertEquals(1500L, prices.last().guideValueMinor())
    }
}
