package com.dominik.cardvault.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.R
import com.dominik.cardvault.auth.GoogleAccountState
import com.dominik.cardvault.ui.theme.CardVaultTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production presentation components with synthetic callbacks; no repository, login or writes. */
@RunWith(AndroidJUnit4::class)
class DomiDexOverlayInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun realBottomNavigationKeepsAllFiveTabsReachableAt320dpAnd150PercentText() {
        assertRealNavigation(compact = true)
    }

    @Test fun realBottomNavigationKeepsAllFiveTabsReachableAt360dpNormalText() {
        assertRealNavigation(compact = false)
    }

    private fun assertRealNavigation(compact: Boolean) {
        val selected = mutableStateOf(Destination.DASHBOARD)
        val callbacks = mutableListOf<Destination>()
        compose.setContent {
            OverlayFrame(compact) {
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    // The actual navigation, not a fixture reconstruction of its five tabs.
                    DomiDexBottomNavigation(selected.value) {
                        callbacks += it
                        selected.value = it
                    }
                }
            }
        }
        val destinations = listOf(Destination.DASHBOARD, Destination.SCANNER, Destination.BINDER,
            Destination.COLLECTION, Destination.SETTINGS)
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.onAllNodes(tabRole).assertCountEquals(5)
        val frame = compose.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
        val density = frame.width / if (compact) 320f else 360f
        saveScreenshot(if (compact) "navigation-320dp-150-percent.png" else "navigation-360dp-normal.png")
        val bounds = destinations.map { destination ->
            val node = compose.onNode(tabRole and hasText(context.getString(destination.label)))
            node.assertIsDisplayed().assertHasClickAction()
            val rect = node.fetchSemanticsNode().boundsInRoot
            assertTrue("${destination.name} fell outside the actual overlay viewport", rect.left >= frame.left - .5f &&
                rect.right <= frame.right + .5f && rect.top >= frame.top - .5f && rect.bottom <= frame.bottom + .5f)
            assertTrue("${destination.name} touch target is ${rect.width / density} x ${rect.height / density} dp; expected at least 48 x 48 dp", rect.width >= 48f * density - .5f &&
                rect.height >= 48f * density - .5f)
            rect
        }
        bounds.zipWithNext().forEach { (left, right) ->
            assertTrue("Adjacent navigation tabs overlap", left.right <= right.left + .5f)
        }
        // Change tabs, reselect the current one, then return home. The visual redesign
        // must neither swallow nor duplicate a real onSelected callback.
        val sequence = listOf(Destination.BINDER, Destination.COLLECTION, Destination.SCANNER,
            Destination.SETTINGS, Destination.SETTINGS, Destination.DASHBOARD)
        for (destination in sequence) {
            compose.onNode(tabRole and hasText(context.getString(destination.label))).performClick()
            compose.waitForIdle()
            destinations.forEach { item ->
                val node = compose.onNode(tabRole and hasText(context.getString(item.label)))
                if (item == destination) node.assertIsSelected() else node.assertIsNotSelected()
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(context.getString(item.label), useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                // Layout sizes round to physical pixels; tolerate that rounding, never ellipsis.
                assertTrue("${item.name} label clipped: ${layouts.map { "size=${it.size}, paragraph=${it.multiParagraph.width}x${it.multiParagraph.height}, lines=${it.lineCount}, ellipsis=${(0 until it.lineCount).any(it::isLineEllipsized)}" }}",
                    layouts.isNotEmpty() && layouts.all { layout ->
                        layout.multiParagraph.height <= layout.size.height + 1f &&
                            (0 until layout.lineCount).none { layout.isLineEllipsized(it) ||
                                layout.getLineLeft(it) < -1f || layout.getLineRight(it) > layout.size.width + 1f }
                    })
            }
            compose.onAllNodes(tabRole and SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
                .assertCountEquals(1)
            if (destination == Destination.BINDER) {
                saveScreenshot(if (compact) "navigation-320dp-150-percent.png" else "navigation-360dp-normal.png")
            }
        }
        compose.runOnIdle { assertEquals(sequence, callbacks) }
    }

    @Test fun longSettingRowAndEmptyStateKeepTheirActualActionsReachableAtLargeText() {
        val title = "Anzeige, Bewegung und persönliche Gestaltung"
        var settingsOpened = 0
        var cardsAdded = 0
        compose.setContent {
            OverlayFrame(compact = true) {
                Column(
                    Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    DomiGlassCard {
                        DomiSettingRow(
                            icon = DomiIconKind.SETTINGS,
                            title = title,
                            subtitle = "Passe Darstellung und Bewegungen in Ruhe an deine persönlichen Vorlieben an.",
                            value = "Standard",
                            onClick = { settingsOpened++ },
                        )
                    }
                    DomiEmptyState(
                        title = "Hier beginnt deine Sammlung",
                        body = "Deine Lieblingskarten bekommen hier ihren Platz. Füge die erste Karte hinzu, sobald du bereit bist.",
                        actionLabel = "Karte hinzufügen",
                        onAction = { cardsAdded++ },
                    )
                }
            }
        }
        compose.onNodeWithText(title).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened); assertEquals(0, cardsAdded) }
        saveScreenshot("settings-long-title-320dp-150-percent.png")
        compose.onNodeWithText("Karte hinzufügen").performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, settingsOpened); assertEquals(1, cardsAdded) }
        saveScreenshot("empty-state-action-320dp-150-percent.png")
    }

    @Test fun actualThemeAccountCardKeepsFakeAccountActionsReachableAtLargeText() {
        val account = mutableStateOf(GoogleAccountState(true, "DomiDex Testkonto", "demo@example.test"))
        var switches = 0
        var logouts = 0
        var logins = 0
        compose.setContent {
            OverlayFrame(compact = true) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp)) {
                    GoogleAccountCardContent(
                        account = account.value,
                        working = false,
                        feedback = null,
                        onSignIn = { logins++ },
                        onSignOut = { logouts++; account.value = GoogleAccountState(false, null) },
                        onSwitchAccount = { switches++ },
                    )
                }
            }
        }
        compose.onNodeWithText("DomiDex Testkonto").assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.account_switch)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, switches); assertEquals(0, logouts); assertEquals(0, logins) }
        saveScreenshot("account-synthetic-320dp-150-percent.png")
        compose.onNodeWithText(context.getString(R.string.account_sign_out)).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("demo@example.test").assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.account_login_google)).performScrollTo().assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, switches); assertEquals(1, logouts); assertEquals(1, logins) }
        saveScreenshot("account-signed-out-320dp-150-percent.png")
    }

    @Composable private fun OverlayFrame(compact: Boolean, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, if (compact) 1.5f else 1f)) {
            CardVaultTheme {
                Box(
                    Modifier.requiredSize(if (compact) 320.dp else 360.dp, if (compact) 560.dp else 640.dp)
                        .background(MaterialTheme.colorScheme.background).testTag(ROOT_TAG),
                    content = content,
                )
            }
        }
    }

    private fun saveScreenshot(name: String) {
        require(name.matches(Regex("[a-z0-9-]+\\.png")))
        compose.waitForIdle()
        val image = compose.onNodeWithTag(ROOT_TAG).captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "overlay-theme-fixtures")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, name).outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private companion object { const val ROOT_TAG = "domidex-overlay-synthetic-fixture" }
}
