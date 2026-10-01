package com.dominik.cardvault.data.sharing

import com.squareup.moshi.Moshi
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Public allowlisted values only. No owner identity, collection entity or private fields. */
internal data class SharedBinderPrice(val amountEur: Double?, val asOf: String?)
internal data class SharedBinderTotal(val amountEur: Double?, val complete: Boolean, val pricedCards: Int, val visibleCards: Int)
internal data class SharedBinderCard(
    val slot: Int, val cardId: String, val name: String, val setName: String, val number: String,
    val imageUrl: String?, val finish: String, val holoEffectId: String, val holoIntensity: Float,
    val price: SharedBinderPrice? = null,
)
internal data class SharedBinderPage(
    val title: String, val coverStyle: String, val page: Int, val pageCount: Int,
    val cards: List<SharedBinderCard>, val unavailableSlots: Set<Int>,
    val displayName: String? = null, val total: SharedBinderTotal? = null,
)
internal class SharedBinderReadException(
    message: String = "Der Binder konnte nicht geladen werden. Prüfe die Internetverbindung und versuche es erneut.",
    val unavailable: Boolean = false,
) : Exception(message)

internal interface SharedBinderGateway {
    suspend fun load(token: String, page: Int): SharedBinderPage
    suspend fun image(url: String): ByteArray
}

internal data class SharedBinderResponse(val status: Int, val bytes: ByteArray)
internal fun interface SharedBinderTransport {
    suspend fun get(url: String, maxBytes: Int, image: Boolean): SharedBinderResponse
}

internal object SharedBinderContract {
    const val MAX_PAGE_BYTES = 128 * 1024
    const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
    const val MAX_PAGE = 100_000
    private val json = Moshi.Builder().build().adapter(Any::class.java)
    private val id = Regex("[A-Za-z0-9._-]{1,120}")
    private val visualId = Regex("[a-z][a-z0-9_]{0,79}")
    private val imagePath = Regex("/[A-Za-z0-9/_.-]+\\.(webp|png|jpe?g)", RegexOption.IGNORE_CASE)

    fun url(token: String, page: Int): String {
        if (!isCanonicalSharedBinderToken(token) || page !in 0..MAX_PAGE) throw unavailable()
        return "https://domidex.web.app/api/shared-binders/$token?page=$page"
    }

    fun imageUrl(value: String?): String? {
        if (value == null || value.length > 512) return null
        return try {
            val uri = URI(value)
            value.takeIf {
                uri.scheme == "https" && uri.rawAuthority == "assets.tcgdex.net" && uri.port == -1 &&
                    uri.rawQuery == null && uri.rawFragment == null && uri.rawUserInfo == null &&
                    imagePath.matches(uri.rawPath.orEmpty()) && uri.rawPath.split('/').none { it == "." || it == ".." }
            }
        } catch (_: Exception) { null }
    }

    fun parse(bytes: ByteArray, expectedPage: Int): SharedBinderPage {
        if (bytes.size > MAX_PAGE_BYTES || expectedPage !in 0..MAX_PAGE) throw invalid()
        try {
            val root = json.fromJson(bytes.toString(Charsets.UTF_8)) as? Map<*, *> ?: throw invalid()
            val page = root.integer("page", 0, MAX_PAGE)
            val pageCount = root.integer("pageCount", 1, MAX_PAGE + 1)
            if (page != expectedPage || page >= pageCount) throw invalid()
            val firstSlot = page * 9
            val rawCards = root["cards"] as? List<*> ?: throw invalid()
            val rawUnavailable = root["unavailableSlots"] as? List<*> ?: throw invalid()
            if (rawCards.size > 9 || rawUnavailable.size > 9) throw invalid()
            val cards = rawCards.map { value ->
                val card = value as? Map<*, *> ?: throw invalid()
                val cardId = card.string("cardId", 120).takeIf(id::matches) ?: throw invalid()
                val intensity = (card["holoIntensity"] as? Number)?.toDouble()
                    ?.takeIf { it.isFinite() && it in 0.0..1.0 } ?: throw invalid()
                val price = if (card.containsKey("price")) {
                    val price = card["price"] as? Map<*, *> ?: throw invalid()
                    if (price["currency"] != "EUR" || price["source"] != "Cardmarket") throw invalid()
                    SharedBinderPrice(price.amount(), price.optionalString("asOf", 80))
                } else null
                SharedBinderCard(
                    card.integer("slot", firstSlot, firstSlot + 8), cardId,
                    card.string("name", 160), card.string("setName", 160), card.string("collectorNumber", 40),
                    imageUrl(card.optionalString("image", 512)), card.string("finish", 120),
                    card.string("holoEffectId", 80).takeIf(visualId::matches) ?: "none", intensity.toFloat(), price,
                )
            }
            val unavailable = rawUnavailable.map { integer(it, firstSlot, firstSlot + 8) }
            val allSlots = cards.map { it.slot } + unavailable
            if (allSlots.distinct().size != allSlots.size) throw invalid()
            val total = if (root.containsKey("total")) {
                val total = root["total"] as? Map<*, *> ?: throw invalid()
                if (total["currency"] != "EUR") throw invalid()
                val complete = total["complete"] as? Boolean ?: throw invalid()
                val priced = total.integer("pricedCards", 0, 5000)
                val visible = total.integer("visibleCards", 0, 5000)
                val amount = total.amount()
                if (priced > visible || complete != (priced == visible) || complete != (amount != null)) throw invalid()
                SharedBinderTotal(amount, complete, priced, visible)
            } else null
            return SharedBinderPage(
                root.string("title", 120), root.string("coverStyle", 80).takeIf(visualId::matches) ?: "nebula",
                page, pageCount, cards, unavailable.toSet(), root.optionalString("displayName", 60), total,
            )
        } catch (failure: SharedBinderReadException) { throw failure }
        catch (_: Exception) { throw invalid() }
    }

