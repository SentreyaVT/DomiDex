package com.dominik.cardvault.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageImagePolicyTest {
    @Test fun acceptsOnlySupportedImageSignatures() {
        assertEquals("image/jpeg", detectSupportedImageMime(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())))
        assertEquals("image/png", detectSupportedImageMime(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        assertEquals("image/webp", detectSupportedImageMime("RIFF0000WEBP".toByteArray()))
        assertNull(detectSupportedImageMime("not-an-image".toByteArray()))
    }

    @Test fun pathsStayInsideAuthenticatedUidArea() {
        val image = ValidatedStorageImage("image/jpeg", "jpg", "a".repeat(64))
        assertEquals("catalog-contributions/user-A/${"a".repeat(64)}/card.jpg", DomiDexStoragePaths.catalogContribution("user-A", image))
        assertEquals("users/user-A/scans/front.jpg", DomiDexStoragePaths.privateImage("user-A", "scans/front", "jpg"))
        assertNull(DomiDexStoragePaths.privateImage("user-A", "../other-user", "jpg"))
        assertTrue(MAX_STORAGE_IMAGE_BYTES == 10L * 1024L * 1024L)
    }
}
