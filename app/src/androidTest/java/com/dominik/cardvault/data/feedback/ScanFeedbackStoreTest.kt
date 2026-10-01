package com.dominik.cardvault.data.feedback

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.backup.LocalDataSession
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScanFeedbackStoreTest {
    private lateinit var root: File
    private val session = LocalDataSession("test-owner") { true }

    @Before fun createRoot() {
        root = File(
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "scan-feedback-test-${UUID.randomUUID()}",
        ).apply { mkdirs() }
    }

    @After fun removeRoot() {
        root.deleteRecursively()
    }

    @Test fun savesExportsAndImportsImageAnnotationsAndCorrections() {
        val sourceImage = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val source = ScanFeedbackStore(File(root, "source-store"), true)
        val feedback = source.save(
            sourceImage,
            ScanFeedback(
                id = "feedback-1",
                createdAtEpochMillis = 42L,
                imageRelativePath = "",
                originalPrediction = "Pikachu 123/198",
                originalConfidence = 61,
                originalOcrResults = listOf("Pikachu", "123/198"),
                annotations = listOf(
                    ScanFeedbackAnnotation(
                        id = "annotation-1",
                        x = .1f,
                        y = .2f,
                        width = .3f,
                        height = .08f,
                        label = "collector_number",
                        type = ScanFeedbackAnnotationType.POSITIVE,
                        detectedText = "123/I98",
                        correctedText = "123/198",
                    ),
                ),
                appVersion = "1.0.5-test",
            ), session,
        )
        assertEquals("images/feedback-1.jpg", feedback.imageRelativePath)

        val archive = ByteArrayOutputStream().use { buffer ->
            ZipOutputStream(buffer).use { zip -> source.exportTo(zip, session) }
            buffer.toByteArray()
        }
        val entries = unzip(archive)
        val target = ScanFeedbackStore(File(root, "target-store"), true)

        assertEquals(1, target.importFromZip(entries, session))
        val restored = target.list(session).single()
        assertEquals("Pikachu 123/198", restored.originalPrediction)
        assertEquals("123/198", restored.annotations.single().correctedText)
        assertTrue(File(File(root, "target-store"), restored.imageRelativePath).isFile)
        assertEquals(0, target.importFromZip(entries, session))
    }

    @Test fun skipsEntriesWhoseConsentImageIsMissing() {
        val manifest = """{
            "format":"domidex.scan-feedback",
            "version":1,
            "feedback":[{
              "id":"missing-image",
              "createdAtEpochMillis":1,
              "imageRelativePath":"images/missing-image.jpg",
              "annotations":[],
              "appVersion":"test"
            }]
        }""".trimIndent().toByteArray()
        val store = ScanFeedbackStore(File(root, "store"), true)

        assertEquals(0, store.importFromZip(mapOf("scan_feedback/feedback.json" to manifest), session))
        assertTrue(store.list(session).isEmpty())
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    @Test fun rejectsTraversalBeforeWritingAnyEarlierRecord() {
        val manifest = """{"format":"domidex.scan-feedback","version":1,"feedback":[
            {"id":"valid","createdAtEpochMillis":1,"imageRelativePath":"images/valid.jpg","annotations":[],"appVersion":"test"},
            {"id":"../outside","createdAtEpochMillis":1,"imageRelativePath":"images/../outside.jpg","annotations":[],"appVersion":"test"}]}"""
        val directory = File(root, "safe-store")
        val store = ScanFeedbackStore(directory, true)
        try { store.importFromZip(mapOf("scan_feedback/feedback.json" to manifest.toByteArray(), "scan_feedback/images/valid.jpg" to byteArrayOf(1)), session); org.junit.Assert.fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(store.list(session).isEmpty()); assertTrue(File(directory, "images").listFiles().orEmpty().isEmpty())
    }

    @Test fun databaseFailureRollsBackImportedFeedbackFiles() {
        val manifest = """{"format":"domidex.scan-feedback","version":1,"feedback":[
            {"id":"valid","createdAtEpochMillis":1,"imageRelativePath":"images/valid.jpg","annotations":[],"appVersion":"test"}]}"""
        val directory = File(root, "rollback-store")
        val store = ScanFeedbackStore(directory, true)
        try { store.importAtomically(mapOf("scan_feedback/feedback.json" to manifest.toByteArray(), "scan_feedback/images/valid.jpg" to byteArrayOf(1)), session) { error("database failure") }; org.junit.Assert.fail() }
        catch (_: IllegalStateException) { }
        assertTrue(store.list(session).isEmpty()); assertTrue(File(directory, "images").listFiles().orEmpty().isEmpty())
    }

    @Test fun symlinkDirectoryCannotEscapeStore() {
        val outside = File(root, "outside").apply { mkdirs() }
        val directory = File(root, "linked-store").apply { mkdirs() }
        android.system.Os.symlink(outside.absolutePath, File(directory, "images").absolutePath)
        val store = ScanFeedbackStore(directory, true)
        val image = File(root, "source.jpg").apply { writeBytes(byteArrayOf(1)) }
        try { store.save(image, ScanFeedback("safe-id", 1, "", annotations = emptyList(), appVersion = "test"), session); org.junit.Assert.fail() }
        catch (_: IllegalArgumentException) { }
        assertTrue(outside.listFiles().orEmpty().isEmpty())
        File(directory, "images").delete() // do not traverse the test symlink during cleanup
    }
}
