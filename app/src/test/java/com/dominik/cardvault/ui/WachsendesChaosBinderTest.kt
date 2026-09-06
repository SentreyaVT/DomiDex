package com.dominik.cardvault.ui

import com.dominik.cardvault.data.WachsendesChaosMasterSet
import com.dominik.cardvault.data.catalog.PokemonPrintVariant
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WachsendesChaosBinderTest {
    @Test
    fun `all 194 fixed slots keep canonical source identity and order`() {
        val slots = emptySlots()

        assertEquals(194, slots.size)
        assertEquals(
            WachsendesChaosMasterSet.cards.map { listOf(it.sourceSetId, it.sourceCardId, it.number, it.finish, it.variant) },
            slots.map { listOf(it.sourceSetId, it.sourceCardId, it.number, it.finish, it.variant) },
        )
        assertSlot(slots, "me04-001", "Normal", 0)
        assertSlot(slots, "me04-050", "Normal", 90)
        assertSlot(slots, "me04-087", "Holo", 158)
        assertSlot(slots, "me04-122", "Holo", 193, "Standard · Folie: Gold")
    }

    @Test
    fun `fixed binder exposes 122 source cards while live details load`() {
        val cards = wachsendesChaosSourceCards(SET_NAME)

        assertEquals(122, cards.size)
        assertEquals((1..122).map { "me04-${it.toString().padStart(3, '0')}" }, cards.map { it.id })
        assertEquals(listOf("Normal", "Reverse Holo"), cards.first().printVariants.map { it.finish })
        assertEquals(listOf("Holo"), cards.last().printVariants.map { it.finish })
        assertEquals(193, cards.flatMap { it.printVariants }.count { it.variant == "Standard" })
        assertEquals("Standard · Folie: Gold", cards.last().printVariants.single().variant)
    }

    @Test
    fun `owned early middle late and legacy cards replace only their fixed slots`() {
        val references = emptySlots()
        val early = references[0].owned(11)
        val middle = references[112].owned(12)
        val late = references.last().owned(13)
        val legacy = references[158].owned(14).copy(sourceSetId = null, sourceCardId = null)
        val overflow = references[0].owned(15).copy(
            id = "me04-999-owned", name = "Nicht zugeordnete Karte", number = "999/122",
            sourceCardId = "me04-999",
        )

        val slots = arrangeBinderCards(SET_NAME, listOf(late, overflow, early, legacy, middle), 194).filterNotNull()

        assertEquals(195, slots.size)
        assertEquals(11L, slots[0].entryId)
        assertEquals(12L, slots[112].entryId)
        assertEquals(14L, slots[158].entryId)
        assertEquals("me04", slots[158].sourceSetId)
        assertEquals("me04-087", slots[158].sourceCardId)
        assertEquals(13L, slots[193].entryId)
        assertEquals(15L, slots.last().entryId)
        assertEquals(5, slots.count { it.entryId > 0L })
    }

    @Test
    fun `an additional owned copy remains visible as overflow without duplicating a fixed slot`() {
        val reference = emptySlots().first()
        val first = reference.owned(21)
        val second = reference.owned(22).copy(id = "second-condition", condition = "Gespielt")

        val slots = arrangeBinderCards(SET_NAME, listOf(first, second), 194).filterNotNull()

        assertEquals(195, slots.size)
        assertEquals(21L, slots.first().entryId)
        assertEquals(22L, slots.last().entryId)
        assertEquals(2, slots.count { it.entryId > 0L })
    }

    @Test
    fun `a stamped printing remains overflow and cannot replace the ordinary master slot`() {
        val reference = emptySlots().first { it.sourceCardId == "me04-010" && it.finish == "Holo" }
        val stamped = reference.owned(23).copy(variant = "Standard · Stempel: GameStop")

        val slots = arrangeBinderCards(SET_NAME, listOf(stamped), 194).filterNotNull()

        assertEquals(195, slots.size)
        assertEquals(0L, slots.first { it.id == reference.id }.entryId)
        assertEquals(23L, slots.last().entryId)
        assertEquals("Standard · Stempel: GameStop", slots.last().variant)
    }

    @Test
    fun `a build 17 standard Mega Quajutsu row adopts the corrected gold slot without migration`() {
        val reference = emptySlots().last()
        val legacy = reference.owned(24).copy(variant = "Standard")

        val slots = arrangeBinderCards(SET_NAME, listOf(legacy), 194).filterNotNull()

        assertEquals(194, slots.size)
        assertEquals(24L, slots.last().entryId)
        assertEquals("Holo", slots.last().finish)
        assertEquals("Standard · Folie: Gold", slots.last().variant)
    }

    @Test
    fun `early reverse and late holo slots resolve only their declared Cardmarket product`() {
        val slots = emptySlots()
        val normal = slots.first { it.sourceCardId == "me04-001" && it.finish == "Normal" }
        val reverse = slots.first { it.sourceCardId == "me04-001" && it.finish == "Reverse Holo" }
        val secret = slots.first { it.sourceCardId == "me04-122" }

        assertResolvedPrice(normal, productId = 886393L, expectedPrice = 0.86, reversePrice = 0.08)
        assertResolvedPrice(reverse, productId = 886393L, expectedPrice = 0.08, reversePrice = 0.08)
        assertResolvedPrice(secret, productId = 886515L, expectedPrice = 129.70, reversePrice = null)
    }

    @Test
    fun `a source product for another printing is never borrowed`() {
        val reverse = emptySlots().first { it.sourceCardId == "me04-001" && it.finish == "Reverse Holo" }
        val source = sourceCard(reverse, 886393L).copy(
            printVariants = listOf(PokemonPrintVariant("normal", "Normal", "Standard", 886393L)),
        )
        val candidate = marketProduct(reverse, 886393L, 0.86, 0.08)

        assertTrue(binderReferenceCardmarketProducts(reverse, source, listOf(source), listOf(candidate)).isEmpty())
    }

    private fun assertResolvedPrice(reference: CollectionCard, productId: Long, expectedPrice: Double, reversePrice: Double?) {
        val source = sourceCard(reference, productId)
        val candidate = marketProduct(reference, productId, expectedPrice = if (reference.finish.contains("reverse", true)) 0.86 else expectedPrice, reversePrice = reversePrice)
        val result = binderReferenceCardmarketProducts(reference, source, listOf(source), listOf(candidate)).singleOrNull()

        assertNotNull(result)
        assertEquals(productId, result!!.productId)
        assertEquals(reference.sourceSetId, result.sourceSetId)
        assertEquals(reference.sourceCardId, result.sourceCardId)
        assertEquals(reference.finish, result.finish)
        assertEquals(reference.variant, result.variant)
        assertEquals(expectedPrice, result.trendPriceEur!!, 0.0001)
    }

    private fun sourceCard(reference: CollectionCard, productId: Long): PokemonVisualCard = PokemonVisualCard(
        id = reference.sourceCardId!!,
        name = reference.name,
        number = reference.number.substringBefore('/'),
        rarity = null,
        smallImageUrl = reference.imageUrl.orEmpty(),
        largeImageUrl = reference.imageUrl.orEmpty(),
        set = PokemonSet("me04", SET_NAME, "Mega-Entwicklung", 86, 122, "", null, "CRI"),
        printVariants = listOf(PokemonPrintVariant("declared-$productId", reference.finish, reference.variant, productId)),
    )

    private fun marketProduct(reference: CollectionCard, productId: Long, expectedPrice: Double, reversePrice: Double?) = CatalogSearchResult(
        productId = productId,
        name = reference.name,
        expansionId = "chaos-market",
        trendPriceEur = expectedPrice,
        currency = "EUR",
        visualSetName = SET_NAME,
        collectorNumber = reference.number,
        finish = reference.finish,
        variant = reference.variant,
        holoTrendPriceEur = reversePrice,
    )

    private fun emptySlots(): List<CollectionCard> =
        arrangeBinderCards(SET_NAME, emptyList(), 194).filterNotNull()

    private fun CollectionCard.owned(entry: Long) = copy(
        entryId = entry,
        id = "owned-$entry",
        quantity = 1,
        condition = "Nicht geprüft",
        binderId = "tcgdex-me04",
    )

    private fun assertSlot(
        slots: List<CollectionCard>, sourceCardId: String, finish: String, index: Int, variant: String = "Standard",
    ) {
        val slot = slots[index]
        assertEquals(sourceCardId, slot.sourceCardId)
        assertEquals(finish, slot.finish)
        assertEquals(variant, slot.variant)
        assertEquals(CardLanguage.GERMAN, slot.language)
    }

    private companion object {
        const val SET_NAME = "Wachsendes Chaos"
    }
}
