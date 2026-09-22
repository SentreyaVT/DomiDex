package com.dominik.cardvault.data.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Callable

/** Versioned, owner-scoped snapshot. Restores missing identities, never increments existing counts. */
class UserBackupStore(private val database: CardVaultDatabase, private val ownerUid: String) {
    data class Result(val addedCards: Int, val addedBinders: Int, val alreadyImported: Boolean)

    fun exportJson(settings: JSONObject = JSONObject()): String = database.runInTransaction(Callable {
        val db = database.openHelper.writableDatabase
        JSONObject().apply {
            put("format", FORMAT); put("version", VERSION); put("databaseVersion", db.version)
            put("createdAtEpochMillis", System.currentTimeMillis())
            put("restorePolicy", "MERGE_MISSING_IDENTITIES")
            put("settings", settings)
            put("tables", JSONObject().apply {
                put("collection_entries", rows(db, "SELECT * FROM collection_entries WHERE ownerUid=? AND deletedAtEpochMillis IS NULL", ownerUid))
                put("catalog_cards", rows(db, "SELECT DISTINCT c.* FROM catalog_cards c JOIN collection_entries e ON c.id=e.cardId WHERE e.ownerUid=? AND e.deletedAtEpochMillis IS NULL", ownerUid))
                put("binders", rows(db, "SELECT DISTINCT b.* FROM binders b JOIN collection_entries e ON b.id=e.binderId WHERE e.ownerUid=? AND e.deletedAtEpochMillis IS NULL", ownerUid))
                put("private_binders", rows(db, "SELECT * FROM private_binders WHERE ownerUid=? AND deletedAtEpochMillis IS NULL", ownerUid))
                put("private_binder_placements", rows(db, "SELECT p.* FROM private_binder_placements p JOIN private_binders b ON b.ownerUid=p.ownerUid AND b.id=p.binderId JOIN collection_entries e ON e.id=p.collectionEntryId AND e.ownerUid=p.ownerUid WHERE p.ownerUid=? AND p.deletedAtEpochMillis IS NULL AND b.deletedAtEpochMillis IS NULL AND e.deletedAtEpochMillis IS NULL", ownerUid))
                put("collection_layout", rows(db, "SELECT l.* FROM collection_layout l JOIN collection_entries e ON e.id=l.collectionEntryId WHERE l.ownerUid=? AND e.deletedAtEpochMillis IS NULL", ownerUid))
                put("grading_analyses", rows(db, "SELECT g.* FROM grading_analyses g JOIN collection_entries e ON e.id=g.collectionEntryId WHERE e.ownerUid=? AND e.deletedAtEpochMillis IS NULL", ownerUid))
            })
        }.toString()
    })

    fun validate(raw: String): JSONObject {
        require(raw.length <= 14 * 1024 * 1024) { "Das Sammlungsbackup ist zu groß." }
        val root = JSONObject(raw)
        require(root.getString("format") == FORMAT && root.getInt("version") == VERSION) { "Nicht unterstützte Backupversion." }
        require(root.getInt("databaseVersion") <= database.openHelper.readableDatabase.version) { "Das Backup stammt aus einer neueren DomiDex-Version." }
        val tables = root.getJSONObject("tables")
        require(tables.keys().asSequence().toSet() == TABLES.toSet()) { "Unvollständiges Backup." }
        var count = 0
        TABLES.forEach { table ->
            val columns = columns(database.openHelper.readableDatabase, table)
            val values = tables.getJSONArray(table)
            count += values.length()
            require(values.length() <= 50_000 && count <= 150_000) { "Zu viele Datensätze im Backup." }
            for (i in 0 until values.length()) {
                val row = values.getJSONObject(i)
                require(row.keys().asSequence().toSet() == columns) { "Ungültige Felder im Backup: $table" }
                row.keys().forEach { key ->
                    val value = row.get(key)
                    require(value == JSONObject.NULL || value is String || value is Number) { "Ungültiger Backupwert." }
                    require(value !is String || value.length <= 32_768) { "Ein Backupwert ist zu lang." }
                    require(value !is Number || value.toDouble().isFinite()) { "Ungültiger Zahlenwert." }
                }
                if (row.has("deletedAtEpochMillis")) require(row.isNull("deletedAtEpochMillis"))
                if (table == "collection_entries") require(row.getInt("quantity") in 1..999_999)
                if (table == "private_binders") require(row.getInt("minimumPageCount") in 1..100_000)
                if (table == "private_binder_placements") {
                    require(row.getLong("slotPosition") in 0..899_999)
                    require(row.getDouble("holoIntensity") in 0.0..1.0)
                }
            }
        }
        validateRelations(tables)
        BackupSettings.validate(root.optJSONObject("settings") ?: JSONObject())
        return root
    }

