package com.dominik.cardvault.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.feedback.ScanFeedbackStore
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.model.ManualCardInput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LocalBackupOwnerInstrumentedTest {
    private fun fixture(onQuery: (String, List<Any?>) -> Unit = { _, _ -> }, test: suspend (CardVaultDatabase, File) -> Unit) = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java)
            .setQueryCallback({ sql, args -> onQuery(sql, args) }, java.util.concurrent.Executor { it.run() }).build()
        val root = File(context.cacheDir, "backup-owner-test-${UUID.randomUUID()}").apply { mkdirs() }
        try { test(db, root) } finally { db.close(); root.deleteRecursively() }
    }
    private suspend fun seed(db: CardVaultDatabase, owner: String) {
        RoomCollectionRepository(db, MutableStateFlow(owner)).addManualCard(ManualCardInput(
            name = "Private-$owner", setName = "Test", collectorNumber = "001", notes = "secret-$owner",
            language = com.dominik.cardvault.model.CardLanguage.GERMAN, finish = "Normal", variant = "Standard",
            quantity = 1, condition = "Near Mint", acquisitionPriceEur = null, acquisitionDateEpochDay = null))
    }
    private fun rows(raw: String) = JSONObject(raw).getJSONObject("tables").getJSONArray("collection_entries")
    private fun archiveFeedback() = mapOf(
        "scan_feedback/feedback.json" to JSONObject().put("format", ScanFeedbackStore.EXPORT_FORMAT)
            .put("version", ScanFeedbackStore.EXPORT_VERSION).put("feedback", JSONArray().put(JSONObject()
                .put("id", "imported").put("ownerUid", "untrusted-archive-owner")
                .put("createdAtEpochMillis", 1L).put("imageRelativePath", "images/imported.jpg")
                .put("annotations", JSONArray()).put("appVersion", "test"))).toString().toByteArray(),
        "scan_feedback/images/imported.jpg" to byteArrayOf(1),
    )

    @Test fun repositoryExportsOnlyCapturedOwnerAndAllowsGuestWithoutCloudChecks() = fixture { db, _ ->
        seed(db, "owner-a"); seed(db, "owner-b"); seed(db, GUEST_OWNER_UID)
        val owner = MutableStateFlow("owner-a")
        val repo = RoomCollectionRepository(db, owner)
        val session = repo.captureLocalDataSession()
        val own = rows(repo.exportUserBackup("{}", session))
        assertEquals(1, own.length()); assertEquals("owner-a", own.getJSONObject(0).getString("ownerUid"))
        owner.value = GUEST_OWNER_UID
        assertTrue(runCatching { repo.exportUserBackup("{}", session) }.isFailure)
        val guest = rows(repo.exportUserBackup("{}", repo.captureLocalDataSession()))
        assertEquals(1, guest.length()); assertEquals(GUEST_OWNER_UID, guest.getJSONObject(0).getString("ownerUid"))
    }

    @Test fun oldPickerSessionCannotRegainAuthorityAfterAba() = fixture { db, _ ->
        val owner = MutableStateFlow("owner-a")
        var generation = 1
        val repo = RoomCollectionRepository(db, owner, captureLocalSession = { capturedOwner ->
            val captured = generation
            { owner.value == capturedOwner && generation == captured }
        })
        val beforePicker = repo.captureLocalDataSession()
        owner.value = "owner-b"; generation++
        owner.value = "owner-a"; generation++
        assertTrue(runCatching { repo.exportUserBackup("{}", beforePicker) }.isFailure)
        assertEquals(0, rows(repo.exportUserBackup("{}", repo.captureLocalDataSession())).length())
    }

    @Test fun explicitlyConfirmedImportBindsBothCollectionAndFeedbackToSameTarget() = fixture { db, root ->
        seed(db, "source")
        val raw = UserBackupStore(db, "source").exportJson()
        val repo = RoomCollectionRepository(db, MutableStateFlow("target"))
        val session = repo.captureLocalDataSession()
        val feedback = ScanFeedbackStore(File(root, "scan_feedback"), true)
        val journal = BackupImportJournal(root, db, true)
        journal.restore { before, key ->
            feedback.withImportedFeedback(archiveFeedback(), session, before) {
                repo.restoreUserBackup(raw, key, session)
            }
        }
        assertEquals(1, rows(UserBackupStore(db, "source").exportJson()).length())
        assertEquals(1, rows(UserBackupStore(db, "target").exportJson()).length())
        assertEquals("target", feedback.list(session).single().ownerUid)
        assertTrue(feedback.list(LocalDataSession("untrusted-archive-owner") { true }).isEmpty())
        assertFalse(File(root, "backup-import-journal.json").exists())
    }

    @Test fun accountChangeAfterFileWritesRollsBackJournalAndDoesNotImportRoomRows() = fixture { db, root ->
        seed(db, "source")
        val raw = UserBackupStore(db, "source").exportJson()
        val owner = MutableStateFlow("target")
        val repo = RoomCollectionRepository(db, owner)
        val session = repo.captureLocalDataSession()
        val feedback = ScanFeedbackStore(File(root, "scan_feedback"), true)
        val journal = BackupImportJournal(root, db, true)
        assertTrue(runCatching {
            journal.restore { before, key ->
                feedback.withImportedFeedback(archiveFeedback(), session, before) {
                    owner.value = "another"
                    repo.restoreUserBackup(raw, key, session)
                }
            }
        }.isFailure)
        assertEquals(0, rows(UserBackupStore(db, "target").exportJson()).length())
        assertEquals(0, rows(UserBackupStore(db, "another").exportJson()).length())
        assertTrue(File(root, "scan_feedback/metadata").listFiles().orEmpty().isEmpty())
        assertTrue(File(root, "scan_feedback/images").listFiles().orEmpty().isEmpty())
        assertFalse(File(root, "backup-import-journal.json").exists())
    }

    @Test fun staleBeforeFinalRoomCommitRollsBackEntireGraph() = fixture { db, _ ->
        seed(db, "source")
        val raw = UserBackupStore(db, "source").exportJson()
        val repo = RoomCollectionRepository(db, MutableStateFlow("target"))
        var checks = 0
        val session = LocalDataSession("target") { ++checks < 3 }
        assertTrue(runCatching { repo.restoreUserBackup(raw, session = session) }.isFailure)
        assertEquals(0, rows(UserBackupStore(db, "target").exportJson()).length())
        assertEquals(1, rows(UserBackupStore(db, "source").exportJson()).length())
    }

    @Test fun ownerChangeAfterConfirmedCommitDoesNotDeleteCommittedFeedback() = fixture { db, root ->
        seed(db, "source")
        val raw = UserBackupStore(db, "source").exportJson()
        val owner = MutableStateFlow("target")
        val repo = RoomCollectionRepository(db, owner)
        val session = repo.captureLocalDataSession()
        val feedback = ScanFeedbackStore(File(root, "scan_feedback"), true)
        BackupImportJournal(root, db, true).restore { before, key ->
            feedback.withImportedFeedback(archiveFeedback(), session, before) {
                repo.restoreUserBackup(raw, key, session).also { owner.value = "another" }
            }
        }
        assertEquals(1, rows(UserBackupStore(db, "target").exportJson()).length())
        assertEquals(0, rows(UserBackupStore(db, "another").exportJson()).length())
        assertEquals("target", JSONObject(File(root, "scan_feedback/metadata/imported.json").readText()).getString("ownerUid"))
        assertTrue(File(root, "scan_feedback/images/imported.jpg").isFile)
        assertFalse(File(root, "backup-import-journal.json").exists())
    }

    @Test fun legacyLastMarkerWriteMustNotCommitAfterSessionGenerationChanges() {
        var current = true
        var armed = false
        fixture(onQuery = { sql, args ->
            if (armed && sql.startsWith("INSERT", true) && args.any { it.toString().startsWith("backup-v1:") }) current = false
        }) { db, _ ->
            val repo = RoomCollectionRepository(db, MutableStateFlow("target"))
            val session = LocalDataSession("target") { current }
            armed = true
            assertTrue("Last marker write must be guarded before commit", runCatching {
                repo.restoreLegacyBackup("legacy-fixture", emptyList(), session = session)
            }.isFailure)
            armed = false
            assertFalse(current)
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM app_metadata WHERE `key` LIKE 'backup-v1:%'").use {
                assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
            }
        }
    }

    @Test fun legacyIdempotentLookupMustNotReturnSuccessAfterSessionGenerationChanges() {
        var current = true
        var armed = false
        fixture(onQuery = { sql, args ->
            if (armed && sql.startsWith("SELECT", true) && args.any { it.toString().startsWith("backup-v1:") }) current = false
        }) { db, _ ->
            val repo = RoomCollectionRepository(db, MutableStateFlow("target"))
            val session = LocalDataSession("target") { current }
            assertEquals(0, repo.restoreLegacyBackup("legacy-fixture", emptyList(), session = session))
            armed = true
            assertTrue("Idempotent return must validate the captured session", runCatching {
                repo.restoreLegacyBackup("legacy-fixture", emptyList(), session = session)
            }.isFailure)
            assertFalse(current)
        }
    }

    @Test fun legacyImportWithoutCloudAccessSurvivesItsOwnSameOwnerSyncRequest() = fixture { db, _ ->
        val owner = MutableStateFlow("offline-owner")
        val lifecycle = LocalDataOwnerSession(owner.value)
        var syncRequests = 0
        val repo = RoomCollectionRepository(db, owner,
            onSyncNeeded = { syncRequests++; lifecycle.observeOwner(owner.value) },
            captureLocalSession = lifecycle::capture)
        val session = repo.captureLocalDataSession()
        val input = ManualCardInput(name = "Offline", setName = "Local", collectorNumber = "1",
            language = com.dominik.cardvault.model.CardLanguage.GERMAN, finish = "Normal", variant = "Standard",
            quantity = 1, condition = "Near Mint", notes = "", acquisitionPriceEur = null, acquisitionDateEpochDay = null)
        assertEquals(1, repo.restoreLegacyBackup("offline-fixture", listOf(input), session = session))
        assertEquals(1, syncRequests)
        assertEquals(1, rows(UserBackupStore(db, owner.value).exportJson()).length())
        session.requireCurrent()
    }
}
