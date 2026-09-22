package com.dominik.cardvault.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import com.dominik.cardvault.model.GermanMasterSetCardDefinition
import com.dominik.cardvault.model.GermanMasterSetDefinition
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual reference cache; no installed collection, preferences, network or Firebase writes. */
class BinderReferenceCountsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var database: CardVaultDatabase
    private lateinit var repository: RoomCollectionRepository
    private val set = GermanMasterSetDefinition("count-fixture", "Count Fixture")

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
        repository = RoomCollectionRepository(database)
    }

    @After fun closeDatabase() = database.close()

    @Test fun returningFromReaderReloadsNewVariantCountsWithTheSameSetList() {
        cache("Normal")
        val selectedSetName = mutableStateOf<String?>(null)
        compose.setContent {
            val counts by rememberBinderReferenceCounts(repository, listOf(set.name), selectedSetName.value)
            Text("Slots: ${counts[set.name] ?: 0}")
        }
        awaitSlots(1)

        // Same collection and same set names throughout; only the reader's cached slots change.
        listOf("Reverse Holo", "Holo").forEachIndexed { index, finish ->
            compose.runOnIdle { selectedSetName.value = set.name }
            compose.waitForIdle()
            cache(finish)
            compose.onNodeWithText("Slots: ${index + 1}").assertExists()
            compose.runOnIdle { selectedSetName.value = null }
            awaitSlots(index + 2)
        }

        // Reopening without source changes is idempotent and never adds to the total.
        compose.runOnIdle { selectedSetName.value = set.name }
        compose.waitForIdle()
        compose.runOnIdle { selectedSetName.value = null }
        awaitSlots(3)
    }

    @Test fun changedSetListStillLoadsItsOwnReferenceCounts() {
        cache("Normal")
        val names = mutableStateOf(emptyList<String>())
        compose.setContent {
            val counts by rememberBinderReferenceCounts(repository, names.value, null)
            Text("Slots: ${counts[set.name] ?: 0}")
        }
        awaitSlots(0)
        compose.runOnIdle { names.value = listOf(set.name) }
        awaitSlots(1)
        compose.runOnIdle { names.value = emptyList() }
        awaitSlots(0)
    }

    @Test fun marketProductCountCannotMasqueradeAsGermanVariantSlots() = runBlocking {
        database.cardVaultDao().insertCatalogCard(CatalogCardEntity(
            "cm-pokemon-42", "POKEMON", "fixture", set.name, "Market product", "001",
            "GERMAN", "UNKNOWN", "Cardmarket-Produkt", 1_000L,
            externalSource = CardmarketSyncContract.SOURCE, externalProductId = 42L,
        ))
        assertEquals(0, repository.binderReferenceCount(set.name))
        cache("Normal")
        assertEquals(1, repository.binderReferenceCount(set.name))
        cache("Reverse Holo")
        assertEquals(2, repository.binderReferenceCount(set.name))
    }

    private fun cache(finish: String) = runBlocking {
        repository.cacheGermanMasterSetCards(set, listOf(
            GermanMasterSetCardDefinition("count-fixture-001-$finish", "Karte 1", "001", null, finish),
        ))
    }

    private fun awaitSlots(count: Int) {
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Slots: $count").fetchSemanticsNodes().size == 1 }
    }
}
