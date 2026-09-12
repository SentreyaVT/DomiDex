package com.dominik.cardvault.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.BuildConfig
import com.dominik.cardvault.MainActivity
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.google.firebase.auth.FirebaseAuth
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only navigation against the emulator's guest account; never creates/deletes/syncs data. */
@RunWith(AndroidJUnit4::class)
class SettingsNavigationInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun find(node: AccessibilityNodeInfo?, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isVisibleToUser && predicate(node)) return node
        repeat(node.childCount) { find(node.getChild(it), predicate)?.let { return it } }
        return null
    }

    private fun waitText(text: String): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 30_000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()?.equals(text, ignoreCase = true) == true }?.let { return it }
            SystemClock.sleep(100)
        }
        error("Settings did not display expected label: $text")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }

    private fun tap(text: String) {
        val bounds = Rect()
        waitText(text).getBoundsInScreen(bounds)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
        SystemClock.sleep(350)
    }

    private fun scrollTo(text: String) {
        repeat(9) {
            if (find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()?.equals(text, ignoreCase = true) == true } != null) return
            find(instrumentation.uiAutomation.rootInActiveWindow) { it.isScrollable }
                ?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            SystemClock.sleep(350)
        }
        waitText(text)
    }

    @Test fun guestSettingsConsentVersionAndSafeDeletionNavigation() {
        assertNull("Requires guest emulator; do not sign out a real user for this test", canonicalCollectionOwnerUid(FirebaseAuth.getInstance().currentUser))
        instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitText("Settings")
        SystemClock.sleep(2500)
        tap("Settings")
        waitText("Konto & Datenschutz")
        tap("Konto")
        waitText("Mit Google anmelden")
        tap("Firebase / Sync-Status")
        waitText("Nicht angemeldet · Gastbestand bleibt lokal. Keine Kontosynchronisierung.")
        tap("Fertig")
        tap("Scanner-Verbesserung")
        waitText("Fehlscans werden nur über „An ScanLab schicken“ gesendet.")
        waitText("Melde dich an, um einen Fehlscan zu senden.")
        tap("Fertig")
        tap("Datenschutz")
        waitText("https://domidex.web.app/datenschutz")
        tap("Fertig")
        tap("Update")
        waitText("DomiDex ${BuildConfig.VERSION_NAME} · Build ${BuildConfig.VERSION_CODE}")
        waitText("App-Updates über Google Play")
        waitText("Firebase-Synchronisierung")
        assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString()?.contains("GitHub", ignoreCase = true) == true })
        assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Nach Update suchen" })
        tap("Fertig")
        scrollTo("Über DomiDex")
        tap("Über DomiDex")
        waitText("Pre-Beta · privat · Non-Profit")
        waitText("Version ${BuildConfig.VERSION_NAME}\nBuild ${BuildConfig.VERSION_CODE}")
        tap("Fertig")
        scrollTo("Konto löschen")
        tap("Konto löschen")
        val action = waitText("E-Mail-Anfrage vorbereiten")
        var parent: AccessibilityNodeInfo? = action
        while (parent != null && !parent.isClickable) parent = parent.parent
        assertTrue("Unconfirmed deletion request must be disabled", parent?.isEnabled != true)
        tap("Abbrechen")
        waitText("Konto- & Datenverwaltung")
        assertNull(find(instrumentation.uiAutomation.rootInActiveWindow) { it.text?.toString() == "Admin / Entwickler" })
        tap("Binder")
        waitText("Deine Binder")
    }
}
