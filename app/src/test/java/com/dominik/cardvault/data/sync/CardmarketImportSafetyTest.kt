package com.dominik.cardvault.data.sync

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import okio.Buffer

class CardmarketImportSafetyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val sourceTime = "2026-09-22T02:48:57+0200"
    private val product = CardmarketProduct(10, "Existing product", 51, 1, null)
    private val price = CardmarketPrice(10, 51, 1200, 1000, 1500, null, null, null, null, null, null)
    private fun products(records: List<CardmarketProduct> = listOf(product)) = ParsedDownload(1, sourceTime, records)
    private fun prices(createdAt: String? = sourceTime, records: List<CardmarketPrice> = listOf(price)) = ParsedDownload(1, createdAt, records)

    @Test fun manualAndWorkerEntriesShareOneImportGuard() = runBlocking {
        withTimeout(2_000) {
            val firstEntered = CompletableDeferred<Unit>()
            val releaseFirst = CompletableDeferred<Unit>()
            var secondEntered = false
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                CardmarketImportGuard.run { firstEntered.complete(Unit); releaseFirst.await() }
            }
            firstEntered.await()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                CardmarketImportGuard.run { secondEntered = true }
            }
            try { assertFalse("A worker must not enter while a manual import is active", secondEntered) }
            finally { releaseFirst.complete(Unit); first.await(); second.await() }
            assertTrue(secondEntered)
        }
    }

    @Test fun importGuardIsReleasedAfterFailure() = runBlocking {
        assertThrows(IOException::class.java) { runBlocking { CardmarketImportGuard.run { throw IOException("fixture") } } }
        assertEquals(7, withTimeout(1_000) { CardmarketImportGuard.run { 7 } })
    }

    @Test fun cancellingAQueuedImportCannotRunItsBody() = runBlocking {
        withTimeout(2_000) {
            val release = CompletableDeferred<Unit>()
            val first = async(start = CoroutineStart.UNDISPATCHED) { CardmarketImportGuard.run { release.await() } }
            var ran = false
            val queued = async(start = CoroutineStart.UNDISPATCHED) { CardmarketImportGuard.run { ran = true } }
            queued.cancel()
            release.complete(Unit); first.await(); queued.join()
            assertFalse(ran)
        }
    }

    @Test fun declaredOversizeIsRejectedBeforeReadingOrWriting() {
        var reads = 0
        val input = object : InputStream() { override fun read(): Int { reads++; return -1 } }
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { copyCardmarketDownload(input, output, 9, 8) }
        assertEquals(0, reads); assertEquals(0, output.size())
    }

    @Test fun unknownLengthStreamCannotWriteBeyondLimit() { rejectsOversize(-1) }
    @Test fun dishonestSmallLengthCannotBypassStreamLimit() { rejectsOversize(1) }
    @Test fun decompressedUnknownLengthStillUsesExpandedByteLimit() {
        val compressed = ByteArrayOutputStream().also { bytes ->
            GZIPOutputStream(bytes).use { it.write(ByteArray(32_768)) }
        }.toByteArray()
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            assertThrows(IOException::class.java) { copyCardmarketDownload(input, output, -1, 8_208) }
        }
        assertTrue(output.size() <= 8_208)
        assertTrue("Fixture must exercise multiple reads", output.size() >= 8_192)
    }
    @Test fun validDecompressedUnknownLengthPayloadIsPreserved() {
        val payload = ByteArray(8_208) { (it % 127).toByte() }
        val compressed = ByteArrayOutputStream().also { bytes -> GZIPOutputStream(bytes).use { it.write(payload) } }.toByteArray()
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            assertEquals(payload.size.toLong(), copyCardmarketDownload(input, output, -1, payload.size.toLong()))
        }
        assertArrayEquals(payload, output.toByteArray())
    }
    private fun rejectsOversize(contentLength: Long) {
        val output = ByteArrayOutputStream()
        assertThrows(IOException::class.java) { copyCardmarketDownload(ByteArrayInputStream(ByteArray(100)), output, contentLength, 8) }
        assertTrue(output.size() <= 8)
    }

    @Test fun exactLimitIsAcceptedWithDeclaredLength() { acceptsExactLimit(8) }
    @Test fun exactLimitIsAcceptedWithUnknownLength() { acceptsExactLimit(-1) }
    private fun acceptsExactLimit(contentLength: Long) {
        val bytes = ByteArray(8) { it.toByte() }; val output = ByteArrayOutputStream()
        assertEquals(8L, copyCardmarketDownload(ByteArrayInputStream(bytes), output, contentLength, 8))
        assertArrayEquals(bytes, output.toByteArray())
    }

    @Test fun truncatedDeclaredBodyIsRejected() {
        assertThrows(IOException::class.java) { copyCardmarketDownload(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream(), 8, 8) }
    }

    @Test fun declaredSmallerBodyIsRejectedEvenBelowLimit() {
        assertThrows(IOException::class.java) { copyCardmarketDownload(ByteArrayInputStream(ByteArray(4)), ByteArrayOutputStream(), 2, 8) }
    }

    @Test fun invalidLimitDoesNotReadInput() {
        assertThrows(IllegalArgumentException::class.java) { copyCardmarketDownload(ByteArrayInputStream(byteArrayOf(1)), ByteArrayOutputStream(), -1, 0) }
    }

    @Test fun originalSourceOffsetFormatKeepsItsActualInstant() {
        assertEquals(Instant.parse("2026-09-22T00:48:57Z").toEpochMilli(), prepareCardmarketImport(products(), prices()).effectiveAtEpochMillis)
    }

    @Test fun isoUtcSourceTimeIsAcceptedWithoutDeviceClockFallback() {
        val source = "2026-08-16T00:49:33Z"
        assertEquals(Instant.parse(source).toEpochMilli(), prepareCardmarketImport(products(), prices(source)).effectiveAtEpochMillis)
    }

    @Test fun isoColonOffsetAndFractionalSecondsAreAccepted() {
        assertEquals(Instant.parse("2026-09-22T00:48:57.123Z").toEpochMilli(), prepareCardmarketImport(products(), prices("2026-09-22T02:48:57.123+02:00")).effectiveAtEpochMillis)
    }

    @Test fun missingGuideTimestampCannotUseProductDateOrDeviceNow() { rejectsBeforeCommit(products(), prices(null)) }
    @Test fun malformedGuideTimestampCannotBeMarkedFresh() { rejectsBeforeCommit(products(), prices("yesterday")) }
    @Test fun impossibleCalendarDateIsNotSilentlyCorrected() { rejectsBeforeCommit(products(), prices("2026-02-30T02:48:57+0200")) }
    @Test fun timezoneFreeTimestampIsNotGuessed() { rejectsBeforeCommit(products(), prices("2026-09-22T02:48:57")) }
    @Test fun emptyProductsKeepLastGoodData() { rejectsBeforeCommit(products(emptyList()), prices()) }
    @Test fun emptyPricesKeepLastGoodData() { rejectsBeforeCommit(products(), prices(records = emptyList())) }
    @Test fun disjointPayloadsDoNotPublishSuccessWithZeroPrices() { rejectsBeforeCommit(products(), prices(records = listOf(price.copy(productId = 11)))) }

    private fun rejectsBeforeCommit(products: ParsedDownload<CardmarketProduct>, prices: ParsedDownload<CardmarketPrice>) {
        var commits = 0
        val lastGood = listOf(99L, 1200L)
        var stored = lastGood
        assertThrows(IllegalArgumentException::class.java) {
            val prepared = prepareCardmarketImport(products, prices)
            commits++; stored = prepared.prices.map { it.productId }
        }
        assertEquals(0, commits); assertSame(lastGood, stored)
    }

    @Test fun partialIntersectionRetainsMatchingPricesWithoutInventingOtherPrices() {
        val prepared = prepareCardmarketImport(products(), prices(records = listOf(price, price.copy(productId = 11))))
        assertEquals(listOf(price), prepared.prices)
        assertEquals(sourceTime, prepared.sourceCreatedAt)
    }

    @Test fun nullTrendRemainsNullAndDoesNotMakeAValidSourceInvalid() {
        assertNull(prepareCardmarketImport(products(), prices(records = listOf(price.copy(trendMinor = null)))).prices.single().trendMinor)
    }

    @Test fun contradictoryPriceRowsInOneGuideAreRejectedInEitherOrder() {
        val complete = price.copy(
            average1Minor = 1100, average7Minor = 1150, average30Minor = 1175,
            holoAverageMinor = 2100, holoLowMinor = 1900, holoTrendMinor = 2200,
        )
        // Every currently parsed value participates, including null versus present.
        val conflicts = listOf(
            complete.copy(categoryId = 52),
            complete.copy(averageMinor = 1201), complete.copy(lowMinor = 1001),
            complete.copy(trendMinor = 1501), complete.copy(average1Minor = 1101),
            complete.copy(average7Minor = 1151), complete.copy(average30Minor = 1176),
            complete.copy(holoAverageMinor = 2101), complete.copy(holoLowMinor = 1901),
            complete.copy(holoTrendMinor = 2201),
            complete.copy(averageMinor = null), complete.copy(lowMinor = null),
            complete.copy(trendMinor = null), complete.copy(average1Minor = null),
            complete.copy(average7Minor = null), complete.copy(average30Minor = null),
            complete.copy(holoAverageMinor = null), complete.copy(holoLowMinor = null),
            complete.copy(holoTrendMinor = null),
        )
        conflicts.forEach { other ->
            assertNotEquals(complete, other)
            listOf(listOf(complete, other), listOf(other, complete)).forEach { records ->
                rejectsBeforeCommit(products(), prices(records = records))
            }
        }
        // Full-file validation must not silently hide a conflict through the catalog join.
        val unknown = complete.copy(productId = 11)
        listOf(
            listOf(complete, unknown, unknown.copy(trendMinor = 1501)),
            listOf(complete, unknown.copy(trendMinor = 1501), unknown),
        ).forEach { records -> rejectsBeforeCommit(products(), prices(records = records)) }
        // Conflicts need not be adjacent, and an agreeing repeat is not an end marker.
        val conflicting = complete.copy(trendMinor = 1501)
        listOf(
            listOf(complete, unknown, conflicting),
            listOf(complete, unknown, complete.copy(), conflicting),
        ).forEach { records -> rejectsBeforeCommit(products(), prices(records = records)) }
    }

    @Test fun agreeingDuplicatePriceRowsKeepOrderAndExistingImportCounts() {
        val second = price.copy(productId = 11, trendMinor = null)
        val records = listOf(price, second, price.copy(), second.copy())
        val prepared = prepareCardmarketImport(
            products(listOf(product, product.copy(id = 11))), prices(records = records),
        )
        assertEquals(records, prepared.prices)
        assertEquals(sourceTime, prepared.sourceCreatedAt)
        assertEquals(Instant.parse("2026-09-22T00:48:57Z").toEpochMilli(), prepared.effectiveAtEpochMillis)
    }

    @Test fun differentFilesMayCorrectTheSameSourceTimestampWithoutStickyDuplicateState() {
        val corrected = price.copy(trendMinor = null, averageMinor = 1625, holoTrendMinor = 2500)
        val original = prepareCardmarketImport(products(), prices(records = listOf(price)))
        val next = prepareCardmarketImport(products(), prices(records = listOf(corrected)))
        assertEquals(listOf(price), original.prices)
        assertEquals(listOf(corrected), next.prices)
        assertEquals(original.sourceCreatedAt, next.sourceCreatedAt)
        assertEquals(original.effectiveAtEpochMillis, next.effectiveAtEpochMillis)
        assertEquals(original, prepareCardmarketImport(products(), prices(records = listOf(price))))
    }

    @Test fun malformedJsonNeverReachesCommitBoundary() {
        var commits = 0
        assertThrows(IOException::class.java) {
            val malformed = CardmarketParsers.prices(Buffer().writeUtf8("{\"priceGuides\":["))
            prepareCardmarketImport(products(), malformed); commits++
        }
        assertEquals(0, commits)
    }

    @Test fun downloadFilesAreRemovedAfterSuccess() = runBlocking {
        var files = emptyList<File>()
        withCardmarketDownloadFiles(temporary.root) { productFile, priceFile ->
            files = listOf(productFile, priceFile)
            assertTrue(files.all(File::exists))
        }
        assertTrue(files.none(File::exists))
    }

    @Test fun failedParallelDownloadRemovesBothTemporaryFilesAfterSiblingCompletes() = runBlocking {
        var files = emptyList<File>()
        var siblingCompleted = false
        assertThrows(IOException::class.java) { runBlocking {
            withCardmarketDownloadFiles(temporary.root) { productFile, priceFile ->
                files = listOf(productFile, priceFile)
                coroutineScope {
                    val sibling = async(start = CoroutineStart.UNDISPATCHED) { siblingCompleted = true }
                    val failing = async { throw IOException("fixture download failure") }
                    awaitAll(sibling, failing)
                }
            }
        } }
        assertTrue(siblingCompleted); assertTrue(files.none(File::exists))
    }

    @Test fun cancellationRemovesFilesAndIsNotConvertedIntoSuccess() = runBlocking {
        var files = emptyList<File>()
        assertThrows(CancellationException::class.java) { runBlocking {
            withCardmarketDownloadFiles(temporary.root) { a, b -> files = listOf(a, b); throw CancellationException("fixture") }
        } }
        assertTrue(files.none(File::exists))
    }

    @Test fun secondTemporaryFileCreationFailureAlsoRemovesFirstFile() = runBlocking {
        val first = temporary.newFile("first-download.json")
        var creates = 0
        assertThrows(IOException::class.java) { runBlocking {
            withCardmarketDownloadFiles(temporary.root, createFile = {
                if (++creates == 1) first else throw IOException("fixture disk failure")
            }) { _, _ -> fail("No download may start") }
        } }
        assertFalse(first.exists())
    }
}
