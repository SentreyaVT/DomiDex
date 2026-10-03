package com.dominik.cardvault.cache

import coil3.disk.DiskCache
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real Coil journal/files in an exclusively owned temporary directory; no Android/network. */
class DomiDexImageCachePolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun fixedBudgetKeepsTheExistingCoilDirectoryWithoutCreatingASiblingCache() {
        val parent = temporary.newFolder("application-cache").toOkioPath()
        val directory = DomiDexImageCachePolicy.directory(parent)
        assertEquals(parent / "coil3_disk_cache", directory)
        val cache = DomiDexImageCachePolicy.createDiskCache(directory)
        try {
            assertEquals(67_108_864L, cache.maxSize)
            assertEquals(directory, cache.directory)
            assertFalse("Construction must not eagerly touch files", directory.toFile().exists())
        } finally { cache.shutdown() }
    }

    @Test fun reopeningAnExistingCoilJournalPreservesItsUsableImage() {
        val directory = DomiDexImageCachePolicy.directory(temporary.newFolder("reuse").toOkioPath())
        val image = byteArrayOf(4, 8, 15, 16, 23, 42)
        val old = DiskCache.Builder().directory(directory).maxSizeBytes(250L * 1024 * 1024).build()
        try { write(old, "same-image", image.size.toLong(), image) } finally { old.shutdown() }

        val current = DomiDexImageCachePolicy.createDiskCache(directory)
        try {
            assertEquals(67_108_864L, current.maxSize)
            val snapshot = requireNotNull(current.openSnapshot("same-image"))
            try { assertArrayEquals(image, current.fileSystem.read(snapshot.data) { readByteArray() }) }
            finally { snapshot.close() }
        } finally { current.shutdown() }
    }

    @Test fun realLruTrimsCommittedBytesWithoutTouchingPhotosUploadsOrEvidenceSiblings() {
        val parentFile = temporary.newFolder("isolated-app")
        val protectedFiles = listOf("backup_photos/photo.bin", "scan_feedback/images/feedback.jpg",
            "firebase_storage_uploads/pending.jpg", "scan_knowledge/outbox-v2/pending.json",
            "scan_knowledge/uploaded-v2/receipt.json").map { relative ->
            val file = parentFile.resolve(relative)
            val parent = requireNotNull(file.parentFile)
            check(parent.mkdirs() || parent.isDirectory)
            file.writeBytes(byteArrayOf(12, 34, 56))
            file
        }
        val directory = DomiDexImageCachePolicy.directory(parentFile.toOkioPath())
        val cache = DomiDexImageCachePolicy.createDiskCache(directory)
        try {
            for (key in listOf("oldest", "middle", "newest")) {
                write(cache, key, 24L * 1024 * 1024)
            }
        } finally { cache.shutdown() } // Genuine Coil synchronous final trim, not a polling assertion.

        val reopened = DomiDexImageCachePolicy.createDiskCache(directory)
        try {
            assertEquals(48L * 1024 * 1024, reopened.size)
            assertNull(reopened.openSnapshot("oldest"))
            for (key in listOf("middle", "newest")) {
                val snapshot = reopened.openSnapshot(key)
                assertNotNull(snapshot)
                snapshot?.close()
            }
            protectedFiles.forEach { assertArrayEquals(byteArrayOf(12, 34, 56), it.readBytes()) }
        } finally { reopened.shutdown() }
    }

    @Test fun abortedReplacementKeepsTheCommittedImage() {
        val directory = DomiDexImageCachePolicy.directory(temporary.newFolder("abort").toOkioPath())
        val cache = DomiDexImageCachePolicy.createDiskCache(directory)
        try {
            val original = byteArrayOf(1, 3, 5, 7)
            write(cache, "image", original.size.toLong(), original)
            val editor = requireNotNull(cache.openEditor("image"))
            try { cache.fileSystem.write(editor.data) { write(byteArrayOf(9, 9)) } }
            finally { editor.abort() }
            val snapshot = requireNotNull(cache.openSnapshot("image"))
            try { assertArrayEquals(original, cache.fileSystem.read(snapshot.data) { readByteArray() }) }
            finally { snapshot.close() }
            assertEquals(original.size.toLong(), cache.size)
        } finally { cache.shutdown() }
    }

    private fun write(cache: DiskCache, key: String, bytes: Long, block: ByteArray = ByteArray(8192) { 7 }) {
        val editor = requireNotNull(cache.openEditor(key))
        try {
            cache.fileSystem.write(editor.metadata) { }
            cache.fileSystem.write(editor.data) {
                var remaining = bytes
                while (remaining > 0) {
                    val count = minOf(remaining, block.size.toLong()).toInt()
                    write(block, 0, count)
                    remaining -= count
                }
            }
            editor.commit()
        } catch (error: Throwable) {
            editor.abort()
            throw error
        }
    }
}
