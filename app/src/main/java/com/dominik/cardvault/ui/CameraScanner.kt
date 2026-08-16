package com.dominik.cardvault.ui

import android.media.MediaActionSound
import android.os.Handler
import android.os.Looper
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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

private data class FrameQuality(val level: QualityLevel, val guidance: QualityGuidance, val exposure: Float, val sharpness: Float, val stability: Float, val coverage: Float, val perspective: Float)
internal enum class QualityLevel { RED, YELLOW, GREEN }
internal enum class QualityGuidance { MORE_LIGHT, LESS_GLARE, HOLD_STILL, FOCUS, FIND_CARD, MOVE_CLOSER, MOVE_AWAY, ALIGN_PARALLEL, READY }

@Composable fun LiveCameraScanner(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHapticFeedback.current
    val confirmationSound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var quality by remember { mutableStateOf(FrameQuality(QualityLevel.RED, QualityGuidance.HOLD_STILL, 0f, 0f, 0f, 0f, 0f)) }
    var captureMessage by remember { mutableStateOf<Int?>(null) }
    var autoCaptureEnabled by rememberSaveable { mutableStateOf(true) }
    var captureInProgress by remember { mutableStateOf(false) }
    var latestCapture by remember { mutableStateOf<File?>(null) }
    val autoCaptureGate = remember { AutoCaptureGate() }
    val controller = remember {
        LifecycleCameraController(context).apply {
            setEnabledUseCases(CameraController.IMAGE_CAPTURE or CameraController.IMAGE_ANALYSIS)
        }
    }
    DisposableEffect(controller, lifecycleOwner) {
        val analyzer = FrameQualityAnalyzer { quality = it }
        controller.setImageAnalysisAnalyzer(analysisExecutor, analyzer)
        controller.bindToLifecycle(lifecycleOwner)
        onDispose { controller.clearImageAnalysisAnalyzer(); controller.unbind(); analysisExecutor.shutdown() }
    }
    DisposableEffect(confirmationSound) { onDispose { confirmationSound.release() } }
    fun capture(auto: Boolean) {
        if (captureInProgress) return
        captureInProgress = true; captureMessage = null; autoCaptureGate.disarm()
        val file = File(context.cacheDir, "cardvault-test-capture-${System.currentTimeMillis()}.jpg")
        controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                latestCapture = file; captureInProgress = false
                captureMessage = if (auto) R.string.capture_saved_automatically else R.string.capture_saved
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                confirmationSound.play(MediaActionSound.SHUTTER_CLICK)
            }
            override fun onError(exception: ImageCaptureException) { captureInProgress = false; captureMessage = R.string.capture_failed }
        })
    }
    LaunchedEffect(quality, autoCaptureEnabled, captureInProgress) {
        if (autoCaptureEnabled && !captureInProgress && autoCaptureGate.onFrame(quality.level, quality.guidance)) capture(true)
    }
    Box(modifier.background(VaultNight, CardVaultShapes.feature).border(1.dp, VaultOutline, CardVaultShapes.feature)) {
        AndroidView(factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; this.controller = controller } }, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .08f)))
        captureMessage?.let { message ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
                color = if (message == R.string.capture_failed) MaterialTheme.colorScheme.errorContainer else VaultPositive.copy(alpha = .96f),
                contentColor = if (message == R.string.capture_failed) MaterialTheme.colorScheme.onErrorContainer else VaultNight,
                shape = CardVaultShapes.card,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(stringResource(message), modifier = Modifier.weight(1f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (latestCapture != null) TextButton(onClick = { latestCapture?.delete(); latestCapture = null; captureMessage = R.string.capture_undone }) { Text(stringResource(R.string.action_undo)) }
                }
            }
        }
        Box(
            Modifier.align(Alignment.Center).fillMaxWidth(.82f).aspectRatio(63f / 88f)
                .border(3.dp, quality.level.color, RoundedCornerShape(18.dp))
        )
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(VaultNight.copy(alpha = .88f)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).background(quality.level.color, RoundedCornerShape(50)))
                Text(stringResource(when (quality.level) { QualityLevel.RED -> R.string.capture_quality_red; QualityLevel.YELLOW -> R.string.capture_quality_yellow; QualityLevel.GREEN -> R.string.capture_quality_green }), color = Color.White, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(stringResource(if (quality.level == QualityLevel.GREEN) R.string.capture_quality_ready else R.string.capture_quality_not_ready), color = VaultTextMuted, fontSize = 11.sp)
            }
            Text(stringResource(quality.guidance.stringResource), color = Color.White, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityMetric(stringResource(R.string.capture_metric_light), quality.exposure, Modifier.weight(1f))
                QualityMetric(stringResource(R.string.capture_metric_focus), quality.sharpness, Modifier.weight(1f))
                QualityMetric(stringResource(R.string.capture_metric_stability), quality.stability, Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityMetric(stringResource(R.string.capture_metric_coverage), quality.coverage, Modifier.weight(1f))
                QualityMetric(stringResource(R.string.capture_metric_perspective), quality.perspective, Modifier.weight(1f))
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(stringResource(R.string.auto_capture_title), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold); Text(stringResource(R.string.auto_capture_hint), color = VaultTextMuted, fontSize = 10.sp) }; Switch(checked = autoCaptureEnabled, onCheckedChange = { autoCaptureEnabled = it }) }
            Button(onClick = { capture(false) }, enabled = !captureInProgress, modifier = Modifier.fillMaxWidth(), shape = CardVaultShapes.card) { Text(stringResource(if (captureInProgress) R.string.capture_in_progress else R.string.capture_test_photo)) }
        }
    }
}

