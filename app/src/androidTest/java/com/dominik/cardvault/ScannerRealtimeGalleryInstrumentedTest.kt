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
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.ui.ScanPipelineStage
import com.google.firebase.auth.FirebaseAuth
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in control for the hypothesis that a Compose test clock changes E2E runtime contention.
 * Deliberately has NO ComposeTestRule, test scheduler, Espresso idling or virtual-time advance.
 * ActivityScenario runs the real app; accessibility polls observe ordinary Android real time.
 * The system picker result alone is stubbed, exactly as in ScannerFastInputInstrumentedTest.
 * No login, collection write, scan upload or app-data clearing is performed.
 */
@RunWith(AndroidJUnit4::class)
class ScannerRealtimeGalleryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val stageLabels = ScanPipelineStage.entries.map { it.label }.toSet()
    private val terminalLabels = setOf("Treffer bereit", "Mehrere Treffer – bitte prüfen",
        "Manuelle Prüfung nötig", "Analyse fehlgeschlagen")

    @Test fun publicGalleryFixtureUsesRealtimeAccessibilityWithoutComposeTestClock() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("scannerRealtimeGalleryBenchmark") == "true")
        assumeTrue("Never exercise a physical camera", Build.HARDWARE in setOf("ranchu", "goldfish") ||
            Build.FINGERPRINT.contains("emulator") || Build.MODEL.contains("sdk_gphone"))
        assumeTrue("Requires scoped-storage test fixtures", Build.VERSION.SDK_INT >= 29)
        assertGuest()
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.CAMERA)

        val fixtureRoot = File(context.cacheDir, "scanner-public-fixtures").canonicalFile
        val fixtures = JSONObject(File(fixtureRoot, "manifest.json").readText()).getJSONArray("fixtures")
        val fixture = (0 until fixtures.length()).map(fixtures::getJSONObject)
            .single { it.getString("id") == "swsh12-047" }
        val source = File(fixtureRoot, fixture.getString("path")).canonicalFile
        require(source.parentFile == fixtureRoot && source.isFile)
        val originalBytes = source.readBytes()
        assertEquals("Public original differs from manifest", fixture.getString("sha256"), sha256(originalBytes))
        val bytes = cleanFrame(originalBytes)
        assertEquals("Must replay the identical previously measured Araqua PNG",
            "4bcdf68936c0844d9c9152c7f27e1ef28e722b38a856258732a8f4fb67d709d6", sha256(bytes))
        val label = (args.getString("scannerGalleryLabel") ?: "realtime-accessibility")
            .replace(Regex("[^A-Za-z0-9_-]"), "_").take(80)
        val warmupMillis = args.getString("scannerGalleryWarmupMillis")?.toLong() ?: 0L
        require(warmupMillis in 0L..30_000L)
        val reportFile = File(context.cacheDir, "scanner-gallery-realtime-$label.json")
        val observations = JSONArray()
        val report = JSONObject().put("schemaVersion", 1).put("label", label)
            .put("harness", "ActivityScenario + UiAutomation accessibility; Android real time")
            .put("composeTestRule", false).put("virtualTimeAdvance", false)
            .put("accessibilityDiscoveryVersion", 2).put("freshAccessibilityCache", Build.VERSION.SDK_INT >= 33)
            .put("hypothesis", "Test-clock contention may affect OCR; this run alone does not establish causality")
            .put("scope", "Real import/OCR/existing catalogue/queue UI; picker selection itself is stubbed")
            .put("fixtureId", fixture.getString("id")).put("fixtureSha256", fixture.getString("sha256"))
            .put("framed", true).put("inputWidth", 1000).put("inputHeight", 1400)
            .put("inputSha256", sha256(bytes)).put("inputByteCount", bytes.size)
            .put("inputMimeType", "image/png").put("inputExtension", "png")
            .put("expectedName", fixture.getString("name")).put("expectedLocalId", fixture.getString("localId"))
            .put("expectedSourceSetId", fixture.getString("sourceSetId"))
            .put("scannerWarmupMillis", warmupMillis).put("deadlineMillis", 40_000)
            .put("pollingIntervalMillis", 150).put("timingsAreObservationUpperBounds", true)
            .put("queueVisibleMillis", JSONObject.NULL).put("firstCandidateVisibleMillis", JSONObject.NULL)
            .put("terminalVisibleMillis", JSONObject.NULL).put("recognitionOutcome", "not_observed")
            .put("observations", observations).put("complete", false)
            .put("collectionMutationRequested", false).put("uploadRequested", false)
        fun persist() = reportFile.writeText(report.toString(2))
        persist()

        val resolver = context.contentResolver
        var fixtureUri: android.net.Uri? = null
        var startedAt = 0L
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                openScannerLocally()
                if (warmupMillis > 0L) SystemClock.sleep(warmupMillis)
                assertGuest()
                assertFalse("Do not mix this fixture with an existing queue", hasText("Scan-Warteschlange"))
                val priorImports = context.cacheDir.listFiles().orEmpty()
                    .filter { file -> file.name.startsWith("domidex-gallery-") }.map { file -> file.name }.toSet()
                val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "domidex-realtime-scanner-${UUID.randomUUID()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/DomiDexScannerTests")
                }))
                fixtureUri = uri
                checkNotNull(resolver.openOutputStream(uri)).use { output -> output.write(bytes) }

                val pickerAction = ActivityResultContracts.PickVisualMedia().createIntent(context,
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)).action
                val requests = AtomicInteger()
                val monitor = object : Instrumentation.ActivityMonitor() {
                    override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                        if (intent.action != pickerAction) return null
                        requests.incrementAndGet()
                        return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(uri))
                    }
                }
                instrumentation.addMonitor(monitor)
                try {
                    val gallery = waitNode("Galerie")
                    startedAt = SystemClock.elapsedRealtime()
                    click(gallery, "Galerie")
                    waitUntil(startedAt + 4_000L, "Exactly one gallery request") { requests.get() == 1 }
                } finally { instrumentation.removeMonitor(monitor) }
                assertEquals(1, requests.get())
                waitUntil(startedAt + 5_000L, "Visible imported-image queue") {
                    hasText("Scan-Warteschlange") && !hasText("Import …")
                }
                report.put("queueVisibleMillis", SystemClock.elapsedRealtime() - startedAt)
                val imported = context.cacheDir.listFiles().orEmpty().single { file ->
                    file.name.startsWith("domidex-gallery-") && file.name !in priorImports
                }
                assertTrue("Import changed supplied bytes", bytes.contentEquals(imported.readBytes()))
                report.put("importedInputSha256", sha256(imported.readBytes()))
                click(waitNode("Scan-Warteschlange"), "Scan-Warteschlange")

                var lastTexts = emptyList<String>()
                var terminalLabel: String? = null
                var firstCandidateObserved = false
                var lastAccessibilitySnapshotAt = -5_000L
                waitUntil(startedAt + 40_000L, "Terminal queue result within real-time deadline") {
                    val texts = queueCardTexts()
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    // Keep a small, sparse runtime hierarchy for diagnosing discovery failures;
                    // ActivityScenario has already closed by the outer failure handler.
                    if (elapsed - lastAccessibilitySnapshotAt >= 5_000L) {
                        report.put("lastAccessibilitySnapshotMillis", elapsed)
                            .put("lastAccessibilitySnapshot", accessibilitySnapshot())
                        lastAccessibilitySnapshotAt = elapsed
                    }
                    if (texts.isNotEmpty() && texts != lastTexts) {
                        lastTexts = texts
                        observations.put(JSONObject().put("elapsedMillis", elapsed).put("texts", JSONArray(texts)))
                        report.put("lastQueueTexts", JSONArray(texts))
                        val title = texts.firstOrNull()
                        if (!firstCandidateObserved && title != null && title != "Karte wird verarbeitet" && title !in stageLabels) {
                            firstCandidateObserved = true
                            report.put("firstCandidateVisibleMillis", elapsed).put("firstCandidateName", title)
                        }
                        terminalLabel = texts.firstOrNull { text -> text in terminalLabels }
                        terminalLabel?.let { terminal ->
                            report.put("terminalVisibleMillis", elapsed).put("terminalLabel", terminal)
                                .put("recognitionOutcome", when (terminal) {
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
                assertTrue("A real terminal result must be visible", lastTexts.isNotEmpty())
                assertTrue("The actual pipeline failed", terminalLabel != "Analyse fehlgeschlagen")
                report.put("expectedNameVisible", lastTexts.any { text -> text.equals(fixture.getString("name"), true) })
                if (terminalLabel == "Treffer bereit" || terminalLabel == "Mehrere Treffer – bitte prüfen") {
                    // Normal/reverse ambiguity is an honest expected result for this public card.
                    // It must still expose the real catalogue identity, not merely any candidate.
                    assertTrue("Matched/ambiguous fixture name differs", report.getBoolean("expectedNameVisible"))
                    val identityLine = lastTexts.firstOrNull { text ->
                        " · " in text && Regex("\\b0*47(?:/195)?\\b").containsMatchIn(text.substringAfterLast(" · "))
                    }.orEmpty()
                    val number = identityLine.substringAfterLast(" · ")
                        .substringBefore('/').trim().trimStart('0')
                    assertEquals("Matched/ambiguous visible collector number", fixture.getString("localId").trimStart('0'), number)
                    assertTrue("Matched/ambiguous set is not the fixture's Silberne Sturmwinde/SIT",
                        identityLine.substringBeforeLast(" · ").let { set ->
                            set.contains("Silberne Sturmwinde", true) || set.equals("SIT", true) || set.equals("swsh12", true)
                        })
                    report.put("visibleFixtureIdentityVerified", true)
                        .put("variantAmbiguityPermitted", terminalLabel == "Mehrere Treffer – bitte prüfen")
                }
                report.put("terminalAccessibilitySnapshot", accessibilitySnapshot())
                assertTrue("Auto capture must remain disabled", hasText("Auto aus"))
                assertGuest()
                assertEquals("Original public image changed", fixture.getString("sha256"), sha256(source.readBytes()))
                // Discard only this test's terminal queue item, never add/confirm a collection card.
                click(waitNode("Überspringen"), "Überspringen")
                report.put("complete", true)
            }
        } catch (failure: Throwable) {
            report.put("testFailure", failure.javaClass.simpleName).put("testFailureMessage", failure.message)
                .put("elapsedAtFailureMillis", if (startedAt == 0L) JSONObject.NULL else SystemClock.elapsedRealtime() - startedAt)
                .put("failureAccessibilitySnapshot", runCatching { accessibilitySnapshot() }.getOrElse { JSONArray() })
            throw failure
        } finally {
            persist()
            fixtureUri?.let { uri -> assertEquals("Remove only this test's MediaStore fixture", 1, resolver.delete(uri, null, null)) }
        }
    }

    private fun assertGuest() = assertNull("Requires a signed-out emulator; never sign a real user out",
        FirebaseAuth.getInstance().currentUser)

    private fun cleanFrame(originalBytes: ByteArray): ByteArray {
        val card = checkNotNull(BitmapFactory.decodeByteArray(originalBytes, 0, originalBytes.size))
        val frame = Bitmap.createBitmap(1000, 1400, Bitmap.Config.ARGB_8888)
        try {
            Canvas(frame).apply {
                drawColor(Color.rgb(35, 39, 45))
                drawBitmap(card, null, RectF(140f, 197f, 860f, 1203f),
                    Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
            }
            return ByteArrayOutputStream().use { output ->
                check(frame.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { frame.recycle(); card.recycle() }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun nodes(): List<AccessibilityNodeInfo> {
        // A formerly disabled queue card becomes actionable at terminal status. Compose may
        // merge it differently; do not retain the disabled card's initial accessibility tree.
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.clearCache()
        return nodes(instrumentation.uiAutomation.rootInActiveWindow)
    }

    private fun nodes(root: AccessibilityNodeInfo?): List<AccessibilityNodeInfo> = buildList {
        fun visit(node: AccessibilityNodeInfo?) {
            if (node == null) return
            if (node.isVisibleToUser) add(node)
            repeat(node.childCount) { visit(node.getChild(it)) }
        }
        visit(root)
    }

    private fun nodeHasText(node: AccessibilityNodeInfo, text: String): Boolean =
        node.text?.toString()?.lineSequence()?.any { it.trim().equals(text, true) } == true ||
            node.contentDescription?.toString()?.equals(text, true) == true

    private fun hasText(text: String): Boolean = nodes().any { nodeHasText(it, text) }

    private fun waitNode(text: String): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil(SystemClock.elapsedRealtime() + 10_000L, "Visible $text") {
            found = nodes().firstOrNull { nodeHasText(it, text) }
            found != null
        }
        return checkNotNull(found)
    }

    private fun click(node: AccessibilityNodeInfo, label: String) {
        val control = generateSequence(node) { it.parent }.firstOrNull { it.isClickable && it.isEnabled }
            ?: error("No enabled clickable control for $label")
        assertTrue("Accessibility click failed: $label", control.performAction(AccessibilityNodeInfo.ACTION_CLICK))
    }

    private fun waitUntil(deadline: Long, description: String, condition: () -> Boolean) {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(150L)
        }
        error("Real-time timeout: $description")
    }

    private fun openScannerLocally() {
        val deadline = SystemClock.elapsedRealtime() + 25_000L
        waitUntil(deadline, "Local scanner without signing in") {
            val visible = nodes()
            if (visible.any { nodeHasText(it, "Auto aus") } && visible.any { nodeHasText(it, "Galerie") }) true
            else {
                val onboarding = listOf("Vorerst lokal nutzen", "Jetzt entdecken", "Nein, nicht mehr fragen",
                    "Verstanden, Kamera öffnen").firstNotNullOfOrNull { text ->
                    visible.firstOrNull { nodeHasText(it, text) }?.let { it to text }
                }
                if (onboarding != null) {
                    click(onboarding.first, onboarding.second)
                    SystemClock.sleep(300L)
                } else {
                    // Prefer the explicit dashboard action; fall back to the bottom navigation tab.
                    val scanner = visible.firstOrNull { nodeHasText(it, "Scannen") }
                        ?: visible.filter { nodeHasText(it, "Scanner") }.maxByOrNull { node ->
                            Rect().also(node::getBoundsInScreen).centerY()
                        }
                    if (scanner != null && visible.none { nodeHasText(it, "Verstanden, Kamera öffnen") }) {
                        click(scanner, "Scanner")
                        SystemClock.sleep(300L)
                    }
                }
                false
            }
        }
    }

    private fun queueCardTexts(): List<String> {
        val fresh = nodes()
        fun texts(node: AccessibilityNodeInfo): List<String> = nodes(node).flatMap { child ->
            child.text?.toString()?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList().orEmpty()
        }
        fun ownTexts(node: AccessibilityNodeInfo): List<String> = node.text?.toString()
            ?.lineSequence()?.map(String::trim)?.filter(String::isNotEmpty)?.toList().orEmpty()
        // At terminal status the clickable OutlinedCard may merge its image and text into one
        // node. Anchor on the actual stage first, not on a possibly hidden/reparented image.
        val stageAnchors = fresh.filter { node -> ownTexts(node).any { it in stageLabels } }
            .sortedByDescending { node -> ownTexts(node).any { it in terminalLabels } }
        val candidates = stageAnchors.flatMap { anchor -> generateSequence(anchor) { it.parent }.take(8).toList() }
            .map(::texts).filter { values ->
                values.any { it in stageLabels } && values.none { it.equals("Scan-Warteschlange", true) } &&
                    ("Karte wird verarbeitet" in values || "Überspringen" in values)
            }
        candidates.minByOrNull(List<String>::size)?.let { return it }
        // During nonterminal candidate matching, the card has a name but no skip button yet.
        val preview = fresh.firstOrNull {
            it.contentDescription?.toString()?.contains("Lokale Vorschau der aufgenommenen Karte") == true
        }
        preview?.let { image ->
            generateSequence(image) { it.parent }.map(::texts)
                .firstOrNull { values -> values.any { it in stageLabels } }?.let { return it }
        }
        // The test asserts an initially empty queue and imports exactly one image. If the whole
        // row is merged, extract only its segment starting at the actual card title.
        val visibleTexts = fresh.flatMap(::ownTexts)
        val start = visibleTexts.indexOfFirst { it == "Karte wird verarbeitet" || it == "Araqua" }
        return if (start >= 0) visibleTexts.drop(start).takeWhile { it != "Scan-Warteschlange" }
            .takeIf { values -> values.any { it in stageLabels } }.orEmpty() else emptyList()
    }

    private fun accessibilitySnapshot(): JSONArray = JSONArray(nodes().map { node ->
        JSONObject().put("text", node.text?.toString()).put("description", node.contentDescription?.toString())
            .put("viewId", node.viewIdResourceName).put("class", node.className?.toString())
            .put("clickable", node.isClickable).put("enabled", node.isEnabled)
            .put("bounds", Rect().also(node::getBoundsInScreen).toShortString())
    })
}
