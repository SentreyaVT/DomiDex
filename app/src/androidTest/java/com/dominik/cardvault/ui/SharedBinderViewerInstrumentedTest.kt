package com.dominik.cardvault.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.dominik.cardvault.data.sharing.*
import com.dominik.cardvault.ui.theme.CardVaultTheme
import java.io.File
import kotlinx.coroutines.awaitCancellation
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Native recipient fixtures only: no account, real share token, network or collection writes. */
@RunWith(AndroidJUnit4::class)
class SharedBinderViewerInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val token = "A".repeat(43)

    private class FakeGateway : SharedBinderGateway {
        val pages = mutableListOf<Int>()
        @Volatile var revoked = false
        override suspend fun load(token: String, page: Int): SharedBinderPage {
            pages += page
            if (revoked) throw SharedBinderContract.unavailable()
            return SharedBinderPage(
                "Geteilter Testbinder", "galaxy", page, 2,
                listOf(SharedBinderCard(page * 9, "base1-1", if (page == 0) "Pikachu" else "Evoli", "Basis", "1", null, "Holo", "rainbow", .5f)),
                setOf(page * 9 + 1), displayName = "Testsammler",
            )
        }
        override suspend fun image(url: String): ByteArray = throw AssertionError("No external image in fixtures")
    }

    @Test fun nativeReaderShowsPublicCoverGridAndDetailsWithoutOwnerActions() {
        val gateway = FakeGateway()
        compose.setContent { CardVaultTheme { SharedBinderScreen(token, gateway, true, {}) } }
        compose.onNodeWithText("Geteilter Binder · Nur ansehen").assertIsDisplayed()
        compose.onNodeWithText("Geteilter Testbinder").assertIsDisplayed()
        compose.onNodeWithText("Geteilt von Testsammler").assertIsDisplayed()
        compose.onNodeWithTag("shared-binder-grid").assertIsDisplayed()
        listOf("Löschen", "Teilen", "Karte", "Zur Sammlung hinzufügen", "Freigabelink erstellen").forEach { compose.onNodeWithText(it).assertDoesNotExist() }
        compose.onNodeWithText("Binderwert:", substring = true).assertDoesNotExist()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "native-shared-binder-fixture.png")
            .outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        compose.onNodeWithContentDescription("Pikachu, Nummer 1, groß ansehen").performClick()
        compose.onNodeWithTag("shared-card-detail").assertIsDisplayed()
        compose.onNodeWithText("Basis · 1").assertIsDisplayed()
        compose.onNodeWithText("Holo-Effekt wählen").assertDoesNotExist()
        compose.onNodeWithText("Cardmarket:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Großansicht schließen").performClick()
        compose.onNodeWithTag("shared-card-detail").assertDoesNotExist()
    }

    @Test fun nativePagingRequestsOnlySelectedPages() {
        val gateway = FakeGateway()
        compose.setContent { CardVaultTheme { SharedBinderScreen(token, gateway, true, {}) } }
        compose.onNodeWithTag("shared-page-previous").assertIsNotEnabled()
        compose.onNodeWithTag("shared-page-next").performClick()
        compose.onNodeWithText("Seite 2 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Evoli, Nummer 1, groß ansehen").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pikachu, Nummer 1, groß ansehen").assertDoesNotExist()
        compose.onNodeWithTag("shared-page-next").assertIsNotEnabled()
        compose.onNodeWithTag("shared-page-previous").performClick()
        compose.onNodeWithText("Seite 1 / 2").assertIsDisplayed()
        assertEquals(listOf(0, 1, 0), gateway.pages)
    }

    @Test fun revocationRecheckClearsBothNativeGridAndOpenDetail() {
        val gateway = FakeGateway()
        compose.setContent { CardVaultTheme { SharedBinderScreen(token, gateway, true, {}, pollMillis = 100) } }
        compose.onNodeWithContentDescription("Pikachu, Nummer 1, groß ansehen").performClick()
        compose.onNodeWithTag("shared-card-detail").assertIsDisplayed()
        compose.runOnIdle { gateway.revoked = true }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("shared-binder-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("shared-card-detail").assertDoesNotExist()
        compose.onNodeWithTag("shared-binder-grid").assertDoesNotExist()
        compose.onNodeWithText("Diese Freigabe ist nicht verfügbar oder wurde beendet.").assertIsDisplayed()
    }

    @Test fun backgroundHidesContentsAndResumingRechecksAccess() {
        val gateway = FakeGateway()
        val visible = mutableStateOf(true)
        compose.setContent { CardVaultTheme { SharedBinderScreen(token, gateway, visible.value, {}) } }
        compose.onNodeWithTag("shared-binder-grid").assertIsDisplayed()
        compose.runOnIdle { visible.value = false }
        compose.onNodeWithTag("shared-binder-grid").assertDoesNotExist()
        compose.runOnIdle { gateway.revoked = true; visible.value = true }
        compose.onNodeWithTag("shared-binder-error").assertIsDisplayed()
        compose.onNodeWithTag("shared-binder-grid").assertDoesNotExist()
        assertEquals(listOf(0, 0), gateway.pages)
    }

    @Test fun invalidReplacementLinkNeverKeepsPreviousBinderVisible() {
        val gateway = FakeGateway()
        val linkToken = mutableStateOf<String?>(token)
        compose.setContent { CardVaultTheme { SharedBinderScreen(linkToken.value, gateway, true, {}) } }
        compose.onNodeWithContentDescription("Pikachu, Nummer 1, groß ansehen").performClick()
        compose.runOnIdle { linkToken.value = null }
        compose.onNodeWithTag("shared-binder-grid").assertDoesNotExist()
        compose.onNodeWithTag("shared-card-detail").assertDoesNotExist()
        compose.onNodeWithTag("shared-binder-error").assertIsDisplayed()
        assertEquals(listOf(0), gateway.pages)
    }

    @Test fun lifecycleStopCancelsRequestWithoutWaitingForVisibilityRecomposition() {
        val owner = object : LifecycleOwner {
            val registry = LifecycleRegistry(this)
            override val lifecycle: Lifecycle get() = registry
        }
        val loads = AtomicInteger()
        val cancelled = AtomicBoolean()
        val gateway = object : SharedBinderGateway {
            override suspend fun load(token: String, page: Int): SharedBinderPage {
                if (loads.incrementAndGet() == 1) {
                    try { awaitCancellation() } finally { cancelled.set(true) }
                }
                throw SharedBinderContract.unavailable()
            }
            override suspend fun image(url: String) = byteArrayOf()
        }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CardVaultTheme { SharedBinderScreen(token, gateway, true, {}) }
            }
        }
        compose.waitUntil(5_000) { loads.get() == 1 }
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5_000) { cancelled.get() }
        assertEquals(1, loads.get())
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("shared-binder-error").assertIsDisplayed()
        assertEquals(2, loads.get())
    }
}
