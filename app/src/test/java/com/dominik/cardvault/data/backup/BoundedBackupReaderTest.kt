package com.dominik.cardvault.data.backup

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BoundedBackupReaderTest {
    private fun zip(vararg items: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().use { bytes ->
        ZipOutputStream(bytes).use { zip -> items.forEach { (name, data) -> zip.putNextEntry(ZipEntry(name)); zip.write(data); zip.closeEntry() } }
        bytes.toByteArray()
    }
    private fun rejected(action: () -> Unit) { try { action(); fail("Malicious backup accepted") } catch (_: IllegalArgumentException) { } }
    @Test fun supportsLegacyJsonAndZip() {
        assertEquals("{}", BoundedBackupReader.read("{}".byteInputStream()).getValue("collection.json").decodeToString())
        assertEquals("{}", BoundedBackupReader.read(zip("collection.json" to "{}".toByteArray()).inputStream()).getValue("collection.json").decodeToString())
    }
    @Test fun boundsPlainJsonDuringRead() = rejected { BoundedBackupReader.read(ByteArray(1025).inputStream(), BoundedBackupReader.Limits(entryBytes = 1024)) }
    @Test fun boundsCompressedInput() = rejected { BoundedBackupReader.read(zip("collection.json" to "{}".toByteArray()).inputStream(), BoundedBackupReader.Limits(compressedBytes = 20)) }
    @Test fun boundsUncompressedEntry() = rejected { BoundedBackupReader.read(zip("collection.json" to ByteArray(2000)).inputStream(), BoundedBackupReader.Limits(entryBytes = 1000)) }
    @Test fun boundsAggregateAcrossSmallEntries() = rejected { BoundedBackupReader.read(zip("collection.json" to ByteArray(600), "scan_feedback/feedback.json" to ByteArray(600)).inputStream(), BoundedBackupReader.Limits(totalBytes = 1000)) }
    @Test fun boundsNumberOfFiles() = rejected { BoundedBackupReader.read(zip("collection.json" to "{}".toByteArray(), "scan_feedback/feedback.json" to "{}".toByteArray()).inputStream(), BoundedBackupReader.Limits(entries = 1)) }
    @Test fun rejectsBombCompressionRatio() = rejected { BoundedBackupReader.read(zip("collection.json" to ByteArray(400_000)).inputStream()) }
    @Test fun rejectsTraversalAbsoluteUnicodeAndUnknownEntries() {
        listOf("../collection.json", "/collection.json", "C:\\collection.json", "scan_feedback/images/../a.jpg", "scan_feedback/images/a\\b.jpg", "scan_feedback/images/ａ.jpg", "tokens.json").forEach { path ->
            rejected { BoundedBackupReader.read(zip("collection.json" to "{}".toByteArray(), path to byteArrayOf(1)).inputStream()) }
        }
    }
    @Test fun rejectsArchiveWithoutCollection() = rejected { BoundedBackupReader.read(zip("scan_feedback/feedback.json" to "{}".toByteArray()).inputStream()) }
    @Test fun validatesImageAllowlist() {
        assertTrue(BoundedBackupReader.safeEntryName("scan_feedback/images/feedback-1.jpg"))
        assertFalse(BoundedBackupReader.safeEntryName("scan_feedback/images/.jpg"))
    }
}
