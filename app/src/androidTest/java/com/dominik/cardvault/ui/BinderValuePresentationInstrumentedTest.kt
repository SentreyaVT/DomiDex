package com.dominik.cardvault.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.model.BinderSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated presentation fixtures; no account, network or stored collection is modified. */
@RunWith(AndroidJUnit4::class)
class BinderValuePresentationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun missingPriceIsNotAnEndlessSyncPromise() {
        val binder = BinderSummary("fixture", "Test", null, 1, 1, 0.0, null)
        compose.setContent { MaterialTheme { CurrentBinderValueCard(listOf(binder)) {} } }
        compose.onNodeWithText("Preis nicht verfügbar").assertIsDisplayed()
        compose.onNodeWithText("Trendpreise werden noch synchronisiert").assertDoesNotExist()
    }

    @Test fun availablePriceDisclosesMissingCoverage() {
        val priced = BinderSummary("priced", "Test", null, 1, 1, 12.0, 1L)
        val missing = BinderSummary("missing", "Test", null, 1, 1, 0.0, null)
        compose.setContent { MaterialTheme { CurrentBinderValueCard(listOf(priced, missing)) {} } }
        compose.onNodeWithText("Wert verfügbarer Cardmarket-Preise · 1/2 Binder mit Preisdaten. Fehlende Preise sind nicht enthalten.").assertIsDisplayed()
    }

    @Test fun emptyBinderDoesNotPretendPricesAreLoading() {
        compose.setContent { MaterialTheme { CurrentBinderValueCard(emptyList()) {} } }
        compose.onNodeWithText("Noch keine Karten im Binder").assertIsDisplayed()
        compose.onNodeWithText("Preis nicht verfügbar").assertDoesNotExist()
    }

    @Test fun comparisonOnlyBinderContributesAndIsNotPresentedAsATrend() {
        val chosen = BinderSummary("comparison", "Test", null, 1, 1, 5.32, null, alternativePricedCards = 1)
        compose.setContent { MaterialTheme { CurrentBinderValueCard(listOf(chosen)) {} } }
        compose.onNodeWithText("Preis nicht verfügbar").assertDoesNotExist()
        compose.onNodeWithText("Enthält 1 ausdrücklich gewählte Vergleichswerte, keine Trendpreise.", substring = true).assertIsDisplayed()
    }
}
