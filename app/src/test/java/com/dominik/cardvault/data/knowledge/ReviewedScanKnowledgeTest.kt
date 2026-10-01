package com.dominik.cardvault.data.knowledge

import com.dominik.cardvault.data.feedback.ScannerReportKnowledgeContext
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

class ReviewedScanKnowledgeTest {
    private val context = ScannerReportKnowledgeContext("a".repeat(64), "scanner-v2.1", 1)
    private val json = Moshi.Builder().build().adapter(Any::class.java)

    @Test fun requestContainsOnlyOriginalKeyAndVersions() {
        val payload = json.fromJson(requireNotNull(ReviewedScanKnowledgeContract.payload(context))) as Map<*, *>
        assertEquals(setOf("fingerprint", "scannerVersion", "knowledgeVersion"), payload.keys)
        assertEquals(context.fingerprint, payload["fingerprint"])
        assertNull(ReviewedScanKnowledgeContract.payload(context.copy(fingerprint = "reconstructed")))
        assertNull(ReviewedScanKnowledgeContract.payload(context.copy(knowledgeVersion = 2)))
    }

    @Test fun onlyMatchingStrictTesterOrAdminAccountCanRead() {
        for (claim in listOf("pretester", "admin")) {
            assertTrue(ReviewedScanKnowledgeContract.allowedAccount("u", "u", mapOf(claim to true)))
            assertFalse(ReviewedScanKnowledgeContract.allowedAccount("u", "u", mapOf(claim to "true")))
            assertFalse(ReviewedScanKnowledgeContract.allowedAccount("u", "other", mapOf(claim to true)))
            assertFalse(ReviewedScanKnowledgeContract.allowedAccount("u", null, mapOf(claim to true)))
        }
        assertFalse(ReviewedScanKnowledgeContract.allowedAccount("u", "u", mapOf("appAccess" to true)))
        assertFalse(ReviewedScanKnowledgeContract.allowedAccount("u", "u", mapOf("scannerLabAccess" to true)))
    }

    @Test fun responseIsExactVersionBoundAndHasNoCommunityVoteState() {
        val hint = requireNotNull(ReviewedScanKnowledgeContract.parseResponse(context, response()))
        assertEquals("sv1-001", hint.sourceCardId)
        assertEquals("normal", hint.variant)
        for ((key, value) in listOf("fingerprint" to "b".repeat(64), "scannerVersion" to "other", "knowledgeVersion" to 2,
            "knowledgeVersion" to 1.5, "sourceSetId" to "../private", "sourceCardId" to "bad/path", "cardNumber" to "",
            "variant" to "first-edition")) {
            assertNull("Must reject $key=$value", ReviewedScanKnowledgeContract.parseResponse(context, response(mapOf(key to value))))
        }
        for (body in listOf("{}", "[]", "{\"ok\":true,\"hint\":null}", "{\"ok\":false,\"hint\":{}}", "x".repeat(4097))) {
            assertNull(ReviewedScanKnowledgeContract.parseResponse(context, body))
        }
    }

    @Test fun transportHandlesShortValidJsonAndFailsClosedOnErrorsAndOversizedBodies() = runBlocking {
        assertNotNull(FakeCall(200, response()).awaitReviewedHint(context))
        for (status in listOf(201, 302, 401, 403, 404, 500, 503)) {
            assertNull(FakeCall(status, response()).awaitReviewedHint(context))
        }
        assertNull(FakeCall(200, " ".repeat(4097) + response()).awaitReviewedHint(context))
        assertNull(FakeCall(200, "not-json").awaitReviewedHint(context))
    }

    @Test fun timedOutReadCancelsTheTransport() = runBlocking {
        val call = FakeCall(null, "")
        assertNull(withTimeoutOrNull(20) { call.awaitReviewedHint(context) })
        assertTrue(call.isCanceled())
    }

    private fun response(changes: Map<String, Any?> = emptyMap()): String = json.toJson(mapOf("ok" to true,
        "hint" to (mapOf("fingerprint" to context.fingerprint, "scannerVersion" to context.scannerVersion,
            "knowledgeVersion" to 1, "sourceSetId" to "sv1", "sourceCardId" to "sv1-001",
            "cardNumber" to "001", "variant" to "normal") + changes)))

    private class FakeCall(private val status: Int?, private val body: String) : Call {
        private var cancelled = false
        private var executed = false
        override fun request(): Request = Request.Builder().url(ReviewedScanKnowledgeContract.ENDPOINT).build()
        override fun execute(): Response = error("No network calls in this test")
        override fun enqueue(responseCallback: Callback) {
            executed = true
            status?.let { responseCallback.onResponse(this, Response.Builder().request(request()).protocol(Protocol.HTTP_1_1)
                .code(it).message("test").body(body.toResponseBody()).build()) }
        }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = executed
        override fun isCanceled() = cancelled
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall(status, body)
    }
}
