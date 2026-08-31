package com.dominik.cardvault.data

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
}
