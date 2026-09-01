package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.content.ContentResolver
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.core.content.ContextCompat
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.hypot

internal data class CardBoundary(val corners: List<Pair<Float, Float>>) {
    companion object {
        val Default = CardBoundary(listOf(.12f to .08f, .88f to .08f, .88f to .92f, .12f to .92f))
        val InnerDefault = CardBoundary(listOf(.18f to .14f, .82f to .14f, .82f to .86f, .18f to .86f))
    }
}
internal data class CenteringFrames(val outer: CardBoundary, val inner: CardBoundary)

internal fun boundaryDecodeSample(width: Int, height: Int, maxEdge: Int = 2048): Int {
    var sample = 1
    val largest = maxOf(width, height)
    while (largest / sample > maxEdge) sample *= 2
    return sample
}

/** Bounded gallery decode for UI and boundary analysis; content URIs never become file paths. */
internal fun decodeBoundaryBitmap(resolver: ContentResolver, uri: Uri, maxEdge: Int = 2048): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    val boundsInput = resolver.openInputStream(uri) ?: return null
    boundsInput.use { BitmapFactory.decodeStream(it, null, bounds) }
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val decoded = resolver.openInputStream(uri)?.use { input ->
        BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply {
            inSampleSize = boundaryDecodeSample(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    } ?: return null
    val orientation = resolver.openInputStream(uri)?.use { input ->
        runCatching { ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            .getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    } ?: ExifInterface.ORIENTATION_NORMAL
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
    if (matrix.isIdentity) return decoded
    return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true).also { rotated ->
        if (rotated !== decoded) decoded.recycle()
    }
}

/** Deliberately minimal camera for centering photos: no auto-capture, just a guide and shutter. */
@Composable internal fun CenteringCameraCapture(onDismiss: () -> Unit, onCaptured: (Uri) -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current
    val executor = remember(context) { ContextCompat.getMainExecutor(context) }
    val controller = remember { LifecycleCameraController(context).apply {
        setEnabledUseCases(CameraController.IMAGE_CAPTURE)
        // The Camera API exposes the configured emulator webcam as a rear camera;
        // this is also the appropriate card-facing default on a phone.
        cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    } }
    var saving by remember { mutableStateOf(false) }
    var cameraReady by remember { mutableStateOf(false) }
    var captureError by remember { mutableStateOf<String?>(null) }
    DisposableEffect(controller, lifecycle) {
        controller.bindToLifecycle(lifecycle)
        controller.initializationFuture.addListener({
            cameraReady = runCatching { controller.initializationFuture.get(); true }.getOrElse {
                captureError = "Standardkamera ist nicht bereit. Du kannst ein Foto aus der Galerie wählen."
                false
            }
        }, executor)
        onDispose { controller.unbind() }
    }
    fun takePhoto() {
        if (saving) return
        saving = true
        val file = File(context.cacheDir, "domidex-centering-${System.currentTimeMillis()}.jpg")
        runCatching {
            controller.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), executor, object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) { saving = false; onCaptured(Uri.fromFile(file)) }
                override fun onError(exception: ImageCaptureException) { saving = false; captureError = "Kamera ist noch nicht bereit. Bitte kurz warten und erneut auslösen." }
            })
        }.onFailure { saving = false; captureError = "Kamera ist noch nicht bereit. Bitte kurz warten und erneut auslösen." }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PreviewView(it).apply { scaleType = PreviewView.ScaleType.FIT_CENTER; this.controller = controller } }, modifier = Modifier.fillMaxSize())
        Canvas(Modifier.fillMaxSize()) {
            val line = Color.White.copy(alpha = .36f)
            for (part in 1..2) { drawLine(line, androidx.compose.ui.geometry.Offset(size.width * part / 3f, 0f), androidx.compose.ui.geometry.Offset(size.width * part / 3f, size.height), 2f); drawLine(line, androidx.compose.ui.geometry.Offset(0f, size.height * part / 3f), androidx.compose.ui.geometry.Offset(size.width, size.height * part / 3f), 2f) }
            val width = size.width * .72f; val height = width * 1.4f
            val left = (size.width - width) / 2f; val top = (size.height - height) / 2f
            drawRoundRect(Color(0xFF65D6A6), topLeft = androidx.compose.ui.geometry.Offset(left, top), size = androidx.compose.ui.geometry.Size(width, height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(22f), style = Stroke(5f))
        }
        Text("Karte gerade im Rahmen ausrichten", modifier = Modifier.align(Alignment.TopCenter).padding(top = 46.dp).background(Color.Black.copy(alpha = .55f), RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp)
        captureError?.let { Text(it, modifier = Modifier.align(Alignment.TopCenter).padding(top = 102.dp).background(Color(0xCC5A1D1D), RoundedCornerShape(14.dp)).padding(horizontal = 12.dp, vertical = 7.dp), color = Color.White, fontSize = 12.sp) }
        Row(Modifier.align(Alignment.BottomCenter).padding(24.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onDismiss) { Text("Abbrechen") }
            Button(onClick = ::takePhoto, enabled = cameraReady && !saving, shape = RoundedCornerShape(50)) { Text(if (saving) "Speichert…" else "Aufnahme") }
        }
    }
}

