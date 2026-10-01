package com.dominik.cardvault.data.feedback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import com.dominik.cardvault.security.DomiDexAppCheck
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/** One explicitly consented submission; no background job, alternate upload path or automatic retry. */
internal class FirebaseScannerReportUploader(@Suppress("UNUSED_PARAMETER") context: Context) {
    suspend fun upload(draft: ScannerReportDraft, expectedUid: String): ScannerReportReceipt =
        withContext(Dispatchers.IO) {
            try {
                val report = ScannerReportContract.payload(draft)
                val auth = FirebaseAuth.getInstance()
                requireSameAccount(auth, expectedUid)
                currentCoroutineContext().ensureActive()
                val image = prepareJpeg(draft.image)
                currentCoroutineContext().ensureActive()
                // Refresh after image preparation so the token and server-owned roles are current.
                val user = auth.currentUser ?: throw accountFailure()
                if (user.uid != expectedUid) throw accountFailure()
                requireSameAccount(auth, expectedUid)
                val result = try {
                    withTimeout(20_000) { user.getIdToken(true).awaitReportAuthTask() }
                } catch (_: TimeoutCancellationException) {
                    throw ScannerReportUploadException("Die Anmeldung konnte nicht rechtzeitig bestätigt werden. Bitte die Verbindung prüfen.")
                }
                currentCoroutineContext().ensureActive()
                if (!ScannerReportContract.allowedAccount(expectedUid, auth.currentUser?.uid, result.claims)) {
                    throw ScannerReportUploadException("Zum Senden ist dasselbe angemeldete Administrator- oder Pretesterkonto erforderlich.")
                }
                val token = result.token?.takeIf(String::isNotBlank)
                    ?: throw ScannerReportUploadException("Die Anmeldung konnte nicht bestätigt werden. Bitte erneut anmelden.")
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("report", report)
                    .addFormDataPart("image", "capture.jpg", image.toRequestBody("image/jpeg".toMediaType()))
                    .build()
                val request = Request.Builder()
                    .url(ScannerReportContract.ENDPOINT)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                    .header("Cache-Control", "no-store")
                    // One-shot also blocks response-driven follow-ups such as 503/Retry-After: 0.
                    .post(object : RequestBody() {
                        override fun contentType() = body.contentType()
                        override fun contentLength() = body.contentLength()
                        override fun isOneShot() = true
                        override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                    })
                    .build()
                requireSameAccount(auth, expectedUid)
                currentCoroutineContext().ensureActive()
                client.newCall(DomiDexAppCheck.attachCachedHeader(request)).awaitReportReceipt(auth, expectedUid)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: ScannerReportUploadException) {
                throw failure
            } catch (_: Exception) {
                throw ScannerReportUploadException("Der Fehlscan konnte nicht zum Senden vorbereitet werden. Bitte Anmeldung und Verbindung prüfen.")
            }
        }

