package com.dominik.cardvault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CardVaultDatabaseTest {
    private lateinit var database: CardVaultDatabase

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun collectionCrudIsObservable() = runBlocking {
        val dao = database.cardVaultDao()
        val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("test", "POKEMON", "set", "Testset", "Mew", "1/1", "GERMAN", "Holo", "Standard", now))
        val id = dao.insertCollectionEntry(CollectionEntryEntity(cardId = "test", quantity = 1, condition = "Sehr gut", acquisitionPriceMinor = 500, acquisitionCurrency = "EUR", acquisitionDateEpochDay = null, notes = "Test", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now))
        assertEquals(1, dao.observeCollection().first().single().quantity)
        dao.incrementQuantity(id, 2, now + 1)
        assertEquals(3, dao.observeCollection().first().single().quantity)
        dao.deleteCollectionEntry(id)
        assertEquals(0, dao.observeCollection().first().size)
    }
}
