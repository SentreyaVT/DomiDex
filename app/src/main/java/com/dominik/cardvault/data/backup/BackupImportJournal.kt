package com.dominik.cardvault.data.backup

import android.content.Context
import com.dominik.cardvault.data.local.CardVaultDatabase
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** A durable write-ahead list joins imported files to the single SQLite restore transaction. */
class BackupImportJournal private constructor(private val files: File, private val database: CardVaultDatabase) {
    constructor(context: Context, database: CardVaultDatabase) : this(context.filesDir.canonicalFile, database)
    internal constructor(directory: File, database: CardVaultDatabase, @Suppress("UNUSED_PARAMETER") testOnly: Boolean) : this(directory.canonicalFile, database)
    private val journal get() = File(files, "backup-import-journal.json")

    suspend fun <T> restore(block: suspend ((File) -> Unit, String) -> T): T = lock.withLock { withContext(NonCancellable + Dispatchers.IO) {
        recoverLocked()
        val id = UUID.randomUUID().toString()
        val paths = linkedSetOf<String>()
        persist(id, paths)
        try {
                block({ file ->
                    // Android exposes the same app directory via /data/user/0 and /data/data.
                    // Normalize that alias before applying the strict relative-path allowlist.
                    val relative = file.canonicalFile.relativeTo(files).invariantSeparatorsPath
                    val target = validatedTarget(relative)
                    check(!target.exists()) { "Eine Backup-Datei wurde während des Imports geändert." }
                    if (paths.add(relative)) persist(id, paths)
                }, marker(id))
        } finally {
            // If the process dies instead, MainActivity replays this before opening the UI.
            recoverLocked()
        }
    } }

    suspend fun recover() = lock.withLock { withContext(Dispatchers.IO) { recoverLocked() } }

    private fun recoverLocked() {
        if (!journal.exists()) return
        require(journal.canonicalFile == journal && journal.length() <= 256 * 1024) { "Ungültiges Importjournal." }
        val record = JSONObject(journal.readText())
        val id = record.getString("id")
        require(runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false))
        val list = record.getJSONArray("paths")
        require(list.length() <= 1500)
        // Validate the entire list before touching any path.
        val targets = List(list.length()) { validatedTarget(list.getString(it)) }
        val committed = database.openHelper.readableDatabase.query(
            "SELECT value FROM app_metadata WHERE `key`=?", arrayOf(marker(id)),
        ).use { it.moveToFirst() }
        if (!committed) targets.asReversed().forEach { check(!it.exists() || it.delete()) { "Importdatei konnte nicht zurückgesetzt werden." } }
        check(journal.delete()) { "Importjournal konnte nicht abgeschlossen werden." }
        database.openHelper.writableDatabase.execSQL("DELETE FROM app_metadata WHERE `key`=?", arrayOf(marker(id)))
    }

    private fun validatedTarget(relative: String): File {
        require(Regex("(?:backup_photos/[a-f0-9]{64}\\.bin|scan_feedback/(?:images|metadata)/[A-Za-z0-9][A-Za-z0-9_-]{0,79}\\.(?:jpg|json))").matches(relative)) { "Unzulässiger Importpfad." }
        val target = File(files, relative)
        require(target.canonicalFile == target) { "Importpfad verweist außerhalb des erlaubten Ordners." }
        return target
    }

    private fun persist(id: String, paths: Set<String>) {
        val temp = File(files, "backup-import-journal.tmp")
        require(temp.canonicalFile == temp && journal.canonicalFile == journal)
        val bytes = JSONObject().put("id", id).put("paths", JSONArray(paths.toList())).toString().toByteArray()
        FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
        Files.move(temp.toPath(), journal.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        private val lock = Mutex()
        private fun marker(id: String) = "backup-file-commit:$id"
    }
}
