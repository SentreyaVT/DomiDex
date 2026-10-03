package com.dominik.cardvault.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.R
import com.dominik.cardvault.data.catalog.PokemonSet
import com.dominik.cardvault.data.catalog.PokemonVisualCard
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/** Real Coil PNG decoding and rendered pixels, using only exact per-test owned cache files. */
@RunWith(AndroidJUnit4::class)
class CatalogCardArtworkInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var imageRoot: File
    private var ownsImageRoot = false
    private val createdFiles = mutableSetOf<String>()
    private var contentStarted = false
    private val visible = mutableStateOf(true)
    private val selected = mutableStateOf<PokemonVisualCard?>(null)
    private lateinit var low: String
    private lateinit var high: String
    private lateinit var alternate: String
    private lateinit var broken: String

    @Before fun setUp() {
        imageRoot = File(context.cacheDir, "catalog-artwork-${UUID.randomUUID()}").canonicalFile
        check(imageRoot.parentFile == context.cacheDir.canonicalFile && !imageRoot.exists())
        check(imageRoot.mkdir())
        ownsImageRoot = true
        low = createImage("low.png", RED)
        high = createImage("high.png", GREEN)
        alternate = createImage("alternate.png", BLUE)
        val invalid = createOwnedFile("broken.png")
        invalid.writeBytes(byteArrayOf(1, 2, 3, 4, 5))
        broken = Uri.fromFile(invalid).toString()
    }

    @After fun tearDown() {
        try {
            if (contentStarted) {
                compose.runOnIdle { visible.value = false }
                compose.waitForIdle()
            }
        } finally {
            if (ownsImageRoot) {
                check(imageRoot.canonicalFile == imageRoot && imageRoot.parentFile == context.cacheDir.canonicalFile)
                val files = checkNotNull(imageRoot.listFiles())
                check(files.map { it.name }.toSet() == createdFiles)
                check(files.all { it.isFile && it.canonicalFile.parentFile == imageRoot })
                files.forEach { check(it.delete()) }
                check(imageRoot.delete())
                ownsImageRoot = false
            }
        }
    }

    @Test fun validThumbnailRemainsVisibleInsteadOfDifferentLargeImage() {
        val card = card("fixture-001", low, high)
        show(card)
        assertRealPixels(card, RED)
        compose.runOnIdle { selected.value = card.copy(name = "Renamed without changing image identity") }
        assertRealPixels(checkNotNull(selected.value), RED)
    }

    @Test fun corruptThumbnailFallsBackToSameCardLargeImage() {
        val card = card("fixture-001", broken, high)
        show(card)
        assertRealPixels(card, GREEN)
    }

    @Test fun blankThumbnailLoadsLargeImageDirectly() {
        val card = card("fixture-001", "  ", high)
        show(card)
        assertRealPixels(card, GREEN)
    }

    @Test fun cardAndUrlChangesResetFallbackWithoutKeepingPreviousImage() {
        val first = card("fixture-001", broken, high)
        show(first)
        assertRealPixels(first, GREEN)
        val second = card("fixture-002", alternate, low)
        compose.runOnIdle { selected.value = second }
        assertRealPixels(second, BLUE)
        // Enter fallback on the second ID, then change only that card's URLs.
        val secondFallback = second.copy(smallImageUrl = broken, largeImageUrl = high)
        compose.runOnIdle { selected.value = secondFallback }
        assertRealPixels(secondFallback, GREEN)
        val updated = secondFallback.copy(smallImageUrl = low, largeImageUrl = alternate)
        compose.runOnIdle { selected.value = updated }
        assertRealPixels(updated, RED)
    }

    private fun show(card: PokemonVisualCard) {
        selected.value = card
        compose.setContent {
            MaterialTheme {
                if (visible.value) selected.value?.let {
                    CatalogCardArtwork(it, Modifier.size(72.dp, 101.dp).testTag(IMAGE))
                }
            }
        }
        contentStarted = true
    }

    private fun assertRealPixels(card: PokemonVisualCard, expected: Int) {
        val node = compose.onNodeWithTag(IMAGE)
        node.assertIsDisplayed().assertContentDescriptionEquals(
            context.getString(R.string.catalog_card_image_description, card.name, card.number),
        )
        compose.waitUntil(10_000) {
            val pixels = node.captureToImage().toPixelMap()
            listOf(.25f to .25f, .5f to .5f, .75f to .75f).all { (x, y) ->
                val pixel = pixels[(pixels.width * x).toInt(), (pixels.height * y).toInt()]
                abs(pixel.red - Color.red(expected) / 255f) < .03f &&
                    abs(pixel.green - Color.green(expected) / 255f) < .03f &&
                    abs(pixel.blue - Color.blue(expected) / 255f) < .03f && pixel.alpha > .99f
            }
        }
    }

    private fun createOwnedFile(name: String): File {
        check(ownsImageRoot && name in setOf("low.png", "high.png", "alternate.png", "broken.png"))
        val file = File(imageRoot, name)
        check(file.canonicalFile.parentFile == imageRoot && file.createNewFile())
        check(createdFiles.add(name))
        return file
    }

    private fun createImage(name: String, color: Int): String {
        val file = createOwnedFile(name)
        val bitmap = Bitmap.createBitmap(72, 101, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        try { file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { bitmap.recycle() }
        return Uri.fromFile(file).toString()
    }

    private fun card(id: String, small: String, large: String) = PokemonVisualCard(
        id = id, name = "Synthetic $id", number = id.substringAfter('-'), rarity = null,
        smallImageUrl = small, largeImageUrl = large,
        set = PokemonSet("fixture", "Synthetic offline set", "Synthetic", 2, 2, "2026-10-03", null, null),
        sourceLocalId = id.substringAfter('-'),
    )

    private companion object {
        const val IMAGE = "catalog-card-artwork"
        val RED = Color.rgb(220, 36, 48)
        val GREEN = Color.rgb(32, 180, 76)
        val BLUE = Color.rgb(40, 90, 230)
    }
}
