package com.dominik.cardvault.data.backup

import android.content.Context
import com.dominik.cardvault.data.local.CardVaultDatabase
import org.json.JSONArray
import org.json.JSONObject

/** Presentation preferences only. Auth, permissions and consent must never be restored by a ZIP. */
object BackupSettings {
    const val PENDING_KEY = "backup-pending-presentation-settings-v2"
    private val fixed = mapOf(
        "cardvault_ui" to setOf("dashboard_focus", "arrival_seen", "relaxed_tutorial_answered_v1"),
        "cardvault_binders" to setOf("private_binder_effects_animated", "hidden_reference_binders", "overview_sort", "overview_filter", "overview_tab"),
    )
    private fun allowed(group: String, key: String) = key in fixed[group].orEmpty() ||
        (group == "cardvault_binders" && key.startsWith("last_opened_set:") && key.length <= 240)

    fun export(context: Context): JSONObject = JSONObject().apply {
        fixed.keys.forEach { group -> put(group, JSONObject().apply {
            context.getSharedPreferences(group, Context.MODE_PRIVATE).all.forEach { (key, value) ->
                if (allowed(group, key)) put(key, if (value is Set<*>) JSONArray(value.toList()) else value)
            }
        }) }
    }

    fun validate(settings: JSONObject) {
        require(settings.keys().asSequence().all { it in fixed }) { "Unzulässige Einstellungen im Backup." }
        settings.keys().forEach { group ->
            val values = settings.getJSONObject(group)
            require(values.length() <= 500)
            values.keys().forEach { key ->
                require(allowed(group, key)) { "Zugangs- oder Einwilligungseinstellungen dürfen nicht importiert werden." }
                val value = values.get(key)
                when {
                    key in setOf("arrival_seen", "relaxed_tutorial_answered_v1", "private_binder_effects_animated") -> require(value is Boolean)
                    key.startsWith("last_opened_set:") -> require(value is Number && value.toLong() >= 0)
                    key == "hidden_reference_binders" -> require(value is JSONArray)
                    else -> require(value is String)
                }
                require(value.toString().length <= 32_768)
                if (value is JSONArray) {
                    require(key == "hidden_reference_binders" && value.length() <= 500)
                    for (i in 0 until value.length()) require(value.get(i) is String && value.getString(i).length <= 240)
                }
            }
        }
    }

    /** A pending DB journal survives interruption; replay is idempotent and happens before UI startup. */
    fun applyPending(context: Context, database: CardVaultDatabase) {
        val db = database.openHelper.writableDatabase
        val raw = db.query("SELECT value FROM app_metadata WHERE `key`=?", arrayOf(PENDING_KEY)).use { if (it.moveToFirst()) it.getString(0) else null } ?: return
        val settings = JSONObject(raw)
        validate(settings)
        settings.keys().forEach { group ->
            val editor = context.getSharedPreferences(group, Context.MODE_PRIVATE).edit()
            val values = settings.getJSONObject(group)
            values.keys().forEach { key -> when (val value = values.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Number -> editor.putLong(key, value.toLong())
                is JSONArray -> editor.putStringSet(key, (0 until value.length()).map { value.getString(it) }.toSet())
            } }
            check(editor.commit()) { "Einstellungen konnten noch nicht wiederhergestellt werden. Bitte erneut starten." }
        }
        db.execSQL("DELETE FROM app_metadata WHERE `key`=? AND value=?", arrayOf(PENDING_KEY, raw))
    }
}
