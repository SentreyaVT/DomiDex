package com.dominik.cardvault.data.knowledge

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small app-private cache/outbox. It is deliberately separate from Collection Room state. */
internal class ScanKnowledgeStore private constructor(private val root: File) {
    constructor(context: Context) : this(File(context.filesDir, "scan_knowledge"))
    internal constructor(rootDirectory: File, @Suppress("UNUSED_PARAMETER") testOnly: Boolean) : this(rootDirectory)

    private val knowledge = File(root, "cache")
    private val outbox = File(root, "outbox-v2")
    private val uploaded = File(root, "uploaded-v2")

    init {
        synchronized(lock) {
            knowledge.mkdirs(); outbox.mkdirs(); uploaded.mkdirs()
            // Older evidence has no accountable owner/consent and must never be adopted by the
            // account that happens to sign in next. This removes only this obsolete telemetry.
            listOf(File(root, "outbox"), File(root, "uploaded")).forEach { directory ->
                directory.listFiles { file -> file.isFile && file.extension in setOf("json", "tmp") }
                    .orEmpty().forEach(File::delete)
            }
        }
    }

    fun get(fingerprint: String): ScanKnowledgeRecord? = synchronized(lock) {
        safeFile(knowledge, fingerprint)?.takeIf(File::isFile)?.let { file ->
            runCatching { recordFromJson(JSONObject(file.readText())) }.getOrNull()
        }
    }

    fun put(record: ScanKnowledgeRecord) = synchronized(lock) {
        val target = safeFile(knowledge, record.fingerprint) ?: return@synchronized
        atomicWrite(target, record.toJson().toString())
        trim(knowledge, MAX_CACHE_RECORDS)
    }

    fun enqueue(ownerUid: String, evidence: ScanKnowledgeEvidence): Boolean = synchronized(lock) {
        val previous = lastUploadedCardId(ownerUid, evidence.fingerprint)
        if (!shouldUploadScanEvidence(evidence, get(evidence.fingerprint), previous)) return@synchronized false
        val ownerOutbox = ownerDirectory(outbox, ownerUid)
        val target = safeFile(ownerOutbox, evidence.fingerprint) ?: return@synchronized false
        atomicWrite(target, evidence.toJson().put("ownerKey", scanKnowledgeOwnerKey(ownerUid)).toString())
        trim(ownerOutbox, MAX_OUTBOX_RECORDS)
        true
    }

    fun pending(ownerUid: String, limit: Int = 20): List<ScanKnowledgeEvidence> = synchronized(lock) {
        ownerDirectory(outbox, ownerUid).listFiles { file -> file.extension == "json" }.orEmpty()
            .sortedBy(File::lastModified)
            .take(limit.coerceIn(1, 50))
            .mapNotNull { file -> readOwnedEvidence(file, ownerUid) }
    }

    fun markUploaded(ownerUid: String, evidence: ScanKnowledgeEvidence) = synchronized(lock) {
        val ownerUploaded = ownerDirectory(uploaded, ownerUid)
        safeFile(ownerUploaded, evidence.fingerprint)?.let { target ->
            atomicWrite(target, JSONObject().put("cardId", evidence.confirmedCardId).put("at", System.currentTimeMillis()).toString())
        }
        safeFile(ownerDirectory(outbox, ownerUid), evidence.fingerprint)?.let { pending ->
            // A later correction must survive an acknowledgement for an earlier observation.
            if (readOwnedEvidence(pending, ownerUid) == evidence) pending.delete()
        }
        trim(ownerUploaded, MAX_CACHE_RECORDS)
    }

    fun clearPending(ownerUid: String) = synchronized(lock) {
        ownerDirectory(outbox, ownerUid).listFiles { file -> file.isFile }.orEmpty().forEach(File::delete)
    }

    internal fun lastUploadedCardId(ownerUid: String, fingerprint: String): String? = synchronized(lock) {
        safeFile(ownerDirectory(uploaded, ownerUid), fingerprint)?.takeIf(File::isFile)?.let { file ->
            runCatching { JSONObject(file.readText()).optString("cardId").takeIf(String::isNotBlank) }.getOrNull()
        }
    }

    private fun ownerDirectory(directory: File, ownerUid: String): File =
        File(directory, scanKnowledgeOwnerKey(ownerUid)).also { it.mkdirs() }

