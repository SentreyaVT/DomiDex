package com.dominik.cardvault

import android.Manifest
import android.app.Activity
import android.app.Instrumentation
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.ui.LatestCardCapture
import com.dominik.cardvault.ui.ScanPipelineStage
import com.google.firebase.auth.FirebaseAuth
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

/** Camera-backed guard for the scanner's lightweight input path. */
class ScannerFastInputInstrumentedTest {
    private val permissionRule = TestRule { base: Statement, _: Description ->
        object : Statement() {
            override fun evaluate() {
                val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
                    .executeShellCommand("pm grant com.dominik.cardvault ${Manifest.permission.CAMERA}")
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input -> input.readBytes() }
                base.evaluate()
            }
        }
    }
    private val composeRule = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(permissionRule).around(composeRule)

    @Test
    fun manualCaptureEntersQueueWithoutBlockingTheScannerUi() {
        val cameraManager = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(CameraManager::class.java)
        assumeTrue("A camera is required for the shutter latency test", cameraManager.cameraIdList.isNotEmpty())
        composeRule.openScannerLocallyForTest()
        composeRule.onNodeWithText("Galerie").assertExists()

        // Let CameraX finish binding before measuring only the user-visible queue hand-off.
        SystemClock.sleep(2_500L)
        // The title alone is meaningful only if this capture creates the queue.
        composeRule.onNodeWithText("Scan-Warteschlange").assertDoesNotExist()
        val previousCapture = LatestCardCapture.path
        val startedAt = SystemClock.elapsedRealtime()
        composeRule.onNodeWithContentDescription("Foto aufnehmen").performClick()
        composeRule.waitUntil(timeoutMillis = 2_000L) {
            composeRule.onAllNodesWithText("Scan-Warteschlange").fetchSemanticsNodes().isNotEmpty()
        }
        val queueLatency = SystemClock.elapsedRealtime() - startedAt
        assertTrue("Queue hand-off took ${queueLatency}ms", queueLatency < 2_000L)
        composeRule.waitUntil(timeoutMillis = 4_000L) {
            LatestCardCapture.path?.takeIf { it != previousCapture }?.let(::File)?.let { it.exists() && it.length() > 0L } == true
        }
        val savedLatency = SystemClock.elapsedRealtime() - startedAt
        assertTrue("Full-resolution capture took ${savedLatency}ms", savedLatency < 4_000L)
        composeRule.onNodeWithText("Auto aus").assertIsDisplayed()
        composeRule.onNodeWithText("Auto an").assertDoesNotExist()
        assertTrue("Scanner activity closed during capture", !composeRule.activity.isFinishing)
    }

    @Test
    fun cancelledGalleryResultLeavesManualScannerReady() {
        composeRule.openScannerLocallyForTest()
        composeRule.onNodeWithText("Scan-Warteschlange").assertDoesNotExist()
        val previousCapture = LatestCardCapture.path
        withGalleryResult(Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)) {
            composeRule.onNodeWithText("Galerie").performClick()
        }
        composeRule.onNodeWithText("Scan-Warteschlange").assertDoesNotExist()
        composeRule.onNodeWithText("Import …").assertDoesNotExist()
        composeRule.onNodeWithText("Auto aus").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Foto aufnehmen").assertIsEnabled()
        assertEquals(previousCapture, LatestCardCapture.path)
        assertTrue("Scanner activity closed after picker cancellation", !composeRule.activity.isFinishing)
    }

    /** Injected public fixture result: covers import/queue/fallback, not system picker selection. */
    @Test
    fun syntheticGalleryResultQueuesLocalImageAndOffersManualReview() {
        assumeTrue("Disposable MediaStore fixtures require scoped storage", Build.VERSION.SDK_INT >= 29)
        composeRule.openScannerLocallyForTest()
        composeRule.onNodeWithText("Scan-Warteschlange").assertDoesNotExist()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val previousImports = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("domidex-gallery-") }.map { it.name }.toSet()
        // No card identity or private photo: a blank fixture must remain manually reviewable.
        val bitmap = Bitmap.createBitmap(480, 640, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(Color.rgb(110, 110, 110))
            ByteArrayOutputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "domidex-scanner-test-${UUID.randomUUID()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DomiDexScannerTests")
        }
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values))
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            withGalleryResult(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))) {
                composeRule.onNodeWithText("Galerie").performClick()
            }
            composeRule.waitUntil(timeoutMillis = 4_000L) {
                composeRule.onAllNodesWithText("Scan-Warteschlange").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText("Import …").fetchSemanticsNodes().isEmpty()
            }
            val imported = context.cacheDir.listFiles().orEmpty().single {
                it.name.startsWith("domidex-gallery-") && it.name !in previousImports
            }
            assertTrue("Gallery callback did not preserve the fixture bytes", bytes.contentEquals(imported.readBytes()))
            composeRule.onNodeWithText("Auto aus").assertIsDisplayed()
            composeRule.onNodeWithText("Auto an").assertDoesNotExist()
            composeRule.onNodeWithText("Scan-Warteschlange").performClick()
            composeRule.waitUntil(timeoutMillis = 45_000L) {
                composeRule.onAllNodesWithText("Manuelle Prüfung nötig").fetchSemanticsNodes().isNotEmpty() ||
                    composeRule.onAllNodesWithText("Analyse fehlgeschlagen").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithContentDescription("Lokale Vorschau der aufgenommenen Karte").performClick()
            composeRule.onNodeWithText("Set unten auf der Karte").assertIsDisplayed()
            composeRule.onNodeWithText("Set aus Katalog auswählen").assertIsDisplayed()
            assertTrue("Scanner activity closed during gallery review", !composeRule.activity.isFinishing)
        } finally {
            // Only the uniquely named fixture created by this test is removed.
            assertEquals("Disposable gallery fixture was not removed", 1, resolver.delete(uri, null, null))
        }
    }

    /**
     * Opt-in real import -> ML Kit -> existing catalogue -> rendered queue replay. No injected
     * candidates and no collection writes. UI observations are polling upper bounds, not internal
     * stage timings; a missing catalogue match is reported as review, never a successful match.
     */
    @Test
    fun publicGalleryFixtureReportsRealQueueCandidateAndTerminalLatency() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("scannerGalleryBenchmark") == "true")
        assumeTrue("Run only on an emulator", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"))
        assumeTrue("Disposable MediaStore fixtures require scoped storage", Build.VERSION.SDK_INT >= 29)
        assertNull("Use an unsigned-in emulator; this replay must not access a user account",
            FirebaseAuth.getInstance().currentUser)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = File(context.cacheDir, "scanner-public-fixtures").canonicalFile
        val manifest = JSONObject(File(fixtureRoot, "manifest.json").readText())
        val fixtureId = args.getString("scannerGalleryFixtureId") ?: "swsh12-047"
        val fixtures = manifest.getJSONArray("fixtures")
        val fixture = (0 until fixtures.length()).map(fixtures::getJSONObject)
            .single { it.getString("id") == fixtureId }
        val source = File(fixtureRoot, fixture.getString("path")).canonicalFile
        require(source.parentFile == fixtureRoot && source.isFile) { "Public fixture must be inside its cache directory" }
        val originalBytes = source.readBytes()
        fun digest(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals("Public fixture hash", fixture.getString("sha256"), digest(originalBytes))
        val framed = args.getString("scannerGalleryFramed") == "true"
        val warmupMillis = args.getString("scannerGalleryWarmupMillis")?.toLong() ?: 0L
        require(warmupMillis in 0L..30_000L)
        // Identical canvas/background/bounds/filter to OCR benchmark createFrame(case = clean).
        // No recolouring, retouching or OCR preprocessing; the public original stays unchanged.
        val bytes = if (framed) {
            val card = checkNotNull(BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size))
            val frame = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
            try {
                Canvas(frame).apply {
                    drawColor(Color.rgb(35, 39, 45))
                    drawBitmap(card, null, RectF(140f, 197f, 860f, 1203f),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
                }
                ByteArrayOutputStream().use { output ->
                    check(frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally { frame.recycle(); card.recycle() }
        } else originalBytes
        val extension = if (framed) "png" else "jpg"
        val mimeType = if (framed) "image/png" else "image/jpeg"
        val label = (args.getString("scannerGalleryLabel") ?: "public-gallery")
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val reportFile = File(context.cacheDir, "scanner-gallery-e2e-$label.json")
        val observations = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("label", label)
            .put("scope", "Real gallery import and queue UI; existing catalogue only; UI polling upper bounds")
            .put("fixtureId", fixtureId).put("fixtureSha256", fixture.getString("sha256"))
            .put("framed", framed).put("inputSha256", digest(bytes)).put("inputByteCount", bytes.size)
            .put("scannerWarmupMillis", warmupMillis)
            .put("inputMimeType", mimeType).put("inputExtension", extension)
            .put("expectedName", fixture.getString("name")).put("expectedLocalId", fixture.getString("localId"))
            .put("expectedSourceSetId", fixture.getString("sourceSetId"))
            .put("observations", observations).put("complete", false)
            .put("queueVisibleMillis", JSONObject.NULL).put("firstCandidateVisibleMillis", JSONObject.NULL)
            .put("terminalVisibleMillis", JSONObject.NULL).put("recognitionOutcome", "not_observed")
        fun persist() { reportFile.writeText(report.toString(2)) }
        persist()
        composeRule.openScannerLocallyForTest()
        // Explicitly distinguish cold scanner startup from a settled preview; never mix timings.
        if (warmupMillis > 0) SystemClock.sleep(warmupMillis)
        composeRule.onNodeWithText("Scan-Warteschlange").assertDoesNotExist()
        val previousImports = context.cacheDir.listFiles().orEmpty()
            .filter { it.name.startsWith("domidex-gallery-") }.map { it.name }.toSet()
        val resolver = context.contentResolver
        val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "domidex-public-scanner-${UUID.randomUUID()}.$extension")
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DomiDexScannerTests")
        }))
        val stageLabels = ScanPipelineStage.entries.map { it.label }.toSet()
        val terminalLabels = setOf("Treffer bereit", "Mehrere Treffer – bitte prüfen", "Manuelle Prüfung nötig", "Analyse fehlgeschlagen")
        var terminalLabel: String? = null
        var startedAt = 0L
        try {
            checkNotNull(resolver.openOutputStream(uri)).use { it.write(bytes) }
            startedAt = SystemClock.elapsedRealtime()
            withGalleryResult(Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))) {
                composeRule.onNodeWithText("Galerie").performClick()
            }
            composeRule.waitUntil(timeoutMillis = 5_000L) {
                composeRule.onAllNodesWithText("Scan-Warteschlange").fetchSemanticsNodes().isNotEmpty() &&
                    composeRule.onAllNodesWithText("Import …").fetchSemanticsNodes().isEmpty()
            }
            report.put("queueVisibleMillis", SystemClock.elapsedRealtime() - startedAt)
            val imported = context.cacheDir.listFiles().orEmpty().single {
                it.name.startsWith("domidex-gallery-") && it.name !in previousImports
            }
            assertTrue("Import must preserve the supplied image bytes", bytes.contentEquals(imported.readBytes()))
            report.put("importedInputSha256", digest(imported.readBytes()))
            composeRule.onNodeWithText("Scan-Warteschlange").performClick()
            var lastTexts: List<String> = emptyList()
            var firstCandidateObserved = false
            composeRule.waitUntil(timeoutMillis = 55_000L) {
                val texts = visibleQueueCardTexts(stageLabels)
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                if (texts.isNotEmpty() && texts != lastTexts) {
                    observations.put(JSONObject().put("elapsedMillis", elapsed).put("texts", JSONArray(texts)))
                    lastTexts = texts
                    report.put("lastQueueTexts", JSONArray(texts))
                    val title = texts.first()
                    if (title != "Karte wird verarbeitet" && !firstCandidateObserved) {
                        firstCandidateObserved = true
                        report.put("firstCandidateVisibleMillis", elapsed).put("firstCandidateName", title)
                    }
                    terminalLabel = texts.firstOrNull { it in terminalLabels }
                    if (terminalLabel != null) {
                        report.put("terminalVisibleMillis", elapsed).put("terminalLabel", terminalLabel)
                        report.put("recognitionOutcome", when (terminalLabel) {
                            "Treffer bereit" -> "matched_in_existing_catalogue"
                            "Mehrere Treffer – bitte prüfen" -> "ambiguous_requires_manual_confirmation"
                            "Manuelle Prüfung nötig" -> "manual_review_not_a_recognition_success"
                            else -> "analysis_failed_not_a_recognition_success"
                        })
                    }
                    persist()
                }
                terminalLabel != null
            }
            assertTrue("No candidate or review result was rendered", lastTexts.isNotEmpty())
            assertTrue("The real gallery pipeline ended in failure instead of a usable candidate/review",
                terminalLabel != "Analyse fehlgeschlagen")
            report.put("expectedNameVisible", lastTexts.any { it.equals(fixture.getString("name"), ignoreCase = true) })
            if (terminalLabel == "Treffer bereit") {
                assertTrue("Accepted visible name must match the public fixture", report.getBoolean("expectedNameVisible"))
                val observedNumber = lastTexts.getOrNull(1).orEmpty().substringAfterLast(" · ")
                    .substringBefore('/').trim().trimStart('0')
                assertEquals("Accepted visible collector number", fixture.getString("localId").trimStart('0').uppercase(),
                    observedNumber.uppercase())
            }
            composeRule.onNodeWithText("Auto aus").assertIsDisplayed()
            assertTrue("Scanner activity closed during real gallery recognition", !composeRule.activity.isFinishing)
            assertEquals("Original public fixture changed", fixture.getString("sha256"), digest(source.readBytes()))
            report.put("complete", true).put("collectionMutationRequested", false).put("uploadRequested", false)
            // Discard only this test's queue result; never confirm/add a card to a binder.
            composeRule.onNodeWithText("Überspringen").performClick()
        } catch (failure: Throwable) {
            report.put("complete", false).put("testFailure", failure.javaClass.simpleName)
                .put("elapsedAtFailureMillis", if (startedAt == 0L) JSONObject.NULL else SystemClock.elapsedRealtime() - startedAt)
            throw failure
        } finally {
            persist()
            assertEquals("Disposable MediaStore fixture was not removed", 1, resolver.delete(uri, null, null))
        }
    }

    private fun visibleQueueCardTexts(stageLabels: Set<String>): List<String> {
        val preview = composeRule.onAllNodesWithContentDescription(
            "Lokale Vorschau der aufgenommenen Karte", useUnmergedTree = true,
        ).fetchSemanticsNodes().singleOrNull() ?: return emptyList()
        fun texts(node: SemanticsNode): List<String> =
            node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::texts)
        // Stop at the smallest ancestor containing the queue stage, not the whole scanner screen.
        return generateSequence(preview) { it.parent }.map(::texts)
            .firstOrNull { values -> values.any { it in stageLabels } }.orEmpty()
    }

    private fun withGalleryResult(result: Instrumentation.ActivityResult, launch: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pickerAction = ActivityResultContracts.PickVisualMedia().createIntent(
            instrumentation.targetContext,
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        ).action
        val requests = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != pickerAction) return null
                requests.incrementAndGet()
                return result
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launch()
            composeRule.waitUntil(timeoutMillis = 4_000L) { requests.get() == 1 }
            composeRule.waitForIdle()
            assertEquals("Expected exactly one image picker request", 1, requests.get())
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }
}

