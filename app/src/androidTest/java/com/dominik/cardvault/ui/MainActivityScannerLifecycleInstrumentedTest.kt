package com.dominik.cardvault.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.MainActivity
import com.dominik.cardvault.R
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Separate opt-in real MainActivity/ScannerScreen/LiveCameraScanner preview proof.
 * Root must verify exact owned5556/APKs and emulated-back/no-front before launch.
 * No permission grant/revoke, welcome/intro acceptance, photo, queue, collection,
 * account or upload action. Not HOME, process death, fresh-frame/JPEG or OEM proof.
 * Kept outside the immutable existing class-wide371 and camera2 selections.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityScannerLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val automation get() = instrumentation.uiAutomation

    @Test fun mainActivityScannerPreviewStopsAndResumesWithoutImplicitCapture() {
        requireReadOnlyPreconditions()
        val preferences = preferenceSnapshot()
        val protectedFiles = protectedFileSnapshot()
        val previousCapture = LatestCardCapture.path
        val autoRotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val userRotation = Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
        val events = mutableListOf<PreviewView.StreamState>() // Access only on the main thread.
        var scenario: ActivityScenario<MainActivity>? = null
        var observedPreview: PreviewView? = null
        val observer = Observer<PreviewView.StreamState> { events += it }
        try {
            val activityScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activityScenario
            waitUntil("prepared guest dashboard") { hasText(context.getString(R.string.dashboard_title)) }
            // This navigation alone must not answer a welcome/tutorial/camera-consent dialog.
            val entry = waitNode("existing Scanner navigation") {
                visibleNodes().singleOrNull { it.text?.toString() == context.getString(R.string.nav_scanner) }
            }
            click(entry)
            waitUntil("real scanner controls") { hasText("SCANNER") && hasText("Galerie") && hasText("Auto aus") }
            assertManualUiWithoutQueue()
            lateinit var original: MainActivity
            lateinit var preview: PreviewView
            waitUntil("single production PreviewView") {
                var found = false
                activityScenario.onActivity { activity ->
                    val views = previewViews(activity.window.decorView)
                    assertTrue("Scanner must not contain multiple camera previews", views.size <= 1)
                    if (views.size == 1) { original = activity; preview = views.single(); found = true }
                }
                found
            }
            instrumentation.runOnMainSync {
                observedPreview = preview
                preview.previewStreamState.observeForever(observer)
            }
            waitUntil("actual initial STREAMING notification") {
                onMain { preview.previewStreamState.value == PreviewView.StreamState.STREAMING &&
                    events.lastOrNull() == PreviewView.StreamState.STREAMING }
            }
            activityScenario.onActivity {
                assertSame(original, it)
                assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                assertSame(preview, previewViews(it.window.decorView).single())
            }
            assertUnchanged(preferences, protectedFiles, previousCapture)
            val beforeStop = onMain { events.size }

            // CREATED drives a real ON_PAUSE/ON_STOP, unlike STARTED. No manual unbind.
            activityScenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(Lifecycle.State.CREATED, activityScenario.state)
            var stoppedEventCount = -1
            waitUntil("new IDLE notification after actual Activity stop") {
                onMain {
                    assertEquals(Lifecycle.State.CREATED, original.lifecycle.currentState)
                    val stopped = preview.previewStreamState.value == PreviewView.StreamState.IDLE &&
                        events.drop(beforeStop).contains(PreviewView.StreamState.IDLE)
                    if (stopped) stoppedEventCount = events.size
                    stopped
                }
            }
            val holdEnd = SystemClock.elapsedRealtime() + 1_000L
            while (SystemClock.elapsedRealtime() < holdEnd) {
                onMain {
                    assertEquals(Lifecycle.State.CREATED, original.lifecycle.currentState)
                    assertFalse(original.isDestroyed)
                    assertEquals(PreviewView.StreamState.IDLE, preview.previewStreamState.value)
                    assertFalse("Preview restarted during stopped hold",
                        events.drop(stoppedEventCount).contains(PreviewView.StreamState.STREAMING))
                }
                assertEquals(previousCapture, LatestCardCapture.path)
                SystemClock.sleep(50)
            }
            onMain {
                assertEquals(PreviewView.StreamState.IDLE, preview.previewStreamState.value)
                assertFalse(events.drop(stoppedEventCount).contains(PreviewView.StreamState.STREAMING))
            }
            assertUnchanged(preferences, protectedFiles, previousCapture)
            val beforeResume = onMain { events.size }

            // Existing production composition and lifecycle binding recover themselves.
            activityScenario.moveToState(Lifecycle.State.RESUMED)
            waitUntil("new STREAMING notification after actual Activity resume") {
                onMain {
                    assertSame(preview, previewViews(original.window.decorView).single())
                    preview.previewStreamState.value == PreviewView.StreamState.STREAMING &&
                        events.drop(beforeResume).contains(PreviewView.StreamState.STREAMING)
                }
            }
            activityScenario.onActivity {
                assertSame("Stop/resume must not recreate the MainActivity", original, it)
                assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                assertFalse(it.isFinishing || it.isDestroyed)
            }
            waitUntil("manual controls after resume") { hasText("SCANNER") && hasText("Auto aus") }
            assertManualUiWithoutQueue()
            assertUnchanged(preferences, protectedFiles, previousCapture)
        } finally {
            // No permission restoration, file deletion or package stop: this test owns none.
            try {
                instrumentation.runOnMainSync { observedPreview?.previewStreamState?.removeObserver(observer) }
            } finally {
                try { scenario?.close() } finally {
                    instrumentation.waitForIdleSync()
                    assertUnchanged(preferences, protectedFiles, previousCapture)
                    assertEquals(autoRotate, Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0))
                    assertEquals(userRotation, Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0))
                }
            }
        }
    }

    /** A new Activity and production binding, not a same-instance stop/resume or process death. */
    @Test fun mainActivityScannerRecreationRetiresOldPreviewAndStartsNewWithoutCapture() {
        requireReadOnlyPreconditions()
        // Read only the existing business DB before any real Activity/startup/recovery.
        // A framework read handle may take locks/use SHM; this is not whole-file immutability.
        // Canonicalize the OS-owned user0 root once; reject aliases below that root.
        val preparedRoot = context.dataDir.canonicalFile
        assertTrue("Only the prepared standard user0 app root", preparedRoot.path in setOf(
            "/data/user/0/${context.packageName}", "/data/data/${context.packageName}",
        ))
        val preparedDatabase = File(preparedRoot, "databases/cardvault.db")
        assertEquals("Exact Context database location", preparedDatabase, context.getDatabasePath("cardvault.db").canonicalFile)
        assertEquals("No aliased prepared database", preparedDatabase.absoluteFile, preparedDatabase.canonicalFile)
        assertTrue("Never create a missing prepared database", preparedDatabase.isFile)
        val preparedFiles = File(preparedRoot, "files")
        assertEquals("Exact Context files location", preparedFiles, context.filesDir.canonicalFile)
        assertEquals("No aliased prepared files directory", preparedFiles.absoluteFile, preparedFiles.canonicalFile)
        assertTrue("Existing prepared files directory required", preparedFiles.isDirectory)
        fun requireNoBackupJournal() {
            listOf("backup-import-journal.json", "backup-import-journal.tmp").forEach { name ->
                val journal = File(preparedFiles, name)
                assertEquals("No aliased backup journal", journal.absoluteFile, journal.canonicalFile)
                assertFalse("Pending backup recovery requires separate review", journal.exists())
            }
        }
        requireNoBackupJournal()
        android.database.sqlite.SQLiteDatabase.openDatabase(
            preparedDatabase.absolutePath,
            null,
            android.database.sqlite.SQLiteDatabase.OPEN_READONLY or
                android.database.sqlite.SQLiteDatabase.NO_LOCALIZED_COLLATORS,
            // The default corruption handler can delete files even for a read-only open.
            android.database.DatabaseErrorHandler {
                throw android.database.sqlite.SQLiteException("Read-only precondition found corruption; never delete or repair")
            },
        ).use { readOnlyDatabase ->
            assertTrue("Only an explicitly read-only DB handle", readOnlyDatabase.isReadOnly)
            assertEquals("Already-prepared schema required; never migrate", 13, readOnlyDatabase.version)
            readOnlyDatabase.rawQuery(
                "SELECT COUNT(*) FROM app_metadata WHERE `key` = ?",
                arrayOf(com.dominik.cardvault.data.backup.BackupSettings.PENDING_KEY),
            ).use { cursor ->
                assertEquals(1, cursor.columnCount)
                assertEquals(1, cursor.count)
                assertTrue(cursor.moveToFirst())
                assertEquals(android.database.Cursor.FIELD_TYPE_INTEGER, cursor.getType(0))
                assertEquals("Pending presentation restore requires separate review", 0L, cursor.getLong(0))
                assertFalse(cursor.moveToNext())
            }
        }
        requireNoBackupJournal()
        val preferences = preferenceSnapshot()
        val protectedFiles = protectedFileSnapshot()
        val previousCapture = LatestCardCapture.path
        val autoRotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val userRotation = Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0)
        // Both event lists, lifecycle states and View identities are read/written on Main only.
        val oldEvents = mutableListOf<PreviewView.StreamState>()
        val newEvents = mutableListOf<PreviewView.StreamState>()
        val oldObserver = Observer<PreviewView.StreamState> { oldEvents += it }
        val newObserver = Observer<PreviewView.StreamState> { newEvents += it }
        var scenario: ActivityScenario<MainActivity>? = null
        var observedOldPreview: PreviewView? = null
        var observedNewPreview: PreviewView? = null
        try {
            val activityScenario = ActivityScenario.launch(MainActivity::class.java)
            scenario = activityScenario
            waitUntil("prepared guest dashboard before recreation") { hasText(context.getString(R.string.dashboard_title)) }
            click(waitNode("existing Scanner navigation before recreation") {
                visibleNodes().singleOrNull { it.text?.toString() == context.getString(R.string.nav_scanner) }
            })
            waitUntil("real scanner controls before recreation") { hasText("SCANNER") && hasText("Galerie") && hasText("Auto aus") }
            assertManualUiWithoutQueue()
            lateinit var original: MainActivity
            lateinit var oldPreview: PreviewView
            waitUntil("single original production PreviewView") {
                var found = false
                activityScenario.onActivity { activity ->
                    val views = previewViews(activity.window.decorView)
                    assertTrue("Original scanner must not contain multiple previews", views.size <= 1)
                    if (views.size == 1) { original = activity; oldPreview = views.single(); found = true }
                }
                found
            }
            instrumentation.runOnMainSync {
                observedOldPreview = oldPreview
                oldPreview.previewStreamState.observeForever(oldObserver)
            }
            waitUntil("actual original STREAMING before recreation") {
                onMain { oldPreview.previewStreamState.value == PreviewView.StreamState.STREAMING &&
                    oldEvents.lastOrNull() == PreviewView.StreamState.STREAMING }
            }
            activityScenario.onActivity {
                assertSame(original, it)
                assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                assertTrue(oldPreview.isAttachedToWindow)
                assertSame(oldPreview, previewViews(it.window.decorView).single())
            }
            assertUnchanged(preferences, protectedFiles, previousCapture)
            val beforeRecreation = onMain { oldEvents.size }

            // Real Activity destruction/recreation. No manual CameraX bind/unbind or state injection.
            activityScenario.recreate()
            assertEquals(Lifecycle.State.RESUMED, activityScenario.state)
            var firstRetiredIdle = -1
            waitUntil("destroyed Activity and detached old preview with a new IDLE event") {
                onMain {
                    val idleOffset = oldEvents.drop(beforeRecreation).indexOf(PreviewView.StreamState.IDLE)
                    val retired = original.isDestroyed && original.lifecycle.currentState == Lifecycle.State.DESTROYED &&
                        !oldPreview.isAttachedToWindow && oldPreview.previewStreamState.value == PreviewView.StreamState.IDLE &&
                        idleOffset >= 0
                    if (retired) firstRetiredIdle = beforeRecreation + idleOffset
                    retired
                }
            }
            fun assertRetiredPreviewStaysIdle() {
                onMain {
                    assertTrue(original.isDestroyed)
                    assertEquals(Lifecycle.State.DESTROYED, original.lifecycle.currentState)
                    assertFalse(oldPreview.isAttachedToWindow)
                    assertEquals(PreviewView.StreamState.IDLE, oldPreview.previewStreamState.value)
                    assertTrue("An actual post-recreation IDLE event is required", firstRetiredIdle >= beforeRecreation)
                    assertFalse("Retired preview emitted STREAMING after its first retirement IDLE",
                        oldEvents.drop(firstRetiredIdle + 1).contains(PreviewView.StreamState.STREAMING))
                }
            }
            assertRetiredPreviewStaysIdle()
            assertUnchanged(preferences, protectedFiles, previousCapture)

            // The saved scanner destination must restore itself; never navigate again to conceal a lost route.
            waitUntil("restored production scanner controls") { hasText("SCANNER") && hasText("Galerie") && hasText("Auto aus") }
            lateinit var replacement: MainActivity
            lateinit var newPreview: PreviewView
            waitUntil("single replacement production PreviewView") {
                var found = false
                activityScenario.onActivity { activity ->
                    assertFalse("Recreation must replace the original Activity", activity === original)
                    val views = previewViews(activity.window.decorView)
                    assertTrue("Replacement scanner must not contain multiple previews", views.size <= 1)
                    if (views.size == 1) {
                        replacement = activity
                        newPreview = views.single()
                        assertFalse("Recreation must replace the original PreviewView", newPreview === oldPreview)
                        found = true
                    }
                }
                found
            }
            instrumentation.runOnMainSync {
                observedNewPreview = newPreview
                newPreview.previewStreamState.observeForever(newObserver)
            }
            waitUntil("actual replacement STREAMING notification") {
                assertRetiredPreviewStaysIdle()
                onMain { newPreview.isAttachedToWindow &&
                    newPreview.previewStreamState.value == PreviewView.StreamState.STREAMING &&
                    newEvents.lastOrNull() == PreviewView.StreamState.STREAMING }
            }
            activityScenario.onActivity {
                assertSame(replacement, it)
                assertFalse(it.isFinishing || it.isDestroyed)
                assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                assertSame(newPreview, previewViews(it.window.decorView).single())
            }
            // Retain the old observer while the replacement runs, catching transient late reactivation too.
            val holdEnd = SystemClock.elapsedRealtime() + 1_000L
            while (SystemClock.elapsedRealtime() < holdEnd) {
                assertRetiredPreviewStaysIdle()
                assertEquals(previousCapture, LatestCardCapture.path)
                SystemClock.sleep(50)
            }
            assertRetiredPreviewStaysIdle()
            onMain {
                assertTrue(newPreview.isAttachedToWindow)
                assertEquals(PreviewView.StreamState.STREAMING, newPreview.previewStreamState.value)
            }
            assertManualUiWithoutQueue()
            assertUnchanged(preferences, protectedFiles, previousCapture)
        } finally {
            // Only remove this test's observers and close its ActivityScenario; no file/permission cleanup.
            try {
                instrumentation.runOnMainSync {
                    observedOldPreview?.previewStreamState?.removeObserver(oldObserver)
                    observedNewPreview?.previewStreamState?.removeObserver(newObserver)
                }
            } finally {
                try { scenario?.close() } finally {
                    instrumentation.waitForIdleSync()
                    assertUnchanged(preferences, protectedFiles, previousCapture)
                    assertEquals(autoRotate, Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0))
                    assertEquals(userRotation, Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION, 0))
                }
            }
        }
    }

    private fun requireReadOnlyPreconditions() {
        assertEquals("Only the standard app target", "com.dominik.cardvault", context.packageName)
        assertEquals("Only the reviewed API36 emulator", 36, Build.VERSION.SDK_INT)
        assertTrue("Never exercise a physical camera", Build.HARDWARE in setOf("ranchu", "goldfish"))
        assertTrue("Use the fixed Root runner", InstrumentationRegistry.getArguments()
            .getString("domidexRegressionRunId")?.matches(Regex("[a-f0-9]{32}")) == true)
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0))
        assertEquals(0, Settings.Global.getInt(context.contentResolver, "wifi_on", 1))
        assertNull("No active network", context.getSystemService(ConnectivityManager::class.java).activeNetwork)
        assertNull("Requires an already signed-out installation; never signs out", FirebaseAuth.getInstance().currentUser)
        assertEquals("Camera permission must already be granted", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        val ui = context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE)
        assertTrue("Tutorial must already be answered", ui.getBoolean("relaxed_tutorial_answered_v1", false))
        assertTrue("Camera intro must already be accepted", ui.getBoolean("scanner_camera_intro_accepted_v1", false))
        assertFalse("Welcome presentation must already be complete", WelcomePreferences(context).shouldShow())
        assertTrue("Existing prepared app database required; never initialize a fresh installation",
            context.getDatabasePath("cardvault.db").isFile)
        // Opening the real scanner initializes its store. Do not allow its normal
        // legacy-telemetry cleanup to erase any preexisting file during this proof.
        listOf("outbox", "uploaded").forEach { legacy ->
            val directory = File(context.filesDir, "scan_knowledge/$legacy")
            assertTrue("Legacy telemetry requires separate review before opening scanner",
                directory.listFiles().orEmpty().none { it.isFile && it.extension in setOf("json", "tmp") })
        }
    }

    private fun assertUnchanged(preferences: Map<String, Map<String, Any?>>, files: Map<String, String>, capture: String?) {
        assertNull("No account transition", FirebaseAuth.getInstance().currentUser)
        assertEquals(PackageManager.PERMISSION_GRANTED, ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        assertEquals("No implicit capture", capture, LatestCardCapture.path)
        assertEquals("UI/consent preferences changed", preferences, preferenceSnapshot())
        assertEquals("Capture, knowledge or feedback files changed", files, protectedFileSnapshot())
    }

    private fun preferenceSnapshot(): Map<String, Map<String, Any?>> = listOf(
        "cardvault_ui", "cardvault_contribution", "scanner_report_consent", "domidex_scan_knowledge_consent",
    ).associateWith { name -> context.getSharedPreferences(name, Context.MODE_PRIVATE).all.mapValues { (_, value) ->
        if (value is Set<*>) value.toSet() else value
    } }

    private fun protectedFileSnapshot(): Map<String, String> = buildMap {
        fun add(file: File, base: File, label: String) {
            check(file.canonicalFile == file.absoluteFile) { "No aliased protected file" }
            if (file.isDirectory) checkNotNull(file.listFiles()) { "Protected directory could not be read" }
                .forEach { add(it, base, label) }
            else if (file.isFile) {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val block = ByteArray(8192)
                    while (true) { val count = input.read(block); if (count < 0) break; digest.update(block, 0, count) }
                }
                put("$label/${file.relativeTo(base).invariantSeparatorsPath}",
                    digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) })
            }
        }
        val cache = context.cacheDir.canonicalFile
        checkNotNull(cache.listFiles()) { "Cache directory could not be read" }
            .filter { it.name.startsWith("cardvault-capture-") || it.name.startsWith("domidex-gallery-") }
            .forEach { add(it, cache, "cache") }
        val files = context.filesDir.canonicalFile
        listOf("scan_knowledge", "scan_feedback").forEach { add(File(files, it), files, "files") }
    }

    private fun assertManualUiWithoutQueue() {
        val nodes = visibleNodes()
        assertTrue(nodes.any { it.text?.toString() == "Auto aus" })
        assertFalse(nodes.any { it.text?.toString() in setOf("Auto an", "Scan-Warteschlange", "Import …", "Aufnahme läuft …") })
        val shutter = nodes.single { it.contentDescription?.toString() == "Foto aufnehmen" }
        assertTrue("Manual shutter must remain enabled", clickableAncestor(shutter)?.isEnabled == true)
    }

    private fun previewViews(view: View): List<PreviewView> = buildList {
        if (view is PreviewView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(previewViews(view.getChildAt(it))) }
    }

    private fun <T> onMain(block: () -> T): T {
        var answer: Result<T>? = null
        instrumentation.runOnMainSync { answer = runCatching(block) }
        return checkNotNull(answer).getOrThrow()
    }

    private fun visibleNodes(): List<AccessibilityNodeInfo> {
        automation.clearCache()
        return buildList {
            fun visit(node: AccessibilityNodeInfo?) {
                if (node == null) return
                if (node.isVisibleToUser && node.packageName?.toString() == context.packageName) add(node)
                repeat(node.childCount) { visit(node.getChild(it)) }
            }
            visit(automation.rootInActiveWindow)
        }
    }

    private fun hasText(text: String): Boolean = visibleNodes().any { it.text?.toString() == text }

    private fun waitNode(description: String, find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var node: AccessibilityNodeInfo? = null
        waitUntil(description) { node = find(); node != null }
        return checkNotNull(node)
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        generateSequence(node) { it.parent }.firstOrNull { it.isClickable }

    private fun click(node: AccessibilityNodeInfo) {
        val target = checkNotNull(clickableAncestor(node)) { "No production navigation control" }
        assertTrue(target.isEnabled)
        assertTrue(target.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun waitUntil(description: String, predicate: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < end) {
            if (predicate()) return
            SystemClock.sleep(75)
        }
        error("Timed out waiting for $description")
    }
}