@Composable
internal fun CardBoundaryEditor(
    uri: Uri,
    initial: CenteringFrames = CenteringFrames(CardBoundary.Default, CardBoundary.InnerDefault),
    onDismiss: () -> Unit,
    onApply: (CenteringFrames) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    var outerBoundary by remember(uri) { mutableStateOf(initial.outer) }
    var innerBoundary by remember(uri) { mutableStateOf(initial.inner) }
    var editingInner by remember { mutableStateOf(false) }
    var selectedCorner by remember { mutableIntStateOf(0) }
    var detecting by remember { mutableStateOf(false) }
    var decodeFailed by remember(uri) { mutableStateOf(false) }
    LaunchedEffect(uri) {
        val decoded = runCatching { withContext(Dispatchers.IO) { decodeBoundaryBitmap(context.contentResolver, uri) } }.getOrNull()
        runCatching { currentCoroutineContext().ensureActive() }.onFailure {
            decoded?.takeIf { bitmap -> !bitmap.isRecycled }?.recycle()
            throw it
        }
        bitmap?.takeIf { it !== decoded && !it.isRecycled }?.recycle()
        bitmap = decoded
        decodeFailed = decoded == null
    }
    DisposableEffect(Unit) {
        onDispose { bitmap?.takeIf { !it.isRecycled }?.recycle() }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Kartenrahmen ausrichten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Außenrahmen = Kartenkante. Innenrahmen = Druck-/Bildbereich. Wähle einen Rahmen und ziehe seine Ecke präzise; die Lupe folgt genau diesem Punkt.", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { FilterChip(!editingInner, { editingInner = false }, label = { Text("Außenrahmen") }); FilterChip(editingInner, { editingInner = true }, label = { Text("Innenrahmen") }) }
                bitmap?.let { image ->
                    val active = if (editingInner) innerBoundary else outerBoundary
                    BoundaryCanvas(image, outerBoundary, innerBoundary, editingInner, selectedCorner, onSelect = { selectedCorner = it }, onChange = { changed -> if (editingInner) innerBoundary = changed else outerBoundary = changed })
                    Text("Präzisionslupe · ${if (editingInner) "Innen" else "Außen"} · Ecke ${selectedCorner + 1}", style = MaterialTheme.typography.labelMedium)
                    BoundaryMagnifier(image, active.corners[selectedCorner])
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            detecting = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) { detectBoundary(image) }
                                if (result != null) outerBoundary = result
                                detecting = false
                            }
                        }, enabled = !detecting, modifier = Modifier.weight(1f)) { Text(if (detecting) "Erkenne…" else "Automatisch erkennen") }
                        TextButton(onClick = { outerBoundary = CardBoundary.Default; innerBoundary = CardBoundary.InnerDefault }) { Text("Zurücksetzen") }
                    }
                } ?: if (decodeFailed) {
                    Text("Dieses Bild konnte nicht verarbeitet werden. Bitte wähle ein anderes Foto.", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                } else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            }
        },
        confirmButton = { TextButton(onClick = { onApply(CenteringFrames(outerBoundary, innerBoundary)) }, enabled = bitmap != null && !detecting) { Text("Rahmen übernehmen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@Composable private fun BoundaryCanvas(image: Bitmap, outer: CardBoundary, inner: CardBoundary, editingInner: Boolean, selected: Int, onSelect: (Int) -> Unit, onChange: (CardBoundary) -> Unit) {
    // Keeping this pointer-input node stable is essential.  Keying it by the
    // changing corner lists recreated the gesture after every pixel and made a
    // corner appear to move in steps instead of following the finger smoothly.
    val latestOuter = rememberUpdatedState(outer)
    val latestInner = rememberUpdatedState(inner)
    val latestEditingInner = rememberUpdatedState(editingInner)
    val latestSelect = rememberUpdatedState(onSelect)
    val latestChange = rememberUpdatedState(onChange)
    Canvas(
        Modifier.fillMaxWidth().aspectRatio(image.width.toFloat() / image.height).clip(RoundedCornerShape(12.dp)).border(1.dp, Color.White.copy(.35f), RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                var dragged = -1
                var draggingInner = false
                detectDragGestures(onDragStart = { position ->
                    draggingInner = latestEditingInner.value
                    val active = if (draggingInner) latestInner.value else latestOuter.value
                    dragged = active.corners.indices.minByOrNull { i -> hypot(position.x / size.width - active.corners[i].first, position.y / size.height - active.corners[i].second) } ?: 0
                    latestSelect.value(dragged)
                }, onDrag = { change, _ ->
                    change.consume()
                    if (dragged >= 0) {
                        val active = if (draggingInner) latestInner.value else latestOuter.value
                        val moved = active.corners.toMutableList()
                        moved[dragged] = (change.position.x / size.width).coerceIn(0f, 1f) to (change.position.y / size.height).coerceIn(0f, 1f)
                        latestChange.value(CardBoundary(moved))
                    }
                })
            }
    ) {
        drawImage(image.asImageBitmap(), dstSize = IntSize(size.width.toInt(), size.height.toInt()))
        fun drawFrame(frame: CardBoundary, color: Color, active: Boolean) { val path = Path(); frame.corners.forEachIndexed { index, p -> if (index == 0) path.moveTo(p.first * size.width, p.second * size.height) else path.lineTo(p.first * size.width, p.second * size.height) }; path.close(); drawPath(path, color, style = Stroke(if (active) 4f else 2f)); frame.corners.forEachIndexed { i, p -> drawCircle(if (active && i == selected) Color(0xFFFFC857) else color, if (active && i == selected) 13f else 8f, androidx.compose.ui.geometry.Offset(p.first * size.width, p.second * size.height)) } }
        // Red is reserved for the physical card edge; cyan marks the printable
        // inner frame.  The selected handle turns amber for an unambiguous target.
        drawFrame(outer, Color(0xFFFF5A62), !editingInner); drawFrame(inner, Color(0xFF4ADEF0), editingInner)
    }
}

@Composable private fun BoundaryMagnifier(image: Bitmap, point: Pair<Float, Float>) {
    Box(Modifier.fillMaxWidth().height(120.dp).clip(RoundedCornerShape(10.dp)).background(Color.Black).border(1.dp, Color.White.copy(.25f), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) { val cropW = (image.width / 4).coerceAtLeast(1); val cropH = (image.height / 4).coerceAtLeast(1); val x = (point.first * image.width - cropW / 2).toInt().coerceIn(0, image.width - cropW); val y = (point.second * image.height - cropH / 2).toInt().coerceIn(0, image.height - cropH); drawImage(image.asImageBitmap(), srcOffset = IntOffset(x, y), srcSize = IntSize(cropW, cropH), dstSize = IntSize(size.width.toInt(), size.height.toInt())); drawLine(Color(0xFFFFC857), androidx.compose.ui.geometry.Offset(size.width / 2, 0f), androidx.compose.ui.geometry.Offset(size.width / 2, size.height), strokeWidth = 2f); drawLine(Color(0xFFFFC857), androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = 2f) }
    }
}

private fun detectBoundary(bitmap: Bitmap): CardBoundary? {
    val maxSide = 1400
    val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
    // Detection owns its working copy so leaving the editor can safely release the UI bitmap.
    val sampled = if (scale < 1f) {
        Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
    } else {
        bitmap.copy(Bitmap.Config.ARGB_8888, false)
    }
    return try {
        val pixels = IntArray(sampled.width * sampled.height); sampled.getPixels(pixels, 0, sampled.width, 0, 0, sampled.width, sampled.height)
        val luma = ByteArray(pixels.size) { i -> ((android.graphics.Color.red(pixels[i]) * 299 + android.graphics.Color.green(pixels[i]) * 587 + android.graphics.Color.blue(pixels[i]) * 114) / 1000).toByte() }
        val frame = CardFrameDetector().detectPhoto(luma, sampled.width, sampled.height)
        frame.corners.takeIf { frame.found && it.size == 4 }?.let(::CardBoundary)
    } finally {
        if (!sampled.isRecycled) sampled.recycle()
    }
}