    fun readBounded(input: InputStream, length: Long, limit: Int): ByteArray {
        if (length > limit) throw invalid()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > limit) throw invalid()
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    fun unavailable() = SharedBinderReadException("Diese Freigabe ist nicht verfügbar oder wurde beendet.", unavailable = true)
    private fun invalid() = SharedBinderReadException("Der Binder konnte nicht sicher gelesen werden. Bitte erneut versuchen.")
    private fun Map<*, *>.string(key: String, max: Int): String = (this[key] as? String)
        ?.takeIf { it.isNotBlank() && it.length <= max && it.none { char -> char.code < 32 } } ?: throw invalid()
    private fun Map<*, *>.optionalString(key: String, max: Int): String? = if (this[key] == null) null else string(key, max)
    private fun Map<*, *>.integer(key: String, min: Int, max: Int) = integer(this[key], min, max)
    private fun integer(value: Any?, min: Int, max: Int): Int {
        val number = (value as? Number)?.toDouble() ?: throw invalid()
        if (!number.isFinite() || number % 1.0 != 0.0 || number < min || number > max) throw invalid()
        return number.toInt()
    }
    private fun Map<*, *>.amount(): Double? = if (containsKey("amount") && this["amount"] == null) null
        else (this["amount"] as? Number)?.toDouble()?.takeIf { it.isFinite() && it >= 0.0 && it <= 1_000_000_000.0 } ?: throw invalid()
}

/** Unauthenticated, uncached GETs only; the capability token never enters app storage or logs. */
internal class PublicSharedBinderClient(
    private val transport: SharedBinderTransport = PublicSharedBinderTransport,
    private val timeoutMillis: Long = 15_000,
) : SharedBinderGateway {
    private val images = Semaphore(3)
    override suspend fun load(token: String, page: Int): SharedBinderPage = safeRequest {
        val response = transport.get(SharedBinderContract.url(token, page), SharedBinderContract.MAX_PAGE_BYTES, false)
        if (response.status == 404 || response.status == 410) throw SharedBinderContract.unavailable()
        if (response.status != 200) throw SharedBinderReadException()
        SharedBinderContract.parse(response.bytes, page)
    }

    override suspend fun image(url: String): ByteArray = images.withPermit {
        safeRequest {
            val safeUrl = SharedBinderContract.imageUrl(url) ?: throw SharedBinderReadException()
            val response = transport.get(safeUrl, SharedBinderContract.MAX_IMAGE_BYTES, true)
            if (response.status != 200 || response.bytes.size > SharedBinderContract.MAX_IMAGE_BYTES) throw SharedBinderReadException()
            response.bytes
        }
    }

    private suspend fun <T> safeRequest(block: suspend () -> T): T = try {
        withTimeout(timeoutMillis) { block() }
    } catch (_: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        throw SharedBinderReadException()
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: SharedBinderReadException) { throw failure }
    catch (_: Exception) { throw SharedBinderReadException() }
}

private object PublicSharedBinderTransport : SharedBinderTransport {
    private val client = OkHttpClient.Builder().cache(null)
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS).build()

    override suspend fun get(url: String, maxBytes: Int, image: Boolean): SharedBinderResponse {
        val request = Request.Builder().url(url).get()
            .header("Accept", if (image) "image/webp,image/png,image/jpeg" else "application/json")
            .header("Cache-Control", "no-store").build()
        return client.newCall(request).awaitPublicResponse(maxBytes, image)
    }
}

private suspend fun Call.awaitPublicResponse(maxBytes: Int, image: Boolean): SharedBinderResponse = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: java.io.IOException) {
            if (continuation.isActive) continuation.resumeWithException(SharedBinderReadException())
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use {
                    if (!continuation.isActive) return
                    val bytes = if (response.code == 200) {
                        val body = response.body ?: throw SharedBinderReadException()
                        val type = body.contentType()
                        if (image) {
                            if (type?.type != "image" || type.subtype !in setOf("webp", "png", "jpeg")) throw SharedBinderReadException()
                        } else if (type?.type != "application" || type.subtype != "json") throw SharedBinderReadException()
                        body.byteStream().use { SharedBinderContract.readBounded(it, body.contentLength(), maxBytes) }
                    } else byteArrayOf()
                    if (continuation.isActive) continuation.resumeWith(Result.success(SharedBinderResponse(response.code, bytes)))
                }
            } catch (_: Exception) {
                if (continuation.isActive) continuation.resumeWithException(SharedBinderReadException())
            }
        }
    })
}
