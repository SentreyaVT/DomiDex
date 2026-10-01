package com.dominik.cardvault.data.sharing

import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.squareup.moshi.Moshi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

internal data class BinderShareOptions(
    val showPrices: Boolean = false,
    val showTotal: Boolean = false,
    val showDisplayName: Boolean = false,
)

internal data class BinderShareSummary(val shareId: String, val createdAt: Long, val options: BinderShareOptions)
internal data class CreatedBinderShare(val share: BinderShareSummary, val url: String) {
    override fun toString() = "CreatedBinderShare(link=<private>)"
}
internal open class BinderSharingException(message: String) : Exception(message)
internal class BinderSharingSyncException(
    message: String,
    val activeShares: List<BinderShareSummary>? = null,
) : BinderSharingException(message)

internal interface BinderSharingGateway {
    suspend fun list(expectedUid: String, binderId: String): List<BinderShareSummary>
    suspend fun create(expectedUid: String, binderId: String, options: BinderShareOptions, displayName: String?, confirmed: Boolean): CreatedBinderShare
    suspend fun revoke(expectedUid: String, shareId: String)
}

/** Existing owner-only Web contract. No collection data, credentials or links are persisted. */
internal object BinderSharingContract {
    const val ORIGIN = "https://domidex.web.app"
    const val ENDPOINT = "$ORIGIN/api/binder-shares"
    const val MAX_RESPONSE_BYTES = 128 * 1024
    private val binderIdPattern = Regex("[A-Za-z0-9_-]{1,128}")
    private val shareIdPattern = Regex("[a-f0-9]{64}")
    private val pathPattern = Regex("/share/[A-Za-z0-9_-]{43}")
    private val json = Moshi.Builder().build().adapter(Any::class.java)

    fun requireAccount(expectedUid: String, currentUid: String?) {
        if (expectedUid.isBlank() || expectedUid == GUEST_OWNER_UID || expectedUid != currentUid) throw accountFailure()
    }

    fun listUrl(binderId: String): String {
        requireBinderId(binderId)
        return "$ENDPOINT?binderId=$binderId"
    }

    fun createPayload(binderId: String, options: BinderShareOptions, displayName: String?, confirmed: Boolean): String {
        requireBinderId(binderId)
        if (!confirmed) throw BinderSharingException("Bitte die Freigabe ausdrücklich bestätigen.")
        val body = linkedMapOf<String, Any>(
            "binderId" to binderId, "confirm" to true,
            "showPrices" to options.showPrices, "showTotal" to options.showTotal,
            "showDisplayName" to options.showDisplayName,
        )
        if (options.showDisplayName) {
            val name = displayName?.trim().orEmpty()
            if (name.isEmpty() || name.length > 60 || name.any { it == '@' || it == '\r' || it == '\n' }) {
                throw BinderSharingException("Bitte einen Anzeigenamen ohne E-Mail-Adresse eingeben (maximal 60 Zeichen).")
            }
            body["displayName"] = name
        }
        return json.toJson(body)
    }

    fun revokePayload(shareId: String): String {
        if (!shareIdPattern.matches(shareId)) throw BinderSharingException("Die Freigabekennung ist ungültig. Bitte die Freigaben aktualisieren.")
        return json.toJson(mapOf("shareId" to shareId, "confirm" to true))
    }

    fun parseList(status: Int, body: String): List<BinderShareSummary> {
        if (status != 200) throw responseFailure(status)
        val entries = parseObject(body)["shares"] as? List<*> ?: throw invalidResponse()
        val shares = entries.map { summary(it as? Map<*, *> ?: throw invalidResponse()) }
        if (shares.map { it.shareId }.distinct().size != shares.size) throw invalidResponse()
        return shares
    }

    fun parseCreated(status: Int, body: String, expected: BinderShareOptions): CreatedBinderShare {
        if (status != 201) throw responseFailure(status)
        val response = parseObject(body)
        val share = summary(response)
        val path = response["path"] as? String ?: throw invalidResponse()
        if (!pathPattern.matches(path) || share.options != expected) throw invalidResponse()
        val token = path.removePrefix("/share/")
        val decoded = try { Base64.getUrlDecoder().decode(token) } catch (_: IllegalArgumentException) { throw invalidResponse() }
        if (decoded.size != 32 || Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) != token) throw invalidResponse()
        val hash = MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        if (hash != share.shareId) throw invalidResponse()
        return CreatedBinderShare(share, ORIGIN + path)
    }

    fun parseRevoked(status: Int, body: String) {
        if (status != 200) throw responseFailure(status)
        if (parseObject(body)["ok"] != true) throw invalidResponse()
    }

    fun readBody(input: InputStream, declaredLength: Long = -1): String {
        if (declaredLength > MAX_RESPONSE_BYTES) throw invalidResponse()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) throw invalidResponse()
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    fun accountFailure() = BinderSharingException("Das angemeldete Konto hat sich geändert. Bitte den Binder mit seinem Besitzerkonto erneut öffnen.")
    fun unknownOutcome() = BinderSharingException("Die Freigabe konnte nicht bestätigt werden. Bitte die aktiven Freigaben aktualisieren, bevor du es erneut versuchst.")
    fun responseFailure(status: Int) = BinderSharingException(when (status) {
        404 -> "Binder-Freigaben sind noch nicht aktiviert oder dieser Binder ist noch nicht synchronisiert."
        401, 403 -> "Für diese Freigabe fehlt der Zugriff. Bitte mit dem freigeschalteten Besitzerkonto anmelden."
        429 -> "Zu viele Anfragen. Bitte später erneut versuchen."
        else -> "Die Freigabe konnte nicht bestätigt werden. Bitte die aktiven Freigaben aktualisieren, bevor du es erneut versuchst."
    })

    private fun requireBinderId(id: String) {
        if (!binderIdPattern.matches(id)) throw BinderSharingException("Der Binder hat keine gültige Kennung.")
    }
    private fun invalidResponse() = unknownOutcome()
    private fun parseObject(body: String): Map<*, *> {
        if (body.toByteArray(Charsets.UTF_8).size > MAX_RESPONSE_BYTES) throw invalidResponse()
        return try { json.fromJson(body) as? Map<*, *> ?: throw invalidResponse() }
        catch (_: Exception) { throw invalidResponse() }
    }
    private fun summary(value: Map<*, *>): BinderShareSummary {
        val id = value["shareId"] as? String ?: throw invalidResponse()
        val timestamp = (value["createdAt"] as? Number)?.toDouble() ?: throw invalidResponse()
        if (!shareIdPattern.matches(id) || !timestamp.isFinite() || timestamp <= 0 || timestamp > 9_007_199_254_740_991.0 || timestamp % 1.0 != 0.0) throw invalidResponse()
        val options = value["options"] as? Map<*, *> ?: throw invalidResponse()
        return BinderShareSummary(id, timestamp.toLong(), BinderShareOptions(
            options["showPrices"] as? Boolean ?: throw invalidResponse(),
            options["showTotal"] as? Boolean ?: throw invalidResponse(),
            options["showDisplayName"] as? Boolean ?: throw invalidResponse(),
        ))
    }
}
