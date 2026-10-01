package com.dominik.cardvault.data.sharing

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FirebaseBinderSharingGatewayTest {
    @Test fun persistedOwnerMismatchCannotListAnotherAccountsSameBinderId() = runBlocking {
        val session = FakeSession("owner-b")
        var requests = 0
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ -> requests++; emptyListResponse() })
        failure { gateway.list("owner-a", "same-binder-id") }
        assertEquals(0, session.tokens)
        assertEquals(0, requests)
    }

    @Test fun completeRequestUsesOnlyExistingHttpsApiAndCurrentToken() = runBlocking {
        val session = FakeSession("owner-a")
        var requests = 0
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { method, url, token, body ->
            requests++
            assertEquals("GET", method)
            assertEquals("https://domidex.web.app/api/binder-shares?binderId=binder-1", url)
            assertEquals("synthetic-test-token", token)
            assertNull(body)
            emptyListResponse()
        })
        assertTrue(gateway.list("owner-a", "binder-1").isEmpty())
        assertEquals(1, requests)
        assertEquals(1, session.removals)
    }

    @Test fun failedCreateIsOneAttemptWithNoAutomaticRetry() = runBlocking {
        var requests = 0
        val session = FakeSession("owner-a")
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { method, _, _, body ->
            requests++
            assertEquals("POST", method)
            assertTrue(body.orEmpty().contains("\"confirm\":true"))
            throw IllegalStateException("sensitive-server-detail")
        })
        val error = failure { gateway.create("owner-a", "binder", BinderShareOptions(), null, true) }
        assertEquals(1, requests)
        assertFalse(error.message.orEmpty().contains("sensitive"))
        assertTrue(error.message.orEmpty().contains("aktualisieren"))
        assertEquals(1, session.removals)
    }

    @Test fun unconfirmedCreateNeverObtainsTokenOrSendsRequest() = runBlocking {
        val session = FakeSession("owner-a")
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ -> fail("Must not request"); emptyListResponse() })
        failure { gateway.create("owner-a", "binder", BinderShareOptions(), null, false) }
        assertEquals(0, session.tokens)
    }

    @Test fun revokeIsOneDeleteWithExplicitServerConfirmation() = runBlocking {
        var requests = 0
        val session = FakeSession("owner-a")
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { method, url, _, body ->
            requests++
            assertEquals("DELETE", method)
            assertEquals(BinderSharingContract.ENDPOINT, url)
            assertTrue(body.orEmpty().contains("\"confirm\":true"))
            BinderSharingResponse(200, "{\"ok\":true}")
        })
        gateway.revoke("owner-a", "a".repeat(64))
        assertEquals(1, requests)
    }

    @Test(timeout = 5_000) fun accountChangeDuringTokenAcquisitionCancelsWithoutHttp() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val session = FakeSession("owner-a").apply { tokenAction = { started.complete(Unit); awaitCancellation() } }
        var requests = 0
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ -> requests++; emptyListResponse() })
        val pending = async { failure { gateway.list("owner-a", "binder") } }
        started.await()
        session.change("owner-b")
        assertTrue(pending.await().message.orEmpty().contains("Konto"))
        assertEquals(0, requests)
        assertEquals(1, session.removals)
    }

    @Test(timeout = 5_000) fun accountAwayAndBackCannotDeliverOldRequestEvenWhenTransportReturns() = runBlocking {
        val session = FakeSession("owner-a")
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ ->
            session.change("owner-b"); session.change("owner-a")
            emptyListResponse()
        })
        assertTrue(failure { gateway.list("owner-a", "binder") }.message.orEmpty().contains("Konto"))
        assertEquals(1, session.removals)
    }

    @Test fun ownerIsRecheckedAfterSuspensionEvenWithoutNotification() = runBlocking {
        val session = FakeSession("owner-a")
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ -> session.uid = "owner-b"; emptyListResponse() })
        failure { gateway.list("owner-a", "binder") }
        assertEquals(1, session.removals)
    }

    @Test(timeout = 5_000) fun singleDeadlineCoversTokenAndBodyWaits() = runBlocking {
        for (waitInToken in listOf(true, false)) {
            val session = FakeSession("owner-a")
            if (waitInToken) session.tokenAction = { awaitCancellation() }
            var transportCancelled = false
            val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ ->
                try { awaitCancellation() } finally { transportCancelled = true }
            }, timeoutMillis = 50)
            failure { gateway.list("owner-a", "binder") }
            assertEquals(!waitInToken, transportCancelled)
            assertEquals(1, session.removals)
        }
    }

    @Test(timeout = 5_000) fun callerCancellationPropagatesAndClosesAccountObserver() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val session = FakeSession("owner-a")
        var cancelled = false
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        })
        val pending = launch {
            try { gateway.list("owner-a", "binder"); fail("Must cancel") }
            catch (expected: CancellationException) { throw expected }
        }
        started.await(); pending.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(1, session.removals)
    }

    @Test(timeout = 5_000) fun existingShareListDoesNotWaitForBlockedReadiness() = runBlocking {
        var preparations = 0
        var requests = 0
        val gateway = FirebaseBinderSharingGateway(FakeSession("owner-a"), BinderSharingTransport { _, _, _, _ ->
            requests++
            emptyListResponse()
        }, readiness = BinderSharingReadiness { _, _ -> preparations++; awaitCancellation() })
        assertTrue(gateway.list("owner-a", "selected").isEmpty())
        assertEquals(1, requests)
        assertEquals(0, preparations)
    }

    @Test(timeout = 5_000) fun missingRemoteBinderWaitsForSelectedBinderAndRetriesGetOnce() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val ready = CompletableDeferred<Unit>()
        val session = FakeSession("owner-a")
        var requests = 0
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ ->
            requests++
            if (requests == 1) BinderSharingResponse(404, "") else emptyListResponse()
        }, readiness = BinderSharingReadiness { owner, binder ->
            assertEquals("owner-a", owner)
            assertEquals("selected", binder)
            started.complete(Unit)
            ready.await()
        })
        val pending = async { gateway.list("owner-a", "selected") }
        started.await()
        assertEquals(1, requests)
        assertEquals(1, session.tokens)
        ready.complete(Unit)
        assertTrue(pending.await().isEmpty())
        assertEquals(2, requests)
        assertEquals(1, session.removals)
    }

    @Test fun repeatedMissingRemoteBinderDoesNotLoopOrCreate() = runBlocking {
        var requests = 0
        var preparations = 0
        val gateway = FirebaseBinderSharingGateway(FakeSession("owner-a"), BinderSharingTransport { method, _, _, _ ->
            assertEquals("GET", method)
            requests++
            BinderSharingResponse(404, "")
        }, readiness = BinderSharingReadiness { _, _ -> preparations++ })
        failure { gateway.list("owner-a", "selected") }
        assertEquals(2, requests)
        assertEquals(1, preparations)
    }

    @Test fun nonMissingHttpFailuresNeverRequestSyncOrRetry() = runBlocking {
        for (status in listOf(401, 403, 429, 500, 503)) {
            var requests = 0
            val gateway = FirebaseBinderSharingGateway(FakeSession("owner-a"), BinderSharingTransport { _, _, _, _ ->
                requests++
                BinderSharingResponse(status, "")
            }, readiness = BinderSharingReadiness { _, _ -> fail("Unexpected sync") })
            failure { gateway.list("owner-a", "selected") }
            assertEquals(1, requests)
        }
    }

    @Test fun pendingSyncNeverCreatesAPublicLink() = runBlocking {
        var requests = 0
        val session = FakeSession("owner-a")
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ ->
            requests++
            emptyListResponse()
        }, readiness = BinderSharingReadiness { _, _ -> throw BinderSharingSyncException("Bitte synchronisieren.") })
        val error = failure { gateway.create("owner-a", "selected", BinderShareOptions(), null, true) }
        assertTrue(error is BinderSharingSyncException)
        assertEquals(0, requests)
        assertEquals(0, session.tokens)
        assertEquals(1, session.removals)
    }

    @Test fun pendingSyncStillRetrievesExistingSharesForRevocation() = runBlocking {
        val shareId = "a".repeat(64)
        val methods = mutableListOf<String>()
        var preparations = 0
        val session = FakeSession("owner-a")
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { method, _, _, _ ->
            methods += method
            if (method == "DELETE") BinderSharingResponse(200, "{\"ok\":true}")
            else BinderSharingResponse(200, """{"shares":[{"shareId":"$shareId","createdAt":1,"options":{"showPrices":false,"showTotal":false,"showDisplayName":false}}]}""")
        }, readiness = BinderSharingReadiness { _, _ ->
            preparations++
            throw BinderSharingSyncException("Bitte synchronisieren.")
        })
        assertEquals(shareId, gateway.list("owner-a", "selected").single().shareId)
        gateway.revoke("owner-a", shareId)
        assertEquals(listOf("GET", "DELETE"), methods)
        assertEquals(0, preparations)
    }

    @Test(timeout = 5_000) fun accountChangeDuringSyncWaitCannotFallBackToHttp() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val session = FakeSession("owner-a")
        var requests = 0
        var cancelled = false
        val gateway = FirebaseBinderSharingGateway(session, BinderSharingTransport { _, _, _, _ ->
            requests++
            BinderSharingResponse(404, "")
        }, readiness = BinderSharingReadiness { _, _ ->
            started.complete(Unit)
            try { awaitCancellation() } finally { cancelled = true }
        })
        val pending = async { failure { gateway.list("owner-a", "selected") } }
        started.await()
        session.change("owner-b")
        session.change("owner-a")
        assertTrue(pending.await().message.orEmpty().contains("Konto"))
        assertTrue(cancelled)
        assertEquals(1, requests)
        assertEquals(1, session.tokens)
        assertEquals(1, session.removals)
    }

    private suspend fun failure(block: suspend () -> Unit): BinderSharingException {
        try { block() } catch (failure: BinderSharingException) { return failure }
        throw AssertionError("Expected safe BinderSharingException")
    }
    private fun emptyListResponse() = BinderSharingResponse(200, "{\"shares\":[]}")
    private class FakeSession(var uid: String?) : BinderSharingSession {
        override val currentUid get() = uid
        var tokens = 0
        var removals = 0
        var tokenAction: suspend () -> String = { "synthetic-test-token" }
        private var listener: ((String?) -> Unit)? = null
        override suspend fun token(expectedUid: String): String { tokens++; return tokenAction() }
        override fun observeAccount(changed: (String?) -> Unit): () -> Unit {
            listener = changed
            return { removals++; listener = null }
        }
        fun change(value: String?) { uid = value; listener?.invoke(value) }
    }
}
