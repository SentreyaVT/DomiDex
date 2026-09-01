package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.core.app.ApplicationProvider
import com.dominik.cardvault.ui.theme.CardVaultTheme
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

class CardBoundaryEditorComposeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun largeGalleryImageCanBeShownSwitchedAndDismissedWithoutCanvasCrash() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val first = temporaryMediaImage(4_096, 3_072)
        val second = temporaryMediaImage(3_072, 4_096)
        val current = mutableStateOf(first)
        val visible = mutableStateOf(true)
        try {
            compose.setContent {
                CardVaultTheme {
                    if (visible.value) CardBoundaryEditor(current.value, onDismiss = { visible.value = false }, onApply = {})
                }
            }
            compose.waitUntil(timeoutMillis = 8_000) {
                compose.onAllNodesWithText("Kartenrahmen ausrichten").fetchSemanticsNodes().isNotEmpty()
            }
            // The former 799 MB Canvas crash happens when the asynchronously decoded bitmap is
            // first drawn, not when the dialog title appears.
            SystemClock.sleep(2_500)
            compose.waitForIdle()
            compose.runOnUiThread { current.value = second }
            SystemClock.sleep(100)
            compose.runOnUiThread { visible.value = false }
            compose.waitForIdle()
            assertFalse(visible.value)
        } finally {
            context.contentResolver.delete(first, null, null)
            context.contentResolver.delete(second, null, null)
        }
    }

    private fun temporaryMediaImage(width: Int, height: Int): Uri {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.DKGRAY) }
        @Suppress("DEPRECATION")
        val uri = Uri.parse(requireNotNull(MediaStore.Images.Media.insertImage(
            context.contentResolver, source, "domidex-grading-test-${System.nanoTime()}", "Temporary DomiDex grading UI test",
        )))
        source.recycle()
        return uri
    }
}
