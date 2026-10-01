package com.dominik.cardvault.data.feedback

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.backup.LocalDataSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanFeedbackOwnerInstrumentedTest {
    private lateinit var root: File
    private lateinit var store: ScanFeedbackStore
    private val session = LocalDataSession("owner-a") { true }
    @Before fun before() {
        root = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "feedback-owner-test-${UUID.randomUUID()}").apply { mkdirs() }
        store = ScanFeedbackStore(root, true)
    }
    @After fun after() { root.deleteRecursively() }

    private fun record(id: String, owner: String?) = JSONObject().apply {
        put("id", id); put("createdAtEpochMillis", 1L); put("imageRelativePath", "images/$id.jpg")
        put("annotations", JSONArray()); put("appVersion", "test"); put("notes", "private-$id")
        if (owner != null) put("ownerUid", owner)
    }
    private fun seed(id: String, owner: String?) {
        File(root, "metadata/$id.json").writeText(record(id, owner).toString())
        File(root, "images/$id.jpg").writeBytes("image-$id".toByteArray())
    }
    private fun archive(): Map<String, ByteArray> {
        val bytes = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { store.exportTo(it, session) }
            buffer.toByteArray()
        }
        return buildMap {
            ZipInputStream(bytes.inputStream()).use { zip ->
                while (true) { val entry = zip.nextEntry ?: break; put(entry.name, zip.readBytes()) }
            }
        }
    }
    private fun importEntries(owner: String) = mapOf(
        "scan_feedback/feedback.json" to JSONObject().put("format", ScanFeedbackStore.EXPORT_FORMAT)
            .put("version", ScanFeedbackStore.EXPORT_VERSION)
            .put("feedback", JSONArray().put(record("imported", owner))).toString().toByteArray(),
        "scan_feedback/images/imported.jpg" to byteArrayOf(1, 2, 3),
    )

    @Test fun exportIncludesOnlyCapturedOwnerAndDoesNotExposeOwnershipMetadata() {
        seed("own", "owner-a"); seed("foreign", "owner-b")
        val exported = archive()
        assertEquals(setOf("scan_feedback/feedback.json", "scan_feedback/images/own.jpg"), exported.keys)
        val records = JSONObject(exported.getValue("scan_feedback/feedback.json").decodeToString()).getJSONArray("feedback")
        assertEquals(1, records.length()); assertEquals("own", records.getJSONObject(0).getString("id"))
        assertFalse(records.getJSONObject(0).has("ownerUid"))
    }

    @Test fun unknownLegacyRemainsUnchangedAndExcludedFromAccountExport() {
        seed("legacy", null)
        val before = File(root, "metadata/legacy.json").readBytes()
        val exported = archive()
        assertEquals(setOf("scan_feedback/feedback.json"), exported.keys)
        assertEquals(0, JSONObject(exported.getValue("scan_feedback/feedback.json").decodeToString()).getJSONArray("feedback").length())
        assertArrayEquals(before, File(root, "metadata/legacy.json").readBytes())
        assertTrue(File(root, "images/legacy.jpg").isFile)
    }

    @Test fun explicitImportBindsToTargetNotArchivedOwner() {
        assertEquals(1, store.importFromZip(importEntries("foreign-archive-owner"), session))
        assertEquals("owner-a", JSONObject(File(root, "metadata/imported.json").readText()).getString("ownerUid"))
    }

    @Test fun saveCannotOverwriteOtherOwnersExistingId() {
        seed("collision", "owner-b")
        val before = File(root, "metadata/collision.json").readBytes()
        val image = File(root, "new.jpg").apply { writeBytes(byteArrayOf(7)) }
        val outcome = runCatching { store.save(image, ScanFeedback("collision", 2, "", annotations = emptyList(), appVersion = "test"), session) }
        assertTrue("Foreign feedback must not be overwritten", outcome.isFailure)
        assertArrayEquals(before, File(root, "metadata/collision.json").readBytes())
        assertEquals("image-collision", File(root, "images/collision.jpg").readText())
    }

    @Test fun saveCannotClaimLegacyIdForCurrentOwner() {
        seed("legacy", null)
        val before = File(root, "metadata/legacy.json").readBytes()
        val image = File(root, "new.jpg").apply { writeBytes(byteArrayOf(7)) }
        val outcome = runCatching { store.save(image, ScanFeedback("legacy", 2, "", annotations = emptyList(), appVersion = "test"), session) }
        assertTrue("Unknown old feedback must not be claimed", outcome.isFailure)
        assertArrayEquals(before, File(root, "metadata/legacy.json").readBytes())
    }

    @Test fun newlySavedFeedbackIsOwnedAndOnlyThatOwnerCanListIt() {
        val image = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1)) }
        val saved = store.save(image, ScanFeedback("new", 2, "", annotations = emptyList(), appVersion = "test"), session)
        assertEquals("owner-a", saved.ownerUid)
        assertEquals(listOf("new"), store.list(session).map { it.id })
        assertTrue(store.list(LocalDataSession("owner-b") { true }).isEmpty())
    }

    @Test fun saveRejectsForeignDraftOwnerBeforeCreatingFiles() {
        val image = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(runCatching {
            store.save(image, ScanFeedback("foreign", 2, "", annotations = emptyList(), appVersion = "test", ownerUid = "owner-b"), session)
        }.isFailure)
        assertTrue(File(root, "metadata").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "images").listFiles().orEmpty().isEmpty())
    }

    @Test fun guestFeedbackIsExplicitAndDoesNotAdoptLegacy() {
        seed("legacy", null)
        val guest = LocalDataSession(com.dominik.cardvault.data.local.GUEST_OWNER_UID) { true }
        val image = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1)) }
        store.save(image, ScanFeedback("guest", 2, "", annotations = emptyList(), appVersion = "test"), guest)
        assertEquals(listOf("guest"), store.list(guest).map { it.id })
        assertTrue(store.list(session).isEmpty())
        assertFalse(JSONObject(File(root, "metadata/legacy.json").readText()).has("ownerUid"))
    }

    @Test fun importRejectsForeignAndLegacyCollisionWithoutChangingFiles() {
        listOf<String?>("owner-b", null).forEach { owner ->
            seed("imported", owner)
            val before = File(root, "metadata/imported.json").readBytes()
            assertTrue(runCatching { store.importFromZip(importEntries("arbitrary"), session) }.isFailure)
            assertArrayEquals(before, File(root, "metadata/imported.json").readBytes())
            assertEquals("image-imported", File(root, "images/imported.jpg").readText())
        }
    }

    @Test fun ownRepeatedImportDoesNotOverwriteOrDuplicate() {
        assertEquals(1, store.importFromZip(importEntries("arbitrary"), session))
        val before = File(root, "metadata/imported.json").readBytes()
        assertEquals(0, store.importFromZip(importEntries("another"), session))
        assertArrayEquals(before, File(root, "metadata/imported.json").readBytes())
        assertEquals(1, store.list(session).size)
    }

    @Test fun staleSessionCannotSaveExportListOrImport() {
        seed("own", "owner-a")
        val stale = LocalDataSession("owner-a") { false }
        val image = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(runCatching { store.save(image, ScanFeedback("new", 2, "", annotations = emptyList(), appVersion = "test"), stale) }.isFailure)
        assertTrue(runCatching { store.list(stale) }.isFailure)
        assertTrue(runCatching { store.importFromZip(importEntries("other"), stale) }.isFailure)
        assertTrue(runCatching { ZipOutputStream(ByteArrayOutputStream()).use { store.exportTo(it, stale) } }.isFailure)
        assertEquals(listOf("own"), store.list(session).map { it.id })
    }

    @Test fun ownerChangeDuringNewSaveRollsBackOnlyNewFiles() {
        seed("legacy", null)
        var checks = 0
        val stale = LocalDataSession("owner-a") { ++checks < 4 }
        val image = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertTrue(runCatching { store.save(image, ScanFeedback("new", 2, "", annotations = emptyList(), appVersion = "test"), stale) }.isFailure)
        assertFalse(File(root, "metadata/new.json").exists()); assertFalse(File(root, "images/new.jpg").exists())
        assertTrue(File(root, "metadata/legacy.json").isFile); assertEquals("image-legacy", File(root, "images/legacy.jpg").readText())
    }
}
