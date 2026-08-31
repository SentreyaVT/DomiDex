package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BinderPresentationStateTest {

    @Test
    fun fullMotionUsesOrderedNonBlockingPresentationBeats() {
        val beats = binderPresentationBeats(motionEnabled = true)

        assertEquals(
            listOf(
                BinderPresentationStage.SELECTED,
                BinderPresentationStage.APPROACHING,
                BinderPresentationStage.UNLATCHING,
                BinderPresentationStage.OPENING,
                BinderPresentationStage.READY,
            ),
            beats.map { it.stage },
        )
        assertTrue(beats.all { it.durationMillis in 1L..400L })
        assertTrue(beats.sumOf { it.durationMillis } < 1_000L)
    }

    @Test
    fun reducedMotionJumpsDirectlyToReady() {
        assertEquals(
            listOf(BinderPresentationBeat(BinderPresentationStage.READY, 0L)),
            binderPresentationBeats(motionEnabled = false),
        )
    }
}
