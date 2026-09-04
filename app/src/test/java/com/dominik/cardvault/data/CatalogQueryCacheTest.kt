package com.dominik.cardvault.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogQueryCacheTest {
    @Test(timeout = 5000) fun `identical concurrent queries share one load and then reuse it`() = runBlocking {
        val cache = CatalogQueryCache<String>()
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 100) { calls++; release.await(); listOf("normal", "holo") }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 100) { error("Duplicate loader must not run") }
        }
        assertEquals(1, calls)
        release.complete(Unit)
        assertEquals(listOf("normal", "holo"), first.await())
        assertEquals(first.await(), second.await())
        assertEquals(first.await(), cache.getOrLoad("Pikachu", 100) { error("Cache miss") })
    }

    @Test fun `query text and result limit remain separate cache dimensions`() = runBlocking {
        val cache = CatalogQueryCache<String>()
        assertEquals(listOf("short"), cache.getOrLoad("Pikachu", 20) { listOf("short") })
        assertEquals(listOf("long"), cache.getOrLoad("Pikachu", 500) { listOf("long") })
        assertEquals(listOf("other"), cache.getOrLoad("Raichu", 20) { listOf("other") })
        assertEquals(listOf("short"), cache.getOrLoad("Pikachu", 20) { error("Wrong key") })
    }

    @Test(timeout = 5000) fun `source invalidation prevents an old in-flight result reaching cache or waiters`() = runBlocking {
        val cache = CatalogQueryCache<String>()
        val releaseOld = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 100) { releaseOld.await(); listOf("old price") }
        }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 100) { error("Fresh value should already be cached") }
        }
        cache.invalidate()
        assertEquals(listOf("new price"), cache.getOrLoad("Pikachu", 100) { listOf("new price") })
        releaseOld.complete(Unit)
        assertEquals(listOf("new price"), first.await())
        assertEquals(listOf("new price"), waiting.await())
        assertEquals(listOf("new price"), cache.getOrLoad("Pikachu", 100) { error("Old load replaced new cache") })
    }

    @Test fun `failed loads never become empty cached results`() = runBlocking {
        val cache = CatalogQueryCache<String>()
        val failure = runCatching { cache.getOrLoad("Pikachu", 20) { error("Database unavailable") } }
        assertTrue(failure.isFailure)
        assertEquals(listOf("recovered"), cache.getOrLoad("Pikachu", 20) { listOf("recovered") })
    }

    @Test(timeout = 5000) fun `cancelling the initiating caller lets an active waiter retry`() = runBlocking {
        val cache = CatalogQueryCache<String>()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 20) { release.await(); listOf("cancelled") }
        }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 20) { listOf("retried") }
        }
        first.cancelAndJoin()
        assertEquals(listOf("retried"), waiting.await())
        assertEquals(listOf("retried"), cache.getOrLoad("Pikachu", 20) { error("Cancelled result was cached") })
    }

    @Test(timeout = 5000) fun `cancelling a waiter does not cancel the shared load`() = runBlocking {
        val cache = CatalogQueryCache<String>()
        val release = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 20) { release.await(); listOf("loaded") }
        }
        val waiting = async(start = CoroutineStart.UNDISPATCHED) {
            cache.getOrLoad("Pikachu", 20) { error("Duplicate load") }
        }
        waiting.cancelAndJoin()
        release.complete(Unit)
        assertEquals(listOf("loaded"), first.await())
        assertEquals(listOf("loaded"), cache.getOrLoad("Pikachu", 20) { error("Cache miss") })
    }

    @Test fun `empty result keys respect least-recently-used query budget`() = runBlocking {
        val cache = CatalogQueryCache<String>(maxQueries = 2)
        cache.getOrLoad("first", 20) { emptyList() }
        cache.getOrLoad("second", 20) { emptyList() }
        cache.getOrLoad("first", 20) { error("Missing retained key") }
        cache.getOrLoad("third", 20) { emptyList() }
        assertEquals(listOf("reloaded"), cache.getOrLoad("second", 20) { listOf("reloaded") })
    }

    @Test fun `total rows are bounded and oversized loads are not retained`() = runBlocking {
        val cache = CatalogQueryCache<String>(maxResults = 3)
        cache.getOrLoad("first", 20) { listOf("a", "b") }
        cache.getOrLoad("second", 20) { listOf("c", "d") }
        assertEquals(listOf("reloaded"), cache.getOrLoad("first", 20) { listOf("reloaded") })
        cache.getOrLoad("oversized", 20) { listOf("1", "2", "3", "4") }
        assertEquals(emptyList<String>(), cache.getOrLoad("oversized", 20) { emptyList() })
    }

    @Test fun `default budget retains the measured selected-set working set`() = runBlocking {
        val cache = CatalogQueryCache<Int>()
        repeat(145) { query -> cache.getOrLoad("name-$query", 500) { List(54) { it } } }
        repeat(145) { query ->
            assertEquals(54, cache.getOrLoad("name-$query", 500) { error("Selected set evicted its own queries") }.size)
        }
    }

    @Test fun `new successful import revision invalidates before revision-based UI refresh`() = runBlocking {
        val cache = CatalogQueryCache<String>()
        cache.observeRevision(1L)
        cache.getOrLoad("Pikachu", 20) { listOf("old") }
        cache.observeRevision(1L)
        assertEquals(listOf("old"), cache.getOrLoad("Pikachu", 20) { error("Same revision invalidated") })
        cache.observeRevision(2L)
        assertEquals(listOf("new"), cache.getOrLoad("Pikachu", 20) { listOf("new") })
    }
}
