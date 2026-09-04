package com.dominik.cardvault.ui

import android.app.UiAutomation
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import android.view.Surface
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.MainActivity
import com.dominik.cardvault.R
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs against the real activity on a phone/phone AVD. Navigation requires an existing guest
 * installation with the tutorial already answered. No cards, consent, accounts, or permissions
 * are changed, no shutter is pressed, and the device's rotation setting is restored in finally.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class PortraitLockInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation
    private val rotations = listOf(
        Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270,
    )

    @Test fun allDeviceRotationsKeepTheOriginalActivityInPortrait() {
        withActivity { scenario, original ->
            rotations.forEach { rotation ->
                rotateAndAssert(scenario, original, rotation, "launch")
            }
        }
    }

    @Test fun guestNavigationAndGoogleLoginEntryRemainPortraitWithoutRecreation() {
        assertNull(
            "Requires a guest installation; this test must not sign out an existing account",
            canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser),
        )
        assertTrue(
            "Complete the initial tutorial choice before this read-only navigation test",
            context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE)
                .getBoolean("relaxed_tutorial_answered_v1", false),
        )
        withActivity { scenario, original ->
            waitText(string(R.string.dashboard_title))
            rotateAndAssert(scenario, original, Surface.ROTATION_0, "overview")

            // Portfolio is part of Übersicht, not a separate navigation tab.
            scrollToText("Gesamtportfolio · alle Binder")
            rotateAndAssert(scenario, original, Surface.ROTATION_180, "portfolio")

            tapTab(string(R.string.nav_binder))
            waitText("Deine Binder")
            rotateAndAssert(scenario, original, Surface.ROTATION_90, "binder")

            tapTab(string(R.string.nav_collection))
            waitText(string(R.string.collection_eyebrow))
            rotateAndAssert(scenario, original, Surface.ROTATION_270, "collection")

            tapTab(string(R.string.nav_scanner))
            val preferences = context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE)
            if (preferences.getBoolean("scanner_camera_intro_accepted_v1", false)) {
                waitText("SCANNER")
                waitText("Galerie")
            } else {
                // The entry dialog belongs to ScannerScreen and must obey the same lock.
                // Do not accept consent or request camera permission just to make this test pass.
                waitText(string(R.string.scanner_camera_intro_title))
            }
            rotations.forEach { rotation ->
                rotateAndAssert(scenario, original, rotation, "scanner entry")
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            waitText(string(R.string.dashboard_title))

            tapTab(string(R.string.nav_settings))
            waitText("Einstellungen")
            rotateAndAssert(scenario, original, Surface.ROTATION_0, "settings")
            scrollToText("Konto")
            tapText("Konto")
            val login = scrollToText(string(R.string.account_login_google))
            val button = clickableAncestor(login)
            assertTrue("Google sign-in entry must be clickable", button != null)
            assertTrue("Google sign-in entry must be enabled for a guest", button?.isEnabled == true)
            rotateAndAssert(scenario, original, Surface.ROTATION_270, "guest account dialog")
            // Only inspect the sign-in entry: never invoke Credential Manager or Firebase auth.
            tapText("Fertig")
            tapTab(string(R.string.nav_portfolio))
            waitText(string(R.string.dashboard_title))
            assertPortrait(scenario, original, "returned to overview")
            assertNull(canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser))
        }
    }

    private fun withActivity(block: (ActivityScenario<MainActivity>, MainActivity) -> Unit) {
        val autoRotate = Settings.System.getInt(
            context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0,
        )
        val savedRotation = Settings.System.getInt(
            context.contentResolver, Settings.System.USER_ROTATION,
            context.getSystemService(WindowManager::class.java).defaultDisplay.rotation,
        )
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var original: MainActivity
                scenario.onActivity { original = it }
                waitUntil("activity content is laid out") {
                    var laidOut = false
                    scenario.onActivity { laidOut = it.window.decorView.width > 0 }
                    laidOut
                }
                assertPortrait(scenario, original, "initial activity")
                block(scenario, original)
            }
        } finally {
            assertTrue("Could not restore saved device rotation", automation.setRotation(savedRotation))
            if (autoRotate != 0) {
                assertTrue("Could not restore automatic rotation", automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
            }
        }
    }

    private fun rotateAndAssert(
        scenario: ActivityScenario<MainActivity>, original: MainActivity, rotation: Int, screen: String,
    ) {
        assertTrue("Could not simulate rotation $rotation", automation.setRotation(rotation))
        // Sample across configuration dispatch and animation, not just immediately after the request.
        repeat(6) {
            SystemClock.sleep(150)
            assertPortrait(scenario, original, "$screen / rotation $rotation")
        }
    }

    private fun assertPortrait(scenario: ActivityScenario<MainActivity>, original: MainActivity, phase: String) {
        scenario.onActivity { current ->
            assertSame("Activity recreated at $phase", original, current)
            assertFalse("Activity closed at $phase", current.isFinishing || current.isDestroyed)
            assertEquals("Orientation request at $phase", ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, current.requestedOrientation)
            assertEquals("Configuration at $phase", Configuration.ORIENTATION_PORTRAIT, current.resources.configuration.orientation)
            val decor = current.window.decorView
            assertTrue("Content is not portrait at $phase: ${decor.width}x${decor.height}", decor.width < decor.height)
        }
    }

    private fun string(id: Int): String = context.getString(id)

    private fun visibleNodes(): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(automation.rootInActiveWindow)
    }

    private fun matchingText(text: String): List<AccessibilityNodeInfo> = visibleNodes().filter {
        it.text?.toString()?.equals(text, ignoreCase = true) == true
    }

    private fun waitText(text: String): AccessibilityNodeInfo {
        waitUntil("visible text: $text") { matchingText(text).isNotEmpty() }
        return matchingText(text).first()
    }

    private fun tapTab(text: String) {
        waitText(text)
        // A dashboard action or screen heading may share the label; navigation is lowest on screen.
        val node = matchingText(text).maxBy { Rect().also(it::getBoundsInScreen).centerY() }
        click(node, "tab $text")
    }

    private fun tapText(text: String) = click(waitText(text), text)

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current
    }

    private fun click(node: AccessibilityNodeInfo, label: String) {
        val clickable = clickableAncestor(node) ?: error("No clickable control for $label")
        assertTrue("Disabled control: $label", clickable.isEnabled)
        assertTrue("Click failed: $label", clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        SystemClock.sleep(400)
    }

    private fun scrollToText(text: String): AccessibilityNodeInfo {
        repeat(12) {
            matchingText(text).firstOrNull()?.let { return it }
            val scrollable = visibleNodes().filter { it.isScrollable }.maxByOrNull {
                Rect().also(it::getBoundsInScreen).height()
            } ?: error("No scrollable content while looking for $text")
            assertTrue("Cannot scroll to $text", scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD))
            SystemClock.sleep(250)
        }
        return waitText(text)
    }

    private fun waitUntil(description: String, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 30_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (predicate()) return
            SystemClock.sleep(100)
        }
        error("Timed out waiting for $description")
    }
}
