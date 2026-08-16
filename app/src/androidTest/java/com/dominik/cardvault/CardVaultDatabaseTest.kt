package com.dominik.cardvault

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Rule

@RunWith(AndroidJUnit4::class)
class CardVaultDatabaseTest {
    @get:Rule val migrationHelper = MigrationTestHelper(
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation(),
        CardVaultDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory()
    )
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

    @Test fun migrationOneToTwoPreservesCollectionAndRemovesOnlyDemoPrices() {
        val name = "migration-test"
        migrationHelper.createDatabase(name, 1).apply {
            execSQL("INSERT INTO catalog_cards VALUES ('kept','POKEMON','s','Set','Mew','1','GERMAN','Normal','Standard',1)")
            execSQL("INSERT INTO collection_entries VALUES (1,'kept',2,'Sehr gut',NULL,NULL,NULL,'',NULL,NULL,1,1)")
            execSQL("INSERT INTO raw_price_snapshots VALUES (1,'kept','DEMO_LOCAL','EUR',NULL,NULL,100,100,1,1)")
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 2, true, CardVaultDatabase.MIGRATION_1_2).apply {
            query("SELECT quantity FROM collection_entries WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
            query("SELECT COUNT(*) FROM raw_price_snapshots").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            close()
        }
    }
}
