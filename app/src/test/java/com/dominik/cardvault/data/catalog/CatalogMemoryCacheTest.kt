package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogMemoryCacheTest {
    @Test fun concurrentMissesShareOneLoadAndDoNotBlockOtherKeys() = runBlocking {
        val cache = CatalogMemoryCache<String, String>(4, 1_000)
        val release = CompletableDeferred<Unit>()
        var loads = 0
        val readers = (1..8).map {
            async(start = CoroutineStart.UNDISPATCHED) {
                cache.getOrLoad("same") { loads++; release.await(); "value" }
            }
        }
        assertEquals("independent", cache.getOrLoad("other") { "independent" })
        release.complete(Unit)
        assertEquals(List(8) { "value" }, readers.awaitAll())
        assertEquals(1, loads)
    }

    @Test fun ttlExpiresFromLoadTimeAndCapacityEvictsLeastRecentlyUsed() = runBlocking {
        var time = 0L
        val cache = CatalogMemoryCache<String, String>(2, 100) { time }
        var loads = 0
        suspend fun get(key: String) = cache.getOrLoad(key) { loads++; "$key-$loads" }
        assertEquals("a-1", get("a"))
        assertEquals("b-2", get("b"))
        time = 99
        assertEquals("a-1", get("a"))
        assertEquals("c-3", get("c"))
        assertEquals("b-4", get("b"))
        time = 199
        assertEquals("b-5", get("b"))
    }

    @Test fun invalidationDoesNotLetAnEarlierLoadRepopulateTheCache() = runBlocking {
        val cache = CatalogMemoryCache<String, String>(2, 1_000)
        val release = CompletableDeferred<Unit>()
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("key") { release.await(); "old" }
        }
        cache.invalidate()
        release.complete(Unit)
        assertEquals("old", old.await())
        assertEquals("fresh", cache.getOrLoad("key") { "fresh" })
    }

    @Test fun cancelledLoaderReleasesItsWaiterWithoutCachingCancelledWork() = runBlocking {
        val cache = CatalogMemoryCache<String, String>(2, 1_000)
        val release = CompletableDeferred<Unit>()
        val owner = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("key") { release.await(); "cancelled" }
        }
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("key") { "replacement" }
        }
        owner.cancelAndJoin()
        assertEquals("replacement", waiter.await())
        assertEquals("replacement", cache.getOrLoad("key") { "unexpected" })
    }

    @Test fun failedLoadsCanRetry() = runBlocking {
        val cache = CatalogMemoryCache<String, String>(2, 1_000)
        try {
            cache.getOrLoad("key") { error("temporary source failure") }
        } catch (_: IllegalStateException) {
            // Failures must not become a successful empty metadata entry.
        }
        assertEquals("recovered", cache.getOrLoad("key") { "recovered" })
    }
}
