package com.dominik.cardvault.ui

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.CameraSelector
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.view.PreviewView
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.core.content.ContextCompat
import com.dominik.cardvault.R
import com.dominik.cardvault.ui.theme.*
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

internal object LatestCardCapture {
    var path by mutableStateOf<String?>(null)
}

/** A short, low-amplitude two-tone confirmation with an envelope that avoids click transients. */
internal fun buildSoftConfirmationPcm(sampleRate: Int = 16_000): ShortArray {
    val toneMillis = 82
    val gapMillis = 78
    val tailMillis = 18
    val toneSamples = sampleRate * toneMillis / 1_000
    val gapSamples = sampleRate * gapMillis / 1_000
    val tailSamples = sampleRate * tailMillis / 1_000
    val output = ShortArray(toneSamples * 2 + gapSamples + tailSamples)
    fun writeTone(start: Int, frequency: Double) {
        val attack = (sampleRate * .018).toInt().coerceAtLeast(1)
        val release = (sampleRate * .026).toInt().coerceAtLeast(1)
        repeat(toneSamples) { index ->
            val envelope = minOf(
                1.0,
                index.toDouble() / attack,
                (toneSamples - 1 - index).coerceAtLeast(0).toDouble() / release,
            )
            val phase = 2.0 * Math.PI * frequency * index / sampleRate
            // A quiet fundamental plus a tiny second harmonic sounds warmer and less piercing
            // than Android's system shutter or notification tones.
            val wave = sin(phase) * .085 + sin(phase * 2.0) * .012
            output[start + index] = (Short.MAX_VALUE * envelope * wave).toInt().toShort()
        }
    }
    writeTone(0, 360.0)
    writeTone(toneSamples + gapSamples, 410.0)
    return output
}

private class SoftConfirmationSound {
    private val handler = Handler(Looper.getMainLooper())
    private val pcm = buildSoftConfirmationPcm()
    private var activeTrack: AudioTrack? = null

    fun play() {
        releaseTrack()
        runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(16_000)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * Short.SIZE_BYTES)
                .build()
        }.onSuccess { track ->
            activeTrack = track
            val written = track.write(pcm, 0, pcm.size)
            if (written > 0) {
                track.play()
                handler.postDelayed({ if (activeTrack === track) releaseTrack() }, 420L)
            } else releaseTrack()
        }
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        releaseTrack()
    }

    private fun releaseTrack() {
        activeTrack?.let { track ->
            runCatching { if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.stop() }
            track.release()
        }
        activeTrack = null
    }
}

private data class FrameQuality(
    val level: QualityLevel,
    val guidance: QualityGuidance,
    val exposure: Float,
    val sharpness: Float,
    val stability: Float,
    val coverage: Float,
    val perspective: Float,
    val contourStability: Float = 0f,
    val stableFrames: Int = 0,
    val corners: List<Pair<Float, Float>> = emptyList(),
    val glare: Float = 0f,
    val frameQualityScore: Float = 0f,
    val captureEligible: Boolean = false,
    val fingerprint: IntArray? = null,
    /** Aspect ratio of CameraX's current crop after applying ImageInfo rotation. */
    val previewAspectRatio: Float = 4f / 3f,
    /** Monotonic key: identical consecutive frames must still advance the auto-capture gate. */
    val frameSequence: Long = 0L,
)
internal enum class QualityLevel { RED, YELLOW, GREEN }
internal enum class QualityGuidance { MORE_LIGHT, LESS_GLARE, HOLD_STILL, FOCUS, FIND_CARD, MOVE_CLOSER, MOVE_AWAY, ALIGN_PARALLEL, READY }
internal enum class AutoScanState { SEARCHING, CARD_DETECTED, STABILIZING, READY, CAPTURING, READING, MATCHING, CONFIRMED, NOT_FOUND, AMBIGUOUS }
internal data class ScannerDebugSnapshot(
    val state: AutoScanState = AutoScanState.SEARCHING,
    val frameSharpness: Float = 0f,
    val frameStability: Float = 0f,
    val contourStability: Float = 0f,
    val numberConfidence: Float = 0f,
    val candidateCount: Int = 0,
    val finalConfidence: Int = 0,
    val reason: String = "",
    val raritySymbolColor: SymbolColor = SymbolColor.UNKNOWN,
    val raritySymbolConfidence: Float = 0f,
    val frameConfidence: Float = 0f,
    val geometryScore: Float = 0f,
    val perspectiveScore: Float = 0f,
    val frameQualityScore: Float = 0f,
    val glarePercentage: Float = 0f,
    val secondBestConfidence: Int = 0,
    val decision: String = "",
    val artworkConfidence: Float = 0f,
    val artworkMatch: String = "",
    val visualScore: Int = 0,
    val visualFullCardScore: Int = 0,
    val visualArtworkScore: Int = 0,
    val numberScore: Int = 0,
    val collectorNumberScore: Int = 0,
    val setScore: Int = 0,
    val nameScore: Int = 0,
    val languageScore: Int = 0,
    val processingMillis: Long = 0L,
    val scoreMargin: Int = 0,
    val contradictionFlags: String = "",
    val usedFallbackGeometry: Boolean = false,
    val usedDynamicOcr: Boolean = false,
    val usedMultiFrameEvidence: Boolean = false,
    val stageTimings: String = "",
    val scanId: String = "",
)
/** Volatile diagnostic data only; no card, image, or account data is retained here. */
internal object ScannerDebugState { var snapshot by mutableStateOf(ScannerDebugSnapshot()) }