/** Follows current local onboarding without signing in or enabling automatic capture. */
internal fun ComposeContentTestRule.openScannerLocallyForTest() {
    waitForIdle()
    waitUntil(timeoutMillis = 8_000L) {
        onAllNodesWithTag("welcome-continue").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Scannen").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("SCANNER").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithTag("welcome-continue").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Vorerst lokal nutzen").assertIsDisplayed()
        onNodeWithTag("welcome-continue").performClick()
    }
    waitUntil(timeoutMillis = 8_000L) {
        onAllNodesWithText("Scannen").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("SCANNER").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithText("SCANNER").fetchSemanticsNodes().isEmpty()) {
        onNodeWithText("Scannen").performClick()
    }
    waitUntil(timeoutMillis = 8_000L) {
        onAllNodesWithText("Verstanden, Kamera öffnen").fetchSemanticsNodes().isNotEmpty() ||
            onAllNodesWithText("Auto aus").fetchSemanticsNodes().isNotEmpty()
    }
    if (onAllNodesWithText("Verstanden, Kamera öffnen").fetchSemanticsNodes().isNotEmpty()) {
        onNodeWithText("Verstanden, Kamera öffnen").performClick()
    }
    waitUntil(timeoutMillis = 8_000L) {
        onAllNodesWithText("Auto aus").fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithText("SCANNER").assertIsDisplayed()
    onNodeWithText("Auto aus").assertIsDisplayed()
    onNodeWithText("Auto an").assertDoesNotExist()
}
