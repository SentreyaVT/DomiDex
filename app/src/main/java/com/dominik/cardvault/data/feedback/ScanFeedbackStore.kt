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
        val id = FeedbackPathPolicy.validateId(draft.id.ifBlank { UUID.randomUUID().toString() })
        val relativeImage = "images/$id.jpg"
        val record = draft.copy(id = id, imageRelativePath = relativeImage)
        val targetImage = FeedbackPathPolicy.file(root, "images", id, "jpg")
        val temporaryImage = FeedbackPathPolicy.file(root, "images", id, "tmp")
        imageFile.copyTo(temporaryImage, overwrite = true)
        java.nio.file.Files.move(temporaryImage.toPath(), targetImage.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        writeMetadata(record)
        return record
    }

    fun list(): List<ScanFeedback> = metadata.listFiles { file -> file.extension == "json" }
        .orEmpty()
        .mapNotNull { file -> runCatching { fromJson(file.readText()) }.getOrNull() }
        .sortedByDescending { it.createdAtEpochMillis }

    fun exportTo(zip: ZipOutputStream): Int {
        val feedback = list()
        feedback.forEach { item -> require(FeedbackPathPolicy.file(root, "images", item.id, "jpg").isFile) { "Ein lokales Feedbackbild fehlt. Das Backup wurde nicht als vollständig gespeichert." } }
        val manifest = JSONObject().apply {
            put("format", EXPORT_FORMAT)
            put("version", EXPORT_VERSION)
            put("feedback", JSONArray().apply { feedback.forEach { put(it.toJson()) } })
        }.toString(2).toByteArray()
        zip.putNextEntry(java.util.zip.ZipEntry("scan_feedback/feedback.json"))
        zip.write(manifest)
        zip.closeEntry()
        feedback.forEach { item ->
            val image = FeedbackPathPolicy.file(root, "images", item.id, "jpg")
            if (!image.isFile) return@forEach
            zip.putNextEntry(java.util.zip.ZipEntry("scan_feedback/${item.imageRelativePath}"))
            image.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        return feedback.size
    }

    /** Validate all paths before writing anything. Existing records are never overwritten. */
    fun importFromZip(entries: Map<String, ByteArray>): Int = importAtomically(entries) { it }

    fun <T> importAtomically(entries: Map<String, ByteArray>, commit: (Int) -> T): T {
        val prepared = prepareImport(entries)
        val created = mutableListOf<File>()
        try {
            prepared.forEach { (item, image) ->
                val target = FeedbackPathPolicy.file(root, "images", item.id, "jpg")
                val metadataTarget = FeedbackPathPolicy.file(root, "metadata", item.id, "json")
                check(!target.exists() && !metadataTarget.exists()) { "Feedback wurde während des Imports geändert." }
                created.add(target)
                target.outputStream().use { it.write(image) }
                created.add(metadataTarget)
                writeMetadata(item)
            }
            return commit(prepared.size)
        } catch (failure: Throwable) {
            created.asReversed().forEach { file -> file.delete() }
            throw failure
        }
    }

    fun validateImport(entries: Map<String, ByteArray>, requireAllImages: Boolean = false) { prepareImport(entries, requireAllImages) }

    suspend fun <T> withImportedFeedback(entries: Map<String, ByteArray>, beforeCreate: ((File) -> Unit)? = null, commit: suspend (Int) -> T): T {
        val prepared = prepareImport(entries)
        val created = mutableListOf<File>()
        try {
            prepared.forEach { (item, bytes) ->
                val image = FeedbackPathPolicy.file(root, "images", item.id, "jpg")
                val metadataFile = FeedbackPathPolicy.file(root, "metadata", item.id, "json")
                check(!image.exists() && !metadataFile.exists()) { "Feedbackkonflikt während des Imports." }
                beforeCreate?.invoke(image); created.add(image); java.io.FileOutputStream(image).use { it.write(bytes); it.fd.sync() }
                beforeCreate?.invoke(metadataFile); created.add(metadataFile); writeMetadata(item)
            }
            return commit(prepared.size)
        } catch (failure: Throwable) {
            if (beforeCreate == null) created.asReversed().forEach { it.delete() }
            throw failure
        }
    }

    private fun prepareImport(entries: Map<String, ByteArray>, requireAllImages: Boolean = false): List<Pair<ScanFeedback, ByteArray>> {
        val manifest = entries["scan_feedback/feedback.json"] ?: return emptyList()
        val rootJson = JSONObject(manifest.toString(Charsets.UTF_8))
        require(rootJson.optString("format") == EXPORT_FORMAT && rootJson.optInt("version") == EXPORT_VERSION) { "Scan-Feedback im Export ist nicht unterstützt." }
        val records = rootJson.optJSONArray("feedback") ?: return emptyList()
        require(records.length() <= 500) { "Export enthält zu viele Feedbackeinträge." }
        val prepared = mutableListOf<Pair<ScanFeedback, ByteArray>>()
        val ids = mutableSetOf<String>()
        for (index in 0 until records.length()) {
            val item = fromJson(records.getJSONObject(index).toString())
            FeedbackPathPolicy.validateId(item.id)
            require(ids.add(item.id)) { "Doppelte Feedback-ID im Backup." }
            val safePath = "images/${item.id}.jpg"
            require(item.imageRelativePath == safePath) { "Ungültiger Feedback-Bildpfad." }
            require(!requireAllImages || entries.containsKey("scan_feedback/$safePath")) { "Ein Feedbackbild fehlt im Backup." }
            val target = FeedbackPathPolicy.file(root, "images", item.id, "jpg")
            val metadataTarget = FeedbackPathPolicy.file(root, "metadata", item.id, "json")
            if (metadataTarget.exists()) continue
            val image = entries["scan_feedback/$safePath"] ?: continue
            require(image.size <= MAX_IMAGE_BYTES) { "Feedbackbild ist zu groß." }
            require(!target.exists()) { "Bildkonflikt im Feedback-Backup." }
            prepared.add(item to image)
        }
        return prepared
    }

    private fun writeMetadata(item: ScanFeedback) {
        val target = FeedbackPathPolicy.file(root, "metadata", item.id, "json")
        val temp = FeedbackPathPolicy.file(root, "metadata", item.id, "tmp")
        try {
            temp.writeText(item.toJson().toString(2))
            java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temp.delete() }
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