@Composable fun LiveCameraScanner(
    modifier: Modifier = Modifier,
    autoCaptureAllowed: Boolean = true,
    manualCaptureAllowed: Boolean = true,
    onCaptureStarted: (automatic: Boolean) -> Unit = {},
    onCaptureSaved: (File, Boolean, List<File>, automatic: Boolean) -> Unit = { _, _, _, _ -> },
    onCaptureFailed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val confirmationSound = remember { SoftConfirmationSound() }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val captureTimeoutHandler = remember { Handler(Looper.getMainLooper()) }
    var quality by remember { mutableStateOf(FrameQuality(QualityLevel.RED, QualityGuidance.HOLD_STILL, 0f, 0f, 0f, 0f, 0f)) }
    var captureMessage by remember { mutableStateOf<Int?>(null) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    // Opt-in for this camera session only; reopening/restoring the scanner starts manually.
    var autoCaptureEnabled by remember { mutableStateOf(false) }
    var captureInProgress by remember { mutableStateOf(false) }
    var activeCaptureId by remember { mutableLongStateOf(0L) }
    var latestCapture by remember { mutableStateOf<File?>(null) }
    val cameraManager = remember(context) { context.getSystemService(CameraManager::class.java) }
    val torchCameraId = remember(cameraManager) { cameraManager.cameraIdList.firstOrNull { id -> cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
    val maxTorchStrength = remember(cameraManager, torchCameraId) { if (Build.VERSION.SDK_INT >= 33 && torchCameraId != null) cameraManager.getCameraCharacteristics(torchCameraId).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 else 1 }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var torchStrength by rememberSaveable { mutableIntStateOf(1) }
    val autoCaptureGate = remember { AutoCaptureGate(missingFramesToRearm = 3, readyFramesToCapture = 2) }
    val previewView = remember(context) {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FIT_CENTER }
    }
    val camera = remember(context, previewView, cameraManager) {
        PortraitScannerCamera(context, previewView, selectBestScannerCamera(cameraManager))
    }
    DisposableEffect(camera, lifecycleOwner) {
        // Lifecycle-owner changes dispose this binding. Its replacement needs a live executor,
        // not the remembered instance that the previous binding has already shut down.
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val analyzer = FrameQualityAnalyzer { quality = it }
        camera.bindToLifecycle(lifecycleOwner, analysisExecutor, analyzer) {
            captureMessage = R.string.capture_failed
        }
        onDispose {
            camera.unbind()
            // Release the reusable native OpenCV buffers only after any in-flight
            // analyzer call has completed on the same single-thread executor.
            runCatching { analysisExecutor.execute(analyzer::close) }
            analysisExecutor.shutdown()
        }
    }
    DisposableEffect(confirmationSound, cameraManager, torchCameraId) { onDispose { confirmationSound.release(); captureTimeoutHandler.removeCallbacksAndMessages(null); torchCameraId?.let { runCatching { cameraManager.setTorchMode(it, false) } } } }
    fun setTorch(enabled: Boolean, strength: Int = torchStrength) {
        // CameraX controls the lens actually bound to the preview; CameraManager can target a
        // different physical lens on multi-camera devices.
        val future = camera.cameraControl?.enableTorch(enabled) ?: return
        future.addListener({ runCatching { future.get() }.onSuccess {
            torchEnabled = enabled; torchStrength = strength.coerceIn(1, maxTorchStrength)
        } }, mainExecutor)
    }
    fun capture(automatic: Boolean = false) {
        if (captureInProgress || (automatic && !autoCaptureAllowed) || (!automatic && !manualCaptureAllowed)) return
        captureInProgress = true; captureMessage = null
        onCaptureStarted(automatic)
        val captureId = System.nanoTime()
        val captureStartedAt = SystemClock.elapsedRealtime()
        val contributionEligible = quality.level == QualityLevel.GREEN && quality.corners.size == 4
        activeCaptureId = captureId
        val files = mutableListOf<File>()
        // One full-resolution still reaches the queue substantially faster than the former
        // sequential two-shot burst and also prevents parallel OCR/native bitmap pressure.
        val frameCount = 1
        fun finishSuccessful() {
            if (activeCaptureId != captureId || files.isEmpty()) return
            activeCaptureId = 0L
            val primary = files.first()
            latestCapture = primary; captureInProgress = false
            LatestCardCapture.path = primary.absolutePath
            captureMessage = R.string.capture_saved
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            confirmationSound.play()
            captureTimeoutHandler.postDelayed({
                if (activeCaptureId == 0L && latestCapture == primary && captureMessage == R.string.capture_saved) {
                    captureMessage = null
                }
            }, 1_050L)
            ScannerDiagnosticLog.event(
                event = "CAPTURE_SAVED",
                scanId = primary.name,
                "captureTimestamp" to System.currentTimeMillis(),
                "captureLatencyMs" to (SystemClock.elapsedRealtime() - captureStartedAt),
                "frameCount" to files.size,
                "auto" to automatic,
                "selectedBestFrame" to 0,
                "sharpnessScore" to quality.sharpness,
                "glarePercentage" to quality.glare,
                "clippingPercentage" to quality.glare,
                "motionScore" to (1f - quality.stability),
                "cardCoverage" to quality.coverage,
                "perspectiveScore" to quality.perspective,
                "cornerConfidence" to quality.contourStability,
                "frameQualityScore" to quality.frameQualityScore,
            )
            onCaptureSaved(primary, contributionEligible, files.drop(1), automatic)
        }
        fun failCapture() {
            if (activeCaptureId != captureId) return
            activeCaptureId = 0L; captureInProgress = false; captureMessage = R.string.capture_failed
            ScannerDiagnosticLog.event(
                event = "CAPTURE_FAILED",
                scanId = "capture-$captureId",
                "captureLatencyMs" to (SystemClock.elapsedRealtime() - captureStartedAt),
                "savedFrames" to files.size,
            )
            files.forEach(File::delete); onCaptureFailed()
        }
        captureTimeoutHandler.postDelayed({
            if (activeCaptureId == captureId && captureInProgress) {
                failCapture()
            }
        }, 7_000L)
        fun takeFrame(index: Int) {
            if (activeCaptureId != captureId) return
            val file = File(context.cacheDir, "cardvault-capture-${captureId}-${index}.jpg")
            try {
                camera.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (activeCaptureId != captureId) { file.delete(); return }
                        files += file
                        if (index + 1 < frameCount) captureTimeoutHandler.postDelayed({ takeFrame(index + 1) }, 140L)
                        else finishSuccessful()
                    }
                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        // One high-quality frame remains a truthful fallback when a short temporal
                        // burst is interrupted by CameraX; a missing first frame is a real failure.
                        if (files.isNotEmpty()) finishSuccessful() else failCapture()
                    }
                })
            } catch (_: IllegalStateException) {
                file.delete()
                if (files.isNotEmpty()) finishSuccessful() else failCapture()
            }
        }
        takeFrame(0)
    }
    LaunchedEffect(autoCaptureEnabled) {
        if (autoCaptureEnabled) autoCaptureGate.arm()
        else autoCaptureGate.disarm(quality.fingerprint)
    }
    LaunchedEffect(autoCaptureAllowed) {
        if (!autoCaptureAllowed) autoCaptureGate.disarm(quality.fingerprint)
    }
    LaunchedEffect(
        autoCaptureEnabled,
        autoCaptureAllowed,
        captureInProgress,
        quality.level,
        quality.frameSequence,
        quality.fingerprint?.contentHashCode(),
    ) {
        if (
            autoCaptureEnabled &&
            autoCaptureAllowed &&
            !captureInProgress &&
            autoCaptureGate.onFrame(quality.level == QualityLevel.GREEN, quality.fingerprint)
        ) capture(automatic = true)
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.matchParentSize()) {
            // Keep the full scene visible. Detected corners are analysis data only, never a
            // moving outline, shaded cutout or extra capture gate in the camera preview.
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            ScannerCaptureStatus(
                qualityLevel = quality.level,
                captureMessage = captureMessage,
                captureInProgress = captureInProgress,
                autoCaptureEnabled = autoCaptureEnabled,
                autoCaptureAllowed = autoCaptureAllowed,
                manualCaptureAllowed = manualCaptureAllowed,
                modifier = Modifier.align(Alignment.TopCenter).padding(horizontal = 16.dp).padding(top = 76.dp),
            )
        }
        Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(16.dp), color = VaultGlassHigh, shape = RoundedCornerShape(24.dp), border = BorderStroke(1.dp, VaultOutlineSoft), shadowElevation = 10.dp) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (detailsExpanded) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QualityMetric("Licht", quality.exposure, Modifier.width(60.dp)); QualityMetric("Schärfe", quality.sharpness, Modifier.width(60.dp)); QualityMetric("Karte", quality.coverage, Modifier.width(60.dp))
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceAround) {
                    TextButton(onClick = { setTorch(!torchEnabled) }, enabled = torchCameraId != null) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            DomiLineIcon(DomiIconKind.LIGHTNING, if (torchEnabled) MewIce else VaultTextMuted, Modifier.size(17.dp))
                            Text(if (torchEnabled) "Blitz an" else "Blitz", color = if (torchEnabled) MewIce else VaultTextMuted, fontSize = 9.sp)
                        }
                    }
                    TextButton(onClick = { autoCaptureEnabled = !autoCaptureEnabled }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            DomiLineIcon(DomiIconKind.CHECK, if (autoCaptureEnabled) VaultPositive else VaultTextMuted, Modifier.size(18.dp))
                            Text(if (autoCaptureEnabled) "Auto an" else "Auto aus", color = if (autoCaptureEnabled) VaultPositive else VaultTextMuted, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    Button(
                        onClick = {
                            autoCaptureGate.disarm(quality.fingerprint)
                            capture(automatic = false)
                        },
                        enabled = !captureInProgress && manualCaptureAllowed,
                        modifier = Modifier
                            .size(58.dp)
                            .semantics { contentDescription = "Foto aufnehmen" },
                        shape = RoundedCornerShape(29.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MewPlumDeep, contentColor = VaultText),
                        border = BorderStroke(2.dp, MewRose),
                    ) { if (captureInProgress) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp) else DomiLineIcon(DomiIconKind.SCAN, VaultText, Modifier.size(25.dp)) }
                    TextButton(onClick = { detailsExpanded = !detailsExpanded }) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            DomiLineIcon(DomiIconKind.MORE, if (detailsExpanded) MewRose else VaultTextMuted, Modifier.size(17.dp))
                            Text(if (detailsExpanded) "Weniger" else "Details", color = VaultTextMuted, fontSize = 9.sp)
                        }
                    }
                }
            }
        }
    }
}

