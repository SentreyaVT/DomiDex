package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionValueOrderTest {
    @Test fun `collection sorts by displayed reliable total and keeps missing prices last`() {
        val missingHighRawValue = card(1, "Ohne Preis", 999.0, quantity = 5, priced = false)
        val highUnitValue = card(2, "Hoher Einzelwert", 30.0, quantity = 1, priced = true)
        val highestDisplayedTotal = card(3, "Höchster Anzeigewert", 12.0, quantity = 3, priced = true)
        val zeroButAvailable = card(4, "Nullpreis", 0.0, quantity = 1, priced = true)
        val anotherMissingPrice = card(5, "Noch offen", 0.0, quantity = 1, priced = false)

        val ordered = collectionCardsByDisplayedMarketValue(
            listOf(missingHighRawValue, highUnitValue, anotherMissingPrice, zeroButAvailable, highestDisplayedTotal),
        )

        assertEquals(
            listOf(highestDisplayedTotal, highUnitValue, zeroButAvailable, anotherMissingPrice, missingHighRawValue),
            ordered,
        )
    }

    private fun card(
        entryId: Long,
        name: String,
        value: Double,
        quantity: Int,
        priced: Boolean,
    ) = CollectionCard(
        entryId = entryId,
        id = "card-$entryId",
        name = name,
        set = "Testset",
        number = entryId.toString().padStart(3, '0'),
        language = CardLanguage.GERMAN,
        finish = "Normal",
        variant = "Standard",
        condition = "Near Mint",
        quantity = quantity,
        currentValueEur = value,
        changePercent = 0.0,
        priceUpdatedAtEpochMillis = if (priced) 1L else null,
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        accent = 0L,
    )
}
