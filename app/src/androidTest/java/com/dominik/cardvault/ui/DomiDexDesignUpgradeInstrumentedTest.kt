package com.dominik.cardvault.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.R
import com.dominik.cardvault.data.content.ContentUpdateState
import com.dominik.cardvault.model.BinderSummary
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionCard
import com.dominik.cardvault.model.PortfolioSummary
import com.dominik.cardvault.model.PrivateBinderSummary
import com.dominik.cardvault.ui.theme.CardVaultTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Actual presentation components with deterministic, deliberately synthetic values.
 * No activity under test starts app initialization, repositories, sign-in or Firebase.
 * The neutral card face is intentional: no external artwork/network requests are needed.
 */
@RunWith(AndroidJUnit4::class)
class DomiDexDesignUpgradeInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val automaticFocus get() = context.getString(R.string.focus_automatic)
    private val focusTitle get() = context.getString(R.string.focus_choose_title)

    @Test fun emptyDashboardKeepsHonestPriceStateAndBothQuickActions() {
        var binderOpens = 0
        var scannerOpens = 0
        compose.setContent {
            DesignFrame {
                DashboardScreen(
                    PortfolioSummary.Empty, emptyList(), ContentUpdateState(), "auto", {},
                    openBinder = { binderOpens++ }, openScanner = { scannerOpens++ },
                )
            }
        }
        compose.onNodeWithText("Kein Trendpreis verfügbar").assertIsDisplayed()
        saveScreenshot("dashboard-empty-fixture.png")
        compose.onNodeWithText("Scannen").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Binder").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Noch keine Karten im Binder").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, binderOpens)
            assertEquals(1, scannerOpens)
        }
    }

    @Test fun pricedDashboardFocusChooserUsesFixtureIdentityAndDoesNotWriteData() {
        val choices = mutableListOf<String>()
        compose.setContent {
            DesignFrame {
                DashboardScreen(
                    pricedPortfolio(), listOf(pricedBinder()), ContentUpdateState(), "auto", { choices.add(it) },
                    openBinder = {}, openScanner = {},
                )
            }
        }
        compose.onNodeWithText("Gesamtportfolio · alle Binder").assertIsDisplayed()
        compose.onNodeWithText(automaticFocus).assertIsDisplayed()
        saveScreenshot("dashboard-priced-fixture.png")
        compose.onNodeWithText(automaticFocus).performClick()
        compose.onNodeWithText(focusTitle).assertIsDisplayed()
        compose.onNode(hasText("Demokarte Aurora") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf("entry:19001"), choices) }
        compose.onNodeWithText(focusTitle).assertDoesNotExist()
    }

    @Test fun compactLargeTextDashboardKeepsPriceReadableAndActionsReachable() {
        var scannerOpens = 0
        var binderOpens = 0
        compose.setContent {
            DesignFrame(compact = true) {
                DashboardScreen(
                    pricedPortfolio(), listOf(pricedBinder()), ContentUpdateState(), "auto", {},
                    openBinder = { binderOpens++ }, openScanner = { scannerOpens++ },
                )
            }
        }
        // Portfolio figures must not be clipped by the hero's layout or inherited text metrics.
        val displayedValue = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.GERMANY).format(42.5)
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onAllNodesWithText(displayedValue, useUnmergedTree = true).onFirst()
            .assertIsDisplayed()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        saveScreenshot("dashboard-compact-large-text-fixture.png")
        assertTrue("The real portfolio value text must expose layout results", layouts.isNotEmpty())
        val layoutEvidence = layouts.joinToString("\n") { layout ->
            val input = layout.layoutInput
            "text=${input.text.text}; codepoints=${input.text.text.map { it.code.toString(16) }}; " +
                "measuredSize=${layout.size}; constraints=${input.constraints}; " +
                "paragraphWidth=${layout.multiParagraph.width}; paragraphHeight=${layout.multiParagraph.height}; " +
                "fontSize=${input.style.fontSize}; lineHeight=${input.style.lineHeight}; " +
                "letterSpacing=${input.style.letterSpacing}; fontWeight=${input.style.fontWeight}; " +
                "density=${input.density.density}; fontScale=${input.density.fontScale}; " +
                "maxLines=${input.maxLines}; softWrap=${input.softWrap}; overflow=${input.overflow}; " +
                "lineCount=${layout.lineCount}; didExceedMaxLines=${layout.multiParagraph.didExceedMaxLines}; " +
                "didOverflowWidth=${layout.didOverflowWidth}; " +
                "didOverflowHeight=${layout.didOverflowHeight}; " +
                "lines=${(0 until layout.lineCount).map { line ->
                    "[$line:left=${layout.getLineLeft(line)},right=${layout.getLineRight(line)}," +
                        "top=${layout.getLineTop(line)},bottom=${layout.getLineBottom(line)}," +
                        "start=${layout.getLineStart(line)},end=${layout.getLineEnd(line)}," +
                        "ellipsis=${layout.isLineEllipsized(line)}]"
                }}"
        }
        // Compose may retain the full available paragraph width while measuring this short
        // amount to its natural width (e.g. paragraph 640px, text 282px). hasVisualOverflow
        // then reports a false horizontal overflow. Check the actual laid-out text instead.
        // The one physical pixel tolerance covers raster/measurement rounding, not clipping.
        val amountFits = layouts.all { layout ->
            layout.layoutInput.text.text == displayedValue &&
                layout.lineCount > 0 &&
                !layout.multiParagraph.didExceedMaxLines &&
                layout.getLineStart(0) == 0 &&
                layout.getLineEnd(layout.lineCount - 1) == displayedValue.length &&
                (0 until layout.lineCount).all { line ->
                    !layout.isLineEllipsized(line) &&
                        layout.getLineLeft(line) >= -1f &&
                        layout.getLineRight(line) <= layout.size.width + 1f &&
                        layout.getLineTop(line) >= -1f &&
                        layout.getLineBottom(line) <= layout.size.height + 1f &&
                        (line == 0 || layout.getLineStart(line) == layout.getLineEnd(line - 1))
                }
        }
        assertTrue(
            "The portfolio amount is clipped at 320dp / 150% text:\n$layoutEvidence",
            amountFits,
        )
        compose.onNodeWithText(automaticFocus).performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText(focusTitle).assertIsDisplayed()
        compose.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
        // Target the LazyColumn's item lookup after returning from the modal, rather than
        // a label's previously composed off-screen bounds below the large-font hero.
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Scannen"))
        saveScreenshot("dashboard-large-text-actions-fixture.png")
        compose.onNodeWithText("Scannen").assertIsDisplayed().performClick()
        compose.onNodeWithText("Binder").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, scannerOpens); assertEquals(1, binderOpens) }
    }

    @Test fun standard360dpDashboardKeepsVaultCompactAndFocusCardLarger() {
        compose.setContent {
            DesignFrame(standardPhone = true) {
                DashboardScreen(
                    pricedPortfolio(), listOf(pricedBinder()), ContentUpdateState(), "auto", {},
                    openBinder = {}, openScanner = {},
                )
            }
        }
        val hero = compose.onNodeWithTag("dashboard-portfolio-hero")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        val card = compose.onNodeWithTag("dashboard-focus-card")
            .assertIsDisplayed().getUnclippedBoundsInRoot()
        saveScreenshot("dashboard-360dp-compact-vault-fixture.png")
        assertTrue("360dp vault should remain compact, actual bounds: $hero", hero.bottom - hero.top <= 340.dp)
        assertTrue("Focus card must be larger than the old 116dp thumbnail: $card", card.right - card.left >= 132.dp)
        assertTrue("Focus card must leave enough width for readable portfolio information: $card", card.right - card.left <= 145.dp)
        assertTrue("Focus card remains within the vault horizontally", card.left >= hero.left && card.right <= hero.right)
        assertTrue("Focus card remains within the vault vertically", card.top >= hero.top && card.bottom <= hero.bottom)
        compose.onNodeWithText(automaticFocus).assertIsDisplayed()
        compose.onNodeWithText("Scannen").assertIsDisplayed()
        compose.onNodeWithText("Binder").assertIsDisplayed()
    }

    @Test fun standard360dpLargeValuesUseFullWidthWithoutLosingOrWrappingDigits() {
        val currentValue = androidx.compose.runtime.mutableStateOf(12_345.67)
        compose.setContent {
            DesignFrame(standardPhone = true) {
                DashboardScreen(
                    pricedPortfolio(currentValue.value), listOf(pricedBinder()), ContentUpdateState(), "auto", {},
                    openBinder = {}, openScanner = {},
                )
            }
        }
        for ((value, screenshot) in listOf(
            12_345.67 to "dashboard-360dp-large-value-fixture.png",
            1_234_567.89 to "dashboard-360dp-million-value-fixture.png",
        )) {
            compose.runOnIdle { currentValue.value = value }
            val formatted = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.GERMANY).format(value)
            val node = compose.onNodeWithText(formatted, useUnmergedTree = true).assertIsDisplayed()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            saveScreenshot(screenshot)
            assertTrue("The large amount must expose real text metrics", layouts.isNotEmpty())
            for (layout in layouts) {
                val evidence = "value=$formatted; measured=${layout.size}; lines=${layout.lineCount}; " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}; " +
                    "fontSize=${layout.layoutInput.style.fontSize}; lineHeight=${layout.layoutInput.style.lineHeight}"
                assertEquals("All digits must remain in the text: $evidence", formatted, layout.layoutInput.text.text)
                assertEquals("The amount must not break into multiple lines: $evidence", 1, layout.lineCount)
                assertTrue("The amount must not truncate any digits: $evidence",
                    !layout.multiParagraph.didExceedMaxLines && !layout.isLineEllipsized(0) &&
                        layout.getLineStart(0) == 0 && layout.getLineEnd(0) == formatted.length)
                assertTrue("The amount must fit its real measured bounds: $evidence; " +
                    "line=(${layout.getLineLeft(0)},${layout.getLineTop(0)},${layout.getLineRight(0)},${layout.getLineBottom(0)})",
                    layout.getLineLeft(0) >= -1f && layout.getLineRight(0) <= layout.size.width + 1f &&
                        layout.getLineTop(0) >= -1f && layout.getLineBottom(0) <= layout.size.height + 1f)
            }
            val amountBounds = node.getUnclippedBoundsInRoot()
            val cardBounds = compose.onNodeWithTag("dashboard-focus-card").getUnclippedBoundsInRoot()
            assertTrue("Large amounts should use the full-width block above the card row", amountBounds.bottom <= cardBounds.top)
        }
    }

    @Test fun privateBinderShelfRendersActualCoversAndOpensSelectedBinder() {
        var opened: String? = null
        compose.setContent {
            DesignFrame {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    PrivateBinderShelfCard(
                        privateBinder("demo-aurora", "Aurora-Sammlung", "aurora"), selected = true,
                        onClick = { opened = "demo-aurora" }, modifier = Modifier.testTag("aurora-shelf"),
                    )
                    PrivateBinderShelfCard(
                        privateBinder("demo-galaxy", "Kosmische Lieblingskarten", "galaxy"), selected = false,
                        onClick = { opened = "demo-galaxy" }, modifier = Modifier.testTag("galaxy-shelf"),
                    )
                }
            }
        }
        compose.onNodeWithTag("aurora-shelf").assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithTag("galaxy-shelf").assertIsDisplayed().assertHasClickAction()
        saveScreenshot("private-shelf-fixture.png")
        compose.onNodeWithTag("galaxy-shelf").performClick()
        compose.runOnIdle { assertEquals("demo-galaxy", opened) }
    }

    @Test fun compactLargeTextPrivateShelfKeepsLongTitleAndOpenActionAccessible() {
        var opens = 0
        val longName = "Meine besonders seltenen holografischen Lieblingskarten"
        compose.setContent {
            DesignFrame(compact = true) {
                Box(Modifier.padding(12.dp)) {
                    PrivateBinderShelfCard(
                        privateBinder("demo-long", longName, "nebula"), selected = false,
                        onClick = { opens++ }, modifier = Modifier.testTag("long-title-shelf"),
                    )
                }
            }
        }
        // A two-line ellipsis is allowed, but the exact name remains in accessibility semantics.
        compose.onAllNodesWithText(longName, useUnmergedTree = true).onFirst().assertIsDisplayed()
        compose.onNodeWithText("Privaten Binder öffnen", useUnmergedTree = true).assertIsDisplayed()
        val rootBounds = compose.onNodeWithTag(ROOT_TAG).fetchSemanticsNode().boundsInRoot
        val shelfBounds = compose.onNodeWithTag("long-title-shelf").fetchSemanticsNode().boundsInRoot
        assertTrue("Compact shelf must fit horizontally", shelfBounds.left >= rootBounds.left && shelfBounds.right <= rootBounds.right)
        assertTrue("Compact shelf must fit vertically", shelfBounds.top >= rootBounds.top && shelfBounds.bottom <= rootBounds.bottom)
        saveScreenshot("private-shelf-compact-large-text-fixture.png")
        compose.onNodeWithTag("long-title-shelf").assertHasClickAction().performClick()
        compose.runOnIdle { assertEquals(1, opens) }
    }

    @Composable private fun DesignFrame(compact: Boolean = false, standardPhone: Boolean = false, content: @Composable () -> Unit) {
        val currentDensity = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(currentDensity.density, if (compact) 1.5f else 1f),
        ) {
            CardVaultTheme {
                Box(
                    (when {
                        compact -> Modifier.requiredSize(320.dp, 560.dp)
                        standardPhone -> Modifier.requiredSize(360.dp, 640.dp)
                        else -> Modifier.fillMaxSize()
                    })
                        .background(MaterialTheme.colorScheme.background).testTag(ROOT_TAG),
                ) { content() }
            }
        }
    }

    private fun saveScreenshot(name: String) {
        require(name in setOf(
            "dashboard-empty-fixture.png", "dashboard-priced-fixture.png",
            "dashboard-compact-large-text-fixture.png", "private-shelf-fixture.png",
            "private-shelf-compact-large-text-fixture.png", "dashboard-large-text-actions-fixture.png",
            "dashboard-360dp-compact-vault-fixture.png",
            "dashboard-360dp-large-value-fixture.png", "dashboard-360dp-million-value-fixture.png",
        ))
        compose.waitForIdle()
        val bitmap = compose.onNodeWithTag(ROOT_TAG).captureToImage().asAndroidBitmap()
        val directory = File(context.getExternalFilesDir(null) ?: context.cacheDir, "design-upgrade-fixtures")
        check(directory.mkdirs() || directory.isDirectory)
        File(directory, name).outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun pricedPortfolio(valueEur: Double = 42.5) = PortfolioSummary(listOf(CollectionCard(
        entryId = 19001, id = "synthetic-design-fixture", name = "Demokarte Aurora",
        set = "Demonstrationssammlung", number = "001", language = CardLanguage.GERMAN,
        finish = "Normal", variant = "Standard", condition = "Near Mint", quantity = 1,
        currentValueEur = valueEur, changePercent = 0.0, priceUpdatedAtEpochMillis = FIXTURE_TIME,
        notes = "Isolierte UI-Testdaten, kein echter Marktpreis", acquisitionPriceEur = null,
        acquisitionDateEpochDay = null, accent = 0xFF9D6AE8, binderId = "demo-set",
    )), emptyList())

    private fun pricedBinder() = BinderSummary(
        "demo-set", "Demonstrationssammlung", null, 1, 1, 42.5, FIXTURE_TIME,
    )

    private fun privateBinder(id: String, title: String, cover: String) = PrivateBinderSummary(
        id = id, name = title, description = "Isolierte Vorschau · keine Nutzerdaten",
        coverStyle = cover, minimumPageCount = 2, uniqueCards = 7, ownedCards = 9,
        marketValueEur = 127.5, createdAtEpochMillis = FIXTURE_TIME,
        updatedAtEpochMillis = FIXTURE_TIME, lastOpenedAtEpochMillis = null,
    )

    private companion object {
        const val ROOT_TAG = "design-upgrade-fixture-root"
        const val FIXTURE_TIME = 1_790_035_200_000L
    }
}
