package com.dominik.cardvault

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.local.CardVaultDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PriceSelectionMigrationInstrumentedTest {
    @get:Rule val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(), CardVaultDatabase::class.java.canonicalName,
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun migrationTwelveToThirteenAddsOnlyEmptySidecarAndPreservesExistingRows() {
        val name = "migration-twelve-thirteen-price-selection"
        migrationHelper.createDatabase(name, 12).apply {
            execSQL("""INSERT INTO catalog_cards
                (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis)
                VALUES ('kept-v12','POKEMON','me03','Set','Bauz','090/088','GERMAN','Holo','Holo',1000)""")
            execSQL("""INSERT INTO collection_entries
                (id,cardId,quantity,condition,notes,createdAtEpochMillis,updatedAtEpochMillis,ownerUid,syncId,
                 syncState,remoteRevision,sourceSetId,sourceCardId,externalProductSource,externalProductId)
                VALUES (7,'kept-v12',2,'Gut','unverändert',1000,2000,'owner-a','entry-a','CLEAN',4,
                        'me03','me03-090','CARDMARKET_PUBLIC_DOWNLOAD',877507)""")
            execSQL("""INSERT INTO raw_price_snapshots
                (id,cardId,source,currency,averageValueMinor,trendValueMinor,marketValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis)
                VALUES (9,'kept-v12','CARDMARKET_PUBLIC_DOWNLOAD','EUR',532,NULL,NULL,1000,1000)""")
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 13, true, CardVaultDatabase.MIGRATION_12_13).apply {
            query("SELECT quantity,notes,syncId,remoteRevision,sourceCardId FROM collection_entries WHERE id=7").use {
                assertTrue(it.moveToFirst())
                assertEquals(2, it.getInt(0)); assertEquals("unverändert", it.getString(1))
                assertEquals("entry-a", it.getString(2)); assertEquals(4L, it.getLong(3))
                assertEquals("me03-090", it.getString(4))
            }
            query("SELECT averageValueMinor,trendValueMinor,marketValueMinor FROM raw_price_snapshots WHERE id=9").use {
                assertTrue(it.moveToFirst()); assertEquals(532L, it.getLong(0))
                assertTrue(it.isNull(1)); assertTrue(it.isNull(2))
            }
            query("SELECT COUNT(*) FROM collection_price_selections").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
            close()
        }
    }
}
