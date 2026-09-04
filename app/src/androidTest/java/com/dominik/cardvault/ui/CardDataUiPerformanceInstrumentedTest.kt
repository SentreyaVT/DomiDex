package com.dominik.cardvault.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.MainActivity
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only UI benchmark. Requires the emulator's manually added PRE/Owei test card. */
@RunWith(AndroidJUnit4::class)
class CardDataUiPerformanceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val phase get() = InstrumentationRegistry.getArguments().getString("performancePhase") ?: "UNLABELLED"

    private fun find(node: AccessibilityNodeInfo?, predicate: (String) -> Boolean): AccessibilityNodeInfo? {
        if (node == null) return null
        if (predicate(node.text?.toString().orEmpty()) && node.isVisibleToUser) return node
        repeat(node.childCount) { find(node.getChild(it), predicate)?.let { result -> return result } }
        return null
    }

    private fun waitFor(predicate: (String) -> Boolean): AccessibilityNodeInfo {
        val deadline = SystemClock.elapsedRealtime() + 45_000
        while (SystemClock.elapsedRealtime() < deadline) {
            find(instrumentation.uiAutomation.rootInActiveWindow, predicate)?.let { return it }
            SystemClock.sleep(50)
        }
        error("Expected UI state did not appear ($phase)")
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).use {
            android.os.ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> stream.readBytes() }
        }
    }

    private fun tap(text: String) {
        val bounds = Rect()
        waitFor { it == text }.getBoundsInScreen(bounds)
        shell("input tap ${bounds.centerX()} ${bounds.centerY()}")
    }

    private fun measure(label: String, block: () -> Unit) {
        val started = SystemClock.elapsedRealtimeNanos()
        block()
        Log.i("DomiDexPerf", "$phase|$label|ms=${(SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0}")
    }

    @Test fun binderOpenPageTurnAndScannerNavigation() {
        measure("activity_to_usable_home") {
            instrumentation.targetContext.startActivity(Intent(instrumentation.targetContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            waitFor { it == "Settings" }
            // The navigation exists behind the arrival animation; wait for real home content.
            waitFor { it.contains("Lieblingskarte", ignoreCase = true) || it == "Scannen" || it == "SCANNEN" }
        }
        tap("Binder")
        waitFor { it == "Binder öffnen" }
        repeat(2) { pass ->
            measure("binder_open_ui_$pass") {
                tap("Binder öffnen")
                waitFor { it.startsWith("Vorderseite ·") }
            }
            measure("binder_page_ui_$pass") {
                tap("Weiter ›")
                waitFor { it.startsWith("Kartenseite 1 von") }
                waitFor { it == "001" || it == "Owei" }
            }
            shell("input keyevent KEYCODE_BACK")
            waitFor { it == "Binder öffnen" }
        }
        shell("pm grant com.dominik.cardvault android.permission.CAMERA")
        tap("Scanner")
        val scannerEntry = waitFor { it == "SCANNER" || it == "Bereit für deinen ersten Scan?" }
        if (scannerEntry.text?.toString() != "SCANNER") tap("Verstanden, Kamera öffnen")
        waitFor { it == "SCANNER" }
        Log.i("DomiDexPerf", "$phase|scanner_navigation|passed")
    }
}
