package com.dominik.cardvault.ui

import android.media.MediaActionSound
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
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
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
import org.opencv.android.OpenCVLoader
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

internal object LatestCardCapture {
    var path by mutableStateOf<String?>(null)
}

private data class FrameQuality(val level: QualityLevel, val guidance: QualityGuidance, val exposure: Float, val sharpness: Float, val stability: Float, val coverage: Float, val perspective: Float, val contourStability: Float = 0f, val stableFrames: Int = 0, val corners: List<Pair<Float, Float>> = emptyList())
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
    val secondBestConfidence: Int = 0,
    val decision: String = "",
    val artworkConfidence: Float = 0f,
    val artworkMatch: String = "",
    val visualScore: Int = 0,
    val numberScore: Int = 0,
    val setScore: Int = 0,
    val nameScore: Int = 0,
    val languageScore: Int = 0,
    val processingMillis: Long = 0L,
    val stageTimings: String = "",
    val scanId: String = "",
)
/** Volatile diagnostic data only; no card, image, or account data is retained here. */
internal object ScannerDebugState { var snapshot by mutableStateOf(ScannerDebugSnapshot()) }

@Composable fun LiveCameraScanner(
    modifier: Modifier = Modifier,
    onCaptureStarted: (Boolean) -> Unit = {},
    onCaptureSaved: (File, Boolean) -> Unit = { _, _ -> },
    onCaptureFailed: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val confirmationSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val captureTimeoutHandler = remember { Handler(Looper.getMainLooper()) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var quality by remember { mutableStateOf(FrameQuality(QualityLevel.RED, QualityGuidance.HOLD_STILL, 0f, 0f, 0f, 0f, 0f)) }
    var captureMessage by remember { mutableStateOf<Int?>(null) }
    var autoCaptureEnabled by rememberSaveable { mutableStateOf(true) }
    var detailsExpanded by rememberSaveable { mutableStateOf(false) }
    var captureInProgress by remember { mutableStateOf(false) }
    var activeCaptureId by remember { mutableLongStateOf(0L) }
    var latestCapture by remember { mutableStateOf<File?>(null) }
    val cameraManager = remember(context) { context.getSystemService(CameraManager::class.java) }
    val torchCameraId = remember(cameraManager) { cameraManager.cameraIdList.firstOrNull { id -> cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }
    val maxTorchStrength = remember(cameraManager, torchCameraId) { if (Build.VERSION.SDK_INT >= 33 && torchCameraId != null) cameraManager.getCameraCharacteristics(torchCameraId).get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1 else 1 }
    var torchEnabled by rememberSaveable { mutableStateOf(false) }
    var torchStrength by rememberSaveable { mutableIntStateOf(1) }
    var torchSettlingUntil by remember { mutableLongStateOf(0L) }
    val autoCaptureGate = remember { AutoCaptureGate() }
    // This transition exists only while the scanner composable is visible.
    val scannerMotion = rememberInfiniteTransition(label = "scanner-overlay")
    val scanPosition by scannerMotion.animateFloat(0f, 1f, infiniteRepeatable(tween(1_800, easing = LinearEasing), RepeatMode.Restart), label = "scan-line")
    val idleGlow by scannerMotion.animateFloat(.35f, .75f, infiniteRepeatable(tween(1_900), RepeatMode.Reverse), label = "scanner-glow")
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
            // A scan is a still-document capture: prefer readable pixels over
            // shutter speed. Analysis still drops old frames to keep the overlay live.
            setImageCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            setImageAnalysisBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            cameraSelector = selectBestScannerCamera(cameraManager)
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        val analyzer = FrameQualityAnalyzer { quality = it }
        controller.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.clearImageAnalysisAnalyzer(); controller.unbind(); analysisExecutor.shutdown() }
    }
    DisposableEffect(confirmationSound, cameraManager, torchCameraId) { onDispose { confirmationSound.release(); captureTimeoutHandler.removeCallbacksAndMessages(null); torchCameraId?.let { runCatching { cameraManager.setTorchMode(it, false) } } } }
    fun setTorch(enabled: Boolean, strength: Int = torchStrength) {
        // CameraX controls the lens actually bound to the preview; CameraManager can target a
        // different physical lens on multi-camera devices.
        val future = controller.cameraControl?.enableTorch(enabled) ?: return
        future.addListener({ runCatching { future.get() }.onSuccess {
            torchEnabled = enabled; torchStrength = strength.coerceIn(1, maxTorchStrength)
            // Exposure/AF may jump immediately after torch changes. Suppress the
            // next short capture window rather than saving a transition frame.
            torchSettlingUntil = SystemClock.elapsedRealtime() + 450L
        } }, mainExecutor)
    }
    fun capture(auto: Boolean) {
        if (captureInProgress) return
        captureInProgress = true; captureMessage = null; autoCaptureGate.disarm()
        onCaptureStarted(auto)
        val captureId = System.nanoTime()
        val contributionEligible = quality.level == QualityLevel.GREEN && quality.corners.size == 4
        activeCaptureId = captureId
        val file = File(context.cacheDir, "cardvault-test-capture-${System.currentTimeMillis()}.jpg")
        captureTimeoutHandler.postDelayed({
            if (activeCaptureId == captureId && captureInProgress) {
                activeCaptureId = 0L; captureInProgress = false; captureMessage = R.string.capture_failed; file.delete(); onCaptureFailed()
            }
        }, 12_000L)
        try {
            controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    if (activeCaptureId != captureId) { file.delete(); return }
                    activeCaptureId = 0L
                    latestCapture = file; captureInProgress = false
                    LatestCardCapture.path = file.absolutePath
                    captureMessage = if (auto) R.string.capture_saved_automatically else R.string.capture_saved
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    confirmationSound.play(MediaActionSound.SHUTTER_CLICK)
                    onCaptureSaved(file, contributionEligible)
                }
                override fun onError(exception: ImageCaptureException) {
                    if (activeCaptureId != captureId) return
                    activeCaptureId = 0L; captureInProgress = false; captureMessage = R.string.capture_failed; file.delete(); onCaptureFailed()
                }
            })
        } catch (_: IllegalStateException) {
            // Some emulators and a temporarily unavailable physical camera expose the scanner UI
            // before CameraX has an initialized lens. A tap must report a recoverable capture
            // failure, never terminate the whole collection app.
            activeCaptureId = 0L; captureInProgress = false; captureMessage = R.string.capture_failed; file.delete(); onCaptureFailed()
        }
    }
    LaunchedEffect(quality, autoCaptureEnabled, captureInProgress, torchSettlingUntil) {
        if (autoCaptureEnabled && !captureInProgress && SystemClock.elapsedRealtime() >= torchSettlingUntil && autoCaptureGate.onFrame(quality.level, quality.guidance)) capture(true)
    }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        Box(Modifier.matchParentSize()) {
            // Preserve the webcam's complete 4:3 scene. FIT_CENTER intentionally letterboxes
            // the portrait scanner rather than applying PreviewView's crop-to-fill zoom.
            AndroidView(factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; this.controller = controller } }, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f)))
            DomiStatusBanner(
                message = captureMessage?.let { stringResource(it) },
                tone = if (captureMessage == R.string.capture_failed) DomiStatusTone.ERROR else DomiStatusTone.SUCCESS,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                action = if (latestCapture != null) {
                    { TextButton(onClick = { latestCapture?.delete(); latestCapture = null; captureMessage = R.string.capture_undone }) { Text(stringResource(R.string.action_undo)) } }
                } else null,
            )
            Canvas(Modifier.matchParentSize()) {
                // CameraX rotates the landscape analysis stream into a portrait 3:4 preview.
                // Mirror FIT_CENTER's letterbox geometry so detector points remain registered
                // to the visible image rather than the surrounding black bars.
                val imageAspect = 3f / 4f
                val viewAspect = size.width / size.height
                val contentWidth: Float
                val contentHeight: Float
                val offsetX: Float
                val offsetY: Float
                if (viewAspect > imageAspect) {
                    contentHeight = size.height; contentWidth = contentHeight * imageAspect
                    offsetX = (size.width - contentWidth) / 2f; offsetY = 0f
                } else {
                    contentWidth = size.width; contentHeight = contentWidth / imageAspect
                    offsetX = 0f; offsetY = (size.height - contentHeight) / 2f
                }
                if (quality.corners.size == 4) {
                    val path = Path()
                    quality.corners.forEachIndexed { index, point ->
                        val x = offsetX + point.first * contentWidth; val y = offsetY + point.second * contentHeight
                        if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close(); drawPath(path, quality.level.color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 7f))
                    if (quality.level == QualityLevel.GREEN) {
                        // Success is a short, calm visual acknowledgement; no capture or state changes here.
                        drawCircle(VaultPositive.copy(alpha = .14f + idleGlow * .10f), radius = min(contentWidth, contentHeight) * (.16f + idleGlow * .04f), center = androidx.compose.ui.geometry.Offset(offsetX + contentWidth * .5f, offsetY + contentHeight * .5f))
                        drawCircle(VaultPositive.copy(alpha = .80f), radius = 4f + idleGlow * 3f, center = androidx.compose.ui.geometry.Offset(offsetX + contentWidth * .5f, offsetY + contentHeight * .5f))
                    } else {
                        val y = offsetY + contentHeight * scanPosition
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, MewRose.copy(alpha = .90f), MewIce.copy(alpha = .78f), Color.Transparent)), topLeft = androidx.compose.ui.geometry.Offset(offsetX, y - 2f), size = androidx.compose.ui.geometry.Size(contentWidth, 4f))
                    }
                } else {
                    val idleColor = if (quality.level == QualityLevel.RED) MewRose else quality.level.color
                    drawRoundRect(idleColor.copy(alpha = .58f + idleGlow * .25f), topLeft = androidx.compose.ui.geometry.Offset(size.width * .09f, size.height * .08f), size = androidx.compose.ui.geometry.Size(size.width * .82f, size.height * .82f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f))
                    repeat(4) { index ->
                        val x = size.width * (.18f + index * .21f)
                        val y = size.height * (.18f + ((scanPosition + index * .17f) % .62f))
                        drawCircle(MewRose.copy(alpha = .20f + idleGlow * .18f), radius = 2f, center = androidx.compose.ui.geometry.Offset(x, y))
                    }
                }
            }
        }
        Surface(modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp), color = VaultNight.copy(alpha = .82f), shape = RoundedCornerShape(50)) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (detailsExpanded) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    QualityMetric("Licht", quality.exposure, Modifier.width(60.dp)); QualityMetric("Schärfe", quality.sharpness, Modifier.width(60.dp)); QualityMetric("Karte", quality.coverage, Modifier.width(60.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { setTorch(!torchEnabled) }, enabled = torchCameraId != null) { Text(if (torchEnabled) "☀ Blitz an" else "☼ Blitz aus", color = if (torchEnabled) MewIce else Color.White, fontSize = 12.sp) }
                TextButton(onClick = { detailsExpanded = !detailsExpanded }) { Text(if (detailsExpanded) "Details aus" else "Details", color = Color.White, fontSize = 12.sp) }
                Text("Automatik", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Switch(checked = autoCaptureEnabled, onCheckedChange = { autoCaptureEnabled = it }, modifier = Modifier.height(28.dp))
                Button(onClick = { capture(false) }, enabled = !captureInProgress, shape = CardVaultShapes.card, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)) { Text(if (captureInProgress) "…" else "Aufnahme", maxLines = 1) }
                }
            }
        }
    }
}