/** Stationary feedback only: readiness never moves a frame or changes capture/analysis policy. */
@Composable
internal fun ScannerCaptureStatus(
    qualityLevel: QualityLevel,
    captureMessage: Int?,
    captureInProgress: Boolean,
    autoCaptureEnabled: Boolean,
    autoCaptureAllowed: Boolean,
    manualCaptureAllowed: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var settledReady by remember { mutableStateOf(false) }
    LaunchedEffect(qualityLevel) {
        settledReady = false
        if (qualityLevel == QualityLevel.GREEN) {
            // Ignore brief green flashes in the UI only. Manual/auto capture remain immediate.
            delay(180)
            settledReady = true
        }
    }
    val ready = qualityLevel == QualityLevel.GREEN && settledReady
    val saved = captureMessage == R.string.capture_saved
    val failed = captureMessage == R.string.capture_failed
    val color by animateColorAsState(
        targetValue = when {
            failed -> MewRose
            saved || (ready && !captureInProgress) -> VaultPositive
            else -> VaultTextMuted
        },
        animationSpec = tween(180),
        label = "scanner-status-color",
    )
    // A permanent surface avoids enter/exit movement and keeps text at a fixed position.
    Surface(modifier = modifier.fillMaxWidth(), color = VaultNightRaised.copy(alpha = .96f), shape = RoundedCornerShape(16.dp)) {
        Text(
            text = when {
                captureMessage != null -> stringResource(captureMessage)
                captureInProgress -> "Aufnahme läuft …"
                !manualCaptureAllowed -> "Warteschlange voll · bitte ein Ergebnis bearbeiten"
                !autoCaptureAllowed && autoCaptureEnabled -> "Auto pausiert · manuell möglich"
                ready && autoCaptureEnabled -> "Bereit – automatische Aufnahme"
                ready -> "Bereit – Auslöser drücken"
                else -> "Karte ruhig halten"
            },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            color = color,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Orders CameraX candidates deterministically for card scanning.  CameraX receives the complete
 * ordered list, so a device whose preferred rear lens is temporarily unavailable can still fall
 * back safely instead of failing the scanner.  We deliberately never promote an ultrawide/front
 * lens over a normal rear camera simply because it has a larger advertised stream.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
internal fun selectBestScannerCamera(cameraManager: CameraManager): CameraSelector =
    CameraSelector.Builder().addCameraFilter { cameras ->
        val capabilities = cameras.associateWith { info ->
            val characteristics = runCatching { cameraManager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId) }.getOrNull()
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpeg = map?.getOutputSizes(ImageFormat.JPEG).orEmpty().maxOfOrNull { it.width.toLong() * it.height } ?: 0L
            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().maxOfOrNull { it.width.toLong() * it.height } ?: 0L
            val sensorSize = characteristics?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            val autofocusModes = characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES) ?: intArrayOf()
            val minimumFocusDistance = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE)
            ScannerCameraCapabilities(
                isRear = characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK,
                supportsAutoFocus = minimumFocusDistance != 0f && autofocusModes.any {
                    it == CameraCharacteristics.CONTROL_AF_MODE_AUTO ||
                        it == CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_PICTURE ||
                        it == CameraCharacteristics.CONTROL_AF_MODE_CONTINUOUS_VIDEO ||
                        it == CameraCharacteristics.CONTROL_AF_MODE_MACRO
                },
                focalLengthMm = characteristics?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull(),
                sensorWidthMm = sensorSize?.width,
                sensorHeightMm = sensorSize?.height,
                maxOutputPixels = maxOf(jpeg, yuv),
            )
        }
        cameras.sortedWith { left, right -> scannerCameraPreference.compare(capabilities.getValue(left), capabilities.getValue(right)) }
    }.build()

