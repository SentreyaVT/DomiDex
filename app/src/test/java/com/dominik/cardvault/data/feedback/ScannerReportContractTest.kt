package com.dominik.cardvault.data.feedback

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerReportContractTest {
    private val mapAdapter = Moshi.Builder().build().adapter<Map<String, Any?>>(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )
    private val reportId = "8e5ef6de-0918-4bd5-9b23-d1d37b4bbf77"

    @Test fun payloadContainsOnlyTheServerContractAndNeverTheLocalImagePath() {
        val draft = draft().copy(
            guessedName = " Pikachu ",
            guessedSet = " Base Set ",
            guessedNumber = " 58/102 ",
            confidence = 0.75,
            ocrTokens = listOf("Pikachu", "58/102"),
            zoneConfidence = mapOf("name" to 0.9)
        )

        val json = ScannerReportContract.payload(draft)
        val payload = requireNotNull(mapAdapter.fromJson(json))

        assertEquals(
            setOf(
                "scanId", "captureType", "guessedName", "guessedSet", "guessedNumber",
                "confidence", "ocrTokens", "zoneConfidence", "failureReason", "attempts"
            ),
            payload.keys
        )
        assertEquals("manual.scan-1", payload["scanId"])
        assertEquals("MANUAL_CAPTURE", payload["captureType"])
        assertEquals("Pikachu", payload["guessedName"])
        assertEquals("Base Set", payload["guessedSet"])
        assertEquals("58/102", payload["guessedNumber"])
        assertEquals(emptyList<Any>(), payload["attempts"])
        assertFalse(json.contains("private-owner"))
        assertFalse(json.contains("private-capture.jpg"))
    }

    @Test fun automaticAndMislabelledCapturesAreRejectedBeforeUpload() {
        listOf("AUTO_CAPTURE", "MANUAL", "manual_capture", " MANUAL_CAPTURE ", "").forEach { type ->
            assertThrows(ScannerReportUploadException::class.java) {
                ScannerReportContract.payload(draft().copy(captureType = type))
            }
        }
    }

    @Test fun scanIdentifiersMustAlreadyBeSafeAndWithinTheServerLimit() {
        listOf("", " ", "scan/id", "scan\\id", "scan@id", " scan-id ", "a".repeat(129)).forEach { id ->
            assertThrows(ScannerReportUploadException::class.java) {
                ScannerReportContract.payload(draft().copy(scanId = id))
            }
        }
        val allowed = "a".repeat(124) + "._-1"
        assertEquals(allowed, payload(draft().copy(scanId = allowed))["scanId"])
    }

    @Test fun reportTextAndOcrStayWithinServerLimits() {
        val payload = payload(
            draft().copy(
                guessedName = "  " + "n".repeat(201) + "  ",
                guessedSet = "  " + "s".repeat(161) + "  ",
                guessedNumber = "  " + "c".repeat(81) + "  ",
                failureReason = "  " + "f".repeat(1001) + "  ",
                ocrTokens = List(101) { "  " + "t".repeat(121) + "  " }
            )
        )

        assertEquals("n".repeat(200), payload["guessedName"])
        assertEquals("s".repeat(160), payload["guessedSet"])
        assertEquals("c".repeat(80), payload["guessedNumber"])
        assertEquals("f".repeat(1000), payload["failureReason"])
        val tokens = payload["ocrTokens"] as List<*>
        assertEquals(100, tokens.size)
        assertTrue(tokens.all { it == "t".repeat(120) })
    }

    @Test fun blankFailureReasonUsesTheServerFallback() {
        assertEquals(
            "Kein Fehlergrund übermittelt.",
            payload(draft().copy(failureReason = " \n\t "))["failureReason"]
        )
    }

    @Test fun unknownOrInvalidConfidenceIsOmittedInsteadOfBecomingZero() {
        listOf(null, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -0.01, 1.01, 75.0)
            .forEach { confidence ->
                val payload = payload(draft().copy(confidence = confidence))
                assertFalse("Confidence $confidence must be absent", payload.containsKey("confidence"))
            }
        listOf(0.0, 0.75, 1.0).forEach { confidence ->
            assertEquals(confidence, payload(draft().copy(confidence = confidence))["confidence"])
        }
    }

    @Test fun zoneConfidenceDropsInvalidNumbersAndNormalizesKeys() {
        val zones = payload(
            draft().copy(
                zoneConfidence = linkedMapOf(
                    " name " to 0.9,
                    "z".repeat(81) to 0.5,
                    "zero" to 0.0,
                    "one" to 1.0,
                    "negative" to -0.1,
                    "percent" to 85.0,
                    "nan" to Double.NaN,
                    "infinite" to Double.POSITIVE_INFINITY
                )
            )
        )["zoneConfidence"] as Map<*, *>

        assertEquals(mapOf("name" to 0.9, "z".repeat(80) to 0.5, "zero" to 0.0, "one" to 1.0), zones)
    }

    @Test fun zoneConfidenceNeverExceedsTheFirestoreEntryLimit() {
        val zones = payload(
            draft().copy(zoneConfidence = (1..65).associate { "zone-$it" to 0.8 })
        )["zoneConfidence"] as Map<*, *>

        assertEquals(64, zones.size)
        assertTrue(zones.values.all { it == 0.8 })
    }

    @Test fun onlyMatchingAccountsWithBooleanAdminOrPretesterClaimsMayUpload() {
        assertTrue(ScannerReportContract.allowedAccount("owner", "owner", mapOf("admin" to true)))
        assertTrue(ScannerReportContract.allowedAccount("owner", "owner", mapOf("pretester" to true)))
        assertFalse(ScannerReportContract.allowedAccount("owner", "other", mapOf("admin" to true)))
        assertFalse(ScannerReportContract.allowedAccount("owner", null, mapOf("pretester" to true)))
        assertFalse(ScannerReportContract.allowedAccount("owner", "owner", mapOf("appAccess" to true)))
        assertFalse(ScannerReportContract.allowedAccount("owner", "owner", mapOf("admin" to "true")))
        assertFalse(ScannerReportContract.allowedAccount("owner", "owner", mapOf("pretester" to 1)))
        assertFalse(ScannerReportContract.allowedAccount("owner", "owner", mapOf("admin" to false)))
        assertFalse(ScannerReportContract.allowedAccount("owner", "owner", emptyMap<String, Any>()))
    }

    @Test fun onlyACompleteCreatedReceiptConfirmsUpload() {
        val receipt = ScannerReportContract.parseReceipt(201, receiptBody())
        assertEquals(reportId, receipt.reportId)
    }

    @Test fun malformedOrIncompleteCreatedResponsesLeaveTheOutcomeUnknown() {
        val malformedBodies = listOf(
            "",
            "<html>upstream failure</html>",
            "null",
            "[]",
            "{}",
            "{\"ok\":true}",
            "{\"ok\":true,\"reportId\":\"$reportId\"}",
            receiptBody(ok = "false"),
            receiptBody(ok = "\"true\""),
            receiptBody(ok = "1"),
            receiptBody(id = "not-a-uuid"),
            receiptBody(id = "1-1-1-1-1"),
            receiptBody(id = " $reportId "),
            receiptBody(reviewStatus = "REVIEWED")
        )

        malformedBodies.forEach { body ->
            val failure = assertThrows(ScannerReportUploadException::class.java) {
                ScannerReportContract.parseReceipt(201, body)
            }
            assertTrue("A malformed 201 cannot prove rejection: $body", failure.outcomeUnknown)
        }
    }

    @Test fun successShapedBodiesAtOtherStatusesNeverConfirmUpload() {
        listOf(200, 202, 204, 302, 408, 429, 500, 502, 503).forEach { status ->
            val failure = assertThrows(ScannerReportUploadException::class.java) {
                ScannerReportContract.parseReceipt(status, receiptBody())
            }
            assertTrue("Status $status does not establish whether a report was stored", failure.outcomeUnknown)
        }
    }

    @Test fun knownRejectionsAreDefinitiveAndDoNotExposeTheResponseBody() {
        val privateResponse = "server-secret-and-private-account@example.invalid"
        listOf(400, 401, 403, 413, 415).forEach { status ->
            val failure = assertThrows(ScannerReportUploadException::class.java) {
                ScannerReportContract.parseReceipt(status, privateResponse)
            }
            assertFalse("Status $status definitively rejects the upload", failure.outcomeUnknown)
            assertFalse(failure.message.orEmpty().contains(privateResponse))
        }
    }

    @Test fun imageSamplingRoundsUpAtBothDimensionBoundaries() {
        assertEquals(1, ScannerReportContract.decodeSample(2048, 2048))
        assertEquals(2, ScannerReportContract.decodeSample(2049, 1000))
        assertEquals(2, ScannerReportContract.decodeSample(1000, 2049))
        assertEquals(2, ScannerReportContract.decodeSample(4096, 3000))
        assertEquals(4, ScannerReportContract.decodeSample(4097, 3000))
        assertEquals(4, ScannerReportContract.decodeSample(3000, 4097))
        assertEquals(4, ScannerReportContract.decodeSample(3000, 2000, maxEdge = 1000))
        assertEquals(1_048_576, ScannerReportContract.decodeSample(Int.MAX_VALUE, Int.MAX_VALUE))
    }

    @Test fun invalidImageDimensionsCannotEnterTheDecodeLoop() {
        listOf(0 to 100, 100 to 0, -1 to 100, 100 to -1).forEach { (width, height) ->
            assertThrows(ScannerReportUploadException::class.java) {
                ScannerReportContract.decodeSample(width, height)
            }
        }
        listOf(0, -1).forEach { maxEdge ->
            assertThrows(ScannerReportUploadException::class.java) {
                ScannerReportContract.decodeSample(100, 100, maxEdge)
            }
        }
    }

    private fun draft() = ScannerReportDraft(
        scanId = "manual.scan-1",
        image = File("private-owner/private-capture.jpg"),
        failureReason = "No matching card"
    )

    private fun payload(draft: ScannerReportDraft): Map<String, Any?> =
        requireNotNull(mapAdapter.fromJson(ScannerReportContract.payload(draft)))

    private fun receiptBody(
        ok: String = "true",
        id: String = reportId,
        reviewStatus: String = "NEW"
    ) = "{\"ok\":$ok,\"reportId\":\"$id\",\"reviewStatus\":\"$reviewStatus\"}"
}