/**
 * Orders CameraX candidates deterministically for card scanning.  CameraX receives the complete
 * ordered list, so a device whose preferred rear lens is temporarily unavailable can still fall
 * back safely instead of failing the scanner.  We deliberately never promote an ultrawide/front
 * lens over a normal rear camera simply because it has a larger advertised stream.
 */
internal fun selectBestScannerCamera(cameraManager: CameraManager): CameraSelector =
    CameraSelector.Builder().addCameraFilter { cameras ->
        cameras.sortedWith(compareByDescending<androidx.camera.core.CameraInfo> { info ->
            val characteristics = runCatching { cameraManager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId) }.getOrNull()
            if (characteristics?.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK) 1 else 0
        }.thenByDescending { info ->
            val characteristics = runCatching { cameraManager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId) }.getOrNull()
            val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val jpeg = map?.getOutputSizes(ImageFormat.JPEG).orEmpty().maxOfOrNull { it.width.toLong() * it.height } ?: 0L
            val yuv = map?.getOutputSizes(ImageFormat.YUV_420_888).orEmpty().maxOfOrNull { it.width.toLong() * it.height } ?: 0L
            maxOf(jpeg, yuv)
        }.thenByDescending { info ->
            val characteristics = runCatching { cameraManager.getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId) }.getOrNull()
            characteristics?.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)?.any { it != CameraCharacteristics.CONTROL_AF_MODE_OFF } == true
        })
    }.build()