internal fun cardFingerprintDifference(first: IntArray, second: IntArray): Float {
    if (first.isEmpty() || first.size != second.size) return Float.POSITIVE_INFINITY
    val firstMean = first.average()
    val secondMean = second.average()
    return first.indices.sumOf { index ->
        abs((first[index] - firstMean) - (second[index] - secondMean))
    }.toFloat() / first.size
}

internal class AutoCaptureGate(
    private val missingFramesToRearm: Int = 3,
    private val readyFramesToCapture: Int = 2,
    private val minimumNewCardDifference: Float = 13f,
) {
    private var armed = true
    private var transitionFrames = 0
    private var readyFrames = 0
    private var capturedFingerprint: IntArray? = null
    private var confirmedRemoval = false

    fun onFrame(captureEligible: Boolean, fingerprint: IntArray? = null): Boolean {
        if (!armed) {
            // Overlay colour is deliberately irrelevant here. A replacement may remain a valid
            // contour while it is moved into view, but its normalized interior still has to differ
            // from the last capture so shaking/refocusing cannot create an automatic duplicate.
            val previous = capturedFingerprint
            val differentCard = when {
                fingerprint == null -> false
                previous == null -> true
                else -> cardFingerprintDifference(previous, fingerprint) >= minimumNewCardDifference
            }
            when {
                !captureEligible -> {
                    transitionFrames += 1
                    if (transitionFrames >= missingFramesToRearm) confirmedRemoval = true
                }
                confirmedRemoval -> {
                    armed = true
                    transitionFrames = 0
                    confirmedRemoval = false
                }
                differentCard -> {
                    transitionFrames += 1
                    if (transitionFrames >= missingFramesToRearm) {
                        armed = true
                        transitionFrames = 0
                    }
                }
                else -> transitionFrames = 0
            }
        }
        readyFrames = if (armed && captureEligible) readyFrames + 1 else 0
        if (readyFrames >= readyFramesToCapture) {
            armed = false
            readyFrames = 0
            transitionFrames = 0
            confirmedRemoval = false
            capturedFingerprint = fingerprint?.copyOf()
            return true
        }
        return false
    }
    fun arm() { armed = true; transitionFrames = 0; readyFrames = 0; confirmedRemoval = false; capturedFingerprint = null }
    fun disarm(fingerprint: IntArray? = null) {
        armed = false
        transitionFrames = 0
        readyFrames = 0
        confirmedRemoval = false
        if (fingerprint != null) capturedFingerprint = fingerprint.copyOf()
    }
}

/**
 * Keeps raw detector precision out of the preview animation. Small contour jitter is ignored,
 * larger movement advances in bounded steps, and READY locks the last guide until capture.
 * Detection/OCR always continue to use the unsmoothed source geometry.
 */
internal class OverlayCornerStabilizer(
    private val smoothing: Float = .30f,
    private val deadZone: Float = .004f,
    private val maxStep: Float = .028f,
    private val missingFramesToClear: Int = 5,
) {
    private var displayed = emptyList<Pair<Float, Float>>()
    private var missingFrames = 0

    fun update(corners: List<Pair<Float, Float>>, locked: Boolean): List<Pair<Float, Float>> {
        if (corners.size != 4) {
            missingFrames += 1
            if (missingFrames >= missingFramesToClear) displayed = emptyList()
            return displayed
        }
        missingFrames = 0
        if (displayed.size != 4) {
            displayed = corners.map { (x, y) -> x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f) }
            return displayed
        }
        if (locked) return displayed
        displayed = displayed.zip(corners) { old, target ->
            smoothCoordinate(old.first, target.first) to smoothCoordinate(old.second, target.second)
        }
        return displayed
    }

    private fun smoothCoordinate(old: Float, target: Float): Float {
        val delta = target.coerceIn(0f, 1f) - old
        if (abs(delta) <= deadZone) return old
        return (old + (delta * smoothing).coerceIn(-maxStep, maxStep)).coerceIn(0f, 1f)
    }
}

/** Keeps a tiny quality window so auto capture waits for a near-best stable holo frame. */
internal class FrameQualityWindow(private val capacity: Int = 4, private val tolerance: Float = .045f) {
    private val values = ArrayDeque<Float>()
    fun add(value: Float): Boolean {
        values.addLast(value.coerceIn(0f, 1f))
        while (values.size > capacity) values.removeFirst()
        return values.size >= 2 && value >= (values.maxOrNull() ?: value) - tolerance
    }
    fun clear() = values.clear()
    val hasTemporalEvidence: Boolean get() = values.size >= 2
}

@Composable private fun QualityMetric(label: String, value: Float, modifier: Modifier) = Column(modifier) {
    Text(label, color = VaultTextMuted, fontSize = 10.sp)
    LinearProgressIndicator(progress = { value.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp))
}

private val QualityGuidance.stringResource: Int get() = when (this) {
    QualityGuidance.MORE_LIGHT -> R.string.capture_move_to_light
    QualityGuidance.LESS_GLARE -> R.string.capture_reduce_glare
    QualityGuidance.HOLD_STILL -> R.string.capture_hold_still
    QualityGuidance.FOCUS -> R.string.capture_focus
    QualityGuidance.FIND_CARD -> R.string.capture_show_corners
    QualityGuidance.MOVE_CLOSER -> R.string.capture_move_closer
    QualityGuidance.MOVE_AWAY -> R.string.capture_move_away
    QualityGuidance.ALIGN_PARALLEL -> R.string.capture_align_parallel
    QualityGuidance.READY -> R.string.capture_ready
}

private class FrameQualityAnalyzer(private val onQuality: (FrameQuality) -> Unit) : ImageAnalysis.Analyzer, AutoCloseable {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val samples = IntArray(16 * 20)
    private val previous = IntArray(16 * 20)
    private var hasPrevious = false
    private var lastPublished = 0L
    private var publishedFrameSequence = 0L
    private var stableReadyFrames = 0
    private var trackedFrame: CardFrame? = null
    private var missedTrackedFrames = 0
    private var priorDetectedFrame: CardFrame? = null
    private val detector = CardFrameDetector()
    private val qualityWindow = FrameQualityWindow()
    private val overlayCorners = OverlayCornerStabilizer()