    fun restore(raw: String, journalKey: String? = null, ownerIsCurrent: () -> Boolean = { true }): Result {
        require(journalKey == null || journalKey.matches(Regex("backup-file-commit:[a-f0-9-]{36}")))
        val root = validate(raw)
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        return database.runInTransaction(Callable {
            check(ownerIsCurrent()) { "Konto während des Imports gewechselt." }
            val db = database.openHelper.writableDatabase
            if (journalKey != null) db.execSQL("INSERT OR REPLACE INTO app_metadata (`key`,value) VALUES (?,?)", arrayOf(journalKey, "done"))
            val key = "backup-v2:$ownerUid:$digest"
            if (scalar(db, "SELECT value FROM app_metadata WHERE `key`=?", key) != null) return@Callable Result(0, 0, true)
            val tables = root.getJSONObject("tables")
            val catalogs = mutableMapOf<String, String>()
            val binders = mutableMapOf<String, String>()
            val entries = mutableMapOf<Long, Long>()
            val newEntries = mutableSetOf<Long>()
            val newBinders = mutableSetOf<String>()
            var addedCards = 0
            each(tables, "catalog_cards") { row ->
                val original = row.getString("id")
                val existing = scalar(db, "SELECT id FROM catalog_cards WHERE id=?", original) ?: if (!row.isNull("externalProductId"))
                    scalar(db, "SELECT id FROM catalog_cards WHERE externalSource=? AND externalProductId=?", row.getString("externalSource"), row.getLong("externalProductId")) else null
                if (existing == null) insert(db, "catalog_cards", row)
                else db.query("SELECT * FROM catalog_cards WHERE id=?", arrayOf(existing)).use { cursor ->
                    check(cursor.moveToFirst())
                    listOf("tcg", "setId", "collectorNumber", "language", "finish", "variant").forEach { field ->
                        require(cursor.getString(cursor.getColumnIndexOrThrow(field)) == row.getString(field)) {
                            "Die Kartenidentität im Backup widerspricht dem vorhandenen Katalog. Es wurde nichts importiert."
                        }
                    }
                }
                catalogs[original] = existing ?: original
            }
            each(tables, "binders") { row ->
                val original = row.getString("id")
                val existing = scalar(db, "SELECT id FROM binders WHERE setKey=?", row.getString("setKey"))
                if (existing == null) {
                    // Never reuse an unrelated binder primary key from an imported document.
                    if (scalar(db, "SELECT id FROM binders WHERE id=?", original) != null) row.put("id", UUID.randomUUID().toString())
                    insert(db, "binders", row)
                }
                binders[original] = existing ?: row.getString("id")
            }
            each(tables, "collection_entries") { row ->
                val oldId = row.getLong("id")
                val syncId = row.getString("syncId")
                val existing = scalar(db, "SELECT id FROM collection_entries WHERE ownerUid=? AND syncId=?", ownerUid, syncId)?.toLong()
                if (existing != null) {
                    require(scalar(db, "SELECT cardId FROM collection_entries WHERE id=?", existing) == catalogs.getValue(row.getString("cardId"))) { "Widersprüchliche Sammlungsidentität im Backup." }
                    entries[oldId] = existing; return@each
                }
                row.remove("id")
                row.put("cardId", catalogs.getValue(row.getString("cardId")))
                row.put("binderId", binders[row.getString("binderId")] ?: "inbox")
                localize(row)
                val id = insert(db, "collection_entries", row)
                entries[oldId] = id; newEntries.add(id); addedCards++
                if (ownerUid != GUEST_OWNER_UID) {
                    val now = System.currentTimeMillis()
                    db.execSQL("INSERT INTO collection_sync_outbox (entryKey,mutationId,ownerUid,syncId,operation,expectedRemoteRevision,createdAtEpochMillis,state,lastError) VALUES (?,?,?,?,?,0,?,'PENDING',NULL)", arrayOf("$ownerUid:$syncId", UUID.randomUUID().toString(), ownerUid, syncId, "UPSERT", now))
                }
            }
            each(tables, "private_binders") { row ->
                val id = row.getString("id")
                if (scalar(db, "SELECT id FROM private_binders WHERE ownerUid=? AND id=?", ownerUid, id) != null) return@each
                localize(row); insert(db, "private_binders", row); newBinders.add(id)
                queuePrivate(db, "BINDER", id, row.getLong("updatedAtEpochMillis"))
            }
            each(tables, "private_binder_placements") { row ->
                if (row.getString("binderId") !in newBinders) return@each
                val entryId = entries.getValue(row.getLong("collectionEntryId"))
                row.put("collectionEntryId", entryId); localize(row)
                insert(db, "private_binder_placements", row)
                queuePrivate(db, "PLACEMENT", row.getString("id"), row.getLong("updatedAtEpochMillis"))
            }
            each(tables, "collection_layout") { row ->
                val entryId = entries.getValue(row.getLong("collectionEntryId"))
                if (entryId !in newEntries) return@each
                row.put("collectionEntryId", entryId); localize(row); insert(db, "collection_layout", row)
                val syncId = scalar(db, "SELECT syncId FROM collection_entries WHERE id=?", entryId)!!
                queuePrivate(db, "LAYOUT", syncId, row.getLong("updatedAtEpochMillis"))
            }
            each(tables, "grading_analyses") { row ->
                val entryId = entries.getValue(row.getLong("collectionEntryId"))
                if (entryId !in newEntries) return@each
                row.remove("id"); row.put("collectionEntryId", entryId); insert(db, "grading_analyses", row)
            }
            db.execSQL("INSERT OR REPLACE INTO app_metadata (`key`,value) VALUES (?,?)", arrayOf(key, "done"))
            // Same commit as the restored data. Replayed idempotently after a crash, before UI setup.
            db.execSQL("INSERT OR REPLACE INTO app_metadata (`key`,value) VALUES (?,?)", arrayOf(BackupSettings.PENDING_KEY, (root.optJSONObject("settings") ?: JSONObject()).toString()))
            check(ownerIsCurrent()) { "Konto während des Imports gewechselt." }
            Result(addedCards, newBinders.size, false)
        })
    }