internal class AutoCaptureGate(private val missingFramesToRearm: Int = 3, private val readyFramesToCapture: Int = 2) {
    private var armed = true
    private var missingFrames = 0
    private var readyFrames = 0
    fun onFrame(level: QualityLevel, guidance: QualityGuidance): Boolean {
        if (!armed) {
            missingFrames = if (guidance == QualityGuidance.FIND_CARD) missingFrames + 1 else 0
            if (missingFrames >= missingFramesToRearm) { armed = true; missingFrames = 0 }
        }
        readyFrames = if (armed && level == QualityLevel.GREEN && guidance == QualityGuidance.READY) readyFrames + 1 else 0
        if (readyFrames >= readyFramesToCapture) { armed = false; readyFrames = 0; return true }
        return false
    }
    fun disarm() { armed = false; missingFrames = 0; readyFrames = 0 }
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

private val QualityLevel.color: Color get() = when (this) { QualityLevel.RED -> Color(0xFFE86E78); QualityLevel.YELLOW -> Color(0xFFFFC857); QualityLevel.GREEN -> Color(0xFF65D6A6) }

private class FrameQualityAnalyzer(private val onQuality: (FrameQuality) -> Unit) : ImageAnalysis.Analyzer {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var previous: IntArray? = null
    private var lastPublished = 0L
    private var stableReadyFrames = 0
    private var trackedFrame: CardFrame? = null
    private var missedTrackedFrames = 0
    private var priorDetectedFrame: CardFrame? = null
    private val detector = CardFrameDetector()

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis(); if (now - lastPublished < 250) return
            val plane = image.planes.first(); val buffer = plane.buffer; val width = image.width; val height = image.height
            val samples = IntArray(16 * 20); var index = 0; var bright = 0; var dark = 0; var total = 0.0; var detail = 0.0; var centerDetail = 0.0; var centerPairs = 0
            for (gy in 0 until 20) for (gx in 0 until 16) {
                val x = ((gx + .5) * width / 16).toInt().coerceIn(0, width - 1); val y = ((gy + .5) * height / 20).toInt().coerceIn(0, height - 1)
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
            val motion = previous?.let { old -> samples.indices.sumOf { abs(samples[it] - old[it]) }.toFloat() / samples.size } ?: 100f
            previous = samples; lastPublished = now
            val exposureScore = (1f - (abs(mean - 128.0) / 128.0).toFloat()).coerceIn(0f, 1f)
            val sharpnessScore = (detail / (samples.size - 20) / 28.0).toFloat().coerceIn(0f, 1f)
            val centeredCardEvidence = (centerDetail / centerPairs.coerceAtLeast(1) / 24.0).toFloat().coerceIn(0f, 1f)
            val stabilityScore = (1f - motion / 32f).coerceIn(0f, 1f)
            val detectedFrame = detector.detect(buffer, width, height, plane.rowStride, plane.pixelStride)
            val frame = track(detectedFrame)
            val contourStability = contourStability(detectedFrame)
            // Only a fresh, current frame may arm auto-capture. The short tracker
            // grace period is purely visual so the proposal does not flicker when a
            // hand, glare or a webcam frame briefly hides an otherwise found edge.
            // FIT_CENTER deliberately leaves more context around a physical card.
            // The former thresholds effectively required a close-up, so an otherwise
            // clearly visible card in the wide scanner view could never auto-capture.
            val hasCardEvidence = detectedFrame.found && detectedFrame.coverage in .11f..1.12f && detectedFrame.perspective >= .54f
            // A textured background alone must never arm auto capture.  Centre
            // detail remains a guidance fallback, while capture requires an actual
            // stable four-corner proposal across consecutive analysis frames.
            val baseReady = mean in 52.0..215.0 && clippedBright <= .16f && clippedDark <= .42f && stabilityScore >= .47f && sharpnessScore >= .15f && contourStability >= .60f && hasCardEvidence
            stableReadyFrames = if (baseReady) stableReadyFrames + 1 else 0
            val guidance = when { mean < 52 || clippedDark > .42f -> QualityGuidance.MORE_LIGHT; mean > 215 || clippedBright > .16f -> QualityGuidance.LESS_GLARE; stabilityScore < .47f || (frame.found && contourStability < .60f) -> QualityGuidance.HOLD_STILL; sharpnessScore < .15f -> QualityGuidance.FOCUS; !frame.found && centeredCardEvidence < .30f -> QualityGuidance.FIND_CARD; frame.found && frame.coverage < .11f -> QualityGuidance.MOVE_CLOSER; frame.found && frame.coverage > .96f -> QualityGuidance.MOVE_AWAY; frame.found && frame.perspective < .54f -> QualityGuidance.ALIGN_PARALLEL; stableReadyFrames >= 3 -> QualityGuidance.READY; else -> QualityGuidance.HOLD_STILL }
            val level = when { guidance == QualityGuidance.READY -> QualityLevel.GREEN; guidance in setOf(QualityGuidance.FIND_CARD, QualityGuidance.MOVE_CLOSER, QualityGuidance.MOVE_AWAY, QualityGuidance.ALIGN_PARALLEL) || baseReady -> QualityLevel.YELLOW; else -> QualityLevel.RED }
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
                    reason = if (ScannerDebugState.snapshot.scanId.isBlank()) guidance.name else ScannerDebugState.snapshot.reason,
                )
                onQuality(FrameQuality(level, guidance, exposureScore, sharpnessScore, stabilityScore, frame.coverage.coerceIn(0f, 1f), frame.perspective.coerceIn(0f, 1f), contourStability, stableReadyFrames, frame.corners))
            }
        } finally { image.close() }
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
                corners = previousFrame.corners.zip(detected.corners) { old, fresh ->
                    old.first * .60f + fresh.first * .40f to old.second * .60f + fresh.second * .40f
                },
            )
        } else detected
        trackedFrame = smoothed
        return smoothed
    }
}

