package com.dominik.cardvault.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CachedAppCheckHeaderTest {
    private var now = 1_000_000L
    private val cache = CachedAppCheckHeader { now }
    private val testToken = "synthetic.payload.signature"
    private val share = "https://domidex.web.app/api/binder-shares"

    private fun seed() = cache.update(testToken, now + 3_600_000L)

    @Test fun disabledStartupDoesNotInvokeProvider() {
        startAppCheckIfEnabled(false) { error("Provider must remain untouched") }
    }

    @Test fun enabledStartupInstallsOncePerInvocation() {
        var count = 0
        startAppCheckIfEnabled(true) { count++ }
        assertEquals(1, count)
    }

    @Test fun emptyCacheIsAnImmediateMiss() {
        repeat(10_000) { assertNull(cache.forRequest("POST", share)) }
    }

    @Test fun validTokenAllowsOnlyPrivateSharingMethodsIncludingQuery() {
        seed()
        for (method in listOf("GET", "POST", "DELETE")) {
            assertEquals(testToken, cache.forRequest(method, share))
        }
        assertEquals(testToken, cache.forRequest("GET", "$share?binderId=synthetic-binder"))
        assertNull(cache.forRequest("PATCH", share))
        assertNull(cache.forRequest("PUT", share))
    }

    @Test fun reportAndKnowledgeAllowPostOnly() {
        seed()
        for (path in listOf("pretester/scan-reports", "scanner/reviewed-knowledge")) {
            val url = "https://domidex.web.app/api/$path"
            assertEquals(testToken, cache.forRequest("POST", url))
            for (method in listOf("GET", "DELETE", "PUT", "PATCH")) assertNull(cache.forRequest(method, url))
        }
    }

    @Test fun refusesWrongOriginPortsPublicAndExternalRoutes() {
        seed()
        for (url in listOf(
            "http://domidex.web.app/api/binder-shares",
            "https://domidex.web.app:444/api/binder-shares",
            "https://domidex.web.app.evil.example/api/binder-shares",
            "https://evil@domidex.web.app/api/binder-shares",
            "https://domidex.web.app@evil.example/api/binder-shares",
            "https://domidex.web.app/share/synthetic",
            "https://domidex.web.app/api/catalog",
            "https://www.cardmarket.com/api/binder-shares",
            "https://api.tcgdex.net/api/binder-shares",
            "$share/extra", "$share#fragment", "not a URL",
            "https://domidex.web.app/api/%62inder-shares",
        )) assertNull("No credential to $url", cache.forRequest("POST", url))
    }

    @Test fun explicitHttpsDefaultPortIsAllowed() {
        seed()
        assertEquals(testToken, cache.forRequest("POST", "https://domidex.web.app:443/api/binder-shares"))
    }

    @Test fun blankMalformedInjectedAndOversizedTokensClearCache() {
        for (invalid in listOf("", " ", "no-jwt", "x.y.z\r\nInjected: yes", "ä.y.z", "x".repeat(4_093) + ".y.z")) {
            seed()
            cache.update(invalid, now + 3_600_000L)
            assertNull(cache.forRequest("POST", share))
        }
    }

    @Test fun tokenLengthBoundaryMatchesBackendContract() {
        val limitToken = "x".repeat(4_092) + ".y.z"
        cache.update(limitToken, now + 3_600_000)
        assertEquals(limitToken, cache.forRequest("POST", share))
        cache.update(limitToken + "x", now + 3_600_000)
        assertNull(cache.forRequest("POST", share))
    }

    @Test fun rejectsExpiredAndNearExpiryAtInsertion() {
        for (remaining in listOf(-1L, 0L, 59_999L, 60_000L)) {
            seed()
            cache.update(testToken, now + remaining)
            assertNull(cache.forRequest("POST", share))
        }
    }

    @Test fun ceasesUseAtSafetyBoundaryWithoutRefresh() {
        cache.update(testToken, now + 60_001)
        assertEquals(testToken, cache.forRequest("POST", share))
        now++
        assertNull(cache.forRequest("POST", share))
        now += 60_000
        assertNull(cache.forRequest("POST", share))
    }

    @Test fun backwardClockChangeDoesNotExtendLifetime() {
        seed()
        now--
        assertNull(cache.forRequest("POST", share))
    }

    @Test fun forwardClockJumpExpiresToken() {
        seed()
        now += 4_000_000
        assertNull(cache.forRequest("POST", share))
    }

    @Test fun explicitClearAndNewInstanceContainNoPersistedToken() {
        seed()
        assertNull(CachedAppCheckHeader { now }.forRequest("POST", share))
        cache.clear()
        assertNull(cache.forRequest("POST", share))
    }

    @Test fun tokenRefreshReplacesSnapshotAndNegativeClockIsRejected() {
        seed()
        cache.update("synthetic.new.signature", now + 3_600_000)
        assertEquals("synthetic.new.signature", cache.forRequest("POST", share))
        now = -1
        cache.update(testToken, 3_600_000)
        assertNull(cache.forRequest("POST", share))
    }
}