internal class AutoCaptureGate(private val missingFramesToRearm: Int = 3) {
    private var armed = true
    private var missingFrames = 0
    fun onFrame(level: QualityLevel, guidance: QualityGuidance): Boolean {
        if (!armed) {
            missingFrames = if (guidance == QualityGuidance.FIND_CARD) missingFrames + 1 else 0
            if (missingFrames >= missingFramesToRearm) { armed = true; missingFrames = 0 }
        }
        if (armed && level == QualityLevel.GREEN) { armed = false; return true }
        return false
    }
    fun disarm() { armed = false; missingFrames = 0 }
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
    private val detector = CardFrameDetector()

    override fun analyze(image: ImageProxy) {
        try {
            val now = System.currentTimeMillis(); if (now - lastPublished < 250) return
            val plane = image.planes.first(); val buffer = plane.buffer; val width = image.width; val height = image.height
            val samples = IntArray(16 * 20); var index = 0; var bright = 0; var dark = 0; var total = 0.0; var detail = 0.0
            for (gy in 0 until 20) for (gx in 0 until 16) {
                val x = ((gx + .5) * width / 16).toInt().coerceIn(0, width - 1); val y = ((gy + .5) * height / 20).toInt().coerceIn(0, height - 1)
                val offset = y * plane.rowStride + x * plane.pixelStride; val value = buffer.get(offset).toInt() and 0xFF
                samples[index] = value; total += value; if (value > 245) bright++; if (value < 20) dark++
                if (gx > 0) detail += abs(value - samples[index - 1]); index++
            }
            val mean = total / samples.size; val clippedBright = bright.toFloat() / samples.size; val clippedDark = dark.toFloat() / samples.size
            val motion = previous?.let { old -> samples.indices.sumOf { abs(samples[it] - old[it]) }.toFloat() / samples.size } ?: 100f
            previous = samples; lastPublished = now
            val exposureScore = (1f - (abs(mean - 128.0) / 128.0).toFloat()).coerceIn(0f, 1f)
            val sharpnessScore = (detail / (samples.size - 20) / 28.0).toFloat().coerceIn(0f, 1f)
            val stabilityScore = (1f - motion / 32f).coerceIn(0f, 1f)
            val frame = detector.detect(buffer, width, height, plane.rowStride, plane.pixelStride)
            val baseReady = mean in 55.0..210.0 && clippedBright <= .12f && clippedDark <= .35f && stabilityScore >= .58f && sharpnessScore >= .28f && frame.found && frame.coverage in .26f..1f && frame.perspective >= .68f
            stableReadyFrames = if (baseReady) stableReadyFrames + 1 else 0
            val guidance = when { mean < 55 || clippedDark > .35f -> QualityGuidance.MORE_LIGHT; mean > 210 || clippedBright > .12f -> QualityGuidance.LESS_GLARE; stabilityScore < .58f -> QualityGuidance.HOLD_STILL; sharpnessScore < .28f -> QualityGuidance.FOCUS; !frame.found -> QualityGuidance.FIND_CARD; frame.coverage < .26f -> QualityGuidance.MOVE_CLOSER; frame.coverage > .85f -> QualityGuidance.MOVE_AWAY; frame.perspective < .68f -> QualityGuidance.ALIGN_PARALLEL; stableReadyFrames >= 6 -> QualityGuidance.READY; else -> QualityGuidance.HOLD_STILL }
            val level = when { guidance == QualityGuidance.READY -> QualityLevel.GREEN; guidance in setOf(QualityGuidance.FIND_CARD, QualityGuidance.MOVE_CLOSER, QualityGuidance.MOVE_AWAY, QualityGuidance.ALIGN_PARALLEL) || baseReady -> QualityLevel.YELLOW; else -> QualityLevel.RED }
            mainHandler.post { onQuality(FrameQuality(level, guidance, exposureScore, sharpnessScore, stabilityScore, frame.coverage.coerceIn(0f, 1f), frame.perspective.coerceIn(0f, 1f))) }
        } finally { image.close() }
    }
}

