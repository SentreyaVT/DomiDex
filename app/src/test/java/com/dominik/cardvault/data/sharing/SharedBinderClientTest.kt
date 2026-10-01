package com.dominik.cardvault.data.sharing

import java.io.ByteArrayInputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SharedBinderClientTest {
    private val token = "A".repeat(43)

    @Test fun publicDtoHasOnlySharedFieldsAndPreservesAbsentPrices() {
        val page = SharedBinderContract.parse(fixture().replace("\"title\":", "\"ownerUid\":\"hidden-owner\",\"notes\":\"private-note\",\"email\":\"hidden@example.test\",\"title\":").toByteArray(), 0)
        assertEquals("Fixture", page.title)
        assertEquals("rainbow", page.cards.single().holoEffectId)
        assertEquals(.5f, page.cards.single().holoIntensity)
        assertNull(page.cards.single().price)
        assertNull(page.total)
        assertNull(page.displayName)
        assertFalse(page.toString().contains("private-note"))
        assertFalse(page.toString().contains("hidden-owner"))
        assertFalse(page.toString().contains("hidden@example.test"))
    }

    @Test fun optionalPublishedValuesAreParsedWithoutInventingMissingPrices() {
        val json = fixture().replace("\"holoIntensity\":0.5", "\"holoIntensity\":0.5,\"price\":{\"amount\":null,\"currency\":\"EUR\",\"source\":\"Cardmarket\",\"asOf\":null}")
            .replace("\"title\":", "\"displayName\":\"Sammler\",\"total\":{\"amount\":null,\"currency\":\"EUR\",\"complete\":false,\"pricedCards\":0,\"visibleCards\":1},\"title\":")
        val page = SharedBinderContract.parse(json.toByteArray(), 0)
        assertEquals("Sammler", page.displayName)
        assertNotNull(page.cards.single().price)
        assertNull(page.cards.single().price!!.amountEur)
        assertFalse(page.total!!.complete)
        assertNull(page.total!!.amountEur)
    }

    // Synthetic public wire DTOs only: these are not an Android/Web end-to-end test.
    @Test fun optionalSelectionBasisRemainsBackwardCompatibleWithoutChangingPublishedPrice() {
        val original = pricedFixture("12.34")
        val selection = original.replace("\"source\":\"Cardmarket\"", "\"source\":\"Cardmarket\",\"basis\":\"selection\"")
        val expected = SharedBinderContract.parse(original.toByteArray(), 0)
        val actual = SharedBinderContract.parse(selection.toByteArray(), 0)
        assertEquals(expected, actual)
        assertEquals(12.34, actual.cards.single().price!!.amountEur!!, 0.0)
        assertEquals("2026-10-01T00:00:00.000Z", actual.cards.single().price!!.asOf)
        assertNull(actual.total)
    }

    @Test fun publishedPriceAndTotalAcceptTheExactAmountCeiling() {
        val page = SharedBinderContract.parse(pricedFixture("1000000000", """{"amount":1000000000,"currency":"EUR","complete":true,"pricedCards":1,"visibleCards":1}""").toByteArray(), 0)
        assertEquals(1_000_000_000.0, page.cards.single().price!!.amountEur!!, 0.0)
        assertEquals(1_000_000_000.0, page.total!!.amountEur!!, 0.0)
        assertTrue(page.total!!.complete)
        assertEquals(1, page.total!!.pricedCards)
        assertEquals(1, page.total!!.visibleCards)
    }

    @Test fun publishedPriceAboveTheAmountCeilingRejectsThePageInsteadOfClamping() {
        listOf("1000000000.01", "90071992547409.91").forEach { amount ->
            failure { SharedBinderContract.parse(pricedFixture(amount).toByteArray(), 0) }
        }
    }

    @Test fun publishedTotalAboveTheAmountCeilingRejectsThePageDespiteAValidCardPrice() {
        listOf("1000000000.01", "90071992547409.91").forEach { amount ->
            val total = """{"amount":$amount,"currency":"EUR","complete":true,"pricedCards":1,"visibleCards":1}"""
            failure { SharedBinderContract.parse(pricedFixture("12.34", total).toByteArray(), 0) }
        }
    }

    @Test fun omittedOverflowingTotalKeepsReadableCardPricesWithoutInventingZeroOrASum() {
        val json = pricedFixture("600000000")
        val firstCard = json.substringAfter("\"cards\":[").substringBefore("],\"unavailableSlots\"")
        val secondCard = firstCard.replace("\"slot\":0", "\"slot\":1").replace("\"cardId\":\"base1-1\"", "\"cardId\":\"base1-2\"")
        val page = SharedBinderContract.parse(json.replace(firstCard, "$firstCard,$secondCard").toByteArray(), 0)
        assertEquals(listOf(0, 1), page.cards.map { it.slot })
        assertEquals(listOf(600_000_000.0, 600_000_000.0), page.cards.map { it.price!!.amountEur })
        assertNull("The server omitted total; the client must not replace it with zero or 1.2 billion EUR", page.total)
    }

    @Test fun inconsistentPublishedTotalCompletenessOrCountsRemainRejected() {
        listOf(
            """{"amount":12.34,"currency":"EUR","complete":false,"pricedCards":1,"visibleCards":1}""",
            """{"amount":null,"currency":"EUR","complete":true,"pricedCards":1,"visibleCards":1}""",
            """{"amount":12.34,"currency":"EUR","complete":true,"pricedCards":0,"visibleCards":1}""",
            """{"amount":12.34,"currency":"EUR","complete":false,"pricedCards":0,"visibleCards":1}""",
            """{"amount":null,"currency":"EUR","complete":false,"pricedCards":2,"visibleCards":1}""",
        ).forEach { total ->
            failure { SharedBinderContract.parse(pricedFixture("12.34", total).toByteArray(), 0) }
        }
    }

    @Test fun malformedPagesCannotMixSlotsOrDisplayDuplicateCards() {
        listOf(
            fixture().replace("\"page\":0", "\"page\":1"),
            fixture().replace("\"slot\":0", "\"slot\":9"),
            fixture().replace("\"pageCount\":2", "\"pageCount\":0"),
            fixture().replace("\"unavailableSlots\":[]", "\"unavailableSlots\":[0]"),
            fixture().replace("\"holoIntensity\":0.5", "\"holoIntensity\":1.1"),
            fixture().replace("\"cardId\":\"base1-1\"", "\"cardId\":\"../private\""),
        ).forEach { json -> failure { SharedBinderContract.parse(json.toByteArray(), 0) } }
    }

    @Test fun imageAllowlistRejectsRedirectTargetsCredentialsQueriesAndTraversal() {
        val valid = "https://assets.tcgdex.net/de/base/base1/1/high.webp"
        assertEquals(valid, SharedBinderContract.imageUrl(valid))
        listOf(
            "http://assets.tcgdex.net/de/base/1.webp", "https://other.test/1.webp",
            "https://assets.tcgdex.net.evil.test/1.webp", "https://user@assets.tcgdex.net/1.webp",
            "https://assets.tcgdex.net:443/1.webp", "https://assets.tcgdex.net/1.webp?token=secret",
            "https://assets.tcgdex.net/1.webp#secret", "https://assets.tcgdex.net/../1.webp",
            "https://assets.tcgdex.net/%2e%2e/1.webp", "https://assets.tcgdex.net/1.svg",
        ).forEach { assertNull(SharedBinderContract.imageUrl(it)) }
        val page = SharedBinderContract.parse(fixture().replace("\"image\":null", "\"image\":\"https://other.test/private.webp\"").toByteArray(), 0)
        assertNull(page.cards.single().imageUrl)
    }

    @Test fun responseBodyIsBoundedEvenWithoutContentLength() {
        failure { SharedBinderContract.readBounded(ByteArrayInputStream(ByteArray(9)), -1, 8) }
        failure { SharedBinderContract.readBounded(ByteArrayInputStream(byteArrayOf()), 9, 8) }
        assertEquals(8, SharedBinderContract.readBounded(ByteArrayInputStream(ByteArray(8)), -1, 8).size)
    }

    @Test fun onlyCanonicalTokensAndBoundedPageNumbersFormAnApiUrl() {
        assertEquals("https://domidex.web.app/api/shared-binders/$token?page=1", SharedBinderContract.url(token, 1))
        listOf("bad", "A".repeat(42) + "B", "$token?admin=1").forEach { failure { SharedBinderContract.url(it, 0) } }
        failure { SharedBinderContract.url(token, -1) }
        failure { SharedBinderContract.url(token, 100001) }
    }

    @Test fun clientOnlyFetchesPublicPageAndNeverFollowsAResponseRedirect() = runBlocking {
        var requests = 0
        val client = PublicSharedBinderClient(SharedBinderTransport { url, limit, image ->
            requests++
            assertEquals(SharedBinderContract.url(token, 0), url)
            assertEquals(SharedBinderContract.MAX_PAGE_BYTES, limit)
            assertFalse(image)
            SharedBinderResponse(302, byteArrayOf())
        })
        suspendFailure { client.load(token, 0) }
        assertEquals(1, requests)
        suspendFailure { client.image("https://other.test/a.webp") }
        suspendFailure { client.load("invalid", 0) }
        assertEquals(1, requests)
    }

    @Test(timeout = 5_000) fun requestDeadlineAndCallerCancellationStopTheUnderlyingRequest() = runBlocking {
        var cancelled = 0
        val secondStarted = CompletableDeferred<Unit>()
        val transport = SharedBinderTransport { _, _, _ ->
            if (cancelled == 1) secondStarted.complete(Unit)
            try { awaitCancellation() } finally { cancelled++ }
        }
        suspendFailure { PublicSharedBinderClient(transport, timeoutMillis = 30).load(token, 0) }
        assertEquals(1, cancelled)
        val pending = launch { PublicSharedBinderClient(transport).load(token, 0) }
        secondStarted.await()
        pending.cancelAndJoin()
        assertEquals(2, cancelled)
    }

    @Test(timeout = 5_000) fun visibleRecheckClearsAllContentWhenShareIsRevoked() = runBlocking {
        var loads = 0
        val gateway = object : SharedBinderGateway {
            override suspend fun load(token: String, page: Int): SharedBinderPage {
                if (loads++ > 0) throw SharedBinderContract.unavailable()
                return SharedBinderContract.parse(fixture().toByteArray(), page)
            }
            override suspend fun image(url: String) = byteArrayOf()
        }
        val states = observeSharedBinderPage(gateway, token, 0, pollMillis = 1).toList()
        assertEquals(3, states.size)
        assertTrue(states[0].loading)
        assertNotNull(states[1].binder)
        assertNull(states[2].binder)
        assertTrue(states[2].error.orEmpty().contains("beendet"))
        assertEquals(2, loads)
    }

    @Test(timeout = 5_000) fun leavingTheViewerCancelsItsPendingPageRequest() = runBlocking {
        val started = CompletableDeferred<Unit>()
        var cancelled = false
        val gateway = object : SharedBinderGateway {
            override suspend fun load(token: String, page: Int): SharedBinderPage {
                started.complete(Unit)
                try { awaitCancellation() } finally { cancelled = true }
            }
            override suspend fun image(url: String) = byteArrayOf()
        }
        val pending = launch { observeSharedBinderPage(gateway, token, 0, pollMillis = 1).take(2).toList() }
        started.await()
        pending.cancelAndJoin()
        assertTrue(cancelled)
    }

    private fun fixture() = """{"title":"Fixture","coverStyle":"galaxy","page":0,"pageCount":2,"cards":[{"slot":0,"cardId":"base1-1","name":"Pikachu","setName":"Basis","collectorNumber":"1","image":null,"finish":"Holo","holoEffectId":"rainbow","holoIntensity":0.5}],"unavailableSlots":[]}"""
    private fun pricedFixture(amount: String, total: String? = null): String {
        val priced = fixture().replace("\"holoIntensity\":0.5", """"holoIntensity":0.5,"price":{"amount":$amount,"currency":"EUR","source":"Cardmarket","asOf":"2026-10-01T00:00:00.000Z"}""")
        return if (total == null) priced else priced.replace("\"title\":", "\"total\":$total,\"title\":")
    }
    private fun failure(block: () -> Unit): SharedBinderReadException {
        try { block() } catch (failure: SharedBinderReadException) { return failure }
        throw AssertionError("Expected safe failure")
    }
    private suspend fun suspendFailure(block: suspend () -> Unit): SharedBinderReadException {
        try { block() } catch (failure: SharedBinderReadException) { return failure }
        throw AssertionError("Expected safe failure")
    }
}
