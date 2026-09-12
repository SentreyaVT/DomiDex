package com.dominik.cardvault.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateBinderVisualEngineTest {
    @Test fun `catalog exposes stable unique core choices`() {
        val coverIds = PrivateBinderVisualEngine.coverThemes.map { it.id }
        val holoIds = PrivateBinderVisualEngine.holoEffects.map { it.id }

        assertEquals(20, coverIds.size)
        assertEquals(coverIds.size, coverIds.toSet().size)
        assertEquals(32, holoIds.size)
        assertEquals(holoIds.size, holoIds.toSet().size)
        assertTrue(PrivateBinderVisualEngine.DEFAULT_COVER_ID in coverIds)
        assertTrue(PrivateBinderVisualEngine.NORMAL_HOLO_ID in holoIds)
        assertTrue(PrivateBinderVisualEngine.coverThemes.all { it.description.isNotBlank() })
        assertTrue(PrivateBinderVisualEngine.holoEffects.all { it.description.isNotBlank() })
    }

    @Test fun `holo catalog retains every stable core id`() {
        val ids = PrivateBinderVisualEngine.holoEffects.map { it.id }.toSet()
        assertTrue(
            ids.containsAll(
                setOf(
                    "none", "classic_holo", "rainbow", "linear", "diagonal", "galaxy", "nebula",
                    "aurora", "crystal", "prism", "diamond", "stardust", "bubble", "oil_slick",
                    "liquid_metal", "hyper_wave", "fire", "ice", "pixel", "void", "holo_spark",
                ),
            ),
        )
    }

    @Test fun `holo catalog contains every supported card element`() {
        val ids = PrivateBinderVisualEngine.holoEffects.map { it.id }.toSet()

        assertTrue(
            ids.containsAll(
                setOf(
                    "element_grass", "element_fire", "element_water", "element_electric",
                    "element_psychic", "element_fighting", "element_darkness", "element_metal",
                    "element_dragon", "element_colorless", "element_fairy",
                ),
            ),
        )
    }

    @Test fun `none holo keeps its stable id and uses an unambiguous presentation label`() {
        val none = PrivateBinderVisualEngine.holoEffect("none")

        assertEquals("none", none.id)
        assertEquals("Kein Effekt", none.displayName)
        assertEquals(HoloPattern.NONE, none.pattern)
    }

    @Test fun `unknown values fail safe to default cover and normal card`() {
        assertEquals(PrivateBinderVisualEngine.DEFAULT_COVER_ID, PrivateBinderVisualEngine.coverTheme("missing").id)
        assertEquals(PrivateBinderVisualEngine.DEFAULT_COVER_ID, PrivateBinderVisualEngine.coverTheme(null).id)
        assertEquals(PrivateBinderVisualEngine.DEFAULT_COVER_ID, PrivateBinderVisualEngine.coverTheme("DOMIDEX").id)
        assertEquals(PrivateBinderVisualEngine.DEFAULT_COVER_ID, PrivateBinderVisualEngine.coverTheme("default").id)
        assertEquals(PrivateBinderVisualEngine.NORMAL_HOLO_ID, PrivateBinderVisualEngine.holoEffect("missing").id)
        assertEquals(PrivateBinderVisualEngine.NORMAL_HOLO_ID, PrivateBinderVisualEngine.holoEffect("normal").id)
        assertEquals(HoloPattern.NONE, PrivateBinderVisualEngine.holoEffect(null).pattern)
    }

    @Test fun `known ids are normalized without changing their stable identity`() {
        assertEquals("aurora", PrivateBinderVisualEngine.coverTheme("  AURORA ").id)
        assertEquals("classic_holo", PrivateBinderVisualEngine.holoEffect(" CLASSIC_HOLO ").id)
    }

    @Test fun `render plan clamps intensity and tilt`() {
        val plan = PrivateBinderVisualEngine.renderPlan(
            PrivateBinderVisualPreferences(intensity = 4f, maxTiltDegrees = 20f),
        )

        assertEquals(1f, plan.intensity, 0f)
        assertEquals(PrivateBinderVisualEngine.MAX_TILT_DEGREES, plan.maxTiltDegrees, 0f)
        assertTrue(plan.animate)
    }

    @Test fun `reduced motion is static and has no touch tilt`() {
        val plan = PrivateBinderVisualEngine.renderPlan(
            PrivateBinderVisualPreferences(reduceMotion = true),
        )

        assertFalse(plan.animate)
        assertEquals(0f, plan.maxTiltDegrees, 0f)
    }

    @Test fun `low quality keeps a bounded primitive budget`() {
        val plan = PrivateBinderVisualEngine.renderPlan(
            PrivateBinderVisualPreferences(quality = PrivateBinderVisualQuality.LOW),
        )

        assertTrue(plan.animate)
        assertEquals(3, plan.primitiveBudget)
        assertEquals(4_600, plan.durationMillis)
    }

    @Test fun `visual quality degrades predictably for constrained devices`() {
        assertEquals(PrivateBinderVisualQuality.LOW, privateBinderVisualQuality(isLowRamDevice = true, memoryClassMb = 768))
        assertEquals(PrivateBinderVisualQuality.LOW, privateBinderVisualQuality(isLowRamDevice = false, memoryClassMb = 192))
        assertEquals(PrivateBinderVisualQuality.MEDIUM, privateBinderVisualQuality(isLowRamDevice = false, memoryClassMb = 384))
        assertEquals(PrivateBinderVisualQuality.HIGH, privateBinderVisualQuality(isLowRamDevice = false, memoryClassMb = 512))
    }

    @Test fun `stored holo intensity maps only to the three supported presets`() {
        assertEquals(PrivateBinderHoloIntensityPreset.SOFT, PrivateBinderHoloIntensityPreset.fromStored(0f))
        assertEquals(PrivateBinderHoloIntensityPreset.SOFT, PrivateBinderHoloIntensityPreset.fromStored(.52f))
        assertEquals(PrivateBinderHoloIntensityPreset.NORMAL, PrivateBinderHoloIntensityPreset.fromStored(.68f))
        assertEquals(PrivateBinderHoloIntensityPreset.INTENSE, PrivateBinderHoloIntensityPreset.fromStored(.82f))
        assertEquals(PrivateBinderHoloIntensityPreset.INTENSE, PrivateBinderHoloIntensityPreset.fromStored(1f))
    }

    @Test fun `touch tilt is responsive clamped and centered`() {
        val size = IntSize(200, 400)
        assertEquals(PrivateBinderTilt(0f, 0f), privateBinderTouchTilt(Offset(100f, 200f), size, 3.5f))
        assertEquals(PrivateBinderTilt(3.5f, -3.5f), privateBinderTouchTilt(Offset(0f, 0f), size, 3.5f))
        assertEquals(PrivateBinderTilt(-8f, 8f), privateBinderTouchTilt(Offset(900f, 900f), size, 50f))
    }

    @Test fun `invalid numeric preferences fall back safely`() {
        val plan = PrivateBinderVisualEngine.renderPlan(
            PrivateBinderVisualPreferences(intensity = Float.NaN, maxTiltDegrees = Float.POSITIVE_INFINITY),
        )

        assertEquals(0f, plan.intensity, 0f)
        assertEquals(0f, plan.maxTiltDegrees, 0f)
        assertFalse(plan.animate)
    }
}