    override fun analyze(image: ImageProxy) {
        try {
            // Roughly 7 lightweight boundary checks per second feel immediate while CameraX
            // keeps every preview frame independent and discards stale analyzer input.
            val now = SystemClock.elapsedRealtime(); if (now - lastPublished < 140) return
            val plane = image.planes.first(); val buffer = plane.buffer; val width = image.width; val height = image.height
            val crop = image.cropRect
            val frameGeometry = CameraFrameGeometry(
                bufferWidth = width,
                bufferHeight = height,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropRight = crop.right,
                cropBottom = crop.bottom,
                rotationDegrees = image.imageInfo.rotationDegrees,
            )
            var index = 0; var bright = 0; var dark = 0; var total = 0.0; var detail = 0.0; var centerDetail = 0.0; var centerPairs = 0
            for (gy in 0 until 20) for (gx in 0 until 16) {
                val x = (frameGeometry.cropLeftPx + (gx + .5) * frameGeometry.cropWidthPx / 16).toInt().coerceIn(frameGeometry.cropLeftPx, frameGeometry.cropRightPx - 1)
                val y = (frameGeometry.cropTopPx + (gy + .5) * frameGeometry.cropHeightPx / 20).toInt().coerceIn(frameGeometry.cropTopPx, frameGeometry.cropBottomPx - 1)
                val offset = y * plane.rowStride + x * plane.pixelStride; val value = buffer.get(offset).toInt() and 0xFF
                samples[index] = value; total += value; if (value > 245) bright++; if (value < 20) dark++
                if (gx > 0) detail += abs(value - samples[index - 1])
                // A card held inside the guide has much more local structure than an
                // empty wall/desk. This is a conservative fallback when glare hides
                // one outer edge from OpenCV's four-corner contour.
                if (gx in 5..10 && gy in 6..13 && gx > 5) { centerDetail += abs(value - samples[index - 1]); centerPairs++ }
                index++
            }
            val mean = total / samples.size; val clippedBright = bright.toFloat() / samples.size; val clippedDark = dark.toFloat() / samples.size
            val motion = if (hasPrevious) samples.indices.sumOf { abs(samples[it] - previous[it]) }.toFloat() / samples.size else 100f
            samples.copyInto(previous); hasPrevious = true; lastPublished = now
            val exposureScore = (1f - (abs(mean - 128.0) / 128.0).toFloat()).coerceIn(0f, 1f)
            val sharpnessScore = (detail / (samples.size - 20) / 28.0).toFloat().coerceIn(0f, 1f)
            val centeredCardEvidence = (centerDetail / centerPairs.coerceAtLeast(1) / 24.0).toFloat().coerceIn(0f, 1f)
            val stabilityScore = (1f - motion / 32f).coerceIn(0f, 1f)
            val detectedFrame = detector.detect(buffer, width, height, plane.rowStride, plane.pixelStride, frameGeometry)
            val frame = track(detectedFrame)
            val contourStability = contourStability(detectedFrame)
            val glareQuality = (1f - clippedBright / .42f).coerceIn(0f, 1f)
            val frameQualityScore = (sharpnessScore * .26f + exposureScore * .16f + glareQuality * .18f + stabilityScore * .18f + detectedFrame.geometryScore * .14f + detectedFrame.perspective * .08f).coerceIn(0f, 1f)
            // Only a fresh, current frame may arm auto-capture. The short tracker
            // grace period is purely visual so the proposal does not flicker when a
            // hand, glare or a webcam frame briefly hides an otherwise found edge.
            // FIT_CENTER deliberately leaves more context around a physical card.
            // The former thresholds effectively required a close-up, so an otherwise
            // clearly visible card in the wide scanner view could never auto-capture.
            // Live preview answers one question only: is the complete, physical trading card
            // inside a plausible outer rectangle? OCR, variants and prices belong to the queue.
            // Inner artwork/text boxes are therefore never sufficient for a green capture state.
            val hasCardEvidence = isWholeTradingCardFrame(detectedFrame)
            val captureEligible = isCaptureEligibleTradingCardFrame(detectedFrame)
            val cardFingerprint = if (captureEligible) {
                sampleCardFingerprint(buffer, width, height, plane.rowStride, plane.pixelStride, detectedFrame.corners, frameGeometry)
            } else null
            // A textured background alone must never arm auto capture.  Centre
            // detail remains a guidance fallback, while capture requires an actual
            // stable four-corner proposal across consecutive analysis frames.
            val stableQuality = if (hasCardEvidence && stabilityScore >= .47f) qualityWindow.add(frameQualityScore) else { qualityWindow.clear(); false }
            val baseReady = mean in 45.0..225.0 && clippedBright <= .32f && clippedDark <= .48f && stabilityScore >= .47f && sharpnessScore >= .15f && contourStability >= .60f && frameQualityScore >= .52f && stableQuality && hasCardEvidence
            stableReadyFrames = if (baseReady) stableReadyFrames + 1 else 0
            val guidance = when { mean < 45 || clippedDark > .48f -> QualityGuidance.MORE_LIGHT; mean > 225 || clippedBright > .32f -> QualityGuidance.LESS_GLARE; stabilityScore < .47f || (frame.found && contourStability < .60f) -> QualityGuidance.HOLD_STILL; sharpnessScore < .15f -> QualityGuidance.FOCUS; !frame.found && centeredCardEvidence < .30f -> QualityGuidance.FIND_CARD; frame.found && frame.coverage < .15f -> QualityGuidance.MOVE_CLOSER; frame.found && frame.coverage > .96f -> QualityGuidance.MOVE_AWAY; frame.found && (frame.perspective < .56f || frame.aspectRatio !in .58f..0.84f) -> QualityGuidance.ALIGN_PARALLEL; stableReadyFrames >= 2 -> QualityGuidance.READY; else -> QualityGuidance.HOLD_STILL }
            val level = when { guidance == QualityGuidance.READY -> QualityLevel.GREEN; guidance in setOf(QualityGuidance.FIND_CARD, QualityGuidance.MOVE_CLOSER, QualityGuidance.MOVE_AWAY, QualityGuidance.ALIGN_PARALLEL) || baseReady -> QualityLevel.YELLOW; else -> QualityLevel.RED }
            val displayedCorners = overlayCorners.update(frame.corners, locked = level == QualityLevel.GREEN)
            publishedFrameSequence = nextPublishedFrameSequence(publishedFrameSequence)
            val frameSequence = publishedFrameSequence
            val scanState = when {
                !frame.found -> AutoScanState.SEARCHING
                guidance == QualityGuidance.READY -> AutoScanState.READY
                baseReady -> AutoScanState.STABILIZING
                else -> AutoScanState.CARD_DETECTED
            }
            mainHandler.post {
                ScannerDebugState.snapshot = ScannerDebugState.snapshot.copy(
                    state = scanState,
                    frameSharpness = sharpnessScore,
                    frameStability = stabilityScore,
                    contourStability = contourStability,
                    frameConfidence = frame.coverage.coerceIn(0f, 1f),
                    geometryScore = detectedFrame.geometryScore,
                    perspectiveScore = detectedFrame.perspective,
                    frameQualityScore = frameQualityScore,
                    glarePercentage = clippedBright,
                    usedFallbackGeometry = detectedFrame.usedFallbackGeometry,
                    usedMultiFrameEvidence = qualityWindow.hasTemporalEvidence,
                    reason = if (ScannerDebugState.snapshot.scanId.isBlank()) guidance.name else ScannerDebugState.snapshot.reason,
                )
                onQuality(FrameQuality(level, guidance, exposureScore, sharpnessScore, stabilityScore, frame.coverage.coerceIn(0f, 1f), frame.perspective.coerceIn(0f, 1f), contourStability, stableReadyFrames, displayedCorners, clippedBright, frameQualityScore, captureEligible, cardFingerprint, frameGeometry.previewAspectRatio, frameSequence))
            }
        } finally { image.close() }
    }

