package com.dominik.cardvault.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dominik.cardvault.data.local.CardVaultDatabase
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CatalogSearchIndexMigrationInstrumentedTest {
    @Test fun migrationNineToTenAddsOnlyCoveringIndexAndPreservesEveryStoredField() {
        val schema = InstrumentationRegistry.getInstrumentation().context.assets
            .open("com.dominik.cardvault.data.local.CardVaultDatabase/9.json")
            .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
        val entities = schema.getJSONArray("entities")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(null) // Framework SQLite opens an isolated in-memory database.
                .callback(object : SupportSQLiteOpenHelper.Callback(9) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (index in 0 until entities.length()) {
                            val entity = entities.getJSONObject(index)
                            val table = entity.getString("tableName")
                            db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                            val indices = entity.optJSONArray("indices")
                            if (indices != null) for (entry in 0 until indices.length()) {
                                db.execSQL(indices.getJSONObject(entry).getString("createSql").replace("\${TABLE_NAME}", table))
                            }
                        }
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                })
                .build(),
        )
        try {
            val db = helper.writableDatabase
            seedVersionNineData(db)
            val tableNames = (0 until entities.length()).map { entities.getJSONObject(it).getString("tableName") }
            val rowsBefore = tableNames.associateWith { table -> rows(db, "SELECT * FROM $table ORDER BY rowid") }
            val tablesBefore = rows(db, "SELECT name, sql FROM sqlite_master WHERE type = 'table' ORDER BY name")
            val indicesBefore = rows(db, "SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name").flatten().toSet()

            CardVaultDatabase.MIGRATION_9_10.migrate(db)

            assertEquals(tablesBefore, rows(db, "SELECT name, sql FROM sqlite_master WHERE type = 'table' ORDER BY name"))
            assertEquals(rowsBefore, tableNames.associateWith { table -> rows(db, "SELECT * FROM $table ORDER BY rowid") })
            assertEquals(
                indicesBefore + "index_catalog_cards_externalSource_externalProductId_name",
                rows(db, "SELECT name FROM sqlite_master WHERE type = 'index' ORDER BY name").flatten().toSet(),
            )
            assertEquals(
                listOf("externalSource", "externalProductId", "name"),
                rows(db, "PRAGMA index_info(index_catalog_cards_externalSource_externalProductId_name)").map { it[2] },
            )
        } finally {
            helper.close()
        }
    }

    private fun seedVersionNineData(db: SupportSQLiteDatabase) {
        db.execSQL("""
            INSERT INTO catalog_cards (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId,imageUrl,rarity)
            VALUES ('cm-pokemon-42','POKEMON','1585','Cardmarket Set','Pikachu','','UNKNOWN','UNKNOWN','Cardmarket-Produkt',1000,'CARDMARKET_PUBLIC_DOWNLOAD',42,'https://example.invalid/market.webp','RARE')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO catalog_cards (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,imageUrl,rarity,effectOverride)
            VALUES ('tcgdex-sv85-sv85-001','POKEMON','sv85','Testset','Pikachu','001/131','GERMAN','Normal','Standard',1000,'TCGDEX_DE','https://example.invalid/source.webp','RARE','holo')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO binders (id,setKey,name,createdAtEpochMillis,updatedAtEpochMillis)
            VALUES ('tcgdex-sv85','tcgdex-sv85','Testset',1000,2000)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO collection_entries (id,cardId,quantity,condition,acquisitionPriceMinor,acquisitionCurrency,notes,createdAtEpochMillis,updatedAtEpochMillis,externalProductSource,externalProductId,binderId,ownerUid,syncId,syncState,remoteRevision,finishOverride,variantOverride,sourceSetId,sourceCardId)
            VALUES (1,'tcgdex-sv85-sv85-001',2,'Gut',1250,'EUR','owner A',1000,2000,'CARDMARKET_PUBLIC_DOWNLOAD',42,'tcgdex-sv85','owner-A','entry-A','PENDING',7,'Normal','Standard','sv85','sv85-001')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO collection_entries (id,cardId,quantity,condition,notes,createdAtEpochMillis,updatedAtEpochMillis,externalProductSource,externalProductId,binderId,ownerUid,syncId,syncState,remoteRevision,finishOverride,variantOverride,sourceSetId,sourceCardId)
            VALUES (2,'tcgdex-sv85-sv85-001',3,'Sehr gut','owner B',1000,2000,'CARDMARKET_PUBLIC_DOWNLOAD',42,'tcgdex-sv85','owner-B','entry-B','CLEAN',4,'Reverse Holo','Reverse Holo','sv85','sv85-001')
        """.trimIndent())
        db.execSQL("""
            INSERT INTO raw_price_snapshots (id,cardId,source,currency,lowValueMinor,averageValueMinor,trendValueMinor,marketValueMinor,holoTrendValueMinor,fetchedAtEpochMillis,effectiveAtEpochMillis)
            VALUES (1,'cm-pokemon-42','CARDMARKET_PUBLIC_DOWNLOAD','EUR',100,200,345,345,512,1000,1000)
        """.trimIndent())
        db.execSQL("""
            INSERT INTO collection_sync_outbox (entryKey,mutationId,ownerUid,syncId,operation,expectedRemoteRevision,state,createdAtEpochMillis,lastError)
            VALUES ('owner-A:entry-A','pending-mutation','owner-A','entry-A','UPSERT',7,'PENDING',2000,NULL)
        """.trimIndent())
    }

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<String?>> = db.query(sql).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                if (cursor.isNull(column)) null else cursor.getString(column)
            })
        }
    }
}
