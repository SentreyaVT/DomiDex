package com.dominik.cardvault.data.storage

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.dominik.cardvault.auth.canUseFirebaseAppBackend
import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.dominik.cardvault.auth.currentFirebaseEntitlementAccount
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal const val MAX_STORAGE_IMAGE_BYTES: Long = 10L * 1024L * 1024L

internal data class ValidatedStorageImage(
    val contentType: String,
    val extension: String,
    val sha256: String,
)

internal enum class StorageFailureReason {
    NOT_SIGNED_IN,
    FILE_MISSING,
    EMPTY_FILE,
    FILE_TOO_LARGE,
    UNSUPPORTED_IMAGE,
    INVALID_PATH,
    PERMISSION_DENIED,
    NETWORK,
    UNKNOWN,
}

internal sealed interface StorageUploadResult {
    data class Uploaded(val receipt: CatalogContributionReceipt, val alreadyPresent: Boolean) : StorageUploadResult
    data class Failed(val reason: StorageFailureReason) : StorageUploadResult
}

internal data class CatalogContributionReceipt(
    val contentHash: String,
    val extension: String,
)

/** Stable paths are the only source of Storage object names in the Android client. */
internal object DomiDexStoragePaths {
    private val safeSegment = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")

    fun privateImage(uid: String, objectKey: String, extension: String): String? {
        val segments = objectKey.split('/').filter(String::isNotBlank)
        if (!safeUid(uid) || segments.isEmpty() || segments.size > 8 || segments.any { !safeSegment.matches(it) }) return null
        if (extension !in setOf("jpg", "png", "webp")) return null
        return "users/$uid/${segments.joinToString("/")}.$extension"
    }

    fun catalogContribution(uid: String, image: ValidatedStorageImage): String? {
        if (!safeUid(uid) || !image.sha256.matches(Regex("[a-f0-9]{64}"))) return null
        return "catalog-contributions/$uid/${image.sha256}/card.${image.extension}"
    }

    private fun safeUid(uid: String): Boolean = uid.isNotBlank() && uid.length <= 128 &&
        uid.none { it == '/' || it == '\\' || it.isWhitespace() }
}

/**
 * Small client-side guard before Firebase Security Rules run. Rules validate size/MIME again;
 * a future moderation backend must still inspect decoded bytes because clients are untrusted.
 */
internal object StorageImagePolicy {
    suspend fun validate(file: File): Result<ValidatedStorageImage> = withContext(Dispatchers.IO) {
        runCatching {
            require(file.isFile) { StorageFailureReason.FILE_MISSING.name }
            require(file.length() > 0L) { StorageFailureReason.EMPTY_FILE.name }
            require(file.length() <= MAX_STORAGE_IMAGE_BYTES) { StorageFailureReason.FILE_TOO_LARGE.name }
            val header = ByteArray(16)
            val count = FileInputStream(file).use { it.read(header) }
            val contentType = detectSupportedImageMime(header.copyOf(count.coerceAtLeast(0)))
                ?: error(StorageFailureReason.UNSUPPORTED_IMAGE.name)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0) { StorageFailureReason.UNSUPPORTED_IMAGE.name }
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read > 0) digest.update(buffer, 0, read)
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            ValidatedStorageImage(contentType, extensionFor(contentType), hash)
        }
    }

    private fun extensionFor(contentType: String): String = when (contentType) {
        "image/jpeg" -> "jpg"
        "image/png" -> "png"
        else -> "webp"
    }
}

internal fun detectSupportedImageMime(header: ByteArray): String? = when {
    header.size >= 3 &&
        header[0].toInt() and 0xFF == 0xFF &&
        header[1].toInt() and 0xFF == 0xD8 &&
        header[2].toInt() and 0xFF == 0xFF -> "image/jpeg"
    header.size >= 8 && header.copyOfRange(0, 8).contentEquals(
        byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
    ) -> "image/png"
    header.size >= 12 &&
        header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
        header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
    else -> null
}

