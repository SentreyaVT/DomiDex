package com.dominik.cardvault.ui

import com.dominik.cardvault.model.BinderTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class PrivateBinderUiPolicyTest {
    @Test fun `target picker maps set and concrete private binder explicitly`() {
        assertEquals(BinderTarget.MatchingSetBinder, binderTarget(null))
        assertEquals(BinderTarget.PrivateBinder("favorite"), binderTarget("favorite"))
    }

    @Test fun `global positions map to stable three by three pages`() {
        assertEquals(0, privateBinderPageForPosition(0))
        assertEquals(0, privateBinderPageForPosition(8))
        assertEquals(1, privateBinderPageForPosition(9))
        assertEquals(8, privateBinderSlotForPosition(17))
        assertEquals(29L, privateBinderPosition(3, 2))
    }

    @Test fun `page count grows only as far as highest occupied position`() {
        assertEquals(1, privateBinderPageCount(emptyList()))
        assertEquals(1, privateBinderPageCount(listOf(0L, 8L)))
        assertEquals(4, privateBinderPageCount(listOf(0L, 31L)))
    }

    @Test fun `new assignment prefers a free slot on visible page`() {
        val occupied = setOf(0L, 1L, 9L, 10L)
        assertEquals(11L, privateBinderNextAvailablePosition(occupied, preferredPage = 1))
    }

    @Test fun `full preferred page grows forward without an artificial page limit`() {
        val occupied = (9L..17L).toSet() + setOf(0L, 1L, 3L)
        assertEquals(18L, privateBinderNextAvailablePosition(occupied, preferredPage = 1))
    }

    @Test fun `horizontal edge drag crosses pages and keeps the row`() {
        val forward = privateBinderDragDestination(8L, dragX = 110f, dragY = 0f, slotWidth = 100f, slotHeight = 100f, currentPageCount = 1)
        assertEquals(1, forward.page)
        assertEquals(6, forward.slot)
        assertEquals(15L, forward.position)

        val backward = privateBinderDragDestination(9L, dragX = -110f, dragY = 0f, slotWidth = 100f, slotHeight = 100f, currentPageCount = 2)
        assertEquals(0, backward.page)
        assertEquals(2, backward.slot)
        assertEquals(2L, backward.position)
    }

    @Test fun `vertical drag selects slot in same page`() {
        val destination = privateBinderDragDestination(10L, dragX = 0f, dragY = 105f, slotWidth = 100f, slotHeight = 100f, currentPageCount = 2)
        assertEquals(1, destination.page)
        assertEquals(4, destination.slot)
        assertEquals(13L, destination.position)
    }

    @Test fun `drag before the first page clamps without wrapping to the opposite edge`() {
        val destination = privateBinderDragDestination(0L, dragX = -110f, dragY = 0f, slotWidth = 100f, slotHeight = 100f, currentPageCount = 1)
        assertEquals(0, destination.page)
        assertEquals(0, destination.slot)
        assertEquals(0L, destination.position)
    }
}
