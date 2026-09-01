package com.dominik.cardvault.data

import com.dominik.cardvault.data.local.CollectionEntryEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class BinderIdentityTest {
    @Test
    fun `cardmarket expansion id becomes the stable binder id while display text is irrelevant`() {
        assertEquals("cardmarket-6209", binderSetKey("6209", "Mega-Entwicklung"))
        assertEquals("cardmarket-6209", binderSetKey("6209", "Mega Evolution"))
        assertEquals("cardmarket-6209", binderIdForSetKey("cardmarket-6209"))
    }

    @Test
    fun `legacy manual set keeps its existing localized fallback key`() {
        assertEquals("prismatische-entwicklungen", binderSetKey(null, "Prismatische Entwicklungen"))
        assertEquals("set-prismatische-entwicklungen", binderIdForSetKey("prismatische-entwicklungen"))
    }

    @Test
    fun `display labels can never masquerade as a Cardmarket expansion id`() {
        assertEquals("prismatische-entwicklungen", binderSetKey("Prismatische Entwicklungen", "Prismatische Entwicklungen"))
    }

    @Test
    fun `tcgdex source id wins and cached foil slot restores canonical card id`() {
        assertEquals("tcgdex-sv08-5", binderSetKey("6209", "Beliebiger Name", "sv08-5"))
        assertEquals("tcgdex-sv08-5", binderIdForSetKey("tcgdex-sv08-5"))
        assertEquals(
            "sv08-5-001",
            sourceCardIdFromCatalogReference("tcgdex-sv08-5-sv08-5-001-reverseholofoil", "sv08-5"),
        )
    }

    @Test
    fun `sync successor is stable across client timestamps and never reuses tombstone id`() {
        val first = occupied(updatedAt = 100, deletedAt = 90)
        val second = occupied(updatedAt = 999, deletedAt = 800)
        val successor = deterministicSuccessorSyncId("base", first)
        assertEquals(successor, deterministicSuccessorSyncId("base", second))
        assert(successor != "base")
    }

    private fun occupied(updatedAt: Long, deletedAt: Long?) = CollectionEntryEntity(
        cardId = "card",
        quantity = 1,
        condition = "Gut",
        acquisitionPriceMinor = null,
        acquisitionCurrency = null,
        acquisitionDateEpochDay = null,
        notes = "",
        frontPhotoUri = null,
        backPhotoUri = null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = updatedAt,
        remoteRevision = 3,
        deletedAtEpochMillis = deletedAt,
    )
}