/** Firebase Auth remains the single identity source; no email or provider token is stored here. */
internal class FirebaseDomiDexStorageGateway(
    context: Context,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val storage: FirebaseStorage = FirebaseStorage.getInstance(),
) {
    private val stagingDirectory = File(context.cacheDir, "firebase_storage_uploads")
    private val uploadMutex = Mutex()

    suspend fun uploadCatalogContribution(source: File, appVersion: String): StorageUploadResult = uploadMutex.withLock {
        val canonicalUid = canonicalCollectionOwnerUid(auth.currentUser)
            ?: return@withLock StorageUploadResult.Failed(StorageFailureReason.NOT_SIGNED_IN)
        val account = try {
            auth.currentFirebaseEntitlementAccount(forceRefresh = false)
        } catch (error: Throwable) {
            return@withLock StorageUploadResult.Failed(error.toStorageFailureReason())
        } ?: return@withLock StorageUploadResult.Failed(StorageFailureReason.NOT_SIGNED_IN)
        if (!canUseFirebaseAppBackend(account.uid, canonicalUid, account.entitlements)) {
            return@withLock StorageUploadResult.Failed(StorageFailureReason.PERMISSION_DENIED)
        }
        val uid = account.uid
        val validation = StorageImagePolicy.validate(source)
        val image = validation.getOrElse { error ->
            return@withLock StorageUploadResult.Failed(error.toStorageFailureReason())
        }
        val remotePath = DomiDexStoragePaths.catalogContribution(uid, image)
            ?: return@withLock StorageUploadResult.Failed(StorageFailureReason.INVALID_PATH)
        stagingDirectory.mkdirs()
        val staged = File(stagingDirectory, "${image.sha256}.${image.extension}")
        return@withLock try {
            withContext(Dispatchers.IO) {
                if (!staged.isFile || staged.length() != source.length()) source.copyTo(staged, overwrite = true)
            }
            val reference = storage.reference.child(remotePath)
            val alreadyPresent = try {
                reference.metadata.awaitStorageTask()
                true
            } catch (error: StorageException) {
                if (error.errorCode == StorageException.ERROR_OBJECT_NOT_FOUND) false else throw error
            }
            val receipt = CatalogContributionReceipt(image.sha256, image.extension)
            if (alreadyPresent) StorageUploadResult.Uploaded(receipt, alreadyPresent = true)
            else {
                val safeVersion = appVersion.filter { it.isLetterOrDigit() || it in ".-_" }.take(32).ifBlank { "unknown" }
                val metadata = StorageMetadata.Builder()
                    .setContentType(image.contentType)
                    .setCustomMetadata("ownerUid", uid)
                    .setCustomMetadata("reviewStatus", "PENDING_REVIEW")
                    .setCustomMetadata("contentSha256", image.sha256)
                    .setCustomMetadata("appVersion", safeVersion)
                    .build()
                reference.putFile(Uri.fromFile(staged), metadata).awaitStorageTask()
                StorageUploadResult.Uploaded(receipt, alreadyPresent = false)
            }
        } catch (error: Throwable) {
            Log.w(LOG_TAG, "Optionaler Katalogbild-Upload fehlgeschlagen (${error.javaClass.simpleName}).")
            StorageUploadResult.Failed(error.toStorageFailureReason())
        } finally {
            withContext(Dispatchers.IO) { staged.delete() }
        }
    }

    suspend fun deleteCatalogContribution(receipt: CatalogContributionReceipt): Boolean {
        val canonicalUid = canonicalCollectionOwnerUid(auth.currentUser) ?: return false
        val account = runCatching { auth.currentFirebaseEntitlementAccount(forceRefresh = false) }
            .getOrNull() ?: return false
        if (!canUseFirebaseAppBackend(account.uid, canonicalUid, account.entitlements)) return false
        val uid = account.uid
        val image = ValidatedStorageImage("image/${receipt.extension}", receipt.extension, receipt.contentHash)
        val remotePath = DomiDexStoragePaths.catalogContribution(uid, image) ?: return false
        return runCatching { storage.reference.child(remotePath).delete().awaitStorageTask(); true }
            .onFailure { error -> Log.w(LOG_TAG, "Katalogbild konnte nicht zurückgezogen werden (${error.javaClass.simpleName}).") }
            .getOrDefault(false)
    }

    private companion object { const val LOG_TAG = "DomiDexStorage" }
}

private fun Throwable.toStorageFailureReason(): StorageFailureReason {
    message?.let { message -> StorageFailureReason.entries.firstOrNull { it.name == message }?.let { return it } }
    val storageError = this as? StorageException
    return when (storageError?.errorCode) {
        StorageException.ERROR_NOT_AUTHENTICATED -> StorageFailureReason.NOT_SIGNED_IN
        StorageException.ERROR_NOT_AUTHORIZED -> StorageFailureReason.PERMISSION_DENIED
        StorageException.ERROR_RETRY_LIMIT_EXCEEDED -> StorageFailureReason.NETWORK
        else -> StorageFailureReason.UNKNOWN
    }
}

private suspend fun <T> Task<T>.awaitStorageTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        if (task.isSuccessful) continuation.resumeWith(Result.success(task.result))
        else continuation.resumeWithException(task.exception ?: IllegalStateException("Firebase Storage operation failed"))
    }
}
