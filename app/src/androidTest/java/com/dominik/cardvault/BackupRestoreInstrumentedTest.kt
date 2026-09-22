package com.dominik.cardvault

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.backup.UserBackupStore
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.BinderTarget
import com.dominik.cardvault.model.ManualCardInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupRestoreInstrumentedTest {
    private fun database() = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), CardVaultDatabase::class.java).build()
    private suspend fun fixture(db: CardVaultDatabase, owner: String, setId: String = "test"): RoomCollectionRepository {
        val repository = RoomCollectionRepository(db, MutableStateFlow(owner))
        val a = repository.addManualCard(ManualCardInput(name = "Bisasam", setName = "Testset $setId", collectorNumber = "001/100", language = CardLanguage.GERMAN, finish = "Normal", variant = "Standard", quantity = 2, condition = "Near Mint", notes = "Meine Karte", acquisitionPriceEur = 1.25, acquisitionDateEpochDay = 20000, sourceSetId = setId, sourceCardId = "$setId-001"), BinderTarget.MatchingSetBinder)
        val b = repository.addManualCard(ManualCardInput(name = "Bisasam", setName = "Testset $setId", collectorNumber = "001/100", language = CardLanguage.GERMAN, finish = "Reverse Holo", variant = "Reverse", quantity = 1, condition = "Near Mint", notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null, sourceSetId = setId, sourceCardId = "$setId-001"), BinderTarget.MatchingSetBinder)
        val first = repository.createPrivateBinder("Favoriten", "Persönliche Sammlung – nicht zum Tausch", "aurora")
        val second = repository.createPrivateBinder("Tauschen", "Doppelte Karten", "galaxy")
        repository.setPrivateBinderCoverTheme(second, "crystal")
        repository.setPrivateBinderMinimumPageCount(first, 4)
        repository.markPrivateBinderOpened(first)
        val placement = repository.assignToPrivateBinder(first, a.entryId, 20)
        val reversePlacement = repository.assignToPrivateBinder(first, b.entryId, 2)
        repository.assignToPrivateBinder(second, a.entryId, 8)
        repository.setVisibleInSetBinder(a.entryId, false)
        repository.setPrivateBinderPlacementHolo(first, placement, "galaxy", .75f)
        repository.setPrivateBinderPlacementHolo(first, reversePlacement, "aurora", .5f)
        db.openHelper.writableDatabase.execSQL(
            """INSERT INTO grading_analyses
                (collectionEntryId,status,estimatedMinimumGrade,estimatedMaximumGrade,confidence,
                 centeringFinding,cornersFinding,edgesFinding,surfaceFinding,createdAtEpochMillis,
                 completedAtEpochMillis,analyzerVersion,rawValueMinorAtAnalysis,gradingCostMinor,
                 shippingCostMinor,insuranceCostMinor,economicRecommendation,economicReasoning)
                VALUES (?, 'COMPLETE', 7.5, 9.0, 0.8, 'Links etwas breiter', 'Saubere Ecken',
                        'Kleine Kante', 'Ohne Kratzer', 1700000000000, 1700000001000,
                        'backup-test-v1', 1234, 2500, 500, 200, 'KEEP_RAW', 'Testnotiz')""".trimIndent(),
            arrayOf(a.entryId),
        )
        return repository
    }

    /** Local row IDs and owner/sync transport state may change; every business field must survive. */
    private fun businessGraph(raw: String): Map<String, List<Map<String, String>>> {
        val tables = JSONObject(raw).getJSONObject("tables")
        val entries = tables.getJSONArray("collection_entries")
        val syncIds = (0 until entries.length()).associate {
            entries.getJSONObject(it).let { row -> row.getLong("id") to row.getString("syncId") }
        }
        return tables.keys().asSequence().associateWith { table ->
            val rows = tables.getJSONArray(table)
            (0 until rows.length()).map { index ->
                val row = rows.getJSONObject(index)
                row.keys().asSequence().filterNot { key ->
                    key in setOf("ownerUid", "syncState", "remoteRevision") ||
                        (key == "id" && table in setOf("collection_entries", "grading_analyses"))
                }.sorted().associateWith { key ->
                    if (key == "collectionEntryId") syncIds.getValue(row.getLong(key)) else row.get(key).toString()
                }
            }.sortedBy { it.toString() }
        }
    }

    @Test fun restoresWholeOwnerGraphAndIsIdempotent() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            val original = fixture(source, GUEST_OWNER_UID)
            val snapshot = UserBackupStore(source, GUEST_OWNER_UID).exportJson()
            // Force different local entry IDs and prove another owner's existing graph is untouched.
            fixture(target, "pre-existing-owner", "unrelated")
            val unrelatedBefore = UserBackupStore(target, "pre-existing-owner").exportJson()
            val targetStore = UserBackupStore(target, "restored-owner")
            val result = targetStore.restore(snapshot)
            assertEquals(2, result.addedCards); assertEquals(2, result.addedBinders)
            val restored = RoomCollectionRepository(target, MutableStateFlow("restored-owner"))
            assertEquals(original.portfolio.first().totalCards, restored.portfolio.first().totalCards)
            val before = original.privateBinders.first().sortedBy { it.name }
            val after = restored.privateBinders.first().sortedBy { it.name }
            assertEquals(setOf("aurora", "crystal"), before.map { it.coverStyle }.toSet())
            assertEquals(before.map { listOf(it.name, it.description, it.coverStyle, it.minimumPageCount, it.lastOpenedAtEpochMillis) },
                after.map { listOf(it.name, it.description, it.coverStyle, it.minimumPageCount, it.lastOpenedAtEpochMillis) })
            before.zip(after).forEach { (a,b) ->
                val left = original.privateBinderContents(a.id).first()
                val right = restored.privateBinderContents(b.id).first()
                assertEquals(left.map { Triple(it.position, it.holoEffectId, it.holoIntensity) }, right.map { Triple(it.position, it.holoEffectId, it.holoIntensity) })
            }
            assertEquals(businessGraph(snapshot), businessGraph(targetStore.exportJson()))
            assertEquals(businessGraph(unrelatedBefore), businessGraph(UserBackupStore(target, "pre-existing-owner").exportJson()))
            assertEquals(original.setBinderVisibility.first().values.toList(), restored.setBinderVisibility.first().values.toList())
            assertTrue(targetStore.restore(snapshot).alreadyImported)
            val changedTimestamp = JSONObject(snapshot).put("createdAtEpochMillis", 1).toString()
            assertEquals(0, targetStore.restore(changedTimestamp).addedCards)
            assertEquals(businessGraph(snapshot), businessGraph(targetStore.exportJson()))
            assertEquals(3, restored.portfolio.first().totalCards)
            assertEquals(0, RoomCollectionRepository(target, MutableStateFlow("other-owner")).portfolio.first().totalCards)
        } finally { source.close(); target.close() }
    }

    @Test fun constraintFailureRollsBackAllTablesAndMarker() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            fixture(source, GUEST_OWNER_UID)
            val snapshot = JSONObject(UserBackupStore(source, GUEST_OWNER_UID).exportJson())
            snapshot.getJSONObject("tables").getJSONArray("collection_entries").getJSONObject(1).put("condition", JSONObject.NULL)
            try { UserBackupStore(target, GUEST_OWNER_UID).restore(snapshot.toString()); fail("Import should fail") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            listOf("catalog_cards", "collection_entries", "private_binders", "app_metadata").forEach { table ->
                target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
            }
        } finally { source.close(); target.close() }
    }

    @Test fun ownerChangeBeforeCommitRollsBackCompleteGraph() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            fixture(source, GUEST_OWNER_UID)
            val raw = UserBackupStore(source, GUEST_OWNER_UID).exportJson()
            var checks = 0
            try { UserBackupStore(target, "owner").restore(raw, ownerIsCurrent = { ++checks == 1 }); fail() }
            catch (_: IllegalStateException) { }
            listOf("catalog_cards", "collection_entries", "private_binders", "private_binder_placements", "collection_sync_outbox", "app_metadata").forEach { table ->
                target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); assertEquals(table, 0, it.getInt(0)) }
            }
        } finally { source.close(); target.close() }
    }

    @Test fun rejectsFutureVersionDuplicateSlotsAndConsentInjectionBeforeWriting() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            fixture(source, GUEST_OWNER_UID)
            val raw = UserBackupStore(source, GUEST_OWNER_UID).exportJson()
            val invalid = listOf(
                JSONObject(raw).put("version", 999),
                JSONObject(raw).put("settings", JSONObject().put("cardvault_contribution", JSONObject().put("opt_in", true))),
                JSONObject(raw).apply {
                    val rows = getJSONObject("tables").getJSONArray("private_binder_placements")
                    val first = rows.getJSONObject(0); val second = rows.getJSONObject(1)
                    second.put("binderId", first.getString("binderId")); second.put("slotPosition", first.getLong("slotPosition"))
                },
            )
            invalid.forEach { snapshot ->
                try { UserBackupStore(target, GUEST_OWNER_UID).restore(snapshot.toString()); fail("Invalid snapshot accepted") } catch (_: IllegalArgumentException) { }
            }
            target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM collection_entries").use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        } finally { source.close(); target.close() }
    }
}
