package com.dominik.cardvault.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.dominik.cardvault.ui.theme.CardVaultTheme
import org.junit.Rule
import org.junit.Test

/** Deterministic presentation only; no live cache, settings or collection writes. */
class BinderCapacityPresentationInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun unknownSlotsDoNotPretendAConfirmedTotalOrProgress() {
        compose.setContent { CardVaultTheme {
            DomiBinderShelfCard("Testset", 1, null, 3, "Preis nicht verfügbar", null, false, {})
        } }
        compose.onNodeWithText("3 Karten im Binder", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Slotzahl nach Laden des Sets verfügbar", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Sammelplätze", substring = true, useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("%", substring = true, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun knownSlotsKeepCanonicalProgressSeparateFromDuplicateQuantity() {
        compose.setContent { CardVaultTheme {
            DomiBinderShelfCard("Testset", 1, 2, 3, "Preis nicht verfügbar", null, false, {})
        } }
        compose.onNodeWithText("3 Karten im Binder", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("1 / 2 Sammelplätze", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("50%", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Slotzahl nach Laden des Sets verfügbar", useUnmergedTree = true).assertDoesNotExist()
    }
}
