package com.dominik.cardvault.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
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

    @Test fun holoSelectorKeepsInteractivePreviewPinnedWhenSelectingLowerEffects() {
        assertPinnedHoloSelector(compactLargeText = false)
    }

    @Test fun compactLargeTextHoloSelectorKeepsPreviewAndActionsReachable() {
        assertPinnedHoloSelector(compactLargeText = true)
    }

    @Test fun anniversarySelectionRoundTripsItsStableIdAndIntensityWhenReopened() {
        val open = mutableStateOf(true)
        val savedId = mutableStateOf<String?>(null)
        val savedIntensity = mutableFloatStateOf(0f)
        var confirmations = 0
        compose.setContent {
            MaterialTheme {
                if (open.value) {
                    PrivateBinderHoloSelectorDialog(
                        placementId = "anniversary-reopen-fixture",
                        card = testCard(),
                        initialEffectId = savedId.value,
                        initialIntensity = savedIntensity.floatValue,
                        animationsEnabled = true,
                        saving = false,
                        error = null,
                        onDismiss = { open.value = false },
                        onConfirm = { effectId, intensity ->
                            savedId.value = effectId
                            savedIntensity.floatValue = intensity
                            confirmations++
                            open.value = false
                        },
                    )
                }
            }
        }
        val effectDescription = "Holo ${PrivateBinderVisualEngine.holoEffect("anniversary_30").displayName}"
        compose.onNodeWithContentDescription("Holo-Effekte").performScrollToKey("anniversary_30")
        compose.onNodeWithContentDescription(effectDescription).assertIsDisplayed().performClick()
        compose.onNodeWithContentDescription("Interaktive Holo-Vorschau").assertIsDisplayed()
        compose.onNodeWithText("Intensiv").assertIsDisplayed().performClick()
        compose.onNodeWithText("Übernehmen").performClick()
        compose.runOnIdle {
            assertEquals("anniversary_30", savedId.value)
            assertEquals(PrivateBinderHoloIntensityPreset.INTENSE.storedValue, savedIntensity.floatValue, 0f)
            assertEquals(1, confirmations)
        }
        // Recreate the dialog from its parent's saved values; do not rely on still-live
        // picker state. This verifies the UI round trip, not repository persistence.
        compose.runOnIdle { open.value = true }
        compose.onNodeWithContentDescription("Holo-Effekte").performScrollToKey("anniversary_30")
        compose.onNodeWithContentDescription(effectDescription).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Intensiv").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("Übernehmen").performClick()
        compose.runOnIdle {
            assertEquals("anniversary_30", savedId.value)
            assertEquals(PrivateBinderHoloIntensityPreset.INTENSE.storedValue, savedIntensity.floatValue, 0f)
            assertEquals(2, confirmations)
        }
    }

    @Test fun anniversaryFoilHasNoAutopilotAndRespondsToRenderedCardTilt() {
        val angleX = mutableFloatStateOf(0f)
        val angleY = mutableFloatStateOf(0f)
        val legacyPhase = mutableFloatStateOf(.5f)
        val tiltState = PrivateBinderTiltState().also { it.bind(angleX, angleY, legacyPhase) }
        compose.mainClock.autoAdvance = false
        try {
            compose.setContent {
                Box(
                    Modifier.size(width = 126.dp, height = 176.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .background(HOLO_TEST_BACKGROUND).testTag("anniversary-motion"),
                ) {
                    PrivateBinderHoloLayer(
                        effectId = "anniversary_30",
                        modifier = Modifier.fillMaxSize(),
                        preferences = PrivateBinderVisualPreferences(
                            intensity = PrivateBinderHoloIntensityPreset.NORMAL.storedValue,
                            quality = PrivateBinderVisualQuality.MEDIUM,
                        ),
                        tiltState = tiltState,
                    )
                }
            }
            compose.mainClock.advanceTimeByFrame()
            val node = compose.onNodeWithTag("anniversary-motion")
            val initialImage = node.captureToImage()
            val initial = initialImage.toPixelMap()
            compose.mainClock.advanceTimeBy(1_500L)
            compose.runOnIdle { legacyPhase.floatValue = .95f }
            compose.mainClock.advanceTimeByFrame()
            val stationary = node.captureToImage().toPixelMap()
            compose.runOnIdle { angleX.floatValue = -4f; angleY.floatValue = 6f }
            compose.mainClock.advanceTimeByFrame()
            val tiltedImage = node.captureToImage()
            val tilted = tiltedImage.toPixelMap()
            compose.runOnIdle { angleX.floatValue = 0f; angleY.floatValue = 0f }
            compose.mainClock.advanceTimeByFrame()
            val returned = node.captureToImage().toPixelMap()
            var stationaryChanges = 0
            var tiltedChanges = 0
            var returnedChanges = 0
            for (y in 0 until initial.height) {
                for (x in 0 until initial.width) {
                    if (rgbDistance(initial[x, y], stationary[x, y]) >= .01f) stationaryChanges++
                    if (rgbDistance(initial[x, y], tilted[x, y]) >= .08f) tiltedChanges++
                    if (rgbDistance(initial[x, y], returned[x, y]) >= .01f) returnedChanges++
                }
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val directory = java.io.File(context.getExternalFilesDir(null) ?: context.cacheDir, "holo-selector-fixtures")
            check(directory.mkdirs() || directory.isDirectory)
            listOf("anniversary-neutral.png" to initialImage, "anniversary-tilted.png" to tiltedImage).forEach { (name, image) ->
                java.io.File(directory, name).outputStream().use {
                    image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            assertEquals("Anniversary foil moved without any rendered card rotation", 0, stationaryChanges)
            assertTrue("Anniversary foil ignored rendered card tilt: $tiltedChanges changed pixels", tiltedChanges >= initial.width * initial.height / 100)
            assertEquals("Anniversary foil did not return to the same appearance at the same angle", 0, returnedChanges)
        } finally {
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun anniversaryDefaultIntensityHasVisibleColoredMotifsOnLightAndDarkWithoutCardFilm() {
        val background = mutableStateOf(HOLO_TEST_BRIGHT_BACKGROUND)
        val showFoil = mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                Box(
                    Modifier.size(width = 126.dp, height = 176.dp)
                        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                        .background(background.value).testTag("anniversary-default-contrast"),
                ) {
                    PrivateBinderHoloLayer(
                        effectId = if (showFoil.value) "anniversary_30" else "none",
                        modifier = Modifier.fillMaxSize(),
                        preferences = PrivateBinderVisualPreferences(
                            intensity = .68f,
                            quality = PrivateBinderVisualQuality.MEDIUM,
                            reduceMotion = true,
                        ),
                        tiltState = null,
                    )
                }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null) ?: context.cacheDir, "holo-selector-fixtures")
        check(directory.mkdirs() || directory.isDirectory)
        val failures = mutableListOf<String>()
        val evidence = mutableListOf<String>()
        val node = compose.onNodeWithTag("anniversary-default-contrast")
        for ((name, surface) in listOf("light" to HOLO_TEST_BRIGHT_BACKGROUND, "dark" to HOLO_TEST_BACKGROUND)) {
            compose.runOnIdle { background.value = surface; showFoil.value = false }
            val baseline = node.captureToImage().toPixelMap()
            compose.runOnIdle { showFoil.value = true }
            val image = node.captureToImage()
            val rendered = image.toPixelMap()
            assertEquals(baseline.width, rendered.width)
            assertEquals(baseline.height, rendered.height)
            val area = rendered.width * rendered.height
            val hueSectors = IntArray(6)
            val coloredQuadrants = IntArray(4)
            var coloredMotifPixels = 0
            var untouchedPixels = 0
            for (y in 0 until rendered.height) {
                for (x in 0 until rendered.width) {
                    val pixel = rendered[x, y]
                    val original = baseline[x, y]
                    if (rgbDistance(pixel, original) < .02f) untouchedPixels++
                    // The gold badge must not satisfy the visibility assertion by itself.
                    // Exclude its whole right-hand envelope, including feathering.
                    val inPlaqueEnvelope = x >= rendered.width * .65f &&
                        y >= rendered.height * .44f && y <= rendered.height * .70f
                    if (!inPlaqueEnvelope && isVisibleShimmerColor(pixel, original)) {
                        coloredMotifPixels++
                        hueSectors[shimmerHue(pixel).toInt().coerceIn(0, 5)]++
                        val quadrant = (if (y >= rendered.height / 2) 2 else 0) +
                            (if (x >= rendered.width / 2) 1 else 0)
                        coloredQuadrants[quadrant]++
                    }
                }
            }
            // Require visible saturated contours at NORMAL strength, not merely a few
            // tinted antialiasing pixels or a single dense logo. The same RGB/chroma
            // threshold as the other shimmer tests is .15 RGB distance / .12 spread.
            val minimumColored = area * 2 / 100
            val spectralSectors = hueSectors.count { it >= area / 1_000 }
            val populatedQuadrants = coloredQuadrants.count { it >= area / 400 }
            val minimumUntouched = area * 70 / 100
            val metrics = "anniversary_30/$name at .68 neutral: colored-motif-pixels=$coloredMotifPixels (min=$minimumColored); " +
                "hue-sectors=$spectralSectors; populated-quadrants=$populatedQuadrants; " +
                "untouched=$untouchedPixels (min=$minimumUntouched); size=${rendered.width}x${rendered.height}"
            evidence += metrics
            if (coloredMotifPixels < minimumColored) failures += "Anniversary motifs are too faint: $metrics"
            if (spectralSectors < 3) failures += "Anniversary motifs lack a visible rainbow range: $metrics"
            if (populatedQuadrants < 3) failures += "Anniversary visibility is confined to too little of the card: $metrics"
            if (untouchedPixels < minimumUntouched) failures += "Anniversary effect veils too much artwork: $metrics"
            java.io.File(directory, "anniversary-default-$name.png").outputStream().use {
                check(image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        java.io.File(directory, "anniversary-default-contrast-metrics.txt").writeText(evidence.joinToString("\n"))
        android.util.Log.i("DomiDexHoloTest", evidence.joinToString("; "))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun assertPinnedHoloSelector(compactLargeText: Boolean) {
        var appliedId: String? = null
        var appliedIntensity = 0f
        compose.setContent {
            val originalDensity = LocalDensity.current
            val configuration = Configuration(LocalConfiguration.current).apply {
                screenHeightDp = if (compactLargeText) 560 else 900
                screenWidthDp = if (compactLargeText) 320 else 400
            }
            CompositionLocalProvider(
                LocalConfiguration provides configuration,
                LocalDensity provides Density(originalDensity.density, fontScale = if (compactLargeText) 1.5f else 1f),
            ) {
                MaterialTheme {
                    PrivateBinderHoloSelectorDialog(
                        placementId = "pinned-preview-fixture",
                        card = testCard(),
                        initialEffectId = "rainbow",
                        initialIntensity = PrivateBinderHoloIntensityPreset.NORMAL.storedValue,
                        animationsEnabled = true,
                        saving = false,
                        error = null,
                        onDismiss = {},
                        onConfirm = { effect, intensity -> appliedId = effect; appliedIntensity = intensity },
                    )
                }
            }
        }
        val preview = compose.onNodeWithContentDescription("Interaktive Holo-Vorschau")
        val grid = compose.onNodeWithContentDescription("Holo-Effekte")
        preview.assertIsDisplayed()
        val originalBounds = preview.fetchSemanticsNode().boundsInRoot
        // A stable lower-list ID, independent of later catalogue additions.
        grid.performScrollToKey("element_fairy")
        compose.onNodeWithContentDescription("Holo Element · Fee").assertIsDisplayed().performClick()
        preview.assertIsDisplayed()
        val scrolledBounds = preview.fetchSemanticsNode().boundsInRoot
        assertEquals("Effect-list scrolling moved the preview horizontally", originalBounds.left, scrolledBounds.left, .5f)
        assertEquals("Effect-list scrolling moved the preview vertically", originalBounds.top, scrolledBounds.top, .5f)
        assertEquals("Selecting a lower effect resized the preview", originalBounds.height, scrolledBounds.height, .5f)
        compose.onNodeWithText("Intensiv").assertIsDisplayed().performClick()
        compose.onNodeWithText("Abbrechen").assertIsDisplayed()
        compose.onNodeWithText("Übernehmen").assertIsDisplayed()
        val listBounds = grid.fetchSemanticsNode().boundsInRoot
        val strengthBounds = compose.onNodeWithText("Intensiv").fetchSemanticsNode().boundsInRoot
        val confirmBounds = compose.onNodeWithText("Übernehmen").fetchSemanticsNode().boundsInRoot
        assertTrue("Pinned preview covered the effect list", scrolledBounds.bottom <= listBounds.top + .5f)
        assertTrue("Strength control scrolled into the effect list", strengthBounds.bottom <= listBounds.top + .5f)
        assertTrue("Effects covered the confirmation control", listBounds.bottom <= confirmBounds.top + .5f)
        assertTrue("Effect list has no usable viewport", listBounds.height >= 70f * InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density)

        // Scroll gestures must not steal the independently laid-out preview's touch input.
        val untouched = preview.fetchSemanticsNode().boundsInRoot
        compose.mainClock.autoAdvance = false
        try {
            preview.performTouchInput { down(Offset(width * .86f, height * .18f)) }
            compose.mainClock.advanceTimeBy(160L)
            val tilted = preview.fetchSemanticsNode().boundsInRoot
            val motion = abs(tilted.left - untouched.left) + abs(tilted.top - untouched.top) +
                abs(tilted.right - untouched.right) + abs(tilted.bottom - untouched.bottom)
            assertTrue("Pinned preview stopped responding to card touch", motion > 1f)
        } finally {
            preview.performTouchInput { up() }
            compose.mainClock.advanceTimeBy(800L)
            compose.mainClock.autoAdvance = true
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null) ?: context.cacheDir, "holo-selector-fixtures")
        check(directory.mkdirs() || directory.isDirectory)
        val filename = if (compactLargeText) "pinned-preview-compact-large-text.png" else "pinned-preview-regular.png"
        java.io.File(directory, filename).outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
                .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("Übernehmen").performClick()
        compose.runOnIdle {
            assertEquals("element_fairy", appliedId)
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
        var maximumChannelDelta = 0
        val channelDeltaHistogram = mutableMapOf<Int, Int>()
        val changedPixelDetails = mutableListOf<String>()
        for (y in 0 until first.height) {
            for (x in 0 until first.width) {
                val channelDelta = maxRgbByteDelta(first[x, y], second[x, y])
                maximumChannelDelta = maxOf(maximumChannelDelta, channelDelta)
                if (channelDelta > 0) channelDeltaHistogram[channelDelta] = (channelDeltaHistogram[channelDelta] ?: 0) + 1
                if (rgbDistance(first[x, y], second[x, y]) >= .01f) {
                    changedPixels++
                    if (changedPixelDetails.size < 20) changedPixelDetails +=
                        "($x,$y) max-channel-byte-delta=$channelDelta: ${first[x, y]} -> ${second[x, y]}"
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
            android.util.Log.w("DomiDexHoloTest", "${first.width}x${first.height}; changed=$changedPixels; max-channel-byte-delta=$maximumChannelDelta; histogram=$channelDeltaHistogram; ${changedPixelDetails.joinToString("; ")}")
        }
        assertEquals("Holo animated although its card was stationary", 0, changedPixels)
        compose.mainClock.autoAdvance = true
    }

    @Test fun strongestCrystalFoilDoesNotCreateAMilkyFullCardVeil() {
        compose.setContent {
            Box(
                Modifier.size(width = 126.dp, height = 176.dp)
                    .background(HOLO_TEST_BACKGROUND).testTag("crystal-contrast"),
            ) {
                PrivateBinderHoloLayer(
                    effectId = "crystal",
                    modifier = Modifier.fillMaxSize(),
                    preferences = PrivateBinderVisualPreferences(intensity = 1f, reduceMotion = true),
                )
            }
        }
        val pixels = compose.onNodeWithTag("crystal-contrast").captureToImage().toPixelMap()
        var totalLightening = 0.0
        var chromaticPixels = 0
        for (y in 0 until pixels.height) {
            for (x in 0 until pixels.width) {
                val pixel = pixels[x, y]
                totalLightening += ((pixel.red - HOLO_TEST_BACKGROUND.red) +
                    (pixel.green - HOLO_TEST_BACKGROUND.green) +
                    (pixel.blue - HOLO_TEST_BACKGROUND.blue)) / 3f
                if (maxOf(pixel.red, pixel.green, pixel.blue) - minOf(pixel.red, pixel.green, pixel.blue) >= .12f) {
                    chromaticPixels++
                }
            }
        }
        val area = pixels.width * pixels.height
        assertTrue("Dense crystal material bleached the artwork: ${totalLightening / area}", totalLightening / area < .15)
        assertTrue("Foil lost its colored highlights", chromaticPixels > area / 100)
    }

    @Test fun foilUsesRenderedCardAnglesAndIgnoresUnrelatedAnimationPhase() {
        val angleX = mutableFloatStateOf(0f)
        val angleY = mutableFloatStateOf(0f)
        val legacyPhase = mutableFloatStateOf(.5f)
        val tiltState = PrivateBinderTiltState().also { it.bind(angleX, angleY, legacyPhase) }
        compose.setContent {
            Box(
                Modifier.size(width = 126.dp, height = 176.dp)
                    .background(HOLO_TEST_BRIGHT_BACKGROUND).testTag("angle-driven-holo"),
            ) {
                PrivateBinderHoloLayer(
                    effectId = "rainbow",
                    modifier = Modifier.fillMaxSize(),
                    preferences = PrivateBinderVisualPreferences(intensity = 1f),
                    tiltState = tiltState,
                )
            }
        }
        val node = compose.onNodeWithTag("angle-driven-holo")
        val initial = node.captureToImage().toPixelMap()
        compose.runOnIdle { legacyPhase.floatValue = .95f }
        val phaseOnly = node.captureToImage().toPixelMap()
        compose.runOnIdle { angleX.floatValue = -3f; angleY.floatValue = 6f }
        val tilted = node.captureToImage().toPixelMap()
        compose.runOnIdle { angleX.floatValue = 0f; angleY.floatValue = 0f }
        val returned = node.captureToImage().toPixelMap()
        var phaseChanges = 0
        var angleChanges = 0
        var returnChanges = 0
        var maximumPhaseChannelDelta = 0
        var maximumReturnChannelDelta = 0
        val changedPixelDetails = mutableListOf<String>()
        for (y in 0 until initial.height) {
            for (x in 0 until initial.width) {
                val phaseDelta = maxRgbByteDelta(initial[x, y], phaseOnly[x, y])
                val returnDelta = maxRgbByteDelta(initial[x, y], returned[x, y])
                maximumPhaseChannelDelta = maxOf(maximumPhaseChannelDelta, phaseDelta)
                maximumReturnChannelDelta = maxOf(maximumReturnChannelDelta, returnDelta)
                if ((phaseDelta > 0 || returnDelta > 0) && changedPixelDetails.size < 20) {
                    changedPixelDetails += "($x,$y): phase-channel-byte-delta=$phaseDelta; return-channel-byte-delta=$returnDelta"
                }
                if (rgbDistance(initial[x, y], phaseOnly[x, y]) >= .01f) phaseChanges++
                if (rgbDistance(initial[x, y], tilted[x, y]) >= .08f) angleChanges++
                if (rgbDistance(initial[x, y], returned[x, y]) >= .01f) returnChanges++
            }
        }
        if (phaseChanges > 0 || returnChanges > 0) {
            android.util.Log.w("DomiDexHoloTest", "phase-changed=$phaseChanges; return-changed=$returnChanges; max-phase-channel-byte-delta=$maximumPhaseChannelDelta; max-return-channel-byte-delta=$maximumReturnChannelDelta; ${changedPixelDetails.joinToString("; ")}")
        }
        assertEquals("Foil followed a phase that did not move the card", 0, phaseChanges)
        assertTrue("Foil ignored the card's rendered angles", angleChanges > initial.width * initial.height / 20)
        assertEquals("Returning to the same card angle changed its foil", 0, returnChanges)
    }

    @Test fun defaultRainbowShimmerRemainsChromaticAndChangesHueOnLightArtwork() {
        assertDefaultShimmerOnArtwork(HOLO_TEST_BRIGHT_BACKGROUND, "light")
    }

    @Test fun defaultRainbowShimmerRemainsChromaticAndChangesHueOnDarkArtwork() {
        assertDefaultShimmerOnArtwork(HOLO_TEST_BACKGROUND, "dark")
    }

    @Test fun staticBinderFoilHonorsChosenIntensityLikeEquivalentLowQualityRenderer() {
        val useStaticLayer = mutableStateOf(true)
        val intensity = mutableFloatStateOf(PrivateBinderHoloIntensityPreset.NORMAL.storedValue)
        val tiltState = PrivateBinderTiltState().also {
            it.bind(mutableFloatStateOf(0f), mutableFloatStateOf(-5f), mutableFloatStateOf(.5f))
        }
        compose.setContent {
            Box(
                Modifier.size(width = 126.dp, height = 176.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .background(HOLO_TEST_BRIGHT_BACKGROUND).testTag("static-strength-parity"),
            ) {
                if (useStaticLayer.value) {
                    PrivateBinderStaticHoloLayer("rainbow", intensity.floatValue, Modifier.fillMaxSize(), tiltState)
                } else {
                    PrivateBinderHoloLayer(
                        "rainbow",
                        Modifier.fillMaxSize(),
                        PrivateBinderVisualPreferences(
                            intensity = intensity.floatValue,
                            quality = PrivateBinderVisualQuality.LOW,
                            reduceMotion = true,
                        ),
                        tiltState,
                    )
                }
            }
        }
        val node = compose.onNodeWithTag("static-strength-parity")
        for (preset in PrivateBinderHoloIntensityPreset.entries) {
            compose.runOnIdle { intensity.floatValue = preset.storedValue; useStaticLayer.value = true }
            val static = node.captureToImage().toPixelMap()
            compose.runOnIdle { useStaticLayer.value = false }
            val equivalent = node.captureToImage().toPixelMap()
            var differentPixels = 0
            for (y in 0 until static.height) {
                for (x in 0 until static.width) {
                    if (rgbDistance(static[x, y], equivalent[x, y]) >= .01f) differentPixels++
                }
            }
            assertEquals("Binder silently changed ${preset.label} intensity (${preset.storedValue})", 0, differentPixels)
        }
    }

    /**
     * Normal intensity, not the maximum-strength fallback. Compare corresponding positions
     * on the foil geometry after compensating its known tilt translation. This rejects a
     * fixed-color pattern that merely moves or changes brightness without changing hue.
     */
    private fun assertDefaultShimmerOnArtwork(background: Color, fixtureName: String) {
        val selectedEffect = mutableStateOf("rainbow")
        val angleX = mutableFloatStateOf(0f)
        val angleY = mutableFloatStateOf(-6f)
        val phase = mutableFloatStateOf(.5f)
        val tiltState = PrivateBinderTiltState().also { it.bind(angleX, angleY, phase) }
        compose.setContent {
            Box(
                Modifier.size(width = 126.dp, height = 176.dp)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .background(background).testTag("default-shimmer"),
            ) {
                PrivateBinderHoloLayer(
                    effectId = selectedEffect.value,
                    modifier = Modifier.fillMaxSize(),
                    preferences = PrivateBinderVisualPreferences(
                        intensity = PrivateBinderHoloIntensityPreset.NORMAL.storedValue,
                        quality = PrivateBinderVisualQuality.MEDIUM,
                    ),
                    tiltState = tiltState,
                )
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = java.io.File(context.getExternalFilesDir(null) ?: context.cacheDir, "holo-default-shimmer-fixtures")
        check(directory.mkdirs() || directory.isDirectory)
        val failures = mutableListOf<String>()
        val evidence = mutableListOf<String>()
        val node = compose.onNodeWithTag("default-shimmer")
        for (effect in listOf("rainbow", "element_psychic")) {
            compose.runOnIdle { selectedEffect.value = effect; angleY.floatValue = -6f }
            val leftImage = node.captureToImage()
            compose.runOnIdle { angleY.floatValue = 6f }
            val rightImage = node.captureToImage()
            // Synthetic flat artwork only; the artifacts contain no account/card/user data.
            listOf("left" to leftImage, "right" to rightImage).forEach { (side, image) ->
                java.io.File(directory, "$fixtureName-$effect-$side.png").outputStream().use {
                    image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
            }
            val left = leftImage.toPixelMap()
            val right = rightImage.toPixelMap()
            val area = left.width * left.height
            val leftHues = IntArray(6)
            val rightHues = IntArray(6)
            var leftColored = 0
            var rightColored = 0
            var correspondingColored = 0
            var hueChanges = 0
            // Production point(): x + normalizedTiltY * .035. X angle stays zero, so the
            // tested rainbow arcs and psychic rings translate without changing their shape.
            val geometryTranslation = (2f * (6f / PrivateBinderVisualEngine.MAX_TILT_DEGREES) * .035f * left.width).roundToInt()
            for (y in 0 until left.height) {
                for (x in 0 until left.width) {
                    val a = left[x, y]
                    val b = right[x, y]
                    val aIsColored = isVisibleShimmerColor(a, background)
                    if (aIsColored) {
                        leftColored++
                        leftHues[shimmerHue(a).toInt().coerceIn(0, 5)]++
                    }
                    if (isVisibleShimmerColor(b, background)) {
                        rightColored++
                        rightHues[shimmerHue(b).toInt().coerceIn(0, 5)]++
                    }
                    val alignedX = x + geometryTranslation
                    if (aIsColored && alignedX < right.width) {
                        val aligned = right[alignedX, y]
                        if (isVisibleShimmerColor(aligned, background)) {
                            correspondingColored++
                            val hueDistance = abs(shimmerHue(a) - shimmerHue(aligned))
                            // >33 degrees is a perceptible spectral change, not screenshot
                            // quantization or the same hue getting lighter/darker.
                            if (minOf(hueDistance, 6f - hueDistance) >= .55f) hueChanges++
                        }
                    }
                }
            }
            // Meaningful colored coverage (1.5% of the card) and at least three spectral
            // sectors. A few isolated colored pixels cannot stand in for visible shimmer.
            val minimumColored = area * 15 / 1_000
            val leftHueSectors = leftHues.count { it >= area / 1_000 }
            val rightHueSectors = rightHues.count { it >= area / 1_000 }
            val metrics = "$fixtureName/$effect at .68: colored=$leftColored/$rightColored (min=$minimumColored); hue-sectors=$leftHueSectors/$rightHueSectors; corresponding=$correspondingColored; hue-changed=$hueChanges"
            evidence += metrics
            if (leftColored < minimumColored || rightColored < minimumColored) failures += "Weak default-intensity shimmer: $metrics"
            if (leftHueSectors < 3 || rightHueSectors < 3) failures += "Missing spectral color range: $metrics"
            if (correspondingColored < area / 200) failures += "Too little corresponding foil to validate its hue: $metrics"
            if (hueChanges < area * 3 / 1_000 || hueChanges < correspondingColored / 5) {
                failures += "Tilt moved/lit the foil without a meaningful spectral hue response: $metrics"
            }
        }
        java.io.File(directory, "$fixtureName-metrics.txt").writeText(evidence.joinToString("\n"))
        android.util.Log.i("DomiDexHoloTest", evidence.joinToString("; "))
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun isVisibleShimmerColor(pixel: Color, background: Color): Boolean =
        maxOf(pixel.red, pixel.green, pixel.blue) - minOf(pixel.red, pixel.green, pixel.blue) >= .12f &&
            rgbDistance(pixel, background) >= .15f

    /** Standard six-sector hue, independent of brightness and saturation. */
    private fun shimmerHue(pixel: Color): Float {
        val maximum = maxOf(pixel.red, pixel.green, pixel.blue)
        val minimum = minOf(pixel.red, pixel.green, pixel.blue)
        val spread = maximum - minimum
        if (spread == 0f) return 0f
        val raw = when (maximum) {
            pixel.red -> (pixel.green - pixel.blue) / spread
            pixel.green -> 2f + (pixel.blue - pixel.red) / spread
            else -> 4f + (pixel.red - pixel.green) / spread
        }
        return (raw + 6f) % 6f
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

    private fun maxRgbByteDelta(first: Color, second: Color): Int = maxOf(
        abs((first.red * 255f).roundToInt() - (second.red * 255f).roundToInt()),
        abs((first.green * 255f).roundToInt() - (second.green * 255f).roundToInt()),
        abs((first.blue * 255f).roundToInt() - (second.blue * 255f).roundToInt()),
    )

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