    override fun close() {
        mainHandler.removeCallbacksAndMessages(null)
        detector.close()
    }

    private fun sampleCardFingerprint(
        buffer: java.nio.ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
        corners: List<Pair<Float, Float>>,
        frameGeometry: CameraFrameGeometry,
    ): IntArray? {
        if (corners.size != 4) return null
        val ordered = corners.sortedBy { it.second }.let { vertical ->
            val top = vertical.take(2).sortedBy { it.first }
            val bottom = vertical.takeLast(2).sortedByDescending { it.first }
            top + bottom
        }
        val output = IntArray(6 * 8)
        var index = 0
        for (row in 0 until 8) {
            val v = (row + .5f) / 8f
            val leftX = ordered[0].first + (ordered[3].first - ordered[0].first) * v
            val leftY = ordered[0].second + (ordered[3].second - ordered[0].second) * v
            val rightX = ordered[1].first + (ordered[2].first - ordered[1].first) * v
            val rightY = ordered[1].second + (ordered[2].second - ordered[1].second) * v
            for (column in 0 until 6) {
                val u = (column + .5f) / 6f
                val previewX = leftX + (rightX - leftX) * u
                val previewY = leftY + (rightY - leftY) * u
                val (sourceX, sourceY) = frameGeometry.previewToSource(previewX, previewY)
                val x = (sourceX * (width - 1)).toInt().coerceIn(0, width - 1)
                val y = (sourceY * (height - 1)).toInt().coerceIn(0, height - 1)
                output[index++] = buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
            }
        }
        return output
    }

    private fun contourStability(current: CardFrame): Float {
        val previous = priorDetectedFrame
        priorDetectedFrame = current.takeIf { it.found && it.corners.size == 4 }
        if (previous?.corners?.size != 4 || current.corners.size != 4) return 0f
        val averageTravel = previous.corners.zip(current.corners).sumOf { (old, fresh) ->
            hypot((old.first - fresh.first).toDouble(), (old.second - fresh.second).toDouble())
        }.toFloat() / 4f
        return (1f - averageTravel / .045f).coerceIn(0f, 1f)
    }

    private fun track(detected: CardFrame): CardFrame {
        if (!detected.found) {
            missedTrackedFrames += 1
            return trackedFrame?.takeIf { missedTrackedFrames <= 2 } ?: detected
        }
        missedTrackedFrames = 0
        val previousFrame = trackedFrame
        val smoothed = if (previousFrame?.corners?.size == 4 && detected.corners.size == 4) {
            CardFrame(
                found = true,
                coverage = previousFrame.coverage * .60f + detected.coverage * .40f,
                perspective = previousFrame.perspective * .60f + detected.perspective * .40f,
                geometryScore = previousFrame.geometryScore * .60f + detected.geometryScore * .40f,
                aspectRatio = previousFrame.aspectRatio * .60f + detected.aspectRatio * .40f,
                usedFallbackGeometry = previousFrame.usedFallbackGeometry || detected.usedFallbackGeometry,
                portraitAligned = detected.portraitAligned,
                corners = previousFrame.corners.zip(detected.corners) { old, fresh ->
                    old.first * .60f + fresh.first * .40f to old.second * .60f + fresh.second * .40f
                },
            )
        } else detected
        trackedFrame = smoothed
        return smoothed
    }
}

internal data class CardFrame(
    val found: Boolean,
    val coverage: Float,
    val perspective: Float,
    val corners: List<Pair<Float, Float>> = emptyList(),
    val geometryScore: Float = 0f,
    val usedFallbackGeometry: Boolean = false,
    val aspectRatio: Float = 0f,
    val portraitAligned: Boolean = true,
)

/** Strict live gate for the outer 63:88 Pokémon card, never an internal artwork/text panel. */
internal fun isWholeTradingCardFrame(frame: CardFrame): Boolean =
    frame.found &&
        frame.portraitAligned &&
        frame.coverage in .15f..1.12f &&
        frame.aspectRatio in .58f..0.84f &&
        frame.geometryScore >= .56f &&
        frame.perspective >= .56f

/**
 * Coarse live-capture gate only. The overlay may remain yellow while this accepts a rotated,
 * offset or mildly distorted four-corner card proposal. The saved full-resolution image is still
 * re-detected, rectified and quality-checked by [CardImageNormalizer] before OCR/matching.
 */
internal fun isCaptureEligibleTradingCardFrame(frame: CardFrame): Boolean =
    frame.found &&
        frame.corners.size == 4 &&
        frame.coverage in .09f..1.38f &&
        frame.aspectRatio in .50f..0.92f &&
        frame.geometryScore >= .45f &&
        frame.perspective >= .32f