internal data class CardFrame(val found: Boolean, val coverage: Float, val perspective: Float)

internal class CardFrameDetector {
    private val available = runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)

    fun detect(buffer: java.nio.ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): CardFrame {
        if (!available) return CardFrame(false, 0f, 0f)
        val bytes = ByteArray(width * height); var target = 0
        for (y in 0 until height) for (x in 0 until width) bytes[target++] = buffer.get(y * rowStride + x * pixelStride)
        val gray = Mat(height, width, CvType.CV_8UC1); val blurred = Mat(); val edges = Mat(); val hierarchy = Mat()
        val contours = ArrayList<MatOfPoint>()
        return try {
            gray.put(0, 0, bytes); Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0); Imgproc.Canny(blurred, edges, 55.0, 150.0)
            Imgproc.morphologyEx(edges, edges, Imgproc.MORPH_CLOSE, Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0)))
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
            val imageArea = width.toDouble() * height; var best: CardFrame? = null; var bestArea = 0.0
            contours.forEach { contour ->
                val area = Imgproc.contourArea(contour); if (area < imageArea * .08 || area > imageArea * .92 || area <= bestArea) return@forEach
                val curve = MatOfPoint2f(*contour.toArray()); val approx = MatOfPoint2f(); val perimeter = Imgproc.arcLength(curve, true)
                Imgproc.approxPolyDP(curve, approx, perimeter * .025, true)
                val points = approx.toArray(); if (points.size == 4 && Imgproc.isContourConvex(MatOfPoint(*points))) {
                    val ordered = points.sortedBy { atan2(it.y - points.map { p -> p.y }.average(), it.x - points.map { p -> p.x }.average()) }
                    val lengths = ordered.indices.map { i -> hypot(ordered[(i + 1) % 4].x - ordered[i].x, ordered[(i + 1) % 4].y - ordered[i].y) }
                    val opposite = min(min(lengths[0], lengths[2]) / maxOf(lengths[0], lengths[2]), min(lengths[1], lengths[3]) / maxOf(lengths[1], lengths[3])).toFloat()
                    val rect = Imgproc.minAreaRect(approx); val short = min(rect.size.width, rect.size.height); val long = maxOf(rect.size.width, rect.size.height); val aspect = if (long > 0) short / long else 0.0
                    val aspectScore = (1.0 - abs(aspect - 63.0 / 88.0) / .35).coerceIn(0.0, 1.0).toFloat()
                    val margin = points.all { it.x > width * .015 && it.x < width * .985 && it.y > height * .015 && it.y < height * .985 }
                    val perspective = min(opposite, aspectScore) * if (margin) 1f else .5f
                    best = CardFrame(true, (area / (imageArea * .62)).toFloat(), perspective); bestArea = area
                }
                curve.release(); approx.release()
            }
            best ?: CardFrame(false, 0f, 0f)
        } finally { contours.forEach(Mat::release); gray.release(); blurred.release(); edges.release(); hierarchy.release() }
    }
}
