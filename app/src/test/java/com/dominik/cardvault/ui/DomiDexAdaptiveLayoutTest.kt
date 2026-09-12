package com.dominik.cardvault.ui

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DomiDexAdaptiveLayoutTest {
    @Test
    fun `compact width stays unchanged`() {
        assertEquals(DomiDexWindowWidthClass.COMPACT, domiDexWindowWidthClass(599.dp))
        assertEquals(407.dp, domiDexContentWidth(407.dp))
        assertEquals(599.dp, domiDexContentWidth(599.dp))
    }

    @Test
    fun `medium width is centered within phone friendly maximum`() {
        assertEquals(DomiDexWindowWidthClass.MEDIUM, domiDexWindowWidthClass(600.dp))
        assertEquals(600.dp, domiDexContentWidth(600.dp))
        assertEquals(720.dp, domiDexContentWidth(839.dp))
    }

    @Test
    fun `expanded width is capped instead of stretched`() {
        assertEquals(DomiDexWindowWidthClass.EXPANDED, domiDexWindowWidthClass(840.dp))
        assertEquals(840.dp, domiDexContentWidth(840.dp))
        assertEquals(960.dp, domiDexContentWidth(1_280.dp))
    }

    @Test
    fun `separating vertical hinge selects an unobstructed pane`() {
        assertEquals(
            DomiDexHorizontalPane(start = 0.dp, width = 640.dp),
            domiDexHorizontalPane(windowWidth = 1_300.dp, hingeStart = 640.dp, hingeEnd = 660.dp),
        )
        assertEquals(
            DomiDexHorizontalPane(start = 420.dp, width = 580.dp),
            domiDexHorizontalPane(windowWidth = 1_000.dp, hingeStart = 400.dp, hingeEnd = 420.dp),
        )
    }

    @Test
    fun `invalid or absent hinge keeps the complete window`() {
        assertEquals(DomiDexHorizontalPane(0.dp, 800.dp), domiDexHorizontalPane(800.dp, null, null))
        assertEquals(DomiDexHorizontalPane(0.dp, 800.dp), domiDexHorizontalPane(800.dp, 0.dp, 0.dp))
        assertEquals(DomiDexHorizontalPane(0.dp, 800.dp), domiDexHorizontalPane(800.dp, 700.dp, 900.dp))
    }

    @Test
    fun `dialog center is shifted completely into the selected fold pane`() {
        assertEquals(
            (-330).dp,
            domiDexDialogHorizontalOffset(1_300.dp, DomiDexHorizontalPane(0.dp, 640.dp)),
        )
        assertEquals(
            210.dp,
            domiDexDialogHorizontalOffset(1_000.dp, DomiDexHorizontalPane(420.dp, 580.dp)),
        )
    }
}
