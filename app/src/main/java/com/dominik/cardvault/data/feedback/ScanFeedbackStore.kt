package com.dominik.cardvault.data.feedback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.zip.ZipOutputStream

enum class ScanFeedbackAnnotationType { POSITIVE, NEGATIVE }

data class ScanFeedbackAnnotation(
    val id: String = UUID.randomUUID().toString(),
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    val label: String,
    val type: ScanFeedbackAnnotationType,
    val detectedText: String? = null,
    val correctedText: String? = null,
)

data class ScanFeedback(
    val id: String,
    val createdAtEpochMillis: Long,
    val imageRelativePath: String,
    val originalPrediction: String? = null,
    val originalConfidence: Int? = null,
    val originalOcrResults: List<String> = emptyList(),
    val annotations: List<ScanFeedbackAnnotation>,
    val appVersion: String,
    val scannerVersion: String = "scanner-v1",
    val resolvedCardId: String? = null,
    val notes: String? = null,
)

/** Offline-first, consented scan-feedback storage. No network APIs are used here. */
class ScanFeedbackStore private constructor(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "scan_feedback"))
    internal constructor(rootDirectory: File, @Suppress("UNUSED_PARAMETER") testOnly: Boolean) : this(rootDirectory)
    private val images = File(root, "images")
    private val metadata = File(root, "metadata")

    init { images.mkdirs(); metadata.mkdirs() }

    fun save(imageFile: File, draft: ScanFeedback): ScanFeedback {
        require(imageFile.isFile) { "Das Feedbackbild fehlt." }
        val id = draft.id.ifBlank { UUID.randomUUID().toString() }
        val relativeImage = "images/$id.jpg"
        val record = draft.copy(id = id, imageRelativePath = relativeImage)
        val targetImage = File(root, relativeImage)
        val temporaryImage = File(images, "$id.tmp")
        imageFile.copyTo(temporaryImage, overwrite = true)
        if (targetImage.exists()) targetImage.delete()
        check(temporaryImage.renameTo(targetImage)) { "Feedbackbild konnte nicht gespeichert werden." }
        writeMetadata(record)
        return record
    }

    fun list(): List<ScanFeedback> = metadata.listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull { file -> runCatching { fromJson(file.readText()) }.getOrNull() }
        .sortedByDescending { it.createdAtEpochMillis }

    fun exportTo(zip: ZipOutputStream): Int {
        val feedback = list()
        val manifest = JSONObject().apply {
            put("format", EXPORT_FORMAT)
            put("version", EXPORT_VERSION)
            put("feedback", JSONArray().apply { feedback.forEach { put(it.toJson()) } })
        }.toString(2).toByteArray()
        zip.putNextEntry(java.util.zip.ZipEntry("scan_feedback/feedback.json"))
        zip.write(manifest)
        zip.closeEntry()
        feedback.forEach { item ->
            val image = File(root, item.imageRelativePath)
            if (!image.isFile) return@forEach
            zip.putNextEntry(java.util.zip.ZipEntry("scan_feedback/${item.imageRelativePath}"))
            image.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return feedback.size
    }

    /** Adds non-conflicting feedback only. Damaged records or missing images are skipped. */
    fun importFromZip(entries: Map<String, ByteArray>): Int {
        val manifest = entries["scan_feedback/feedback.json"] ?: return 0
        val rootJson = JSONObject(manifest.toString(Charsets.UTF_8))
        require(rootJson.optString("format") == EXPORT_FORMAT && rootJson.optInt("version") == EXPORT_VERSION) { "Scan-Feedback im Export ist nicht unterstützt." }
        val records = rootJson.optJSONArray("feedback") ?: return 0
        require(records.length() <= 500) { "Export enthält zu viele Feedbackeinträge." }
        var imported = 0
        for (index in 0 until records.length()) {
            val item = runCatching { fromJson(records.getJSONObject(index).toString()) }.getOrNull() ?: continue
            if (item.id.isBlank() || File(metadata, "${item.id}.json").exists()) continue
            val safePath = item.imageRelativePath.takeIf { it.matches(Regex("images/[A-Za-z0-9-]+\\.jpg")) } ?: continue
            val image = entries["scan_feedback/$safePath"] ?: continue
            if (image.size > MAX_IMAGE_BYTES) continue
            val target = File(root, safePath)
            val temp = File(images, "${item.id}.importing")
            var createdImage = false
            runCatching {
                temp.writeBytes(image)
                if (target.exists()) error("Bildkonflikt")
                check(temp.renameTo(target)) { "Bild konnte nicht importiert werden." }
                createdImage = true
                writeMetadata(item.copy(imageRelativePath = safePath))
                imported++
            }.onFailure {
                temp.delete()
                if (createdImage && !File(metadata, "${item.id}.json").exists()) target.delete()
            }
        }
        return imported
    }

    private fun writeMetadata(item: ScanFeedback) {
        val target = File(metadata, "${item.id}.json")
        val temp = File(metadata, "${item.id}.tmp")
        temp.writeText(item.toJson().toString(2))
        if (target.exists()) target.delete()
        check(temp.renameTo(target)) { "Feedbackmetadaten konnten nicht gespeichert werden." }
    }

    companion object {
        const val EXPORT_FORMAT = "domidex.scan-feedback"
        const val EXPORT_VERSION = 1
        private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024

        fun fromJson(raw: String): ScanFeedback {
            val root = JSONObject(raw)
            val annotations = root.optJSONArray("annotations") ?: JSONArray()
            return ScanFeedback(
                id = root.getString("id"),
                createdAtEpochMillis = root.getLong("createdAtEpochMillis"),
                imageRelativePath = root.getString("imageRelativePath"),
                originalPrediction = root.optNullableString("originalPrediction"),
                originalConfidence = root.takeIf { it.has("originalConfidence") && !it.isNull("originalConfidence") }?.optInt("originalConfidence"),
                originalOcrResults = root.optJSONArray("originalOcrResults")?.let { values -> List(values.length()) { values.optString(it) } }.orEmpty(),
                annotations = List(annotations.length()) { index ->
                    val value = annotations.getJSONObject(index)
                    ScanFeedbackAnnotation(
                        id = value.optString("id", UUID.randomUUID().toString()),
                        x = value.optDouble("x").toFloat().coerceIn(0f, 1f),
                        y = value.optDouble("y").toFloat().coerceIn(0f, 1f),
                        width = value.optDouble("width").toFloat().coerceIn(.01f, 1f),
                        height = value.optDouble("height").toFloat().coerceIn(.01f, 1f),
                        label = value.optString("label", "other"),
                        type = runCatching { ScanFeedbackAnnotationType.valueOf(value.optString("type")) }.getOrDefault(ScanFeedbackAnnotationType.POSITIVE),
                        detectedText = value.optNullableString("detectedText"),
                        correctedText = value.optNullableString("correctedText"),
                    )
                },
                appVersion = root.optString("appVersion", "unbekannt"),
                scannerVersion = root.optString("scannerVersion", "scanner-v1"),
                resolvedCardId = root.optNullableString("resolvedCardId"),
                notes = root.optNullableString("notes"),
            )
        }
    }
}

private fun ScanFeedback.toJson() = JSONObject().apply {
    put("id", id); put("createdAtEpochMillis", createdAtEpochMillis); put("imageRelativePath", imageRelativePath)
    put("originalPrediction", originalPrediction ?: JSONObject.NULL); put("originalConfidence", originalConfidence ?: JSONObject.NULL)
    put("originalOcrResults", JSONArray(originalOcrResults)); put("appVersion", appVersion); put("scannerVersion", scannerVersion)
    put("resolvedCardId", resolvedCardId ?: JSONObject.NULL); put("notes", notes ?: JSONObject.NULL)
    put("annotations", JSONArray().apply { annotations.forEach { annotation -> put(JSONObject().apply {
        put("id", annotation.id); put("x", annotation.x); put("y", annotation.y); put("width", annotation.width); put("height", annotation.height)
        put("label", annotation.label); put("type", annotation.type.name); put("detectedText", annotation.detectedText ?: JSONObject.NULL); put("correctedText", annotation.correctedText ?: JSONObject.NULL)
    }) } })
}

private fun JSONObject.optNullableString(key: String): String? = takeIf { has(key) && !isNull(key) }?.optString(key)?.trim()?.takeIf { it.isNotBlank() }
