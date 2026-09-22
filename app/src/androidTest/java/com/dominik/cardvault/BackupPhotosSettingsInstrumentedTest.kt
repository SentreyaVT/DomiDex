package com.dominik.cardvault

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.data.RoomCollectionRepository
import com.dominik.cardvault.data.backup.BackupPhotos
import com.dominik.cardvault.data.backup.BackupSettings
import com.dominik.cardvault.data.backup.UserBackupStore
import com.dominik.cardvault.data.local.CardVaultDatabase
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.model.BinderTarget
import com.dominik.cardvault.model.CardLanguage
import com.dominik.cardvault.model.ManualCardInput
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** In-memory databases/preferences and temporary files only; never reads real collection or consent. */
@RunWith(AndroidJUnit4::class)
class BackupPhotosSettingsInstrumentedTest {
    private val base: Context = ApplicationProvider.getApplicationContext()
    @get:Rule val temporary = TemporaryFolder(base.cacheDir)

    private fun context(name: String) = IsolatedContext(base, temporary.newFolder(name).canonicalFile)
    private fun database() = Room.inMemoryDatabaseBuilder(base, CardVaultDatabase::class.java).build()
    private suspend fun addCard(db: CardVaultDatabase): Long = RoomCollectionRepository(db, MutableStateFlow(GUEST_OWNER_UID))
        .addManualCard(
            ManualCardInput(
                name = "Testkarte", setName = "Backup-Test", collectorNumber = "001/010",
                language = CardLanguage.GERMAN, finish = "Normal", variant = "Standard", quantity = 1,
                condition = "Near Mint", notes = "Lokales Testfoto", acquisitionPriceEur = 1.25,
                acquisitionDateEpochDay = 20000, sourceSetId = "backup-test", sourceCardId = "backup-test-001",
            ), BinderTarget.MatchingSetBinder,
        ).entryId

    @Test fun photoBytesRoundTripAcrossEntriesAndGradingWithStablePortableHash() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            val sourceContext = context("photo-source"); val targetContext = context("photo-target")
            // A deterministic fixture is sufficient: photo transport must preserve every byte.
            val bytes = ByteArray(1027) { (it % 251).toByte() }
            val photo = File(sourceContext.filesDir, "owned-photo.bin").apply { writeBytes(bytes) }
            val localUri = Uri.fromFile(photo).toString()
            val entryId = addCard(source)
            source.openHelper.writableDatabase.execSQL(
                "UPDATE collection_entries SET frontPhotoUri=?,backPhotoUri=? WHERE id=?",
                arrayOf<Any>(localUri, localUri, entryId),
            )
            source.openHelper.writableDatabase.execSQL(
                "INSERT INTO grading_analyses (collectionEntryId,status,frontPhotoUri,backPhotoUri,anglePhotoUris,createdAtEpochMillis) VALUES (?,?,?,?,?,?)",
                arrayOf<Any>(entryId, "COMPLETE", localUri, localUri, JSONArray(listOf(localUri, localUri)).toString(), 1700000000000L),
            )
            val raw = UserBackupStore(source, GUEST_OWNER_UID).exportJson()
            val portable = BackupPhotos.export(sourceContext, raw)
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val portableUri = "backup-photo:$hash"
            assertEquals(setOf("user_photos/$hash.bin"), portable.files.keys)
            assertArrayEquals(bytes, portable.files.getValue("user_photos/$hash.bin"))
            assertEquals(listOf(localUri, localUri, localUri, localUri, localUri, localUri), photoReferences(raw))
            assertEquals(List(6) { portableUri }, photoReferences(portable.raw))
            BackupPhotos.validate(portable.raw, portable.files)

