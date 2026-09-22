package com.dominik.cardvault.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.dominik.cardvault.data.local.CardVaultDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class BackupImportJournalTest {
    @Test fun startupRecoveryWaitsForAnInFlightImportCommit() = fixture { root, db, journal -> coroutineScope {
        val fileCreated = CompletableDeferred<Unit>()
        val mayCommit = CompletableDeferred<Unit>()
        val target = File(root, "scan_feedback/images/inflight.jpg").apply { parentFile!!.mkdirs() }
        val importing = async {
            journal.restore { before, key ->
                before(target); target.writeText("complete")
                fileCreated.complete(Unit)
                mayCommit.await()
                db.openHelper.writableDatabase.execSQL("INSERT INTO app_metadata (`key`,value) VALUES (?, 'done')", arrayOf(key))
            }
        }
        fileCreated.await()
        val recovery = async(start = CoroutineStart.UNDISPATCHED) { journal.recover() }
        try { assertFalse(recovery.isCompleted); assertTrue(target.exists()) }
        finally { mayCommit.complete(Unit) }
        importing.await(); recovery.await()
        assertEquals("complete", target.readText())
    } }
    private fun fixture(test: suspend (File, CardVaultDatabase, BackupImportJournal) -> Unit) = runBlocking(Dispatchers.IO) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val root = File(context.cacheDir, "backup-journal-test-${UUID.randomUUID()}").apply { mkdirs() }
        val db = Room.inMemoryDatabaseBuilder(context, CardVaultDatabase::class.java).build()
        try { test(root, db, BackupImportJournal(root, db, true)) } finally { db.close(); root.deleteRecursively() }
    }
    @Test fun failedCommitRemovesOnlyNewFiles() = fixture { root, _, journal ->
        val dir = File(root, "scan_feedback/images").apply { mkdirs() }
        val existing = File(dir, "existing.jpg").apply { writeText("keep") }
        val added = File(dir, "new.jpg")
        try { journal.restore<Unit> { before, _ -> before(added); added.writeText("new"); error("commit failed") }; fail() } catch (_: IllegalStateException) { }
        assertFalse(added.exists()); assertEquals("keep", existing.readText())
        assertFalse(File(root, "backup-import-journal.json").exists())
    }
    @Test fun committedFilesSurviveCleanup() = fixture { root, db, journal ->
        val target = File(root, "scan_feedback/images/saved.jpg").apply { parentFile!!.mkdirs() }
        journal.restore { before, key ->
            before(target); target.writeText("saved")
            db.openHelper.writableDatabase.execSQL("INSERT INTO app_metadata (`key`, value) VALUES (?, 'done')", arrayOf(key))
        }
        assertEquals("saved", target.readText())
        assertFalse(File(root, "backup-import-journal.json").exists())
    }
    @Test fun errorAfterDatabaseCommitDoesNotRollBackFiles() = fixture { root, db, journal ->
        val target = File(root, "scan_feedback/images/committed.jpg").apply { parentFile!!.mkdirs() }
        try {
            journal.restore<Unit> { before, key ->
                before(target); target.writeText("committed")
                db.openHelper.writableDatabase.execSQL("INSERT INTO app_metadata (`key`,value) VALUES (?, 'done')", arrayOf(key))
                error("post-commit scheduler failed")
            }
            fail()
        } catch (_: IllegalStateException) { }
        assertEquals("committed", target.readText())
    }
    @Test fun restartRecoversInterruptedAndPreservesCommittedImport() = fixture { root, db, journal ->
        val target = File(root, "scan_feedback/images/interrupted.jpg").apply { parentFile!!.mkdirs(); writeText("partial") }
        fun interrupted(id: String) { File(root, "backup-import-journal.json").writeText(JSONObject().put("id", id).put("paths", JSONArray().put("scan_feedback/images/interrupted.jpg")).toString()) }
        interrupted(UUID.randomUUID().toString()); journal.recover(); assertFalse(target.exists())
        target.writeText("complete")
        val id = UUID.randomUUID().toString(); interrupted(id)
        db.openHelper.writableDatabase.execSQL("INSERT INTO app_metadata (`key`,value) VALUES (?, 'done')", arrayOf("backup-file-commit:$id"))
        journal.recover(); assertEquals("complete", target.readText())
    }
    @Test fun journalCannotDeleteOutsideItsAllowlist() = fixture { root, _, journal ->
        val protected = File(root, "protected.txt").apply { writeText("keep") }
        File(root, "backup-import-journal.json").writeText(JSONObject().put("id", UUID.randomUUID().toString()).put("paths", JSONArray().put("protected.txt")).toString())
        try { journal.recover(); fail() } catch (_: IllegalArgumentException) { }
        assertEquals("keep", protected.readText())
    }
}
