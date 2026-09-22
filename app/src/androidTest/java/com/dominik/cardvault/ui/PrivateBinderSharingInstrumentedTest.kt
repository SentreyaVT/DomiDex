package com.dominik.cardvault.ui

import android.graphics.Bitmap
import com.dominik.cardvault.ui.theme.CardVaultTheme as MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.sharing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Isolated UI fixtures only: no live account, public share or stored user collection. */
@RunWith(AndroidJUnit4::class)
class PrivateBinderSharingInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val token = "a".repeat(43)
    private val shareId = "b".repeat(64)
    private val url get() = "https://domidex.web.app/share/$token"

    private inner class FakeGateway : BinderSharingGateway {
        val creates = mutableListOf<Triple<String, String, BinderShareOptions>>()
        val revokes = mutableListOf<Pair<String, String>>()
        var existing = emptyList<BinderShareSummary>()
        var createError = false
        var deferred: CompletableDeferred<CreatedBinderShare>? = null
        var listDeferred: CompletableDeferred<List<BinderShareSummary>>? = null
        var lists = 0
        override suspend fun list(expectedUid: String, binderId: String): List<BinderShareSummary> {
            lists++
            listDeferred?.let { return it.await() }
            return existing
        }
        override suspend fun create(expectedUid: String, binderId: String, options: BinderShareOptions, displayName: String?, confirmed: Boolean): CreatedBinderShare {
            assertTrue(confirmed)
            creates += Triple(expectedUid, binderId, options)
            if (createError) throw BinderSharingException("Verbindung unterbrochen.")
            deferred?.let { return it.await() }
            val result = CreatedBinderShare(BinderShareSummary(shareId, 1L, options), url)
            existing = listOf(result.share)
            return result
        }
        override suspend fun revoke(expectedUid: String, shareId: String) {
            revokes += expectedUid to shareId
            existing = emptyList()
        }
    }

    private fun show(gateway: FakeGateway, onCopy: (String) -> Unit = {}, onShare: (String) -> Unit = {}) {
        compose.setContent { MaterialTheme {
            PrivateBinderShareDialog("binder-fixture", "Mein Testbinder", "owner-a", gateway, {}, onCopy, onShare)
        } }
        compose.waitForIdle()
    }

    @Test fun openingShowsPrivacyAndNeverCreatesShareOrEnablesOptions() {
        val gateway = FakeGateway()
        show(gateway)
        compose.onNodeWithText("Privaten Binder teilen").assertIsDisplayed()
        listOf("share-prices", "share-total", "share-name", "share-confirm").forEach {
            compose.onNodeWithTag(it).assertIsOff()
        }
        compose.onNodeWithTag("share-create").assertIsNotEnabled()
        assertTrue(gateway.creates.isEmpty())
        val image = compose.onNode(isDialog()).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "private-binder-sharing-fixture.png")
            .outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    @Test fun explicitConsentUsesExactOwnerBinderOptionsAndSharesOnlyCreatedLink() {
        val gateway = FakeGateway()
        var copied: String? = null
        var shared: String? = null
        show(gateway, { copied = it }, { shared = it })
        compose.onNodeWithTag("share-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("share-prices").performScrollTo().performClick()
        compose.onNodeWithTag("share-confirm").assertIsOff()
        compose.onNodeWithTag("share-create").assertIsNotEnabled()
        compose.onNodeWithTag("share-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("share-create").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf(Triple("owner-a", "binder-fixture", BinderShareOptions(showPrices = true))), gateway.creates)
        assertNull(shared)
        assertNull(copied)
        compose.onNodeWithText("Kopieren").performScrollTo().performClick()
        compose.onNodeWithText("Link teilen").performScrollTo().performClick()
        assertEquals(url, copied)
        assertEquals(url, shared)
        compose.onNodeWithTag("share-confirm").assertIsOff()
    }

    @Test fun failedCreateRequiresRefreshBeforeAnotherAttempt() {
        val gateway = FakeGateway().apply { createError = true }
        show(gateway)
        compose.onNodeWithTag("share-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("share-create").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("share-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("share-create").assertIsNotEnabled()
        assertEquals(1, gateway.creates.size)
        compose.onNodeWithText("Freigaben aktualisieren").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("share-create").assertIsEnabled()
        assertEquals(2, gateway.lists)
    }

    @Test fun revokeNeedsSeparateConfirmationAndRemovesCreatedLink() {
        val gateway = FakeGateway()
        show(gateway)
        compose.onNodeWithTag("share-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("share-create").performScrollTo().performClick()
        compose.onNodeWithText("Freigabe beenden").performScrollTo().performClick()
        assertTrue(gateway.revokes.isEmpty())
        compose.onNodeWithText("Beenden bestätigen").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(listOf("owner-a" to shareId), gateway.revokes)
        compose.onNodeWithText("Link teilen").assertDoesNotExist()
        compose.onNodeWithText("Aktive Freigaben (0)").assertExists()
    }

    @Test fun sameBinderIdWithDifferentOwnerResetsConsentAndIgnoresPendingResult() {
        val gateway = FakeGateway().apply { deferred = CompletableDeferred() }
        val owner = mutableStateOf("owner-a")
        compose.setContent { MaterialTheme {
            PrivateBinderShareDialog("same-binder", "Testbinder", owner.value, gateway, {}, {}, {})
        } }
        compose.onNodeWithTag("share-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("share-create").performScrollTo().performClick()
        compose.runOnIdle { owner.value = "owner-b" }
        compose.waitForIdle()
        compose.onNodeWithTag("share-confirm").assertIsOff()
        compose.onNodeWithTag("share-create").assertIsNotEnabled()
        compose.runOnIdle { gateway.deferred!!.complete(CreatedBinderShare(BinderShareSummary(shareId, 1L, BinderShareOptions()), url)) }
        compose.waitForIdle()
        compose.onNodeWithText("Link teilen").assertDoesNotExist()
        assertEquals("owner-a", gateway.creates.single().first)
    }

    @Test fun refreshingCannotRaceWithRevokeAndRestoreAnOldActiveShare() {
        val gateway = FakeGateway().apply { existing = listOf(BinderShareSummary(shareId, 1L, BinderShareOptions())) }
        show(gateway)
        compose.onNodeWithText("Freigabe beenden").performScrollTo().performClick()
        compose.runOnIdle { gateway.listDeferred = CompletableDeferred() }
        compose.onNodeWithText("Freigaben aktualisieren").performScrollTo().performClick()
        compose.onNodeWithText("Beenden bestätigen").assertIsNotEnabled()
        compose.onNodeWithText("Freigaben aktualisieren").assertIsNotEnabled()
        assertTrue(gateway.revokes.isEmpty())
        compose.runOnIdle { gateway.listDeferred!!.complete(gateway.existing) }
        compose.waitForIdle()
        compose.onNodeWithText("Beenden bestätigen").assertIsEnabled()
    }

    @Test fun reopeningDoesNotRememberLinkOrConsentButListsExistingShare() {
        val gateway = FakeGateway()
        val open = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (open.value) {
            PrivateBinderShareDialog("binder-fixture", "Testbinder", "owner-a", gateway, { open.value = false }, {}, {})
        } } }
        compose.onNodeWithTag("share-confirm").performScrollTo().performClick()
        compose.onNodeWithTag("share-create").performScrollTo().performClick()
        compose.onNodeWithText("Schließen").performClick()
        compose.runOnIdle { open.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("share-confirm").assertIsOff()
        compose.onNodeWithText("Link teilen").assertDoesNotExist()
        compose.onNodeWithText("Aktive Freigaben (1)").assertExists()
        assertEquals(1, gateway.creates.size)
    }

    @Test fun realPrivateBinderReaderContainsShareEntryWithoutChangingOwnership() {
        val database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
        try {
            val repository = RoomCollectionRepository(database, MutableStateFlow("fixture-owner"))
            val binder = runBlocking {
                val id = repository.createPrivateBinder("Testbinder")
                repository.privateBinders.first().single { it.id == id }
            }
            val showReader = mutableStateOf(true)
            compose.setContent { MaterialTheme { if (showReader.value) PrivateBinderReaderScreen(binder, emptyList(), repository, {}) } }
            compose.onNodeWithText("Teilen").assertIsDisplayed().performClick()
            compose.onNodeWithText("Privaten Binder teilen").assertIsDisplayed()
            compose.onNodeWithText("Freigabelink erstellen").assertDoesNotExist()
            compose.onNodeWithText("Schließen").performClick()
            compose.runOnIdle { runBlocking { assertEquals(0, repository.portfolio.first().totalCards) } }
            compose.runOnIdle { showReader.value = false }
            compose.waitForIdle()
        } finally { database.close() }
    }
}
