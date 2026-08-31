package com.dominik.cardvault.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SoftConfirmationSoundTest {
    @Test fun doubleToneIsShortQuietAndContainsASilentGap() {
        val sampleRate = 16_000
        val pcm = buildSoftConfirmationPcm(sampleRate)
        val toneSamples = sampleRate * 82 / 1_000
        val gapSamples = sampleRate * 78 / 1_000

        assertEquals(sampleRate * (82 * 2 + 78 + 18) / 1_000, pcm.size)
        assertTrue(pcm.maxOf { abs(it.toInt()) } < Short.MAX_VALUE * .11)
        assertTrue(pcm.copyOfRange(toneSamples, toneSamples + gapSamples).all { it == 0.toShort() })
        assertTrue(pcm.take(toneSamples).any { it != 0.toShort() })
        assertTrue(pcm.drop(toneSamples + gapSamples).any { it != 0.toShort() })
    }
}
