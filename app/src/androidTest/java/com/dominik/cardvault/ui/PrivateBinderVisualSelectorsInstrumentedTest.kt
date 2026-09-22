package com.dominik.cardvault.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class PrivateBinderVisualSelectorsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun coverSelectorIsVisible() {
        compose.setContent {
            MaterialTheme {
                PrivateBinderCoverSelectorDialog(
                    initialThemeId = "nebula",
                    animationsEnabled = false,
                    onDismiss = {},
                    onConfirm = {},
                )
            }
        }

        compose.onNodeWithText("Binder-Design").assertIsDisplayed()
        compose.onAllNodesWithText("DomiDex Nebula", useUnmergedTree = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Abbrechen").assertIsDisplayed()
        compose.onNodeWithText("Übernehmen").assertIsDisplayed()
    }

    @Test fun coverSelectorCancelDoesNotApply() {
        var dismissed = false
        var appliedId: String? = null
        compose.setContent {
            MaterialTheme {
                PrivateBinderCoverSelectorDialog(
                    initialThemeId = "nebula",
                    animationsEnabled = false,
                    onDismiss = { dismissed = true },
                    onConfirm = { appliedId = it },
                )
            }
        }

        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertNull(appliedId)
        }
    }

    @Test fun coverSelectorAppliesSelectedStableId() {
        var appliedId: String? = null
        compose.setContent {
            MaterialTheme {
                PrivateBinderCoverSelectorDialog(
                    initialThemeId = "nebula",
                    animationsEnabled = false,
                    onDismiss = {},
                    onConfirm = { appliedId = it },
                )
            }
        }

        compose.onNodeWithContentDescription("Cover Classic Rainbow").performClick()
        compose.onNodeWithText("Übernehmen").performClick()
        compose.runOnIdle { assertEquals("classic_rainbow", appliedId) }
    }

    @Test fun holoSelectorIsVisible() {
        compose.setContent {
            MaterialTheme {
                PrivateBinderHoloSelectorDialog(
                    placementId = "placement-visible",
                    card = testCard(),
                    initialEffectId = null,
                    initialIntensity = 0f,
                    animationsEnabled = false,
                    saving = false,
                    error = null,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        compose.onNodeWithText("Holo-Effekt wählen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Interaktive Holo-Vorschau").assertIsDisplayed()
        compose.onNodeWithText("Abbrechen").assertIsDisplayed()
        compose.onNodeWithText("Übernehmen").assertIsDisplayed()
    }

    @Test fun holoSelectorCancelDoesNotApply() {
        var dismissed = false
        var applyCount = 0
        compose.setContent {
            MaterialTheme {
                PrivateBinderHoloSelectorDialog(
                    placementId = "placement-cancel",
                    card = testCard(),
                    initialEffectId = null,
                    initialIntensity = 0f,
                    animationsEnabled = false,
                    saving = false,
                    error = null,
                    onDismiss = { dismissed = true },
                    onConfirm = { _, _ -> applyCount++ },
                )
            }
        }

        compose.onNodeWithText("Abbrechen").performClick()
        compose.runOnIdle {
            assertTrue(dismissed)
            assertEquals(0, applyCount)
        }
    }

    @Test fun holoSelectorAppliesEffectAndIntensity() {
        var appliedEffectId: String? = null
        var appliedIntensity = -1f
        compose.setContent {
            MaterialTheme {
                PrivateBinderHoloSelectorDialog(
                    placementId = "placement-apply",
                    card = testCard(),
                    initialEffectId = null,
                    initialIntensity = 0f,
                    animationsEnabled = false,
                    saving = false,
                    error = null,
                    onDismiss = {},
                    onConfirm = { effectId, intensity ->
                        appliedEffectId = effectId
                        appliedIntensity = intensity
                    },
                )
            }
        }

        compose.onNodeWithContentDescription("Holo Classic Holo").performClick()
        compose.onNodeWithText("Intensiv").performClick()
        compose.onNodeWithText("Übernehmen").performClick()
        compose.runOnIdle {
            assertEquals("classic_holo", appliedEffectId)
            assertEquals(PrivateBinderHoloIntensityPreset.INTENSE.storedValue, appliedIntensity, 0f)
        }
    }

    @Test fun selectedHoloProducesAVisibleDeterministicOverlay() {
        compose.setContent {
            MaterialTheme {
                Row {
                    Box(
                        Modifier
                            .size(width = 126.dp, height = 176.dp)
                            .background(HOLO_TEST_BACKGROUND)
                            .testTag("holo-baseline"),
                    )
                    Box(
                        Modifier
                            .size(width = 126.dp, height = 176.dp)
                            .background(HOLO_TEST_BACKGROUND)
                            .testTag("holo-rendered"),
                    ) {
                        PrivateBinderStaticHoloLayer(
                            effectId = "classic_holo",
                            intensity = 1f,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        compose.waitForIdle()
        val baseline = compose.onNodeWithTag("holo-baseline").captureToImage().toPixelMap()
        val rendered = compose.onNodeWithTag("holo-rendered").captureToImage().toPixelMap()
        assertEquals(baseline.width, rendered.width)
        assertEquals(baseline.height, rendered.height)

        var visiblyChangedPixels = 0
        for (y in 0 until rendered.height) {
            for (x in 0 until rendered.width) {
                if (rgbDistance(baseline[x, y], rendered[x, y]) >= .08f) visiblyChangedPixels++
            }
        }
        assertTrue(
            "Holo overlay rendered no meaningful shimmer: $visiblyChangedPixels changed pixels",
            visiblyChangedPixels >= rendered.width * rendered.height / 20,
        )
    }

    @Test fun maxIntensityKeepsVisibleChromaOnBrightArtwork() {
        compose.setContent {
            MaterialTheme {
                Row {
                    Box(
                        Modifier
                            .size(width = 126.dp, height = 176.dp)
                            .background(HOLO_TEST_BRIGHT_BACKGROUND)
                            .testTag("holo-bright-baseline"),
                    )
                    Box(
                        Modifier
                            .size(width = 126.dp, height = 176.dp)
                            .background(HOLO_TEST_BRIGHT_BACKGROUND)
                            .testTag("holo-bright-rendered"),
                    ) {
                        PrivateBinderStaticHoloLayer(
                            effectId = "element_psychic",
                            intensity = 1f,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }

        compose.waitForIdle()
        val baseline = compose.onNodeWithTag("holo-bright-baseline").captureToImage().toPixelMap()
        val rendered = compose.onNodeWithTag("holo-bright-rendered").captureToImage().toPixelMap()
        var visiblyChromaticPixels = 0
        for (y in 0 until rendered.height) {
            for (x in 0 until rendered.width) {
                val pixel = rendered[x, y]
                val channelSpread = maxOf(pixel.red, pixel.green, pixel.blue) -
                    minOf(pixel.red, pixel.green, pixel.blue)
                if (channelSpread >= .08f && rgbDistance(baseline[x, y], pixel) >= .08f) {
                    visiblyChromaticPixels++
                }
            }
        }
        assertTrue(
            "Maximum Holo intensity lost its color on bright artwork: $visiblyChromaticPixels pixels",
            visiblyChromaticPixels >= rendered.width * rendered.height / 100,
        )
    }

    @Test fun holoRemainsStillWithoutCardMotion() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .size(width = 126.dp, height = 176.dp)
                        .background(HOLO_TEST_BACKGROUND)
                        .testTag("stationary-holo"),
                ) {
                    PrivateBinderHoloLayer(
                        effectId = "rainbow",
                        modifier = Modifier.fillMaxSize(),
                        preferences = PrivateBinderVisualPreferences(intensity = 1f),
                        tiltState = null,
                    )
                }
            }
        }

        compose.mainClock.advanceTimeByFrame()
        val firstImage = compose.onNodeWithTag("stationary-holo").captureToImage()
        val first = firstImage.toPixelMap()
        compose.mainClock.advanceTimeBy(1_250L)
        val secondImage = compose.onNodeWithTag("stationary-holo").captureToImage()
        val second = secondImage.toPixelMap()
        var changedPixels = 0
        val changedPixelDetails = mutableListOf<String>()
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (rgbDistance(first[x, y], second[x, y]) >= .01f) {
                    changedPixels++
                    if (changedPixelDetails.size < 20) changedPixelDetails +=
                        "($x,$y): ${first[x, y]} -> ${second[x, y]}"
                }
            }
        }
        if (changedPixels != 0) {
            // Failure artifacts are synthetic test pixels only, never card photos or user data.
            val directory = java.io.File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "holo-test-diagnostics")
            directory.mkdirs()
            listOf("before.png" to firstImage, "after.png" to secondImage).forEach { (name, image) ->
                java.io.File(directory, name).outputStream().use {
                    image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            android.util.Log.w("DomiDexHoloTest", "${first.width}x${first.height}; changed=$changedPixels; ${changedPixelDetails.joinToString("; ")}")
        }
        assertEquals("Holo animated although its card was stationary", 0, changedPixels)
        compose.mainClock.autoAdvance = true
    }

    @Test fun interactiveCardAndHoloRemainStillUntilTheCardIsMoved() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                PrivateBinderInteractiveCardVisual(
                    card = testCard(),
                    effectId = "rainbow",
                    intensity = 1f,
                    animationsEnabled = true,
                    modifier = Modifier.width(100.dp).testTag("stationary-interactive-card"),
                )
            }
        }

        compose.mainClock.advanceTimeByFrame()
        val first = compose.onNodeWithTag("stationary-interactive-card").captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(1_250L)
        val second = compose.onNodeWithTag("stationary-interactive-card").captureToImage().toPixelMap()
        var changedPixels = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (rgbDistance(first[x, y], second[x, y]) >= .01f) changedPixels++
            }
        }
        assertEquals("Interactive card or Holo moved without user input", 0, changedPixels)
        compose.mainClock.autoAdvance = true
    }

    @Test fun holoSelectorPreviewRemainsStillUntilTheCardIsMoved() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            MaterialTheme {
                PrivateBinderHoloSelectorDialog(
                    placementId = "placement-still-preview",
                    card = testCard(),
                    initialEffectId = "rainbow",
                    initialIntensity = 1f,
                    animationsEnabled = true,
                    saving = false,
                    error = null,
                    onDismiss = {},
                    onConfirm = { _, _ -> },
                )
            }
        }

        compose.mainClock.advanceTimeByFrame()
        val preview = compose.onNodeWithContentDescription("Interaktive Holo-Vorschau")
        val first = preview.captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(1_250L)
        val second = preview.captureToImage().toPixelMap()
        var changedPixels = 0
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                if (rgbDistance(first[x, y], second[x, y]) >= .01f) changedPixels++
            }
        }
        assertEquals("Holo selector preview moved without user input", 0, changedPixels)
        compose.mainClock.autoAdvance = true
    }

    @Test fun interactiveHoloStaysInsidePokemonCardBounds() {
        compose.setContent {
            MaterialTheme {
                Box(
                    Modifier
                        .size(220.dp)
                        .background(HOLO_TEST_OUTSIDE)
                        .testTag("holo-frame"),
                    contentAlignment = Alignment.Center,
                ) {
                    PrivateBinderInteractiveCardVisual(
                        card = testCard(),
                        effectId = "rainbow",
                        intensity = 1f,
                        animationsEnabled = false,
                        modifier = Modifier.width(100.dp).testTag("holo-card"),
                    )
                }
            }
        }

        compose.waitForIdle()
        val frameNode = compose.onNodeWithTag("holo-frame").fetchSemanticsNode()
        val cardNode = compose.onNodeWithTag("holo-card").fetchSemanticsNode()
        val frameBounds = frameNode.boundsInRoot
        val cardBounds = cardNode.boundsInRoot
        assertEquals(63f / 88f, cardBounds.width / cardBounds.height, .02f)

        val pixels = compose.onNodeWithTag("holo-frame").captureToImage().toPixelMap()
        val left = (cardBounds.left - frameBounds.left).roundToInt()
        val top = (cardBounds.top - frameBounds.top).roundToInt()
        val right = (cardBounds.right - frameBounds.left).roundToInt()
        val bottom = (cardBounds.bottom - frameBounds.top).roundToInt()
        val untouchedReference = pixels[0, 0]
        var changedOutsidePixels = 0
        var outsidePixels = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                // A two-pixel tolerance avoids counting antialiasing at the rounded card edge.
                val safelyOutside = x < left - 2 || x >= right + 2 || y < top - 2 || y >= bottom + 2
                if (safelyOutside) {
                    outsidePixels++
                    if (rgbDistance(untouchedReference, pixels[x, y]) >= .02f) changedOutsidePixels++
                }
            }
        }
        assertTrue("Test fixture did not expose an outside area", outsidePixels > 0)
        assertEquals("Holo pixels escaped the card bounds", 0, changedOutsidePixels)
    }

    private fun rgbDistance(first: Color, second: Color): Float =
        abs(first.red - second.red) + abs(first.green - second.green) + abs(first.blue - second.blue)

    private fun testCard() = CollectionCard(
        entryId = 19L,
        id = "test-card",
        name = "Testkarte",
        set = "Testset",
        number = "001/100",
        language = CardLanguage.GERMAN,
        finish = "Normal",
        variant = "Standard",
        condition = "Near Mint",
        quantity = 1,
        currentValueEur = 1.25,
        changePercent = 0.0,
        priceUpdatedAtEpochMillis = null,
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        accent = 0xFF7C3AED,
        imageUrl = null,
    )

    private companion object {
        val HOLO_TEST_BACKGROUND = Color(0xFF11131A)
        val HOLO_TEST_BRIGHT_BACKGROUND = Color(0xFFD9DBE2)
        val HOLO_TEST_OUTSIDE = Color(0xFF194427)
    }
}