internal class CardFrameDetector(private val maxAnalysisEdge: Int = 600) : AutoCloseable {
    private val available = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)
    private var lumaBytes = ByteArray(0)
    private val gray = Mat()
    private val normalized = Mat()
    private val rawBlurred = Mat()
    private val blurred = Mat()
    private val edges = Mat()
    private val contrastEdges = Mat()
    private val adaptiveEdges = Mat()
    private val luminanceEdges = Mat()
    private val hierarchy = Mat()
    private val kernelDelegate = lazy { Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)) }
    private val kernel by kernelDelegate
    private val claheDelegate = lazy { Imgproc.createCLAHE(2.0, Size(8.0, 8.0)) }
    private val clahe by claheDelegate

    fun detect(
        buffer: java.nio.ByteBuffer,
        sourceWidth: Int,
        sourceHeight: Int,
        rowStride: Int,
        pixelStride: Int,
        frameGeometry: CameraFrameGeometry = CameraFrameGeometry(sourceWidth, sourceHeight),
    ): CardFrame {
        if (!available) return CardFrame(false, 0f, 0f)
        val croppedSourceWidth = frameGeometry.cropWidthPx
        val croppedSourceHeight = frameGeometry.cropHeightPx
        val scale = min(1f, maxAnalysisEdge.toFloat() / maxOf(croppedSourceWidth, croppedSourceHeight).coerceAtLeast(1))
        val width = (croppedSourceWidth * scale).toInt().coerceAtLeast(1)
        val height = (croppedSourceHeight * scale).toInt().coerceAtLeast(1)
        val requiredBytes = width * height
        if (lumaBytes.size != requiredBytes) lumaBytes = ByteArray(requiredBytes)
        var target = 0
        for (y in 0 until height) {
            val sourceY = frameGeometry.cropTopPx + (y * croppedSourceHeight / height).coerceAtMost(croppedSourceHeight - 1)
            for (x in 0 until width) {
                val sourceX = frameGeometry.cropLeftPx + (x * croppedSourceWidth / width).coerceAtMost(croppedSourceWidth - 1)
                lumaBytes[target++] = buffer.get(sourceY * rowStride + sourceX * pixelStride)
            }
        }
        val contours = ArrayList<MatOfPoint>()
        return try {
            gray.create(height, width, CvType.CV_8UC1)
            gray.put(0, 0, lumaBytes)
            Imgproc.GaussianBlur(gray, rawBlurred, Size(5.0, 5.0), 0.0)
            // Webcam feeds tend to have softer, lower-contrast card edges than phone cameras.
            // Combine a conventional edge pass with an adaptive local-contrast pass so a card
            // against a similarly bright desk can still propose its outer contour.
            Imgproc.Canny(rawBlurred, edges, 28.0, 98.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(edges, edges, kernel)
            val imageArea = width.toDouble() * height; var best: CardFrame? = null; var bestScore = 0f
            fun inspectContours(edgeMap: Mat, usedFallback: Boolean) {
                contours.clear()
                Imgproc.findContours(edgeMap, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                contours.forEach { contour ->
                    val area = Imgproc.contourArea(contour); if (area < imageArea * .018 || area > imageArea * .86) return@forEach
                    val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f(); val perimeter = Imgproc.arcLength(curve, true)
                    Imgproc.approxPolyDP(curve, approx, perimeter * .028, true)
                    val points = approx.toArray()
                    val isConvexQuadrilateral = if (points.size == 4) {
                        val polygon = MatOfPoint(*points)
                        try { Imgproc.isContourConvex(polygon) } finally { polygon.release() }
                    } else false
                    if (isConvexQuadrilateral) {
                    val ordered = points.sortedBy { atan2(it.y - points.map { p -> p.y }.average(), it.x - points.map { p -> p.x }.average()) }
                    val lengths = ordered.indices.map { i -> hypot(ordered[(i + 1) % 4].x - ordered[i].x, ordered[(i + 1) % 4].y - ordered[i].y) }
                    val opposite = min(min(lengths[0], lengths[2]) / maxOf(lengths[0], lengths[2]), min(lengths[1], lengths[3]) / maxOf(lengths[1], lengths[3])).toFloat()
                    val rect = Imgproc.minAreaRect(approx); val short = min(rect.size.width, rect.size.height); val long = maxOf(rect.size.width, rect.size.height); val aspect = if (long > 0) short / long else 0.0
                    val aspectScore = (1.0 - abs(aspect - 63.0 / 88.0) / .35).coerceIn(0.0, 1.0).toFloat()
                    val rightAngles = liveRightAngleScore(ordered)
                    val margin = points.all { it.x > width * .015 && it.x < width * .985 && it.y > height * .015 && it.y < height * .985 }
                    val perspective = min(min(opposite, aspectScore), rightAngles) * if (margin) 1f else .5f
                    val cropCorners = points.sortedWith(compareBy<Point> { it.y }.thenBy { it.x }).let { sorted ->
                        val top = sorted.take(2).sortedBy { it.x }; val bottom = sorted.takeLast(2).sortedByDescending { it.x }; top + bottom
                    }
                    val visualCorners = cropCorners.map {
                        frameGeometry.cropToPreview(
                            (it.x / width).toFloat().coerceIn(0f, 1f),
                            (it.y / height).toFloat().coerceIn(0f, 1f),
                        )
                    }
                    val rawCoverage = (area / imageArea).toFloat()
                    val coverageScore = (1f - abs(rawCoverage - .42f) / .42f).coerceIn(0f, 1f)
                    val score = (aspectScore * .32f + rightAngles * .28f + opposite * .22f + coverageScore * .18f) * if (margin) 1f else .70f
                    if (score > bestScore) {
                        best = CardFrame(
                            found = true,
                            coverage = (area / (imageArea * .62)).toFloat(),
                            perspective = perspective,
                            corners = visualCorners,
                            geometryScore = score,
                            usedFallbackGeometry = usedFallback,
                            aspectRatio = aspect.toFloat(),
                            portraitAligned = visualCorners.let { displayed ->
                                val displayedWidth = displayed.maxOf { it.first } - displayed.minOf { it.first }
                                val displayedHeight = displayed.maxOf { it.second } - displayed.minOf { it.second }
                                displayedHeight > displayedWidth
                            },
                        )
                        bestScore = score
                    }
                }
                    curve.release(); approx.release()
                }
                contours.forEach(Mat::release)
                contours.clear()
            }
            inspectContours(edges, false)
            if (bestScore < CardScanConfiguration.FALLBACK_GEOMETRY_TRIGGER) {
                // Uneven-light contrast work belongs to the existing fallback, not every frame.
                // Inputs and thresholds stay identical; the original camera image is untouched.
                clahe.apply(gray, normalized)
                Imgproc.GaussianBlur(normalized, blurred, Size(5.0, 5.0), 0.0)
                Imgproc.Canny(blurred, contrastEdges, 20.0, 82.0)
                Imgproc.adaptiveThreshold(blurred, adaptiveEdges, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 7.0)
                Imgproc.threshold(blurred, luminanceEdges, 0.0, 255.0, Imgproc.THRESH_BINARY_INV or Imgproc.THRESH_OTSU)
                listOf(contrastEdges, adaptiveEdges, luminanceEdges).forEach { map -> Imgproc.morphologyEx(map, map, Imgproc.MORPH_CLOSE, kernel) }
                inspectContours(contrastEdges, true)
                inspectContours(adaptiveEdges, true)
                inspectContours(luminanceEdges, true)
            }
            best ?: CardFrame(false, 0f, 0f)
        } finally {
            contours.forEach(Mat::release)
        }
    }

    private fun liveRightAngleScore(points: List<Point>): Float = points.indices.map { index ->
        val previous = points[(index + 3) % 4]; val current = points[index]; val next = points[(index + 1) % 4]
        val ax = previous.x - current.x; val ay = previous.y - current.y
        val bx = next.x - current.x; val by = next.y - current.y
        val denominator = hypot(ax, ay) * hypot(bx, by)
        if (denominator <= 0.0) 0f else (1.0 - abs((ax * bx + ay * by) / denominator)).coerceIn(0.0, 1.0).toFloat()
    }.average().toFloat()

    /** Detects a still image in its own (unrotated) coordinate system. */
    fun detectPhoto(luma: ByteArray, width: Int, height: Int): CardFrame {
        return detect(java.nio.ByteBuffer.wrap(luma), width, height, width, 1)
    }

    override fun close() {
        gray.release()
        normalized.release()
        rawBlurred.release()
        blurred.release()
        edges.release()
        contrastEdges.release()
        adaptiveEdges.release()
        luminanceEdges.release()
        hierarchy.release()
        if (kernelDelegate.isInitialized()) kernel.release()
        if (claheDelegate.isInitialized()) clahe.collectGarbage()
        lumaBytes = ByteArray(0)
    }
}

internal fun nextPublishedFrameSequence(current: Long): Long = current + 1L

/** CameraX crop/rotation metadata needed to register ImageAnalysis points with FIT_CENTER preview. */
internal data class CameraFrameGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    val cropLeft: Int = 0,
    val cropTop: Int = 0,
    val cropRight: Int = bufferWidth,
    val cropBottom: Int = bufferHeight,
    val rotationDegrees: Int = 0,
) {
    private val safeWidth = bufferWidth.coerceAtLeast(1)
    private val safeHeight = bufferHeight.coerceAtLeast(1)
    internal val cropLeftPx = cropLeft.coerceIn(0, safeWidth - 1)
    internal val cropTopPx = cropTop.coerceIn(0, safeHeight - 1)
    internal val cropRightPx = cropRight.coerceIn(cropLeftPx + 1, safeWidth)
    internal val cropBottomPx = cropBottom.coerceIn(cropTopPx + 1, safeHeight)
    internal val cropWidthPx = cropRightPx - cropLeftPx
    internal val cropHeightPx = cropBottomPx - cropTopPx
    private val cropWidth = cropWidthPx.toFloat()
    private val cropHeight = cropHeightPx.toFloat()
    private val normalizedRotation = ((rotationDegrees % 360) + 360) % 360

    val previewAspectRatio: Float
        get() = if (normalizedRotation == 90 || normalizedRotation == 270) cropHeight / cropWidth else cropWidth / cropHeight

    fun sourceToPreview(x: Float, y: Float): Pair<Float, Float> {
        val cropX = ((x.coerceIn(0f, 1f) * safeWidth - cropLeftPx) / cropWidth).coerceIn(0f, 1f)
        val cropY = ((y.coerceIn(0f, 1f) * safeHeight - cropTopPx) / cropHeight).coerceIn(0f, 1f)
        return cropToPreview(cropX, cropY)
    }

    fun cropToPreview(x: Float, y: Float): Pair<Float, Float> =
        rotateNormalized(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), normalizedRotation)

    fun previewToSource(x: Float, y: Float): Pair<Float, Float> {
        val (cropX, cropY) = inverseRotateNormalized(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), normalizedRotation)
        return ((cropLeftPx + cropX * cropWidth) / safeWidth).coerceIn(0f, 1f) to
            ((cropTopPx + cropY * cropHeight) / safeHeight).coerceIn(0f, 1f)
    }
}

