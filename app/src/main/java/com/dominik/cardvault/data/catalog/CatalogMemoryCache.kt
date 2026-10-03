package com.dominik.cardvault.data.catalog

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Small process-memory LRU. Locks exist only while callers are loading/waiting for a key. */
internal class CatalogMemoryCache<K : Any, V : Any>(
    private val maxEntries: Int,
    private val ttlMillis: Long,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private data class Entry<V>(val value: V, val loadedAt: Long)
    private class LoadLock(val mutex: Mutex = Mutex(), var users: Int = 0)

    private val monitor = Any()
    private val entries = LinkedHashMap<K, Entry<V>>(16, .75f, true)
    private val loadLocks = mutableMapOf<K, LoadLock>()
    private var generation = 0L

    init {
        require(maxEntries > 0 && ttlMillis > 0)
    }

    private fun cached(key: K): V? = synchronized(monitor) {
        val entry = entries[key] ?: return@synchronized null
        if (nowMillis() - entry.loadedAt >= ttlMillis) {
            entries.remove(key)
            null
        } else entry.value
    }

    suspend fun getOrLoad(key: K, loader: suspend () -> V): V {
        currentCoroutineContext().ensureActive()
        cached(key)?.let { return it }
        val loading = synchronized(monitor) {
            loadLocks.getOrPut(key) { LoadLock() }.also { it.users++ }
        }
        try {
            return loading.mutex.withLock {
                cached(key)?.let { return@withLock it }
                val loadGeneration = synchronized(monitor) { generation }
                val value = loader()
                currentCoroutineContext().ensureActive()
                synchronized(monitor) {
                    // Refresh must not resurrect a response started before invalidation.
                    if (generation == loadGeneration) {
                        entries[key] = Entry(value, nowMillis())
                        while (entries.size > maxEntries) entries.entries.iterator().run {
                            next()
                            remove()
                        }
                    }
                }
                value
            }
        } finally {
            synchronized(monitor) {
                if (--loading.users == 0) loadLocks.remove(key)
            }
        }
    }

    fun invalidate() = synchronized(monitor) {
        generation++
        entries.clear()
    }
}

internal data class CatalogSetCards(
    val cards: List<PokemonVisualCard>,
    val byCollectorNumber: Map<String, List<PokemonVisualCard>>,
)

internal data class CatalogCardDetail(
    val rarity: String?,
    val printVariants: List<PokemonPrintVariant>,
    val image: String? = null,
    /** Explicit printing conflicts must survive cache reuse and registry attachment. */
    val blockedCardmarketVariantIds: Set<String> = emptySet(),
    val fromEnglishSource: Boolean = false,
)

/** Metadata only: no bitmaps, prices, or collection state. */
internal class PokemonCatalogMetadataCache {
    private val ttlMillis = 15 * 60 * 1_000L
    val sets = CatalogMemoryCache<String, List<PokemonSet>>(1, ttlMillis)
    val cards = CatalogMemoryCache<String, CatalogSetCards>(24, ttlMillis)
    val searches = CatalogMemoryCache<Pair<String, Int>, PokemonCardSearchPage>(24, ttlMillis)
    val searchPocketSets = CatalogMemoryCache<String, Set<String>>(1, ttlMillis)
    val details = CatalogMemoryCache<String, CatalogCardDetail>(512, ttlMillis)
    val detailArtwork = CatalogMemoryCache<String, PokemonCardArtworkCandidate>(512, ttlMillis)

    fun invalidate() {
        sets.invalidate()
        cards.invalidate()
        searches.invalidate()
        searchPocketSets.invalidate()
        details.invalidate()
        detailArtwork.invalidate()
    }
}
