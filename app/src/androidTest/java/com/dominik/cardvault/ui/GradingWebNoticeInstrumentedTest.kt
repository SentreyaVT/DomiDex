package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.R
import com.dominik.cardvault.data.content.ContentUpdateState
import com.dominik.cardvault.model.PortfolioSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Pure UI with empty local state; no camera, account, collection or network operations. */
@RunWith(AndroidJUnit4::class)
class GradingWebNoticeInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val notice = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.grading_web_notice)

    @Test fun dashboardOffersScannerAndBinderButOnlyInformsAboutWebGrading() {
        var scans = 0
        var binders = 0
        compose.setContent {
            MaterialTheme {
                DashboardScreen(PortfolioSummary.Empty, emptyList(), ContentUpdateState(), "auto", {},
                    openBinder = { binders++ }, openScanner = { scans++ })
            }
        }
        compose.onNodeWithText("Scannen").performScrollTo().performClick()
        compose.onNodeWithText("Binder").performScrollTo().performClick()
        compose.onNodeWithText("Zustand").assertDoesNotExist()
        compose.onNodeWithText(notice).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, scans); assertEquals(1, binders) }
    }

    @Test fun legacyGradingDestinationIsANonInteractiveNotice() {
        compose.setContent { MaterialTheme { GradingWebNotice() } }
        val node = compose.onNodeWithText(notice).assertIsDisplayed().fetchSemanticsNode()
        assertFalse(node.config.contains(SemanticsActions.OnClick))
        compose.onNodeWithText("Ausrichten").assertDoesNotExist()
        compose.onNodeWithText("Foto-Checkliste").assertDoesNotExist()
    }
}