internal data class PreviewContentBounds(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** Mirrors PreviewView.ScaleType.FIT_CENTER for the actual view and rotated analysis crop. */
internal fun fitCenterPreviewBounds(viewWidth: Float, viewHeight: Float, imageAspectRatio: Float): PreviewContentBounds {
    if (viewWidth <= 0f || viewHeight <= 0f || imageAspectRatio <= 0f || !imageAspectRatio.isFinite()) {
        return PreviewContentBounds(0f, 0f, viewWidth.coerceAtLeast(0f), viewHeight.coerceAtLeast(0f))
    }
    val viewAspect = viewWidth / viewHeight
    return if (viewAspect > imageAspectRatio) {
        val width = viewHeight * imageAspectRatio
        PreviewContentBounds((viewWidth - width) / 2f, 0f, width, viewHeight)
    } else {
        val height = viewWidth / imageAspectRatio
        PreviewContentBounds(0f, (viewHeight - height) / 2f, viewWidth, height)
    }
}

/** Largest calm portrait-card guide inside the visible preview; this is never a detector gate. */
internal fun guidedCardZone(content: PreviewContentBounds): PreviewContentBounds {
    val height = minOf(content.height * .74f, content.width * .72f / CardScanConfiguration.CARD_RATIO)
    val width = height * CardScanConfiguration.CARD_RATIO
    return PreviewContentBounds(
        left = content.left + (content.width - width) / 2f,
        top = content.top + (content.height - height) / 2f,
        width = width,
        height = height,
    )
}

private fun rotateNormalized(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
    when (rotationDegrees) {
        90 -> (1f - y) to x
        180 -> (1f - x) to (1f - y)
        270 -> y to (1f - x)
        else -> x to y
    }

private fun inverseRotateNormalized(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
    when (rotationDegrees) {
        90 -> y to (1f - x)
        180 -> (1f - x) to (1f - y)
        270 -> (1f - y) to x
        else -> x to y
    }

/** Backwards-compatible pure helpers for full-buffer transforms. */
internal fun sourceToPreview(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
    rotateNormalized(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), ((rotationDegrees % 360) + 360) % 360)

internal fun previewToSource(x: Float, y: Float, rotationDegrees: Int): Pair<Float, Float> =
    inverseRotateNormalized(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f), ((rotationDegrees % 360) + 360) % 360)