    private fun readOwnedEvidence(file: File, uid: String): ScanKnowledgeEvidence? = runCatching {
        val value = JSONObject(file.readText())
        if (value.optString("ownerKey") != scanKnowledgeOwnerKey(uid)) return@runCatching null
        evidenceFromJson(value).takeIf { it.fingerprint == file.nameWithoutExtension }
    }.getOrNull()

    private fun safeFile(directory: File, fingerprint: String): File? = fingerprint
        .takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        ?.let { File(directory, "$it.json") }

    private fun atomicWrite(target: File, value: String) {
        val file = AtomicFile(target)
        val output = file.startWrite()
        try { output.write(value.toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (failure: Throwable) { file.failWrite(output); throw failure }
    }

    private fun trim(directory: File, maximum: Int) {
        directory.listFiles { file -> file.extension == "json" }.orEmpty()
            .sortedByDescending(File::lastModified)
            .drop(maximum)
            .forEach(File::delete)
    }

    private companion object {
        val lock = Any()
        const val MAX_CACHE_RECORDS = 512
        const val MAX_OUTBOX_RECORDS = 128
    }
}

private fun ScanKnowledgeEvidence.toJson() = JSONObject().apply {
    put("fingerprint", fingerprint); put("confirmedCardId", confirmedCardId)
    put("sourceSetId", sourceSetId ?: JSONObject.NULL); put("cardNumber", cardNumber); put("setCode", setCode ?: JSONObject.NULL)
    put("ocrTokens", JSONArray(ocrTokens)); put("visualFingerprint", visualFingerprint ?: JSONObject.NULL)
    put("candidateIds", JSONArray(candidateIds)); put("originalConfidence", originalConfidence); put("corrected", corrected)
    put("scannerVersion", scannerVersion); put("knowledgeVersion", knowledgeVersion); put("observedAtClient", observedAtClient)
}

private fun evidenceFromJson(value: JSONObject) = ScanKnowledgeEvidence(
    fingerprint = value.getString("fingerprint"),
    confirmedCardId = value.getString("confirmedCardId"),
    sourceSetId = value.optNullableString("sourceSetId"),
    cardNumber = value.getString("cardNumber"),
    setCode = value.optNullableString("setCode"),
    ocrTokens = value.optJSONArray("ocrTokens").toStrings(24),
    visualFingerprint = value.optNullableString("visualFingerprint"),
    candidateIds = value.optJSONArray("candidateIds").toStrings(8),
    originalConfidence = value.optInt("originalConfidence").coerceIn(0, 100),
    corrected = value.optBoolean("corrected"),
    scannerVersion = value.optString("scannerVersion", "unknown").take(40),
    knowledgeVersion = value.optInt("knowledgeVersion", SCAN_KNOWLEDGE_VERSION).coerceAtLeast(1),
    observedAtClient = value.optLong("observedAtClient"),
)

private fun ScanKnowledgeRecord.toJson() = JSONObject().apply {
    put("fingerprint", fingerprint); put("cardId", cardId); put("sourceSetId", sourceSetId ?: JSONObject.NULL)
    put("cardNumber", cardNumber); put("status", status.name); put("confidence", confidence)
    put("confirmations", confirmations); put("corrections", corrections); put("knowledgeVersion", knowledgeVersion)
    put("updatedAtEpochMillis", updatedAtEpochMillis)
}

private fun recordFromJson(value: JSONObject) = ScanKnowledgeRecord(
    fingerprint = value.getString("fingerprint"), cardId = value.getString("cardId"),
    sourceSetId = value.optNullableString("sourceSetId"), cardNumber = value.optString("cardNumber"),
    status = runCatching { ScanKnowledgeStatus.valueOf(value.getString("status")) }.getOrDefault(ScanKnowledgeStatus.CANDIDATE),
    confidence = value.optInt("confidence").coerceIn(0, 100), confirmations = value.optInt("confirmations").coerceAtLeast(0),
    corrections = value.optInt("corrections").coerceAtLeast(0), knowledgeVersion = value.optInt("knowledgeVersion").coerceAtLeast(1),
    updatedAtEpochMillis = value.optLong("updatedAtEpochMillis"),
)

private fun JSONObject.optNullableString(key: String): String? = takeIf { has(key) && !isNull(key) }?.optString(key)?.takeIf(String::isNotBlank)
private fun JSONArray?.toStrings(maximum: Int): List<String> = this?.let { values ->
    List(minOf(values.length(), maximum)) { index -> values.optString(index) }.filter(String::isNotBlank)
}.orEmpty()
