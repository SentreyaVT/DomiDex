package com.dominik.cardvault.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Caches only unchanged raw query results; printing/price selection stays with each caller. */
internal class CatalogQueryCache<T>(
    private val maxQueries: Int = 256,
    // PRE's measured expansion verification touches 7,733 rows across 145 names.
    // A smaller result budget evicts its own earlier queries on every pass.
    private val maxResults: Int = 8192,
) {
    private data class Query(val lookup: String, val limit: Int)
    private class Pending<T>(val generation: Long) {
        // null asks waiting callers to retry after a source-table invalidation.
        val result = CompletableDeferred<List<T>?>()
    }

    private val lock = Any()
    private val values = LinkedHashMap<Query, List<T>>(16, .75f, true)
    private val pending = mutableMapOf<Query, Pending<T>>()
    private var resultCount = 0
    private var generation = 0L
    private var observedRevision = false
    private var catalogRevision: Long? = null

    init {
        require(maxQueries > 0 && maxResults > 0)
    }

    fun invalidate() = synchronized(lock) { invalidateLocked() }

    /** Also clears before the UI's successful-import revision can trigger a new lookup. */
    fun observeRevision(revision: Long?) = synchronized(lock) {
        if (!observedRevision || catalogRevision != revision) {
            observedRevision = true
            catalogRevision = revision
            invalidateLocked()
        }
    }

    private fun invalidateLocked() {
        generation++
        values.clear()
        pending.clear()
        resultCount = 0
    }

    suspend fun getOrLoad(lookup: String, limit: Int, load: suspend () -> List<T>): List<T> {
        val key = Query(lookup, limit)
        while (true) {
            currentCoroutineContext().ensureActive()
            var ownsLoad = false
            val request = synchronized(lock) {
                values[key]?.let { return it }
                pending[key] ?: Pending<T>(generation).also {
                    pending[key] = it
                    ownsLoad = true
                }
            }
            if (!ownsLoad) {
                try {
                    request.result.await()?.let { return it }
                } catch (cancelled: CancellationException) {
                    // A cancelled initiating caller must not cancel an unrelated waiting caller.
                    currentCoroutineContext().ensureActive()
                }
                continue
            }

            try {
                val loaded = load()
                currentCoroutineContext().ensureActive()
                val accepted = synchronized(lock) {
                    if (pending[key] === request) pending.remove(key)
                    if (request.generation != generation) false else {
                        if (loaded.size <= maxResults) {
                            values[key] = loaded
                            resultCount += loaded.size
                            val oldest = values.entries.iterator()
                            while (values.size > maxQueries || resultCount > maxResults) {
                                resultCount -= oldest.next().value.size
                                oldest.remove()
                            }
                        }
                        true
                    }
                }
                request.result.complete(loaded.takeIf { accepted })
                if (accepted) return loaded
            } catch (error: Throwable) {
                synchronized(lock) { if (pending[key] === request) pending.remove(key) }
                request.result.completeExceptionally(error)
                throw error
            }
        }
    }
}
