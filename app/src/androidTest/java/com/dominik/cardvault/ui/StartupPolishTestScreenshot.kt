package com.dominik.cardvault.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File

/** These are isolated demonstration states, not screenshots of a real account or collection. */
internal fun ComposeTestRule.saveSyntheticStartupScreenshot(name: String) {
    require(name in setOf("welcome-test-state.png", "startup-test-state.png"))
    waitForIdle()
    val image = onRoot().captureToImage()
    val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "grand-start-polish-fixtures")
    check(directory.mkdirs() || directory.isDirectory)
    File(directory, name).outputStream().use { check(image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)) }
}
