package com.dominik.cardvault.data.backup

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Portable personal photos; never trust a foreign backup's device-local file/content URI. */
object BackupPhotos {
    data class Export(val raw: String, val files: Map<String, ByteArray>)
    private val hashPattern = Regex("[a-f0-9]{64}")
    private const val MAX_PHOTO = 12 * 1024 * 1024

    fun export(context: Context, raw: String): Export {
        val root = JSONObject(raw)
        val files = linkedMapOf<String, ByteArray>()
        var total = 0L
        transform(root) { source ->
            val uri = Uri.parse(source)
            val bytes = (if (uri.scheme.isNullOrEmpty()) File(source).inputStream() else context.contentResolver.openInputStream(uri)
                ?: error("Ein persönliches Kartenfoto ist nicht mehr erreichbar. Bitte das Foto vor dem Backup prüfen.")).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val read = input.read(buffer); if (read < 0) break; require(output.size().toLong() + read <= MAX_PHOTO) { "Ein Kartenfoto ist zu groß." }; output.write(buffer, 0, read) }
                output.toByteArray()
            }
            val hash = hash(bytes)
            if (files.putIfAbsent("user_photos/$hash.bin", bytes) == null) total += bytes.size
            require(total <= 48L * 1024 * 1024) { "Die Kartenfotos überschreiten das sichere Backup-Limit." }
            "backup-photo:$hash"
        }
        return Export(root.toString(), files)
    }

    fun validate(raw: String, files: Map<String, ByteArray>) {
        transform(JSONObject(raw)) { source ->
            val hash = source.removePrefix("backup-photo:")
            require(source.startsWith("backup-photo:") && hashPattern.matches(hash)) { "Fremder lokaler Fotopfad im Backup." }
            val bytes = files["user_photos/$hash.bin"] ?: error("Ein Kartenfoto fehlt im Backup.")
            require(bytes.size <= MAX_PHOTO && hash(bytes) == hash) { "Ein Kartenfoto im Backup ist beschädigt." }
            source
        }
    }

    suspend fun <T> withRestored(context: Context, raw: String, files: Map<String, ByteArray>, beforeCreate: ((File) -> Unit)? = null, commit: suspend (String) -> T): T {
        validate(raw, files)
        val root = JSONObject(raw)
        val directory = File(context.filesDir.canonicalFile, "backup_photos")
        require(directory.canonicalFile == directory) { "Unzulässiger Fotoordner." }
        check(directory.isDirectory || directory.mkdirs())
        val created = mutableListOf<File>()
        try {
            transform(root) { source ->
                val hash = source.removePrefix("backup-photo:")
                val target = File(directory, "$hash.bin")
                require(target.canonicalFile == target) { "Unzulässiger Fotopfad." }
                val bytes = files.getValue("user_photos/$hash.bin")
                if (!target.exists()) { beforeCreate?.invoke(target); created.add(target); java.io.FileOutputStream(target).use { it.write(bytes); it.fd.sync() } }
                else require(target.length() <= MAX_PHOTO && hash(target.readBytes()) == hash) { "Fotokonflikt beim Import." }
                Uri.fromFile(target).toString()
            }
            return commit(root.toString())
        } catch (failure: Throwable) {
            // With a durable journal only its committed SQLite marker can decide rollback.
            if (beforeCreate == null) created.forEach(File::delete)
            throw failure
        }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun transform(root: JSONObject, convert: (String) -> String) {
        if (root.optInt("version") != 2) return
        val tables = root.getJSONObject("tables")
        listOf("collection_entries", "grading_analyses").forEach { table ->
            val rows = tables.getJSONArray(table)
            for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                listOf("frontPhotoUri", "backPhotoUri").forEach { field -> if (!row.isNull(field) && row.optString(field).isNotBlank()) row.put(field, convert(row.getString(field))) }
                if (row.has("anglePhotoUris") && !row.isNull("anglePhotoUris") && row.optString("anglePhotoUris").isNotBlank()) {
                    val angles = JSONArray(row.getString("anglePhotoUris"))
                    require(angles.length() <= 20)
                    row.put("anglePhotoUris", JSONArray().apply { for (j in 0 until angles.length()) put(convert(angles.getString(j))) }.toString())
                }
            }
        }
    }
}
