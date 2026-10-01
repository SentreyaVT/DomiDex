package com.dominik.cardvault.ui

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.sharing.*
import com.dominik.cardvault.ui.theme.CardVaultTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic memory-only UI tests; no public link, account or collection is used. */
@RunWith(AndroidJUnit4::class)
class PrivateBinderQuickSharingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val result = CreatedBinderShare(
        BinderShareSummary("b".repeat(64), 1L, BinderShareOptions()),
        "https://domidex.web.app/share/${"A".repeat(43)}",
    )
    private inner class FakeGateway : BinderSharingGateway {
        var creates = 0
        var error = false
        var pending: CompletableDeferred<CreatedBinderShare>? = null
        override suspend fun list(expectedUid: String, binderId: String): List<BinderShareSummary> =
            throw AssertionError("Quick sharing must not list links first")
        override suspend fun create(expectedUid: String, binderId: String, options: BinderShareOptions,
            displayName: String?, confirmed: Boolean): CreatedBinderShare {
            creates++
            assertEquals("fixture-owner", expectedUid)
            assertEquals("fixture-binder", binderId)
            assertEquals(BinderShareOptions(), options)
            assertNull(displayName)
            assertTrue(confirmed)
            if (error) throw BinderSharingException("Unklarer Serverstatus. Bitte Freigaben prüfen.")
            return pending?.await() ?: result
        }
        override suspend fun revoke(expectedUid: String, shareId: String) = throw AssertionError("Unexpected revoke")
    }

    @Test fun explicitClickCreatesOnceWithoutListAndOnlyThenOffersCopyAndShare() {
        val gateway = FakeGateway()
        val opened = mutableStateOf(false)
        var copied: String? = null
        var shared: String? = null
        compose.setContent { CardVaultTheme {
            Button(onClick = { opened.value = true }) { Text("Teilen") }
            if (opened.value) PrivateBinderQuickShareDialog(
                "fixture-binder", "Testbinder", "fixture-owner", gateway,
                { opened.value = false }, {}, { copied = it }, { shared = it },
            )
        } }
        compose.waitForIdle()
        assertEquals(0, gateway.creates)
        compose.onNodeWithText("Teilen").performClick()
        compose.waitForIdle()
        assertEquals(1, gateway.creates)
        assertNull(copied)
        assertNull(shared)
        compose.onNodeWithText("Kopieren").performScrollTo().performClick()
        compose.onNodeWithText("Link teilen").performScrollTo().performClick()
        assertEquals(result.url, copied)
        assertEquals(result.url, shared)
        compose.onNodeWithText("Schließen").performClick()
        compose.onNodeWithText(result.url).assertDoesNotExist()
    }

    @Test fun recompositionCannotDuplicatePendingPostOrExposePrematureLink() {
        val gateway = FakeGateway().apply { pending = CompletableDeferred() }
        val name = mutableStateOf("Testbinder")
        compose.setContent { CardVaultTheme {
            PrivateBinderQuickShareDialog("fixture-binder", name.value, "fixture-owner", gateway, {}, {}, {}, {})
        } }
        compose.waitForIdle()
        assertEquals(1, gateway.creates)
        compose.onNodeWithText("Link teilen").assertDoesNotExist()
        compose.onNodeWithText("Freigaben verwalten").assertIsNotEnabled()
        compose.runOnIdle { name.value = "Neuer Titel" }
        compose.waitForIdle()
        assertEquals(1, gateway.creates)
        compose.runOnIdle { gateway.pending!!.complete(result) }
        compose.waitForIdle()
        compose.onNodeWithText("Link teilen").assertExists()
        assertEquals(1, gateway.creates)
    }

    @Test fun ambiguousPostHasNoAutomaticRetryAndOffersManagement() {
        val gateway = FakeGateway().apply { error = true }
        val name = mutableStateOf("Testbinder")
        var managed = false
        compose.setContent { CardVaultTheme {
            PrivateBinderQuickShareDialog("fixture-binder", name.value, "fixture-owner", gateway, {}, { managed = true }, {}, {})
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("quick-sharing-notice").assertExists()
        compose.onNodeWithText("Link teilen").assertDoesNotExist()
        compose.runOnIdle { name.value = "Neuer Titel" }
        compose.waitForIdle()
        assertEquals(1, gateway.creates)
        compose.onNodeWithText("Freigaben verwalten").performScrollTo().performClick()
        assertTrue(managed)
        assertEquals(1, gateway.creates)
    }
}
