package com.dominik.cardvault.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.MainActivity
import com.dominik.cardvault.R
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.model.CollectionCard
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Uses only the emulator's existing guest Owei/PRE binder. Refresh may update catalogue reference
 * caches; no owned card is added, edited, deleted, or linked, and no account is signed in/out.
 */
@RunWith(AndroidJUnit4::class)
class BinderRefreshNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val pagePattern = Regex("^Kartenseite (\\d+) von (\\d+)$")
    private val refreshDescription = "Binder aktualisieren"
    private val loadingLabel = "Deutsche Setvarianten werden geprüft …"

    @Test fun threeRefreshesFromPage22PreserveNavigationActivityAndOwnedCards() {
        assertGuest()
        assertTrue(
            "Complete the initial tutorial choice before this navigation test",
            context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE)
                .getBoolean("relaxed_tutorial_answered_v1", false),
        )
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var original: MainActivity
            scenario.onActivity { original = it }
            waitText(context.getString(R.string.dashboard_title))
            val repository = RoomCollectionRepository(CardVaultDatabase.get(context))
            val before = snapshot(repository)
            assertTrue("Requires the existing Owei collection; never seed it in this test", before.isNotEmpty())
            assertTrue("Only the emulator's Owei/PRE collection is in scope", before.all {
                it.name == "Owei" && it.set == "Prismatische Entwicklungen"
            })
            val binders = runBlocking(Dispatchers.IO) { withTimeout(15_000L) { repository.binders.first() } }
            assertEquals("Requires exactly the existing PRE binder", 1, binders.size)
            assertEquals("Prismatische Entwicklungen", binders.single().name)
            assertTrue("All owned cards must belong to the PRE binder", before.all { it.binderId == binders.single().id })
            assertActivity(scenario, original, "overview")

            val binderTab = waitNodes("Binder tab") { nodes ->
                nodes.filter { it.text?.toString() == context.getString(R.string.nav_binder) }
            }.maxBy { Rect().also(it::getBoundsInScreen).centerY() }
            click(binderTab, "Binder tab")
            waitText("Deine Binder")
            SystemClock.sleep(500)
            // The whole shelf card opens the binder; its bottom caption can be
            // outside the visible viewport while the title is visible.
            click(scrollToText("Prismatische Entwicklungen"), "PRE Binder öffnen")
            waitPosition()
            waitRefreshReady(scenario, original, "initial reference load")
            assertOwnedOweiInNumberedGroup(repository)
            checkpoint(0, "ready", readPosition())

            repeat(3) { index ->
                val pass = index + 1
                navigateToCardPage(22)
                val requestedFrom = readPosition()
                assertEquals("Refresh must exercise the formerly crashing high page", 22, requestedFrom.page)
                assertActivity(scenario, original, "pass $pass before refresh")
                checkpoint(pass, "refresh_requested", requestedFrom)
                val refresh = refreshControl() ?: error("Missing $refreshDescription")
                assertTrue("Refresh is still loading", refresh.isEnabled)
                click(refresh, refreshDescription, settleMillis = 0)
                waitRefreshReady(scenario, original, "pass $pass refresh", afterClick = true)
                checkpoint(pass, "refresh_complete", readPosition())
                assertEquals("Owned collection changed after refresh $pass", before, snapshot(repository))
                assertOwnedOweiInNumberedGroup(repository)

                // Reload may clamp the current page; read the visible position on every step.
                navigateToFrontCover()
                assertEquals(PageKind.FRONT, readPosition().kind)
                checkpoint(pass, "front_cover", readPosition())
                navigateToCardPage(1)
                assertEquals(1, readPosition().page)
                navigateToCardPage(22)
                assertEquals(22, readPosition().page)
                assertActivity(scenario, original, "pass $pass after navigating again")
                assertEquals("Owned collection changed during navigation $pass", before, snapshot(repository))
                checkpoint(pass, "navigation_verified", readPosition())
            }
            assertGuest()
            assertEquals("Owned collection changed after all three refreshes", before, snapshot(repository))
            checkpoint(3, "complete_collection_unchanged", readPosition())
        }
    }

    /** Excludes live market prices, their timestamps, and portfolio history. */
    private data class OwnedCardSnapshot(
        val entryId: Long, val cardId: String, val binderId: String,
        val sourceSetId: String?, val sourceCardId: String?, val externalProductId: Long?,
        val name: String, val set: String, val number: String, val language: String,
        val finish: String, val variant: String, val quantity: Int, val condition: String,
        val notes: String, val acquisitionPriceEur: Double?, val acquisitionDateEpochDay: Long?,
        val effectOverride: String?,
    )

    private fun snapshot(repository: RoomCollectionRepository): List<OwnedCardSnapshot> =
        runBlocking(Dispatchers.IO) {
            withTimeout(15_000L) {
                repository.portfolio.first().cards.sortedBy(CollectionCard::entryId).map {
                    OwnedCardSnapshot(
                        it.entryId, it.id, it.binderId, it.sourceSetId, it.sourceCardId, it.externalProductId,
                        it.name, it.set, it.number, it.language.name, it.finish, it.variant, it.quantity,
                        it.condition, it.notes, it.acquisitionPriceEur, it.acquisitionDateEpochDay, it.effectOverride,
                    )
                }
            }
        }

    private fun assertOwnedOweiInNumberedGroup(repository: RoomCollectionRepository) = runBlocking(Dispatchers.IO) {
        withTimeout(15_000L) {
            val owned = repository.portfolio.first().cards
            val references = repository.binderReferences("Prismatische Entwicklungen")
            val slots = arrangeBinderCards("Prismatische Entwicklungen", owned, 280, references)
            val nextNumberIndex = slots.indexOfFirst { it?.number?.substringBefore('/')?.toIntOrNull() == 2 }
            assertTrue("The existing PRE reference group 002 must be loaded", nextNumberIndex >= 0)
            owned.forEach { card ->
                assertEquals("Requires existing Owei #001", 1, card.number.substringBefore('/').toIntOrNull())
                val index = slots.indexOfFirst { it?.entryId == card.entryId }
                assertTrue("Owned #001 appended or misplaced: index=$index, next=$nextNumberIndex, " +
                    "finish=${card.finish}, variant=${card.variant}, source=${card.sourceSetId}/${card.sourceCardId}",
                    index in 0 until nextNumberIndex)
            }
        }
    }

    private enum class PageKind { FRONT, CARDS, BACK }
    private data class Position(val kind: PageKind, val page: Int?, val total: Int?, val label: String)

    private fun position(nodes: List<AccessibilityNodeInfo>): Position? = nodes.firstNotNullOfOrNull { node ->
        val text = node.text?.toString().orEmpty()
        val match = pagePattern.matchEntire(text)
        when {
            match != null -> Position(PageKind.CARDS, match.groupValues[1].toInt(), match.groupValues[2].toInt(), text)
            text.startsWith("Vorderseite ·") -> Position(PageKind.FRONT, 0, null, text)
            text.startsWith("Rückseite ·") -> Position(PageKind.BACK, null, null, text)
            else -> null
        }
    }

    private fun readPosition(): Position = position(visibleNodes()) ?: error("Binder page label is missing")

    private fun waitPosition(): Position {
        waitNodes("Binder page label") { nodes -> if (position(nodes) != null) nodes else emptyList() }
        return readPosition()
    }

    private fun navigateToCardPage(target: Int) {
        repeat(128) {
            val current = readPosition()
            if (current.kind == PageKind.CARDS) {
                assertTrue("Loaded binder has only ${current.total} card pages; page $target is required", current.total!! >= target)
                if (current.page == target) return
            }
            val forward = current.kind == PageKind.FRONT ||
                (current.kind == PageKind.CARDS && current.page!! < target)
            turnPage(if (forward) "Weiter ›" else "‹ Zurück", current)
        }
        error("Could not navigate to card page $target from ${readPosition().label}")
    }

    private fun navigateToFrontCover() {
        repeat(128) {
            val current = readPosition()
            if (current.kind == PageKind.FRONT) return
            turnPage("‹ Zurück", current)
        }
        error("Could not return to the front cover")
    }

    private fun turnPage(button: String, previous: Position) {
        click(waitText(button), button, settleMillis = 0)
        waitNodes("page change from ${previous.label}", timeoutMillis = 10_000L) { nodes ->
            val next = position(nodes)
            if (next != null && next != previous) nodes else emptyList()
        }
        // Let the outgoing AnimatedContent face finish before the next deliberate page turn.
        SystemClock.sleep(220)
    }

    private fun waitRefreshReady(
        scenario: ActivityScenario<MainActivity>, original: MainActivity, phase: String, afterClick: Boolean = false,
    ) {
        val started = SystemClock.elapsedRealtime()
        var loadingObserved = false
        while (SystemClock.elapsedRealtime() - started < 120_000L) {
            val nodes = visibleNodes()
            val control = refreshControl(nodes)
            val loadingText = nodes.any { it.text?.toString() == loadingLabel }
            if (loadingText || control?.isEnabled == false) loadingObserved = true
            val elapsed = SystemClock.elapsedRealtime() - started
            assertActivity(scenario, original, phase)
            // A queued accessibility click may not have reached Compose yet. Seeing the
            // old enabled button is not completion: observe the reload before waiting for ready.
            if (control?.isEnabled == true && !loadingText && (!afterClick || loadingObserved)) {
                Log.i("DomiDexBinderQA", "$phase|ready|elapsedMs=$elapsed|loadingObserved=$loadingObserved")
                return
            }
            SystemClock.sleep(150)
        }
        error("$refreshDescription did not become enabled within 120 seconds: $phase")
    }

    private fun refreshControl(nodes: List<AccessibilityNodeInfo> = visibleNodes()): AccessibilityNodeInfo? =
        nodes.firstOrNull { it.contentDescription?.toString() == refreshDescription }?.let(::clickableAncestor)

    private fun assertActivity(scenario: ActivityScenario<MainActivity>, original: MainActivity, phase: String) {
        scenario.onActivity { current ->
            assertSame("Activity recreated at $phase", original, current)
            assertFalse("Activity closed at $phase", current.isFinishing || current.isDestroyed)
            assertEquals("Orientation request at $phase", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, current.requestedOrientation)
            assertEquals("Orientation at $phase", Configuration.ORIENTATION_PORTRAIT, current.resources.configuration.orientation)
            assertTrue("Content is not portrait at $phase", current.window.decorView.width < current.window.decorView.height)
        }
    }

    private fun assertGuest() = assertNull(
        "Requires the guest emulator; never sign out a real account for this test",
        canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser),
    )

    private fun checkpoint(pass: Int, phase: String, current: Position) {
        Log.i("DomiDexBinderQA", "pass=$pass|$phase|${current.label}")
    }

    private fun visibleNodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun waitText(text: String): AccessibilityNodeInfo =
        waitNodes("text: $text") { nodes -> nodes.filter { it.text?.toString() == text } }.first()

    private fun waitNodes(
        description: String, timeoutMillis: Long = 30_000L,
        select: (List<AccessibilityNodeInfo>) -> List<AccessibilityNodeInfo>,
    ): List<AccessibilityNodeInfo> {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            select(visibleNodes()).takeIf { it.isNotEmpty() }?.let { return it }
            SystemClock.sleep(75)
        }
        error("Timed out waiting for $description")
    }

    private fun scrollToText(text: String): AccessibilityNodeInfo {
        repeat(16) {
            val nodes = visibleNodes()
            nodes.firstOrNull { it.text?.toString() == text }?.let { return it }
            val scrollable = nodes.filter { it.isScrollable }.maxByOrNull {
                Rect().also(it::getBoundsInScreen).height()
            } ?: error("No scrollable content while looking for $text")
            if (!scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) {
                // Compose can expose its scroll node before the accessibility scroll action
                // is ready. Use a normal gesture within that node, never app/data shortcuts.
                val bounds = Rect().also(scrollable::getBoundsInScreen)
                instrumentation.uiAutomation.executeShellCommand(
                    "input swipe ${bounds.centerX()} ${bounds.bottom - bounds.height() / 8} " +
                        "${bounds.centerX()} ${bounds.top + bounds.height() / 8} 350",
                ).use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
                }
            }
            SystemClock.sleep(500)
        }
        return waitText(text)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }

    private fun click(node: AccessibilityNodeInfo, label: String, settleMillis: Long = 400) {
        val control = clickableAncestor(node) ?: error("No clickable control for $label")
        assertTrue("Disabled control: $label", control.isEnabled)
        assertTrue("Click failed: $label", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        if (settleMillis > 0) SystemClock.sleep(settleMillis)
    }
}
