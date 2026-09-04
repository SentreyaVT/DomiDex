package com.dominik.cardvault.ui

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.catalog.PokemonVisualCatalog
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.model.CatalogSearchResult
import com.dominik.cardvault.model.CollectionRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Read-only measurements against the emulator's real catalogue, never seeded prices/user data. */
@RunWith(AndroidJUnit4::class)
class CardDataPerformanceInstrumentedTest {
    private val phase get() = InstrumentationRegistry.getArguments().getString("performancePhase") ?: "UNLABELLED"
    private suspend fun <T> measure(label: String, block: suspend () -> T): T {
        val start = SystemClock.elapsedRealtimeNanos()
        return block().also {
            Log.i("DomiDexPerf", "$phase|$label|ms=${(SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0}")
        }
    }

    @Test fun realCatalogueLookupAndCorrection() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = RoomCollectionRepository(CardVaultDatabase.get(context))
        var searchCalls = 0
        var searchResults = 0
        val counted = object : CollectionRepository by repository {
            override suspend fun searchCatalog(query: String, limit: Int): List<CatalogSearchResult> {
                searchCalls++
                return repository.searchCatalog(query, limit).also { searchResults += it.size }
            }
        }
        val catalog = PokemonVisualCatalog()
        val sets = measure("set_index_cold") { catalog.sets() }
        val set = sets.first { it.id == "sv08.5" }
        val first = measure("set_number_cold") { catalog.findExact(set.id, "068/131") }
        assertTrue(first.isNotEmpty())
        repeat(5) { measure("set_number_warm_$it") { assertTrue(catalog.findExact(set.id, "068/131").isNotEmpty()) } }
        measure("cross_screen_set_number") { assertTrue(PokemonVisualCatalog().findExact(set.id, "068/131").isNotEmpty()) }

        val analysis = withManualScanIdentity(null, set.id, "PRE", "068/131")
        repeat(2) { iteration ->
            searchCalls = 0
            searchResults = 0
            val resolution = measure("manual_correction_$iteration") { CardScanCandidateResolver.resolve(analysis, counted, catalog) }
            assertTrue(resolution.candidates.isNotEmpty())
            Log.i("DomiDexPerf", "$phase|manual_correction_$iteration|searchCalls=$searchCalls|resultRows=$searchResults|candidates=${resolution.candidates.size}|decision=${resolution.decision.decision}")
        }
        repeat(3) {
            val results = measure("name_search_$it") { repository.searchCatalog("Pikachu", 20) }
            Log.i("DomiDexPerf", "$phase|name_search_$it|results=${results.size}")
        }
        repeat(2) { iteration ->
            val readerCatalog = PokemonVisualCatalog()
            measure("binder_metadata_$iteration") {
                val readerSet = readerCatalog.sets().first { it.id == set.id }
                val cards = readerCatalog.cards(readerSet)
                assertTrue(cards.isNotEmpty())
                measure("binder_first_9_variants_$iteration") {
                    cards.take(9).forEach { readerCatalog.withDeclaredPrintVariants(it) }
                }
            }
            measure("binder_reference_count_$iteration") { repository.binderReferenceCount(set.name) }
        }
        Log.i("DomiDexPerf", "$phase|memory_before_repeat|pssKb=${Debug.getPss()}")
        measure("100_repeated_lookups") { repeat(100) { catalog.findExact(set.id, "068/131") } }
        Log.i("DomiDexPerf", "$phase|memory_after_repeat|pssKb=${Debug.getPss()}")
        Unit
    }
}