internal data class CardFrame(val found: Boolean, val coverage: Float, val perspective: Float, val corners: List<Pair<Float, Float>> = emptyList())

internal class CardFrameDetector {
    private val available = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)

    fun detect(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): CardFrame {
        if (!available) return CardFrame(false, 0f, 0f)
        val bytes = ByteArray(width * height); var target = 0
        for (y in 0 until height) for (x in 0 until width) bytes[target++] = buffer.get(y * rowStride + x * pixelStride)
        val gray = Mat(height, width, CvType.CV_8UC1); val normalized = Mat(); val blurred = Mat(); val edges = Mat(); val adaptiveEdges = Mat(); val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        return try {
            gray.put(0, 0, bytes)
            // Local contrast normalization keeps edges usable in uneven room lighting. It only
            // affects frame finding; the original camera image is never altered or saved.
            val clahe = Imgproc.createCLAHE(2.0, Size(8.0, 8.0)); clahe.apply(gray, normalized); clahe.collectGarbage()
            Imgproc.GaussianBlur(normalized, blurred, Size(5.0, 5.0), 0.0)
            // Webcam feeds tend to have softer, lower-contrast card edges than phone cameras.
            // Combine a conventional edge pass with an adaptive local-contrast pass so a card
            // against a similarly bright desk can still propose its outer contour.
            Imgproc.Canny(blurred, edges, 24.0, 92.0)
            Imgproc.adaptiveThreshold(blurred, adaptiveEdges, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV, 31, 7.0)
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, kernel)
            Imgproc.dilate(edges, edges, kernel)
            Imgproc.morphologyEx(adaptiveEdges, adaptiveEdges, Imgproc.MORPH_CLOSE, kernel)
            kernel.release()
            val imageArea = width.toDouble() * height; var best: CardFrame? = null; var bestScore = 0f
            fun inspectContours(edgeMap: Mat) {
                contours.clear()
                Imgproc.findContours(edgeMap, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
                contours.forEach { contour ->
                    val area = Imgproc.contourArea(contour); if (area < imageArea * .018 || area > imageArea * .86) return@forEach
                    val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f(); val perimeter = Imgproc.arcLength(curve, true)
                    Imgproc.approxPolyDP(curve, approx, perimeter * .028, true)
                    val points = approx.toArray(); if (points.size == 4 && Imgproc.isContourConvex(MatOfPoint(*points))) {
                    val ordered = points.sortedBy { atan2(it.y - points.map { p -> p.y }.average(), it.x - points.map { p -> p.x }.average()) }
                    val lengths = ordered.indices.map { i -> hypot(ordered[(i + 1) % 4].x - ordered[i].x, ordered[(i + 1) % 4].y - ordered[i].y) }
                    val opposite = min(min(lengths[0], lengths[2]) / maxOf(lengths[0], lengths[2]), min(lengths[1], lengths[3]) / maxOf(lengths[1], lengths[3])).toFloat()
                    val rect = Imgproc.minAreaRect(approx); val short = min(rect.size.width, rect.size.height); val long = maxOf(rect.size.width, rect.size.height); val aspect = if (long > 0) short / long else 0.0
                    val aspectScore = (1.0 - abs(aspect - 63.0 / 88.0) / .35).coerceIn(0.0, 1.0).toFloat()
                    val margin = points.all { it.x > width * .015 && it.x < width * .985 && it.y > height * .015 && it.y < height * .985 }
                    val perspective = min(opposite, aspectScore) * if (margin) 1f else .5f
                    val visualCorners = points.sortedWith(compareBy<Point> { it.y }.thenBy { it.x }).let { sorted ->
                        val top = sorted.take(2).sortedBy { it.x }; val bottom = sorted.takeLast(2).sortedByDescending { it.x }; top + bottom
                    }.map { (1f - (it.y / height).toFloat()).coerceIn(0f, 1f) to (it.x / width).toFloat().coerceIn(0f, 1f) }
                    val rawCoverage = (area / imageArea).toFloat()
                    val coverageScore = (1f - abs(rawCoverage - .42f) / .42f).coerceIn(0f, 1f)
                    val score = perspective * .72f + coverageScore * .28f
                    if (score > bestScore) {
                        best = CardFrame(true, (area / (imageArea * .62)).toFloat(), perspective, visualCorners)
                        bestScore = score
                    }
                }
                    curve.release(); approx.release()
                }
                contours.forEach(Mat::release)
                contours.clear()
            }
            inspectContours(edges)
            inspectContours(adaptiveEdges)
            best ?: CardFrame(false, 0f, 0f)
        } finally { contours.forEach(Mat::release); gray.release(); normalized.release(); blurred.release(); edges.release(); adaptiveEdges.release(); hierarchy.release() }
    }

    /** Detects a still image in its own (unrotated) coordinate system. */
    fun detectPhoto(luma: ByteArray, width: Int, height: Int): CardFrame {
        val liveCoordinates = detect(java.nio.ByteBuffer.wrap(luma), width, height, width, 1)
        // The live analyzer rotates CameraX's landscape analysis coordinates for its portrait preview.
        // A gallery/captured bitmap is displayed unrotated, so map them back before editing.
        return liveCoordinates.copy(corners = liveCoordinates.corners.map { it.second.coerceIn(0f, 1f) to (1f - it.first).coerceIn(0f, 1f) })
    }
}
