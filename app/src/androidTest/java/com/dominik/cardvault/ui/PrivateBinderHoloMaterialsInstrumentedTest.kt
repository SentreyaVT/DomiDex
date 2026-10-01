package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path as AndroidPath
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.ui.theme.CardVaultTheme
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real production Holo layers, one fixed synthetic canvas and no catalogue/account/network data.
 * The fixed density makes pixel evidence comparable across emulator resolutions. All captures
 * and contact sheets are generated test artifacts, not modified product/reference artwork.
 */
@RunWith(AndroidJUnit4::class)
class PrivateBinderHoloMaterialsInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun redesignedMaterialsStayDistinctStillAndRespondToTilt() {
        val ids = listOf("rainbow", "linear", "diagonal", "galaxy", "nebula", "aurora", "crystal", "prism", "diamond")
        val effects = PrivateBinderVisualEngine.holoEffects.filter {
            privateBinderUsesPremiumFoil(it.id) || privateBinderUsesElementFoil(it.id) || privateBinderUsesAdditionalFoil(it.id)
        }
        assertEquals(27, effects.size)
        val selected = mutableStateOf("none")
        val angleX = mutableFloatStateOf(0f)
        val angleY = mutableFloatStateOf(0f)
        val strength = mutableFloatStateOf(.68f)
        val tilt = PrivateBinderTiltState().also { it.bind(angleX, angleY, mutableFloatStateOf(.5f)) }
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                CardVaultTheme { HoloMaterialFixture(selected.value, tilt, strength.floatValue) }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "holo-premium-preview")
        check(directory.mkdirs() || directory.isDirectory)
        val allCaptures = mutableListOf<MaterialCapture>()
        val neutral = linkedMapOf<String, MaterialCapture>()
        val tilted = linkedMapOf<String, MaterialCapture>()
        val evidence = JSONArray()
        fun take(id: String) = captureMaterial(id, directory).also(allCaptures::add)
        try {
            val baseline = take("none")
            for (effect in effects) {
                compose.runOnIdle { selected.value = effect.id; angleX.floatValue = 0f; angleY.floatValue = 0f }
                val first = take("${effect.id}-neutral")
                neutral[effect.id] = first
                compose.mainClock.advanceTimeBy(1_250L)
                val still = take("${effect.id}-still")
                val stationaryDelta = difference(first, still)
                assertEquals("${effect.id} must not animate by itself", 0, stationaryDelta.visiblePixels)
                compose.runOnIdle { angleX.floatValue = -3f; angleY.floatValue = 6f }
                val angled = take("${effect.id}-tilted")
                tilted[effect.id] = angled
                val visible = difference(baseline, first)
                val moved = difference(first, angled)
                evidence.put(visible.json("none", effect.id, "default-strength"))
                evidence.put(moved.json(effect.id, "tilted", "card-angle-response"))
                assertTrue("${effect.id} invisible at default strength", visible.visiblePixels >= MIN_VISIBLE_PIXELS)
                assertTrue("${effect.id} does not follow rendered tilt", moved.visiblePixels >= MIN_VISIBLE_PIXELS)
                compose.runOnIdle { strength.floatValue = 0f }
                val zero = take("${effect.id}-zero")
                assertEquals("${effect.id} visible with intensity zero", 0, difference(baseline, zero).visiblePixels)
                compose.runOnIdle { strength.floatValue = .68f }
            }
            val originalNine = ids.map(PrivateBinderVisualEngine::holoEffect)
            saveContactSheet(directory, "rainbow-bis-diamond-neutral.png", "DomiDex · Rainbow bis Diamond", originalNine, neutral, 3,
                "Android-Renderer · Testkarte · normale Stärke 68 % · ruhig / unberührt")
            saveContactSheet(directory, "rainbow-bis-diamond-geneigt.png", "DomiDex · Rainbow bis Diamond", originalNine, tilted, 3,
                "Android-Renderer · Testkarte · normale Stärke 68 % · Kartenneigung -3° / 6°")
            saveContactSheet(directory, "weitere-materialien.png", "DomiDex · Weitere Holo-Materialien",
                effects.filter { privateBinderUsesAdditionalFoil(it.id) }, tilted, 3,
                "Android-Renderer · Testkarte · normale Stärke 68 % · Kartenneigung -3° / 6°")
            saveContactSheet(directory, "element-effekte.png", "DomiDex · Element-Effekte",
                effects.filter { privateBinderUsesElementFoil(it.id) }, tilted, 4,
                "Android-Renderer · Testkarte · normale Stärke 68 % · Kartenneigung -3° / 6°")
            for (i in effects.indices) for (j in i + 1 until effects.size) {
                val difference = difference(neutral.getValue(effects[i].id), neutral.getValue(effects[j].id))
                evidence.put(difference.json(effects[i].id, effects[j].id, "distinct-materials"))
                assertTrue("${effects[i].id} and ${effects[j].id} look the same", difference.visiblePixels >= MIN_VISIBLE_PIXELS)
            }
            File(directory, "premium-pixel-checks.json").writeText(evidence.toString(2))
        } finally {
            allCaptures.forEach { it.bitmap.recycle() }
        }
    }

    @Test fun all32MaterialsAreVisibleDistinctWithinFamiliesAndGenerateContactSheets() {
        val effects = PrivateBinderVisualEngine.holoEffects.filter { it.id != PrivateBinderVisualEngine.NORMAL_HOLO_ID }
        val core = effects.filterNot { it.id.startsWith("element_") }
        val elements = effects.filter { it.id.startsWith("element_") }
        assertEquals("Expected all existing and anniversary material IDs", 32, effects.size)
        assertEquals("Core plus procedural anniversary contact sheet coverage", 21, core.size)
        assertEquals("Element contact sheet coverage", 11, elements.size)
        val selected = mutableStateOf(PrivateBinderVisualEngine.NORMAL_HOLO_ID)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f, fontScale = 1f)) {
                CardVaultTheme { HoloMaterialFixture(selected.value) }
            }
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "holo-material-fixtures")
        check(directory.mkdirs() || directory.isDirectory)
        val captures = linkedMapOf<String, MaterialCapture>()
        val comparisons = JSONArray()
        val failures = mutableListOf<String>()
        try {
            val none = captureMaterial(PrivateBinderVisualEngine.NORMAL_HOLO_ID, directory)
            captures[PrivateBinderVisualEngine.NORMAL_HOLO_ID] = none
            for (effect in effects) {
                compose.runOnIdle { selected.value = effect.id }
                captures[effect.id] = captureMaterial(effect.id, directory)
            }
            // Write review evidence before asserting so a rendering regression remains inspectable.
            saveContactSheet(directory, "holo-core-21-contact-sheet.png", "DomiDex · 21 Holo-Materialien inkl. Jubiläum", core, captures, columns = 5)
            saveContactSheet(directory, "holo-elements-11-contact-sheet.png", "DomiDex · 11 Element-Effekte", elements, captures, columns = 4)

            for (effect in effects) {
                val difference = difference(none, captures.getValue(effect.id))
                comparisons.put(difference.json("none", effect.id, "baseline"))
                if (difference.visiblePixels < MIN_VISIBLE_PIXELS) {
                    failures += "${effect.id} does not visibly differ from none: $difference"
                }
            }
            for ((pattern, family) in effects.groupBy { it.pattern }) {
                for (leftIndex in family.indices) {
                    for (rightIndex in leftIndex + 1 until family.size) {
                        val left = family[leftIndex]
                        val right = family[rightIndex]
                        val difference = difference(captures.getValue(left.id), captures.getValue(right.id))
                        comparisons.put(difference.json(left.id, right.id, pattern.name))
                        if (difference.visiblePixels < MIN_VISIBLE_PIXELS) {
                            failures += "${pattern.name}: ${left.id} and ${right.id} are visually indistinguishable: $difference"
                        }
                    }
                }
            }
            File(directory, "holo-material-pixel-evidence.json").writeText(JSONObject()
                .put("fixture", "Synthetic demonstration card; no real card data")
                .put("intensity", .92)
                .put("quality", "MEDIUM")
                .put("reduceMotion", true)
                .put("tilt", "neutral; no autonomous animation")
                .put("imageWidth", none.bitmap.width)
                .put("imageHeight", none.bitmap.height)
                .put("minimumVisiblePixels", MIN_VISIBLE_PIXELS)
                .put("minimumRgbL1DifferencePerPixel", MIN_RGB_DISTANCE)
                .put("comparisons", comparisons)
                .put("failures", JSONArray(failures))
                .toString(2))
            assertTrue("Holo material rendering regressions:\n${failures.joinToString("\n")}", failures.isEmpty())
        } finally {
            captures.values.forEach { it.bitmap.recycle() }
        }
    }

    private fun captureMaterial(id: String, directory: File): MaterialCapture {
        require(id.matches(Regex("[a-z0-9_-]+")))
        compose.waitForIdle()
        val bitmap = checkNotNull(compose.onNodeWithTag(CARD_TAG).captureToImage()
            .asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false))
        assertEquals("Fixed capture width", 360, bitmap.width)
        assertEquals("Fixed capture height", 504, bitmap.height)
        savePng(bitmap, File(directory, "holo-$id.png"))
        return MaterialCapture(bitmap, IntArray(bitmap.width * bitmap.height).also {
            bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        })
    }

    private fun difference(left: MaterialCapture, right: MaterialCapture): PixelDifference {
        assertEquals(left.bitmap.width, right.bitmap.width)
        assertEquals(left.bitmap.height, right.bitmap.height)
        var visible = 0
        var maximum = 0
        var total = 0L
        for (index in left.pixels.indices) {
            val a = left.pixels[index]
            val b = right.pixels[index]
            val distance = abs(AndroidColor.red(a) - AndroidColor.red(b)) +
                abs(AndroidColor.green(a) - AndroidColor.green(b)) +
                abs(AndroidColor.blue(a) - AndroidColor.blue(b))
            if (distance >= MIN_RGB_DISTANCE) visible++
            maximum = maxOf(maximum, distance)
            total += distance
        }
        return PixelDifference(visible, maximum, total)
    }

    private fun saveContactSheet(
        directory: File,
        filename: String,
        title: String,
        effects: List<PrivateBinderHoloEffect>,
        captures: Map<String, MaterialCapture>,
        columns: Int,
        subtitle: String = "Gleiche Testkarte · Intensität 92 % · neutraler Winkel · keine automatische Bewegung",
    ) {
        val cellWidth = 288
        val cellHeight = 416
        val headerHeight = 100
        val rows = (effects.size + columns - 1) / columns
        val sheet = Bitmap.createBitmap(columns * cellWidth, headerHeight + rows * cellHeight + 36, Bitmap.Config.ARGB_8888)
        try {
            val canvas = AndroidCanvas(sheet)
            canvas.drawColor(AndroidColor.rgb(9, 10, 18))
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = 32f
            paint.color = AndroidColor.rgb(239, 221, 255)
            canvas.drawText(title, 24f, 42f, paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.textSize = 18f
            paint.color = AndroidColor.rgb(190, 192, 212)
            canvas.drawText(subtitle, 24f, 73f, paint)
            effects.forEachIndexed { index, effect ->
                val x = (index % columns) * cellWidth
                val y = headerHeight + (index / columns) * cellHeight
                val card = captures.getValue(effect.id).bitmap
                canvas.drawBitmap(card, null, Rect(x + 20, y, x + 268, y + 347), paint)
                paint.color = AndroidColor.rgb(235, 224, 249)
                paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                paint.textSize = 18f
                canvas.drawText("${index + 1}. ${effect.displayName}", x + 20f, y + 372f, paint)
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                paint.textSize = 14f
                paint.color = AndroidColor.rgb(165, 169, 193)
                canvas.drawText("${effect.id} · ${effect.pattern.name}", x + 20f, y + 396f, paint)
            }
            paint.textSize = 16f
            paint.color = AndroidColor.rgb(165, 169, 193)
            canvas.drawText("Generierte UI-Testdaten · keine echten Karten, Preise oder Personen", 24f, sheet.height - 12f, paint)
            savePng(sheet, File(directory, filename))
        } finally {
            sheet.recycle()
        }
    }

    private fun savePng(bitmap: Bitmap, file: File) {
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Composable private fun HoloMaterialFixture(effectId: String, tilt: PrivateBinderTiltState? = null, strength: Float = .92f) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0A0B14))) {
            Box(Modifier.requiredSize(180.dp, 252.dp)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .clip(RoundedCornerShape(10.dp)).testTag(CARD_TAG)) {
                SyntheticArtwork()
                PrivateBinderHoloLayer(
                    effectId = effectId,
                    modifier = Modifier.fillMaxSize(),
                    preferences = PrivateBinderVisualPreferences(
                        intensity = strength,
                        quality = PrivateBinderVisualQuality.MEDIUM,
                        reduceMotion = true,
                    ),
                    tiltState = tilt,
                )
            }
        }
    }

    /** Broad light/dark colour areas and readable type expose both foil differences and haze. */
    @Composable private fun SyntheticArtwork() {
        Canvas(Modifier.fillMaxSize()) {
            drawIntoCanvas { composeCanvas ->
                val canvas = composeCanvas.nativeCanvas
                val restore = canvas.save()
                try {
                    canvas.scale(size.width / 180f, size.height / 252f)
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                    paint.color = AndroidColor.rgb(215, 200, 138)
                    canvas.drawRoundRect(RectF(0f, 0f, 180f, 252f), 10f, 10f, paint)
                    paint.color = AndroidColor.rgb(239, 239, 217)
                    canvas.drawRoundRect(RectF(6f, 6f, 174f, 246f), 7f, 7f, paint)
                    paint.color = AndroidColor.rgb(34, 39, 61)
                    paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    paint.textSize = 12f
                    canvas.drawText("AURORA · TESTKARTE", 12f, 23f, paint)
                    paint.shader = LinearGradient(12f, 34f, 168f, 149f,
                        intArrayOf(AndroidColor.rgb(29, 52, 89), AndroidColor.rgb(111, 144, 154), AndroidColor.rgb(223, 162, 91)),
                        null, Shader.TileMode.CLAMP)
                    canvas.drawRoundRect(RectF(12f, 33f, 168f, 151f), 4f, 4f, paint)
                    paint.shader = null
                    paint.color = AndroidColor.rgb(243, 224, 158)
                    canvas.drawCircle(133f, 59f, 17f, paint)
                    paint.color = AndroidColor.rgb(34, 77, 80)
                    canvas.drawPath(AndroidPath().apply {
                        moveTo(12f, 129f); lineTo(55f, 65f); lineTo(91f, 125f)
                        lineTo(122f, 82f); lineTo(168f, 137f); lineTo(168f, 151f)
                        lineTo(12f, 151f); close()
                    }, paint)
                    paint.color = AndroidColor.rgb(211, 220, 213)
                    canvas.drawPath(AndroidPath().apply {
                        moveTo(43f, 84f); lineTo(55f, 65f); lineTo(68f, 86f)
                        lineTo(56f, 79f); close()
                    }, paint)
                    paint.color = AndroidColor.rgb(62, 142, 165)
                    canvas.drawOval(RectF(36f, 133f, 160f, 146f), paint)
                    paint.color = AndroidColor.rgb(44, 46, 64)
                    paint.textSize = 11f
                    canvas.drawText("Sternenlicht", 13f, 169f, paint)
                    canvas.drawText("60", 150f, 169f, paint)
                    paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                    paint.textSize = 8f
                    canvas.drawText("Feine Linien, klare Farben und lesbarer Text.", 13f, 183f, paint)
                    canvas.drawText("Diese Karte ist nur eine UI-Testgrafik.", 13f, 195f, paint)
                    paint.color = AndroidColor.rgb(188, 183, 163)
                    paint.strokeWidth = .7f
                    canvas.drawLine(13f, 206f, 166f, 206f, paint)
                    paint.color = AndroidColor.rgb(44, 46, 64)
                    paint.textSize = 9f
                    canvas.drawText("DEMO 001/001", 13f, 221f, paint)
                    paint.textSize = 7f
                    canvas.drawText("Kein Pokémon · keine echten Kartendaten", 13f, 237f, paint)
                } finally {
                    canvas.restoreToCount(restore)
                }
            }
        }
    }

    private data class MaterialCapture(val bitmap: Bitmap, val pixels: IntArray)
    private data class PixelDifference(val visiblePixels: Int, val maximumRgbDistance: Int, val totalRgbDistance: Long) {
        fun json(left: String, right: String, family: String) = JSONObject()
            .put("left", left).put("right", right).put("family", family)
            .put("visiblePixels", visiblePixels).put("maximumRgbDistance", maximumRgbDistance)
            .put("totalRgbDistance", totalRgbDistance)
    }

    private companion object {
        const val CARD_TAG = "holo-material-synthetic-card"
        // Require a meaningful visible difference over at least 2% of the fixed 360x504
        // card, not a handful of changed pixels. Visual contact sheets remain part of QA.
        const val MIN_VISIBLE_PIXELS = 3630
        const val MIN_RGB_DISTANCE = 24
    }
}
