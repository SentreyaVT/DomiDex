package com.dominik.cardvault

import androidx.room.Room
import androidx.room.withTransaction
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.CatalogCardEntity
import com.dominik.cardvault.data.local.CollectionEntryEntity
import com.dominik.cardvault.data.local.RawPriceSnapshotEntity
import com.dominik.cardvault.data.local.CollectionSyncOutboxEntity
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.SourceCardIdentity
import com.dominik.cardvault.data.deterministicSourceSyncId
import com.dominik.cardvault.model.AddCardOutcome
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.CollectionEntryUpdate
import com.dominik.cardvault.model.ManualCardInput
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Test fun explicitCatalogLinkUsesOfficialTrendWithoutReplacingLocalIdentity() = runBlocking {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("manual", "POKEMON", "local", "Mein Set", "Pikachu", "25/165", "GERMAN", "Holo", "Standard", now))
        dao.insertCatalogCard(CatalogCardEntity("cm-pokemon-42", "POKEMON", "1585", "Cardmarket-Erweiterung 1585", "Pikachu", "", "UNKNOWN", "UNKNOWN", "Cardmarket-Produkt", now, "CARDMARKET_PUBLIC_DOWNLOAD", 42))
        val entryId = dao.insertCollectionEntry(CollectionEntryEntity(cardId = "manual", quantity = 2, condition = "Sehr gut", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "lokal", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now))
        dao.insertRawPrices(listOf(RawPriceSnapshotEntity(cardId = "cm-pokemon-42", source = "CARDMARKET_PUBLIC_DOWNLOAD", currency = "EUR", lowValueMinor = 100, averageValueMinor = 200, trendValueMinor = 345, marketValueMinor = 345, holoTrendValueMinor = 512, fetchedAtEpochMillis = now, effectiveAtEpochMillis = now)))
        assertEquals(42L, dao.searchCatalog("CARDMARKET_PUBLIC_DOWNLOAD", "Pikachu", 10).single().externalProductId)
        dao.linkExternalProduct(entryId, "CARDMARKET_PUBLIC_DOWNLOAD", 42, now + 1)
        val linked = dao.observeCollection().first().single()
        assertEquals("Mein Set", linked.setName)
        assertEquals("25/165", linked.collectorNumber)
        assertEquals(512L, linked.marketValueMinor)
        assertEquals(42L, linked.externalProductId)
        assertEquals(entryId, dao.findLinkedEntry("CARDMARKET_PUBLIC_DOWNLOAD", 42, "Sehr gut", "Holo", "Standard")?.id)
        assertEquals(null, dao.findLinkedEntry("CARDMARKET_PUBLIC_DOWNLOAD", 42, "Sehr gut", "Reverse-Holo", "Standard"))
    }

    @Test fun collectionQueriesAreStrictlyOwnerScopedAndHideTombstones() = runBlocking {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("owner-card", "POKEMON", "set", "Set", "Mew", "1", "GERMAN", "Normal", "Standard", now))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "owner-card", quantity = 1, condition = "A", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "only-A", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "A", syncId = "entry-A"))
        val bId = dao.insertCollectionEntry(CollectionEntryEntity(cardId = "owner-card", quantity = 1, condition = "B", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "only-B", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "B", syncId = "entry-B"))

        assertEquals(listOf("only-A"), dao.observeCollection("A").first().map { it.notes })
        assertEquals(listOf("only-B"), dao.observeCollection("B").first().map { it.notes })
        dao.tombstoneCollectionEntry(bId, "B", now + 1, "PENDING")
        assertTrue(dao.observeCollection("B").first().isEmpty())
        assertEquals(1, dao.observeCollection("A").first().size)
    }

    @Test fun guestClaimRequiresExplicitDaoActionAndChangesOnlySelectedRow() = runBlocking {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("guest-card", "POKEMON", "set", "Set", "Mew", "1", "GERMAN", "Normal", "Standard", now))
        val guestId = dao.insertCollectionEntry(CollectionEntryEntity(cardId = "guest-card", quantity = 1, condition = "Gast", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "legacy", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, syncId = "legacy-local"))
        assertEquals(1, dao.observeGuestEntryCount().first())
        assertEquals(1, dao.claimGuestEntry(guestId, "A", "fresh-uuid", now + 1))
        assertEquals(0, dao.observeGuestEntryCount().first())
        val claimed = dao.getCollectionEntryBySyncId("A", "fresh-uuid")!!
        assertEquals("PENDING", claimed.syncState)
        assertEquals("legacy", claimed.notes)
    }

    @Test fun repositoryGuestClaimUsesSameStableSourceIdentityAsNormalAdd() = runBlocking {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("guest-source", "POKEMON", "sv1", "Set", "Mew", "001", "GERMAN", "Holo", "Standard", now))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "guest-source", quantity = 1, condition = "Gut", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "legacy", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, syncId = "legacy-source", sourceSetId = "sv1", sourceCardId = "sv1-001"))
        val repository = RoomCollectionRepository(database, MutableStateFlow("A"))

        assertEquals(1, repository.claimGuestCollectionForCurrentUser())
        val expected = deterministicSourceSyncId("A", SourceCardIdentity("sv1", "sv1-001"), "Gut", "Holo", "Standard")
        val claimed = dao.getCollectionEntryBySyncId("A", expected)
        assertEquals("legacy", claimed?.notes)
        assertEquals("sv1-001", claimed?.sourceCardId)
    }

    @Test fun explicitConflictRetryRebasesOnlyValidatedOwnerConflicts() = runBlocking {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("sync-card", "POKEMON", "set", "Set", "Mew", "1", "GERMAN", "Normal", "Standard", now))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "sync-card", quantity = 1, condition = "Gut", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "lokal", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "A", syncId = "valid-conflict", syncState = "CONFLICT", remoteRevision = 2))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "sync-card", quantity = 1, condition = "Gut", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "bleibt", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "A", syncId = "invalid-conflict", syncState = "CONFLICT", remoteRevision = 2))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "sync-card", quantity = 1, condition = "Gut", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "gelöscht", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "A", syncId = "delete-conflict", syncState = "CONFLICT", remoteRevision = 2, deletedAtEpochMillis = now))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("A:valid-conflict", "mutation-old", "A", "valid-conflict", "UPSERT", 2, "CONFLICT", now, "remote-revision-7"))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("A:invalid-conflict", "mutation-bad", "A", "invalid-conflict", "UPSERT", 2, "CONFLICT", now, "permission-denied"))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("A:delete-conflict", "mutation-delete", "A", "delete-conflict", "DELETE", 2, "CONFLICT", now, "remote-revision-8"))
        var schedules = 0
        val repository = RoomCollectionRepository(database, MutableStateFlow("A"), onSyncNeeded = { schedules++ })

        assertEquals(3, repository.syncConflictCount.first())
        assertEquals(1, repository.retryConflictsKeepingLocal())
        assertEquals(1, schedules)
        val rebased = dao.syncOutbox("A:valid-conflict")!!
        assertEquals("PENDING", rebased.state)
        assertEquals(7L, rebased.expectedRemoteRevision)
        assertTrue(rebased.mutationId != "mutation-old")
        assertEquals("PENDING", dao.getCollectionEntryBySyncId("A", "valid-conflict")!!.syncState)
        assertEquals(7L, dao.getCollectionEntryBySyncId("A", "valid-conflict")!!.remoteRevision)
        assertEquals("CONFLICT", dao.syncOutbox("A:invalid-conflict")!!.state)
        assertEquals("CONFLICT", dao.syncOutbox("A:delete-conflict")!!.state)
        assertEquals(2, repository.syncConflictCount.first())
    }

    @Test fun explicitErrorRetryRequeuesOnlyCurrentOwnersTerminalFailures() = runBlocking {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("error-card", "POKEMON", "set", "Set", "Mew", "1", "GERMAN", "Normal", "Standard", now))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "error-card", quantity = 1, condition = "Gut", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "lokal", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "A", syncId = "error-A", syncState = "ERROR", remoteRevision = 3))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "error-card", quantity = 1, condition = "Gut", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "fremd", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "B", syncId = "error-B", syncState = "ERROR", remoteRevision = 5))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("A:error-A", "error-old-A", "A", "error-A", "UPSERT", 3, "ERROR", now, "permission-denied"))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("B:error-B", "error-old-B", "B", "error-B", "UPSERT", 5, "ERROR", now, "permission-denied"))
        var schedules = 0
        val repository = RoomCollectionRepository(database, MutableStateFlow("A"), onSyncNeeded = { schedules++ })

        assertEquals(1, repository.syncErrorCount.first())
        assertEquals(1, repository.retrySyncErrors())
        assertEquals(1, schedules)
        assertEquals("PENDING", dao.syncOutbox("A:error-A")!!.state)
        assertTrue(dao.syncOutbox("A:error-A")!!.mutationId != "error-old-A")
        assertEquals("PENDING", dao.getCollectionEntryBySyncId("A", "error-A")!!.syncState)
        assertEquals("ERROR", dao.syncOutbox("B:error-B")!!.state)
        assertEquals(0, repository.syncErrorCount.first())
    }

    @Test fun scannerAndManualSourcePathsMergeExactPrintingAndAttachVerifiedPriceProduct() = runBlocking {
        val repository = RoomCollectionRepository(database, MutableStateFlow("A"))
        val manual = sourceInput(externalProductId = null)
        val scanner = sourceInput(externalProductId = 42L)

        assertEquals(AddCardOutcome.CREATED, repository.addManualCard(manual))
        assertEquals(AddCardOutcome.QUANTITY_INCREMENTED, repository.addManualCard(scanner))

        val stored = database.cardVaultDao().observeCollection("A").first().single()
        assertEquals(2, stored.quantity)
        assertEquals("tcgdex-sv08-5", stored.binderId)
        assertEquals("sv08-5", stored.sourceSetId)
        assertEquals("sv08-5-001", stored.sourceCardId)
        assertEquals(42L, stored.externalProductId)
    }

    @Test fun pricedBinderAddUpdatesQuantityPortfolioAndRemovalFromOneRoomSource() = runBlocking {
        val dao = database.cardVaultDao()
        val now = System.currentTimeMillis()
        dao.insertCatalogCard(
            CatalogCardEntity(
                id = "cm-pokemon-42", tcg = "POKEMON", setId = "6209", setName = "Cardmarket Set",
                name = "Mew", collectorNumber = "", language = "UNKNOWN", finish = "UNKNOWN",
                variant = "Cardmarket-Produkt", createdAtEpochMillis = now,
                externalSource = "CARDMARKET_PUBLIC_DOWNLOAD", externalProductId = 42L,
            ),
        )
        dao.insertRawPrices(listOf(RawPriceSnapshotEntity(
            cardId = "cm-pokemon-42", source = "CARDMARKET_PUBLIC_DOWNLOAD", currency = "EUR",
            lowValueMinor = 300, averageValueMinor = 330, trendValueMinor = 345, marketValueMinor = 345,
            fetchedAtEpochMillis = now, effectiveAtEpochMillis = now,
        )))
        val repository = RoomCollectionRepository(database, MutableStateFlow("A"))

        assertEquals(AddCardOutcome.CREATED, repository.addManualCard(sourceInput(42L)))
        assertEquals(3.45, repository.portfolio.first().totalValue, .001)
        assertEquals(AddCardOutcome.QUANTITY_INCREMENTED, repository.addManualCard(sourceInput(42L)))
        val doubled = repository.portfolio.first()
        assertEquals(2, doubled.totalCards)
        assertEquals(6.90, doubled.totalValue, .001)
        assertEquals(6.90, repository.binders.first().single().marketValueEur, .001)

        repository.deleteEntry(doubled.cards.single().entryId)
        assertEquals(0, repository.portfolio.first().totalCards)
        assertEquals(0.0, repository.portfolio.first().totalValue, .001)
    }

    @Test fun deleteThenReaddKeepsTombstoneAndUsesFreshSyncGeneration() = runBlocking {
        val repository = RoomCollectionRepository(database, MutableStateFlow("A"))
        repository.addManualCard(sourceInput())
        val first = database.cardVaultDao().observeCollection("A").first().single()
        val firstEntry = database.cardVaultDao().getCollectionEntry(first.entryId, "A")!!

        repository.deleteEntry(first.entryId)
        repository.addManualCard(sourceInput())

        val readded = database.cardVaultDao().observeCollection("A").first().single()
        val readdedEntry = database.cardVaultDao().getCollectionEntry(readded.entryId, "A")!!
        assertTrue(database.cardVaultDao().getCollectionEntry(first.entryId, "A")!!.deletedAtEpochMillis != null)
        assertTrue(firstEntry.syncId != readdedEntry.syncId)
    }

    @Test fun conditionEditThenAddOldConditionCreatesIndependentEntryWithoutSyncCollision() = runBlocking {
        val repository = RoomCollectionRepository(database, MutableStateFlow("A"))
        repository.addManualCard(sourceInput())
        val original = database.cardVaultDao().observeCollection("A").first().single()
        repository.updateEntry(
            original.entryId,
            CollectionEntryUpdate(1, "Gespielt", "", null, null, "Normal", "Standard", null),
        )

        assertEquals(AddCardOutcome.CREATED, repository.addManualCard(sourceInput()))
        val entries = database.cardVaultDao().observeCollection("A").first()
        assertEquals(2, entries.size)
        assertEquals(setOf("Gespielt", "Nicht geprüft"), entries.map { it.condition }.toSet())
        val syncIds = entries.map { database.cardVaultDao().getCollectionEntry(it.entryId, "A")!!.syncId }
        assertEquals(2, syncIds.distinct().size)
    }

    @Test fun cloudWinsSecondReadNeverRollsBackANewerListenerSnapshot() = runBlocking {
        val dao = database.cardVaultDao(); val now = System.currentTimeMillis()
        dao.insertCatalogCard(CatalogCardEntity("cloud-win-card", "POKEMON", "set", "Set", "Mew", "1", "GERMAN", "Normal", "Standard", now))
        dao.insertCollectionEntry(CollectionEntryEntity(cardId = "cloud-win-card", quantity = 1, condition = "Lokal", acquisitionPriceMinor = null, acquisitionCurrency = null, acquisitionDateEpochDay = null, notes = "lokal", frontPhotoUri = null, backPhotoUri = null, createdAtEpochMillis = now, updatedAtEpochMillis = now, ownerUid = "A", syncId = "cloud-win", syncState = "CONFLICT", remoteRevision = 1))
        dao.upsertSyncOutbox(CollectionSyncOutboxEntity("A:cloud-win", "conflict-mutation", "A", "cloud-win", "UPSERT", 1, "CONFLICT", now, "remote-revision-3"))

        // Phase 1: open the entry for listener updates but retain the conflict outbox as guard.
        database.withTransaction {
            val current = dao.syncOutbox("A:cloud-win")!!
            assertEquals("conflict-mutation", current.mutationId)
            dao.markEntrySynced("A", "cloud-win", 3, "CLEAN")
        }
        assertEquals("CONFLICT", dao.syncOutbox("A:cloud-win")!!.state)

        // A newer listener event lands while the explicit resolver performs its second read.
        database.withTransaction {
            dao.applyCleanRemoteEntry(
                ownerUid = "A", syncId = "cloud-win", quantity = 6, condition = "Cloud neu",
                acquisitionPriceMinor = 1234, acquisitionCurrency = "EUR",
                acquisitionDateEpochDay = null, notes = "remote-neuer", externalProductSource = null,
                externalProductId = null, sourceSetId = "sv1", sourceCardId = "sv1-001",
                binderId = "tcgdex-sv1", updatedAtEpochMillis = now + 100,
                remoteRevision = 4, deletedAtEpochMillis = null, finish = "Holo",
                variant = "Standard", effectOverride = null,
            )
        }

        // The resolver's second server read returned revision 3. Its guarded transaction must
        // clear only the matching conflict and preserve listener revision 4.
        database.withTransaction {
            val current = dao.syncOutbox("A:cloud-win")!!
            assertEquals("conflict-mutation", current.mutationId)
            val local = dao.getCollectionEntryBySyncId("A", "cloud-win")!!
            if (com.dominik.cardvault.data.sync.shouldApplyCloudResolutionSnapshot(local.remoteRevision, 3)) {
                throw AssertionError("A stale Cloud-wins snapshot must never roll back Room")
            }
            dao.deleteSyncOutboxIfCurrent("A:cloud-win", "conflict-mutation")
        }

        val replaced = dao.getCollectionEntryBySyncId("A", "cloud-win")!!
        assertEquals("CLEAN", replaced.syncState)
        assertEquals(4L, replaced.remoteRevision)
        assertEquals(6, replaced.quantity)
        assertEquals("remote-neuer", replaced.notes)
        assertEquals("sv1-001", replaced.sourceCardId)
        assertEquals(null, dao.syncOutbox("A:cloud-win"))
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

    @Test fun migrationTwoToThreePreservesEntryAndAddsNullableCatalogLink() {
        val name = "migration-two-three"
        migrationHelper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO catalog_cards (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId) VALUES ('kept','POKEMON','s','Set','Mew','1','GERMAN','Normal','Standard',1,NULL,NULL)")
            execSQL("INSERT INTO collection_entries (id,cardId,quantity,condition,acquisitionPriceMinor,acquisitionCurrency,acquisitionDateEpochDay,notes,frontPhotoUri,backPhotoUri,createdAtEpochMillis,updatedAtEpochMillis) VALUES (1,'kept',2,'Sehr gut',NULL,NULL,NULL,'',NULL,NULL,1,1)")
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 3, true, CardVaultDatabase.MIGRATION_2_3).apply {
            query("SELECT quantity, externalProductId FROM collection_entries WHERE id=1").use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)); assertTrue(it.isNull(1)) }
            close()
        }
    }

    @Test fun migrationThreeToFourPreservesCardsAndAddsNullableImage() {
        val name = "migration-three-four"
        migrationHelper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO catalog_cards (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId) VALUES ('kept','POKEMON','s','Set','Mew','1','GERMAN','Normal','Standard',1,NULL,NULL)")
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 4, true, CardVaultDatabase.MIGRATION_3_4).apply {
            query("SELECT name, imageUrl FROM catalog_cards WHERE id='kept'").use { assertTrue(it.moveToFirst()); assertEquals("Mew", it.getString(0)); assertTrue(it.isNull(1)) }
            close()
        }
    }


    @Test fun migrationSevenToEightKeepsLegacyCollectionAsUnsyncedGuestData() {
        val name = "migration-seven-eight"
        migrationHelper.createDatabase(name, 7).apply {
            execSQL("INSERT INTO catalog_cards (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId,imageUrl,rarity,effectOverride) VALUES ('legacy','POKEMON','s','Set','Mew','1','GERMAN','Normal','Standard',1,NULL,NULL,NULL,'UNKNOWN',NULL)")
            execSQL("INSERT INTO collection_entries (id,cardId,quantity,condition,acquisitionPriceMinor,acquisitionCurrency,acquisitionDateEpochDay,notes,frontPhotoUri,backPhotoUri,createdAtEpochMillis,updatedAtEpochMillis,externalProductSource,externalProductId,binderId) VALUES (7,'legacy',2,'Sehr gut',NULL,NULL,NULL,'bleibt',NULL,NULL,1,1,NULL,NULL,'inbox')")
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 8, true, CardVaultDatabase.MIGRATION_7_8).apply {
            query("SELECT ownerUid,syncId,syncState,remoteRevision,quantity,notes FROM collection_entries WHERE id=7").use {
                assertTrue(it.moveToFirst())
                assertEquals("__domidex_guest__", it.getString(0))
                assertEquals("legacy-7", it.getString(1))
                assertEquals("LOCAL_ONLY", it.getString(2))
                assertEquals(0L, it.getLong(3))
                assertEquals(2, it.getInt(4))
                assertEquals("bleibt", it.getString(5))
            }
            query("SELECT COUNT(*) FROM collection_sync_outbox").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            close()
        }
    }

    @Test fun migrationEightToNineBackfillsOnlyCanonicalTcgdexIdentityPairs() {
        val name = "migration-eight-nine-source-identity"
        migrationHelper.createDatabase(name, 8).apply {
            execSQL("INSERT INTO catalog_cards (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId,imageUrl,rarity,effectOverride) VALUES ('tcgdex-sv08-5-sv08-5-001-reverseholofoil','POKEMON','sv08-5','Set','Mew','001','GERMAN','Reverse Holo','Reverse Holo',1,'TCGDEX_DE',NULL,NULL,'UNKNOWN',NULL)")
            execSQL("INSERT INTO catalog_cards (id,tcg,setId,setName,name,collectorNumber,language,finish,variant,createdAtEpochMillis,externalSource,externalProductId,imageUrl,rarity,effectOverride) VALUES ('malformed-source-row','POKEMON','sv08-5','Set','Pikachu','002','GERMAN','Normal','Standard',1,'TCGDEX_DE',NULL,NULL,'UNKNOWN',NULL)")
            execSQL("INSERT INTO collection_entries (id,cardId,quantity,condition,notes,createdAtEpochMillis,updatedAtEpochMillis,ownerUid,syncId,syncState,remoteRevision,binderId) VALUES (1,'tcgdex-sv08-5-sv08-5-001-reverseholofoil',1,'Gut','',1,1,'A','one','CLEAN',1,'tcgdex-sv08-5')")
            execSQL("INSERT INTO collection_entries (id,cardId,quantity,condition,notes,createdAtEpochMillis,updatedAtEpochMillis,ownerUid,syncId,syncState,remoteRevision,binderId) VALUES (2,'malformed-source-row',1,'Gut','',1,1,'A','two','CLEAN',1,'inbox')")
            close()
        }
        migrationHelper.runMigrationsAndValidate(name, 9, true, CardVaultDatabase.MIGRATION_8_9).apply {
            query("SELECT sourceSetId,sourceCardId FROM collection_entries WHERE id=1").use {
                assertTrue(it.moveToFirst())
                assertEquals("sv08-5", it.getString(0))
                assertEquals("sv08-5-001", it.getString(1))
            }
            query("SELECT sourceSetId,sourceCardId FROM collection_entries WHERE id=2").use {
                assertTrue(it.moveToFirst())
                assertTrue(it.isNull(0))
                assertTrue(it.isNull(1))
            }
            close()
        }
    }

    private fun sourceInput(externalProductId: Long? = null) = ManualCardInput(
        name = "Mew",
        setName = "Testset",
        collectorNumber = "001/100",
        language = CardLanguage.GERMAN,
        finish = "Normal",
        variant = "Standard",
        quantity = 1,
        condition = "Nicht geprüft",
        notes = "",
        acquisitionPriceEur = null,
        acquisitionDateEpochDay = null,
        externalProductId = externalProductId,
        imageUrl = null,
        cardmarketExpansionId = externalProductId?.let { "6209" },
        sourceSetId = "sv08-5",
        sourceCardId = "sv08-5-001",
    )
}
