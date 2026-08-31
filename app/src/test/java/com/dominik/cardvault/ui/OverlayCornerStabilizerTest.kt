package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayCornerStabilizerTest {
    private val initial = listOf(.10f to .10f, .90f to .10f, .90f to .90f, .10f to .90f)

    @Test fun tinyDetectorJitterIsNotShown() {
        val stabilizer = OverlayCornerStabilizer()
        assertEquals(initial, stabilizer.update(initial, locked = false))

        val jittered = initial.map { (x, y) -> x + .002f to y - .002f }
        assertEquals(initial, stabilizer.update(jittered, locked = false))
    }

    @Test fun largeMovementUsesBoundedVisualSteps() {
        val stabilizer = OverlayCornerStabilizer(maxStep = .018f)
        stabilizer.update(initial, locked = false)
        val moved = initial.map { (x, y) -> x + .20f to y + .20f }
        val displayed = stabilizer.update(moved, locked = false)

        displayed.zip(initial).forEach { (fresh, old) ->
            assertTrue(fresh.first - old.first <= .0181f)
            assertTrue(fresh.second - old.second <= .0181f)
        }
    }

    @Test fun greenReadyStateFreezesGuideAndMissingCardEventuallyClearsIt() {
        val stabilizer = OverlayCornerStabilizer(missingFramesToClear = 3)
        stabilizer.update(initial, locked = false)
        val farAway = initial.map { (x, y) -> x + .20f to y + .20f }

        assertEquals(initial, stabilizer.update(farAway, locked = true))
        repeat(2) { assertEquals(initial, stabilizer.update(emptyList(), locked = false)) }
        assertTrue(stabilizer.update(emptyList(), locked = false).isEmpty())
    }
}
