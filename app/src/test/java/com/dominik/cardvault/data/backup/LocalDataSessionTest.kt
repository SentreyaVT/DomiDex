package com.dominik.cardvault.data.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class LocalDataSessionTest {
    @Test fun localGuardAllowsGuestAndOfflineOwnerWithoutCloudEntitlements() {
        listOf("__domidex_guest__", "offline-without-app-access").forEach {
            assertTrue(isLocalDataSessionCurrent(it, it, 1, 1, false))
        }
    }
    @Test fun oldGenerationDoesNotRegainAuthorityAfterAba() {
        assertFalse(isLocalDataSessionCurrent("a", "b", 1, 2, false))
        assertFalse(isLocalDataSessionCurrent("a", "a", 1, 3, false))
        assertFalse(isLocalDataSessionCurrent("a", "a", 1, 1, true))
        assertTrue(isLocalDataSessionCurrent("a", "a", 3, 3, false))
    }
    @Test fun revokedBeforeReadDoesNotWriteBytes() {
        val target = ByteArrayOutputStream()
        assertTrue(runCatching { LocalDataSession("a") { false }.copyTo(ByteArrayInputStream(byteArrayOf(1)), target) }.isFailure)
        assertEquals(0, target.size())
    }
    @Test fun accountChangeDuringReadDoesNotPublishReadChunk() {
        var current = true
        val source = object : ByteArrayInputStream(byteArrayOf(1, 2)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                return super.read(buffer, offset, length).also { current = false }
            }
        }
        val target = ByteArrayOutputStream()
        assertTrue(runCatching { LocalDataSession("a") { current }.copyTo(source, target) }.isFailure)
        assertEquals(0, target.size())
    }
    @Test fun currentSessionCopiesExactBytes() {
        val bytes = ByteArray(20_000) { it.toByte() }
        val target = ByteArrayOutputStream()
        LocalDataSession("a") { true }.copyTo(ByteArrayInputStream(bytes), target)
        assertArrayEquals(bytes, target.toByteArray())
    }
    @Test fun invalidOwnerCannotBeCaptured() {
        listOf("", " ", "x".repeat(129), "a\nb").forEach { owner ->
            assertTrue(runCatching { LocalDataSession(owner) { true } }.isFailure)
        }
    }

    @Test fun sameOwnerRefreshAndSyncRequestDoNotInvalidateLocalLease() {
        val lifecycle = LocalDataOwnerSession("offline-owner")
        val current = lifecycle.capture("offline-owner")
        lifecycle.observeOwner("offline-owner") // same UID token refresh or requestSync recheck
        assertTrue("Cloud-verification attempts must not invalidate a local backup", current())
    }

    @Test fun observedUidAbaAndCloseInvalidateLocalLease() {
        val lifecycle = LocalDataOwnerSession("a")
        val original = lifecycle.capture("a")
        lifecycle.observeOwner("b"); assertFalse(original())
        lifecycle.observeOwner("a"); assertFalse(original())
        val fresh = lifecycle.capture("a"); assertTrue(fresh())
        lifecycle.close(); assertFalse(fresh())
    }
}