    private fun localize(row: JSONObject) {
        row.put("ownerUid", ownerUid)
        if (row.has("syncState")) row.put("syncState", if (ownerUid == GUEST_OWNER_UID) "LOCAL_ONLY" else "PENDING")
        if (row.has("remoteRevision")) row.put("remoteRevision", 0)
    }

    private fun queuePrivate(db: SupportSQLiteDatabase, type: String, id: String, updated: Long) {
        if (ownerUid == GUEST_OWNER_UID) return
        db.execSQL("INSERT INTO private_binder_sync_outbox (entryKey,mutationId,ownerUid,recordType,recordId,operation,expectedRemoteRevision,updatedAtEpochMillis,createdAtEpochMillis,lastError) VALUES (?,?,?,?,?,'UPSERT',0,?,?,NULL)", arrayOf<Any>("$ownerUid:$type:$id", UUID.randomUUID().toString(), ownerUid, type, id, updated, System.currentTimeMillis()))
    }

    private fun validateRelations(tables: JSONObject) {
        fun unique(table: String, key: String): Set<String> {
            val values = mutableSetOf<String>()
            each(tables, table) { row -> require(values.add(row.get(key).toString())) { "Doppelte Identität in $table." } }
            return values
        }
        val cards = unique("catalog_cards", "id")
        val setBinders = unique("binders", "id")
        unique("binders", "setKey")
        val entries = unique("collection_entries", "id")
        unique("collection_entries", "syncId")
        val binders = unique("private_binders", "id")
        unique("private_binder_placements", "id")
        val slots = mutableSetOf<String>(); val members = mutableSetOf<String>()
        unique("collection_layout", "collectionEntryId")
        each(tables, "collection_entries") {
            require(it.getString("cardId") in cards)
            require(it.getString("binderId") == "inbox" || it.getString("binderId") in setBinders) { "Set-Binder fehlt im Backup." }
            require(it.getString("syncId").isNotBlank() && it.getString("syncId") != "pending") { "Sammlungsidentität fehlt im Backup." }
        }
        each(tables, "private_binder_placements") {
            require(it.getString("binderId") in binders && it.getLong("collectionEntryId").toString() in entries)
            require(slots.add("${it.getString("binderId")}:${it.getLong("slotPosition")}")) { "Doppelter Binderplatz im Backup." }
            require(members.add("${it.getString("binderId")}:${it.getLong("collectionEntryId")}")) { "Doppelte Binderzuordnung." }
        }
        listOf("collection_layout", "grading_analyses").forEach { table -> each(tables, table) { require(it.getLong("collectionEntryId").toString() in entries) } }
    }

    companion object {
        const val FORMAT = "domidex.collection.backup"
        const val VERSION = 2
        private val TABLES = listOf("catalog_cards", "binders", "collection_entries", "private_binders", "private_binder_placements", "collection_layout", "grading_analyses")
        private fun each(tables: JSONObject, table: String, action: (JSONObject) -> Unit) {
            val list = tables.getJSONArray(table)
            for (i in 0 until list.length()) action(list.getJSONObject(i))
        }
        private fun columns(db: SupportSQLiteDatabase, table: String): Set<String> = db.query("PRAGMA table_info(`$table`)").use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name"))) } }
        private fun scalar(db: SupportSQLiteDatabase, sql: String, vararg args: Any): String? = db.query(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
        private fun rows(db: SupportSQLiteDatabase, sql: String, owner: String): JSONArray = db.query(sql, arrayOf(owner)).use { cursor -> JSONArray().apply {
            while (cursor.moveToNext()) put(JSONObject().apply { cursor.columnNames.forEachIndexed { i, column -> put(column, when (cursor.getType(i)) {
                Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                Cursor.FIELD_TYPE_STRING -> cursor.getString(i)
                else -> error("Nicht unterstützter Datentyp im Backup.")
            }) } })
        } }
        private fun insert(db: SupportSQLiteDatabase, table: String, row: JSONObject): Long {
            require(table in TABLES)
            val values = ContentValues()
            row.keys().forEach { key -> when (val value = row.get(key)) {
                JSONObject.NULL -> values.putNull(key)
                is String -> values.put(key, value)
                is Double -> values.put(key, value)
                is Float -> values.put(key, value)
                is Number -> values.put(key, value.toLong())
                else -> error("Ungültiger Backupwert.")
            } }
            return db.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        }
    }
}