            val restored = BackupPhotos.withRestored(targetContext, portable.raw, portable.files) { restoredRaw ->
                UserBackupStore(target, "photo-restored-owner").restore(restoredRaw)
            }
            assertEquals(1, restored.addedCards)
            val restoredRaw = UserBackupStore(target, "photo-restored-owner").exportJson()
            val expectedFile = File(targetContext.filesDir, "backup_photos/$hash.bin").canonicalFile
            assertEquals(List(6) { Uri.fromFile(expectedFile).toString() }, photoReferences(restoredRaw))
            assertArrayEquals(bytes, expectedFile.readBytes())
            assertArrayEquals(bytes, photo.readBytes())
            assertEquals(1, expectedFile.parentFile!!.listFiles()!!.size)
            val secondExport = BackupPhotos.export(targetContext, restoredRaw)
            assertEquals(List(6) { portableUri }, photoReferences(secondExport.raw))
            assertEquals(portable.files.keys, secondExport.files.keys)
            assertArrayEquals(bytes, secondExport.files.getValue("user_photos/$hash.bin"))
        } finally { source.close(); target.close() }
    }

    @Test fun missingLocalPhotoRejectsExportWithoutChangingTheCollection() = runBlocking(Dispatchers.IO) {
        val db = database()
        try {
            val isolated = context("missing-photo")
            val missing = File(isolated.filesDir, "does-not-exist.jpg")
            val entryId = addCard(db)
            db.openHelper.writableDatabase.execSQL("UPDATE collection_entries SET frontPhotoUri=? WHERE id=?", arrayOf<Any>(Uri.fromFile(missing).toString(), entryId))
            val store = UserBackupStore(db, GUEST_OWNER_UID)
            val raw = store.exportJson()
            try { BackupPhotos.export(isolated, raw); fail("Missing photo must not silently disappear from a backup") }
            catch (_: FileNotFoundException) { }
            assertEquals(photoReferences(raw), photoReferences(store.exportJson()))
            assertFalse(missing.exists())
            assertEquals(0, isolated.filesDir.listFiles()!!.size)
        } finally { db.close() }
    }

    @Test fun missingOrCorruptedPortablePhotoIsRejectedBeforeCreatingFilesOrRows() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            val isolated = context("invalid-portable-photo")
            val entryId = addCard(source)
            val hash = "0".repeat(64)
            source.openHelper.writableDatabase.execSQL("UPDATE collection_entries SET frontPhotoUri=? WHERE id=?", arrayOf<Any>("backup-photo:$hash", entryId))
            val raw = UserBackupStore(source, GUEST_OWNER_UID).exportJson()
            var committed = false
            listOf(emptyMap(), mapOf("user_photos/$hash.bin" to byteArrayOf(1, 2))).forEach { files ->
                try {
                    BackupPhotos.withRestored(isolated, raw, files) { restoredRaw ->
                        committed = true
                        UserBackupStore(target, GUEST_OWNER_UID).restore(restoredRaw)
                    }
                    fail("Missing or corrupted backup photo accepted")
                } catch (_: IllegalArgumentException) { } catch (_: IllegalStateException) { }
            }
            assertFalse(committed)
            assertFalse(File(isolated.filesDir, "backup_photos").exists())
            assertEquals(0, JSONObject(UserBackupStore(target, GUEST_OWNER_UID).exportJson()).getJSONObject("tables").getJSONArray("collection_entries").length())
        } finally { source.close(); target.close() }
    }

    @Test fun presentationPreferencesRoundTripButExistingConsentAndAuthStayIndependent() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            val from = context("settings-source"); val to = context("settings-target")
            from.preferences("cardvault_ui").edit().putString("dashboard_focus", "BINDER").putBoolean("arrival_seen", true)
                .putBoolean("relaxed_tutorial_answered_v1", true).putBoolean("scanlab_consent", true).commit()
            from.preferences("cardvault_binders").edit().putBoolean("private_binder_effects_animated", false)
                .putStringSet("hidden_reference_binders", setOf("sv01", "sv02"))
                .putString("overview_sort", "NAME").putString("overview_filter", "ALL").putString("overview_tab", "PRIVATE")
                .putLong("last_opened_set:sv01", 1700000000000L).commit()
            from.preferences("cardvault_contribution").edit().putBoolean("opt_in", true).commit()
            to.preferences("cardvault_contribution").edit().putBoolean("opt_in", false).commit()
            to.preferences("cardvault_ui").edit().putBoolean("scanlab_consent", false).commit()
            to.preferences("auth-test-only").edit().putString("active_owner", "unchanged-owner").commit()
            addCard(source)
            val settings = BackupSettings.export(from)
            assertFalse(settings.has("cardvault_contribution"))
            assertFalse(settings.getJSONObject("cardvault_ui").has("scanlab_consent"))
            val raw = UserBackupStore(source, GUEST_OWNER_UID).exportJson(settings)
            assertEquals(1, UserBackupStore(target, "settings-restored-owner").restore(raw).addedCards)
            assertEquals(1, pendingSettings(target))
            BackupSettings.applyPending(to, target)
            assertEquals(0, pendingSettings(target))
            assertEquals(settingValues(settings), settingValues(BackupSettings.export(to)))
            assertFalse(to.preferences("cardvault_contribution").getBoolean("opt_in", true))
            assertFalse(to.preferences("cardvault_ui").getBoolean("scanlab_consent", true))
            assertEquals("unchanged-owner", to.preferences("auth-test-only").getString("active_owner", null))
            BackupSettings.applyPending(to, target)
            assertEquals(settingValues(settings), settingValues(BackupSettings.export(to)))
        } finally { source.close(); target.close() }
    }

    @Test fun injectedConsentGroupOrKeyRejectsImportBeforeWritingOrChangingPreferences() = runBlocking(Dispatchers.IO) {
        val source = database(); val target = database()
        try {
            val isolated = context("injected-consent")
            isolated.preferences("cardvault_contribution").edit().putBoolean("opt_in", false).commit()
            addCard(source)
            val raw = UserBackupStore(source, GUEST_OWNER_UID).exportJson()
            listOf(
                JSONObject().put("cardvault_contribution", JSONObject().put("opt_in", true)),
                JSONObject().put("cardvault_ui", JSONObject().put("scanlab_consent", true)),
            ).forEach { settings ->
                try { UserBackupStore(target, GUEST_OWNER_UID).restore(JSONObject(raw).put("settings", settings).toString()); fail("Consent import accepted") }
                catch (_: IllegalArgumentException) { }
            }
            assertEquals(0, pendingSettings(target))
            assertEquals(0, JSONObject(UserBackupStore(target, GUEST_OWNER_UID).exportJson()).getJSONObject("tables").getJSONArray("collection_entries").length())
            assertFalse(isolated.preferences("cardvault_contribution").getBoolean("opt_in", true))
        } finally { source.close(); target.close() }
    }

    private fun pendingSettings(db: CardVaultDatabase): Int = db.openHelper.readableDatabase.query(
        "SELECT COUNT(*) FROM app_metadata WHERE `key`=?", arrayOf(BackupSettings.PENDING_KEY),
    ).use { it.moveToFirst(); it.getInt(0) }

    private fun settingValues(settings: JSONObject): Map<String, Map<String, Any>> =
        settings.keys().asSequence().associateWith { group ->
            val values = settings.getJSONObject(group)
            values.keys().asSequence().associateWith { key ->
                when (val value = values.get(key)) {
                    is JSONArray -> (0 until value.length()).map { value.getString(it) }.toSet()
                    else -> value.toString()
                }
            }
        }

    private fun photoReferences(raw: String): List<String> = buildList {
        val tables = JSONObject(raw).getJSONObject("tables")
        listOf("collection_entries", "grading_analyses").forEach { table ->
            val rows = tables.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                listOf("frontPhotoUri", "backPhotoUri").forEach { field -> if (!row.isNull(field)) add(row.getString(field)) }
                if (!row.isNull("anglePhotoUris")) {
                    val angles = JSONArray(row.getString("anglePhotoUris"))
                    for (j in 0 until angles.length()) add(angles.getString(j))
                }
            }
        }
    }

    private class IsolatedContext(base: Context, private val directory: File) : ContextWrapper(base) {
        private val groups = mutableMapOf<String, SharedPreferences>()
        override fun getFilesDir(): File = directory
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = groups.getOrPut(name) { MemoryPreferences() }
        fun preferences(name: String): SharedPreferences = getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = linkedMapOf<String, Any>()
        override fun getAll(): Map<String, *> = values.toMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = linkedMapOf<String, Any?>()
            private var clear = false
            private fun put(key: String?, value: Any?) = apply { pending[requireNotNull(key)] = value }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear() = apply { clear = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (clear) values.clear()
                pending.forEach { (key, value) -> if (value == null) values.remove(key) else values[key] = value }
                return true
            }
        }
    }
}
