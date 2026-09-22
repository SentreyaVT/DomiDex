package com.dominik.cardvault.data.sharing

import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.squareup.moshi.Moshi
import java.io.ByteArrayInputStream
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class BinderSharingContractTest {
    @Test fun reservedLocalGuestCanNeverOwnAPublicShare() {
        assertThrows(BinderSharingException::class.java) {
            BinderSharingContract.requireAccount(GUEST_OWNER_UID, GUEST_OWNER_UID)
        }
    }
    private val json = Moshi.Builder().build().adapter(Any::class.java)
    private val token = "A".repeat(43)
    private val shareId = MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it.toInt() and 255) }
    private val flags = mapOf("showPrices" to false, "showTotal" to false, "showDisplayName" to false)
    private val receipt get() = mapOf("shareId" to shareId, "path" to "/share/$token", "createdAt" to 1_758_456_789_000L, "options" to flags)

    @Test fun allOptionalDataAreOffAndUnselectedDisplayNameIsNotSent() {
        val payload = objectBody(BinderSharingContract.createPayload("binder_1", BinderShareOptions(), "private@example.com", true))
        assertEquals(setOf("binderId", "confirm", "showPrices", "showTotal", "showDisplayName"), payload.keys)
        assertEquals(false, payload["showPrices"])
        assertEquals(false, payload["showTotal"])
        assertEquals(false, payload["showDisplayName"])
        assertEquals(true, payload["confirm"])
        assertFalse(payload.containsKey("ownerUid"))
    }

    @Test fun creationRequiresConfirmationAndExactSafeBinderId() {
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.createPayload("binder", BinderShareOptions(), null, false) }
        for (id in listOf("", " a", "a/b", "a?owner=x", "a&owner=x", "a".repeat(129))) {
            assertThrows(BinderSharingException::class.java) { BinderSharingContract.listUrl(id) }
            assertThrows(BinderSharingException::class.java) { BinderSharingContract.createPayload(id, BinderShareOptions(), null, true) }
        }
        assertEquals("https://domidex.web.app/api/binder-shares?binderId=binder-1_A", BinderSharingContract.listUrl("binder-1_A"))
    }

    @Test fun selectedNameIsExplicitTrimmedAndNeverAnEmail() {
        val selected = BinderShareOptions(showDisplayName = true)
        for (name in listOf(null, "", " ", "name@example.com", "a\nb", "a\rb", "a".repeat(61))) {
            assertThrows(BinderSharingException::class.java) { BinderSharingContract.createPayload("binder", selected, name, true) }
        }
        assertEquals("Sammler", objectBody(BinderSharingContract.createPayload("binder", selected, " Sammler ", true))["displayName"])
    }

    @Test fun receiptMustHaveExactOriginCanonicalTokenMatchingHashAndIdenticalFlags() {
        val created = BinderSharingContract.parseCreated(201, json.toJson(receipt), BinderShareOptions())
        assertEquals("https://domidex.web.app/share/$token", created.url)
        assertEquals(shareId, created.share.shareId)
        assertFalse(created.toString().contains(token))
        for (path in listOf("https://evil.invalid/share/$token", "//evil.invalid/share/$token", "/share/$token?x=1", "/share/$token#x", "/share/" + "B".repeat(43))) {
            assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseCreated(201, json.toJson(receipt + ("path" to path)), BinderShareOptions()) }
        }
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseCreated(201, json.toJson(receipt + ("shareId" to "f".repeat(64))), BinderShareOptions()) }
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseCreated(201, json.toJson(receipt), BinderShareOptions(showPrices = true)) }
    }

    @Test fun listsRejectAmbiguousIdsTimestampsMissingFlagsAndDuplicates() {
        val summary = receipt - "path"
        assertEquals(listOf(BinderShareSummary(shareId, 1_758_456_789_000L, BinderShareOptions())), BinderSharingContract.parseList(200, json.toJson(mapOf("shares" to listOf(summary)))))
        val invalid = listOf(summary - "options", summary + ("createdAt" to -1), summary + ("createdAt" to 1.5), summary + ("createdAt" to 9_007_199_254_740_992.0), summary + ("shareId" to "name"), summary + ("options" to flags + ("showPrices" to "false")))
        for (item in invalid) assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseList(200, json.toJson(mapOf("shares" to listOf(item)))) }
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseList(200, json.toJson(mapOf("shares" to listOf(summary, summary)))) }
    }

    @Test fun revokeRequiresExactHashAndAffirmativeServerReceipt() {
        assertEquals(mapOf("shareId" to shareId, "confirm" to true), objectBody(BinderSharingContract.revokePayload(shareId)))
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.revokePayload("../owner") }
        BinderSharingContract.parseRevoked(200, "{\"ok\":true}")
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseRevoked(200, "{\"ok\":false}") }
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseRevoked(200, "{}") }
    }

    @Test fun errorsAreHonestAndNeverReflectRemoteBody() {
        for (status in listOf(301, 302, 400, 401, 403, 404, 429, 500, 503)) {
            val error = assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseList(status, "private-token-user@example.com") }
            assertFalse(error.message.orEmpty().contains("private-token"))
            if (status == 404) { assertTrue(error.message.orEmpty().contains("nicht aktiviert")); assertTrue(error.message.orEmpty().contains("nicht synchronisiert")) }
            if (status == 401 || status == 403) assertTrue(error.message.orEmpty().contains("Zugriff"))
        }
    }

    @Test fun responseBodyIsBoundedEvenWithoutOrWithFalseLengthHeader() {
        val tooLarge = ByteArray(BinderSharingContract.MAX_RESPONSE_BYTES + 1) { 'a'.code.toByte() }
        for (declared in listOf(-1L, 1L, tooLarge.size.toLong())) assertThrows(BinderSharingException::class.java) { BinderSharingContract.readBody(ByteArrayInputStream(tooLarge), declared) }
        assertEquals("{}", BinderSharingContract.readBody(ByteArrayInputStream("{}".toByteArray())))
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseList(200, " ".repeat(BinderSharingContract.MAX_RESPONSE_BYTES + 1)) }
        assertThrows(BinderSharingException::class.java) { BinderSharingContract.parseList(200, "not-json") }
    }

    @Test fun expectedPersistedOwnerCannotBeBlankOrDifferent() {
        BinderSharingContract.requireAccount("owner-a", "owner-a")
        for ((expected, current) in listOf("" to "", "owner-a" to null, "owner-a" to "owner-b")) {
            assertThrows(BinderSharingException::class.java) { BinderSharingContract.requireAccount(expected, current) }
        }
    }

    private fun objectBody(body: String) = json.fromJson(body) as Map<*, *>
}
