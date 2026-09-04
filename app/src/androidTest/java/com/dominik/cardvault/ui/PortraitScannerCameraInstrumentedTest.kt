package com.dominik.cardvault.ui

import android.Manifest
import android.app.UiAutomation
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Emulator only: actual JPEG captures in the test activity supplied by ui-test-manifest.
 * MainActivity, the app scan queue, and account/collection operations are never started.
 * Display rotation exercises the fixed UI; sensor-pose injection is a separate emulator check.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class PortraitScannerCameraInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun originalCameraJpegsRemainPortraitForEveryDisplayRotation() {
        assertTrue("This test must only capture the emulator camera, never a physical phone",
            Build.HARDWARE in setOf("ranchu", "goldfish") || Build.MODEL.contains("sdk", ignoreCase = true))
        assertEquals("Grant emulator camera permission before running this test", PackageManager.PERMISSION_GRANTED,
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA))
        val automation = instrumentation.uiAutomation
        val autoRotate = Settings.System.getInt(context.contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0)
        val savedRotation = Settings.System.getInt(context.contentResolver, Settings.System.USER_ROTATION,
            context.getSystemService(WindowManager::class.java).defaultDisplay.rotation)
        val files = mutableListOf<File>()
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val frames = AtomicInteger()
        val cameraFailure = AtomicReference<Throwable?>(null)
        var scenario: ActivityScenario<ComponentActivity>? = null
        var scanner: PortraitScannerCamera? = null
        try {
            val activityScenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario = activityScenario
            activityScenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitUntil("portrait test activity", cameraFailure) {
                var portrait = false
                activityScenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                portrait
            }
            // Allow an initial orientation change to finish before recording the activity identity.
            SystemClock.sleep(350)
            lateinit var original: ComponentActivity
            lateinit var preview: PreviewView
            activityScenario.onActivity { activity ->
                original = activity
                preview = PreviewView(activity)
                activity.setContentView(preview)
                scanner = PortraitScannerCamera(activity, preview, CameraSelector.DEFAULT_BACK_CAMERA).also { camera ->
                    camera.bindToLifecycle(activity, analysisExecutor, ImageAnalysis.Analyzer { image ->
                        try { frames.incrementAndGet() } finally { image.close() }
                    }, cameraFailure::set)
                }
            }
            waitUntil("streaming camera and analyzer", cameraFailure) {
                var streaming = false
                activityScenario.onActivity {
                    streaming = scanner?.cameraControl != null &&
                        preview.previewStreamState.value == PreviewView.StreamState.STREAMING
                }
                streaming && frames.get() > 0
            }

            listOf(Surface.ROTATION_0, Surface.ROTATION_90, Surface.ROTATION_180, Surface.ROTATION_270)
                .forEach { rotation ->
                    assertTrue("Could not simulate display rotation $rotation", automation.setRotation(rotation))
                    val previousFrames = frames.get()
                    waitUntil("fresh analysis frames after rotation $rotation", cameraFailure) { frames.get() >= previousFrames + 2 }
                    activityScenario.onActivity { activity ->
                        assertSame("Camera activity recreated for rotation $rotation", original, activity)
                        assertEquals(Configuration.ORIENTATION_PORTRAIT, activity.resources.configuration.orientation)
                        assertTrue("Preview must remain portrait", preview.height > preview.width)
                    }
                    val image = File.createTempFile("portrait-scanner-test-", ".jpg", context.cacheDir).also(files::add)
                    val saved = CountDownLatch(1)
                    val captureFailure = AtomicReference<ImageCaptureException?>(null)
                    activityScenario.onActivity {
                        scanner!!.takePicture(ImageCapture.OutputFileOptions.Builder(image).build(),
                            ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { saved.countDown() }
                                override fun onError(exception: ImageCaptureException) {
                                    captureFailure.set(exception)
                                    saved.countDown()
                                }
                            })
                    }
                    assertTrue("Camera capture timed out for rotation $rotation", saved.await(15, TimeUnit.SECONDS))
                    cameraFailure.get()?.let { throw AssertionError("Camera session failed", it) }
                    assertNull("Capture failed for rotation $rotation", captureFailure.get())
                    assertTrue("No JPEG was saved for rotation $rotation", image.length() > 0L)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(image.absolutePath, bounds)
                    assertTrue("Invalid JPEG dimensions", bounds.outWidth > 0 && bounds.outHeight > 0)
                    val exif = ExifInterface(image.absolutePath)
                    val swapsAxes = exif.rotationDegrees in setOf(90, 270)
                    val width = if (swapsAxes) bounds.outHeight else bounds.outWidth
                    val height = if (swapsAxes) bounds.outWidth else bounds.outHeight
                    assertTrue("JPEG displays as ${width}x$height after EXIF at display rotation $rotation", height > width)
                    activityScenario.onActivity {
                        assertSame("Capture recreated the activity", original, it)
                        assertFalse("Capture closed the activity", it.isFinishing || it.isDestroyed)
                    }
                }
        } finally {
            try {
                instrumentation.runOnMainSync { scanner?.unbind() }
                analysisExecutor.shutdown()
                assertTrue("Analyzer did not stop after unbind", analysisExecutor.awaitTermination(5, TimeUnit.SECONDS))
            } finally {
                try { scenario?.close() } finally {
                    try {
                        assertTrue("Could not restore saved rotation", automation.setRotation(savedRotation))
                        if (autoRotate != 0) assertTrue("Could not restore automatic rotation", automation.setRotation(UiAutomation.ROTATION_UNFREEZE))
                    } finally {
                        files.forEach { file -> assertTrue("Could not remove test capture ${file.name}", !file.exists() || file.delete()) }
                    }
                }
            }
        }
    }

    private fun waitUntil(description: String, failure: AtomicReference<Throwable?>, predicate: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            failure.get()?.let { throw AssertionError("Camera failed while waiting for $description", it) }
            if (predicate()) return
            SystemClock.sleep(75)
        }
        error("Timed out waiting for $description")
    }
}