    private companion object {
        val client = OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

/** Decode/re-encode copies only pixels; no EXIF, GPS, source filename or original bytes are sent. */
private suspend fun prepareJpeg(source: File): ByteArray {
    if (!source.isFile || source.length() <= 0L) {
        throw ScannerReportUploadException("Das Fehlscan-Bild ist nicht mehr verfügbar. Bitte erneut manuell aufnehmen.")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(source.absolutePath, bounds)
    val sample = ScannerReportContract.decodeSample(bounds.outWidth, bounds.outHeight)
    currentCoroutineContext().ensureActive()
    var bitmap: Bitmap? = null
    try {
        bitmap = BitmapFactory.decodeFile(source.absolutePath, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }) ?: throw ScannerReportUploadException("Das Fehlscan-Bild kann nicht gelesen werden.")
        if (maxOf(bitmap.width, bitmap.height) > 2048) {
            val scale = 2048.0 / maxOf(bitmap.width, bitmap.height)
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        currentCoroutineContext().ensureActive()
        val orientation = try {
            ExifInterface(source.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        } catch (_: IOException) {
            ExifInterface.ORIENTATION_NORMAL
        }
        val matrix = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> { setRotate(180f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
        }
        if (!matrix.isIdentity) {
            val oriented = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (oriented !== bitmap) bitmap.recycle()
            bitmap = oriented
        }
        currentCoroutineContext().ensureActive()
        val output = LimitedJpegOutput()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output) || output.size() <= 0) {
            throw ScannerReportUploadException("Das Fehlscan-Bild konnte nicht für den Upload vorbereitet werden.")
        }
        currentCoroutineContext().ensureActive()
        return output.toByteArray()
    } finally {
        bitmap?.recycle()
    }
}

private class LimitedJpegOutput : ByteArrayOutputStream() {
    override fun write(value: Int) {
        checkCapacity(1)
        super.write(value)
    }

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        checkCapacity(length)
        super.write(buffer, offset, length)
    }

    private fun checkCapacity(added: Int) {
        if (size().toLong() + added > ScannerReportContract.MAX_IMAGE_BYTES) {
            throw ScannerReportUploadException("Das Fehlscan-Bild überschreitet die Uploadgrenze von 10 MB.")
        }
    }
}

private suspend fun Call.awaitReportReceipt(auth: FirebaseAuth, expectedUid: String): ScannerReportReceipt =
    suspendCancellableCoroutine { continuation ->
        val requestStarted = AtomicBoolean(false)
        val listener = FirebaseAuth.AuthStateListener { current ->
            if (current.currentUser?.uid != expectedUid) cancel()
        }
        auth.addAuthStateListener(listener)
        continuation.invokeOnCancellation {
            cancel()
            auth.removeAuthStateListener(listener)
        }
        try {
            requireSameAccount(auth, expectedUid)
            if (!continuation.isActive) {
                auth.removeAuthStateListener(listener)
                return@suspendCancellableCoroutine
            }
            requestStarted.set(true)
            enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    auth.removeAuthStateListener(listener)
                    if (continuation.isActive) continuation.resumeWithException(ScannerReportContract.unknownOutcome())
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use {
                            if (!continuation.isActive) return
                            val receipt = if (response.code != 201) {
                                ScannerReportContract.parseReceipt(response.code, "")
                            } else {
                                val bytes = response.body?.byteStream()?.use { input ->
                                    val output = ByteArrayOutputStream()
                                    val buffer = ByteArray(1024)
                                    while (true) {
                                        val count = input.read(buffer)
                                        if (count < 0) break
                                        if (output.size() + count > ScannerReportContract.MAX_RESPONSE_BYTES) {
                                            throw ScannerReportContract.unknownOutcome()
                                        }
                                        output.write(buffer, 0, count)
                                    }
                                    output.toByteArray()
                                } ?: throw ScannerReportContract.unknownOutcome()
                                ScannerReportContract.parseReceipt(response.code, bytes.toString(Charsets.UTF_8))
                            }
                            if (auth.currentUser?.uid != expectedUid) throw ScannerReportContract.unknownOutcome()
                            if (continuation.isActive) continuation.resumeWith(Result.success(receipt))
                        }
                    } catch (failure: ScannerReportUploadException) {
                        if (continuation.isActive) continuation.resumeWithException(failure)
                    } catch (_: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(ScannerReportContract.unknownOutcome())
                    } finally {
                        auth.removeAuthStateListener(listener)
                    }
                }
            })
        } catch (_: Exception) {
            auth.removeAuthStateListener(listener)
            cancel()
            if (continuation.isActive) continuation.resumeWithException(
                if (requestStarted.get()) ScannerReportContract.unknownOutcome() else accountFailure(),
            )
        }
    }

private fun requireSameAccount(auth: FirebaseAuth, expectedUid: String) {
    if (expectedUid.isBlank() || auth.currentUser?.uid != expectedUid) throw accountFailure()
}

private fun accountFailure() = ScannerReportUploadException("Das angemeldete Konto hat sich geändert. Bitte die Aufnahme mit dem aktuellen Konto erneut öffnen.")

private suspend fun <T> Task<T>.awaitReportAuthTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (!continuation.isActive) return@addOnCompleteListener
        when {
            task.isCanceled -> continuation.cancel()
            task.isSuccessful -> continuation.resumeWith(Result.success(task.result))
            else -> continuation.resumeWithException(
                ScannerReportUploadException("Die Anmeldung konnte nicht bestätigt werden. Bitte Anmeldung und Verbindung prüfen."),
            )
        }
    }
}
