package com.dominik.cardvault.ui

import android.content.Context
import android.graphics.PointF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.annotation.MainThread
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/** Camera output follows the portrait preview display, independently of device motion sensors. */
@MainThread
internal class PortraitScannerCamera(
    context: Context,
    private val previewView: PreviewView,
    private val selector: CameraSelector,
) {
    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val preview = Preview.Builder().build()
    private val capture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    private val analysis = ImageAnalysis.Builder()
        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var owner: LifecycleOwner? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var generation = 0L
    private var attemptedViewport: Triple<Int, Int, Int>? = null
    private var focusRequest: ListenableFuture<*>? = null
    private var zoomRequest: ListenableFuture<*>? = null
    private var tap: PointF? = null
    private var hadMultiplePointers = false

    val cameraControl: CameraControl? get() = camera?.cameraControl

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_DESTROY) unbind()
    }
    private val layoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> bindWhenReady() }
    private val attachListener = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = bindWhenReady()
        override fun onViewDetachedFromWindow(view: View) {
            detachUseCases()
            attemptedViewport = null
        }
    }
    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val activeCamera = camera ?: return true
            val zoom = activeCamera.cameraInfo.zoomState.value ?: return true
            // Match the controller's existing 2x pinch response and camera-specific limits.
            val ratio = (zoom.zoomRatio * (1f + (detector.scaleFactor - 1f) * 2f))
                .coerceIn(zoom.minZoomRatio, zoom.maxZoomRatio)
            try {
                val request = activeCamera.cameraControl.setZoomRatio(ratio)
                zoomRequest = request
                watch(request) { camera === activeCamera && zoomRequest === request }
            } catch (error: Exception) { report(error) }
            return true
        }
    })
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent): Boolean = true
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            if (!hadMultiplePointers && !scaleDetector.isInProgress) {
                tap = PointF(event.x, event.y)
                previewView.performClick()
            }
            return true
        }
    })

    fun bindToLifecycle(
        owner: LifecycleOwner,
        executor: Executor,
        analyzer: ImageAnalysis.Analyzer,
        onError: (Throwable) -> Unit,
    ) {
        unbind()
        this.onError = onError
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) {
            report(IllegalStateException("Cannot bind scanner to a destroyed lifecycle"))
            return
        }
        this.owner = owner
        val bindGeneration = generation
        previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        analysis.setAnalyzer(executor, analyzer)
        owner.lifecycle.addObserver(lifecycleObserver)
        previewView.addOnLayoutChangeListener(layoutListener)
        previewView.addOnAttachStateChangeListener(attachListener)
        previewView.setOnClickListener {
            val point = tap ?: PointF(previewView.width / 2f, previewView.height / 2f)
            tap = null
            focus(point)
        }
        previewView.setOnTouchListener { view, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) hadMultiplePointers = false
            if (event.pointerCount > 1) hadMultiplePointers = true
            scaleDetector.onTouchEvent(event)
            gestureDetector.onTouchEvent(event)
            view.parent?.requestDisallowInterceptTouchEvent(scaleDetector.isInProgress)
            if (event.actionMasked == MotionEvent.ACTION_CANCEL) tap = null
            true
        }
        try {
            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                if (generation != bindGeneration || this.owner !== owner) return@addListener
                try {
                    provider = future.get()
                    bindWhenReady()
                } catch (error: Exception) { report(error) }
            }, mainExecutor)
        } catch (error: Exception) { report(error) }
    }

    private fun bindWhenReady() {
        val activeOwner = owner ?: return
        val activeProvider = provider ?: return
        if (!previewView.isAttachedToWindow || previewView.width <= 0 || previewView.height <= 0 ||
            activeOwner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        val rotation = previewView.display?.rotation ?: return
        val dimensions = Triple(previewView.width, previewView.height, rotation)
        if (attemptedViewport == dimensions) return
        val viewport = previewView.getViewPort(rotation) ?: return
        attemptedViewport = dimensions
        try {
            // Only this scanner's use cases are rebound when its actual viewport changes.
            activeProvider.unbind(preview, capture, analysis)
            camera = null
            preview.targetRotation = rotation
            capture.targetRotation = rotation
            analysis.targetRotation = rotation
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val group = UseCaseGroup.Builder().setViewPort(viewport)
                .addUseCase(preview).addUseCase(capture).addUseCase(analysis).build()
            camera = activeProvider.bindToLifecycle(activeOwner, selector, group)
        } catch (error: Exception) { report(error) }
    }

    fun takePicture(
        options: ImageCapture.OutputFileOptions,
        executor: Executor,
        callback: ImageCapture.OnImageSavedCallback,
    ) {
        checkNotNull(camera) { "Scanner camera is not bound" }
        val display = checkNotNull(previewView.display) { "Scanner preview is not attached" }
        // CameraX writes its original JPEG/EXIF using the visible display's rotation.
        capture.targetRotation = display.rotation
        capture.takePicture(options, executor, callback)
    }

    private fun focus(point: PointF) {
        val activeCamera = camera ?: return
        if (previewView.previewStreamState.value != PreviewView.StreamState.STREAMING) return
        try {
            val factory = previewView.meteringPointFactory
            val action = FocusMeteringAction.Builder(factory.createPoint(point.x, point.y, 1f / 6f), FocusMeteringAction.FLAG_AF)
                .addPoint(factory.createPoint(point.x, point.y, 1f / 4f), FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
            val request = activeCamera.cameraControl.startFocusAndMetering(action)
            focusRequest = request
            watch(request) { camera === activeCamera && focusRequest === request }
        } catch (error: Exception) { report(error) }
    }

    private fun watch(request: ListenableFuture<*>, isCurrent: () -> Boolean) {
        request.addListener({
            // Superseded gestures and closed camera sessions have intentionally cancelled work.
            if (isCurrent()) try { request.get() } catch (error: Exception) { report(error) }
        }, mainExecutor)
    }

    private fun report(error: Throwable) {
        onError?.invoke(if (error is ExecutionException) error.cause ?: error else error)
    }

    private fun detachUseCases() {
        camera = null
        focusRequest = null
        zoomRequest = null
        try { provider?.unbind(preview, capture, analysis) } catch (error: Exception) { report(error) }
    }

    fun unbind() {
        generation++
        owner?.lifecycle?.removeObserver(lifecycleObserver)
        owner = null
        previewView.removeOnLayoutChangeListener(layoutListener)
        previewView.removeOnAttachStateChangeListener(attachListener)
        previewView.setOnTouchListener(null)
        previewView.setOnClickListener(null)
        tap = null
        analysis.clearAnalyzer()
        detachUseCases()
        preview.setSurfaceProvider(null)
        attemptedViewport = null
        onError = null
    }
}
