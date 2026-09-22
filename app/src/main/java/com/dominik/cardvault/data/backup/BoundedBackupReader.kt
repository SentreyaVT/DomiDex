package com.dominik.cardvault.data.backup

import java.io.ByteArrayOutputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.util.zip.ZipInputStream

/** Limits are applied while reading, before allocating the next chunk. No archive-sized raw copy. */
object BoundedBackupReader {
    data class Limits(
        val compressedBytes: Long = 28L * 1024 * 1024,
        val totalBytes: Long = 64L * 1024 * 1024,
        val entryBytes: Int = 14 * 1024 * 1024,
        val entries: Int = 650,
        val compressionRatio: Long = 200,
    )

    fun read(input: InputStream, limits: Limits = Limits()): Map<String, ByteArray> {
        val counted = object : FilterInputStream(input) {
            var consumed = 0L
            fun count(size: Int) { if (size > 0) { consumed += size; require(consumed <= limits.compressedBytes) { "Die Backup-Datei ist zu groß." } } }
            override fun read(): Int = super.read().also { if (it >= 0) count(1) }
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also(::count)
        }
        val source = PushbackInputStream(counted, 4)
        val header = ByteArray(4)
        var size = 0
        while (size < header.size) { val next = source.read(header, size, header.size - size); if (next < 0) break; size += next }
        source.unread(header, 0, size)
        if (size < 4 || header[0] != 80.toByte() || header[1] != 75.toByte()) {
            return mapOf("collection.json" to readBounded(source, minOf(limits.entryBytes.toLong(), limits.totalBytes)))
        }
        var total = 0L
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(source).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory && safeEntryName(entry.name)) { "Ungültiger Pfad im Backup." }
                require(files.size < limits.entries && !files.containsKey(entry.name)) { "Zu viele oder doppelte Dateien im Backup." }
                require(entry.size <= limits.entryBytes) { "Ein Backup-Eintrag ist zu groß." }
                val bytes = readBounded(zip, minOf(limits.entryBytes.toLong(), limits.totalBytes - total))
                total += bytes.size
                zip.closeEntry() // validates CRC and makes data-descriptor compressed size available
                require(entry.compressedSize >= 0 && bytes.size.toLong() <= maxOf(1024L, entry.compressedSize) * limits.compressionRatio) {
                    "Unzulässiges Kompressionsverhältnis im Backup."
                }
                files[entry.name] = bytes
            }
        }
        require(files.containsKey("collection.json")) { "Die Sammlung fehlt im Backup." }
        return files
    }

    internal fun safeEntryName(name: String): Boolean = name == "collection.json" || name == "scan_feedback/feedback.json" ||
        Regex("user_photos/[a-f0-9]{64}\\.bin").matches(name) ||
        Regex("scan_feedback/images/[A-Za-z0-9][A-Za-z0-9_-]{0,79}\\.jpg").matches(name)

    private fun readBounded(input: InputStream, limit: Long): ByteArray {
        require(limit >= 0) { "Das entpackte Backup ist zu groß." }
        val buffer = ByteArray(8192)
        return ByteArrayOutputStream().use { output ->
            var count = 0L
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                count += size
                require(count <= limit) { "Das entpackte Backup ist zu groß." }
                output.write(buffer, 0, size)
            }
            output.toByteArray()
        }
    }
}
