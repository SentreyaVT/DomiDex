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
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
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
 * Separately selected emulator test: one genuine CameraX CREATED -> RESUMED cycle.
 * Never included by the existing class-wide 371-case selector.
 * Root must prove virtual camera only (no host-webcam passthrough) before execution.
 * No MainActivity, real card, scan queue, identity, Firebase, or physical phone.
 * Not HOME/process-death/permission-revocation, OEM, or full Gate-7 evidence.
 */
@Suppress("DEPRECATION")
@RunWith(AndroidJUnit4::class)
class PortraitScannerCameraLifecycleInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    /** One actual CameraX stop/resume cycle; no MainActivity, OS permission change, or scan queue. */
    @Test fun cameraStopsInCreatedAndCapturesFreshJpegAfterResume() {
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
        val captureCallbacks = AtomicInteger()
        var scenario: ActivityScenario<ComponentActivity>? = null
        var scanner: PortraitScannerCamera? = null
        try {
            val activityScenario = ActivityScenario.launch(ComponentActivity::class.java)
            scenario = activityScenario
            activityScenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
            waitUntil("portrait lifecycle test activity", cameraFailure) {
                var portrait = false
                activityScenario.onActivity { portrait = it.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT }
                portrait
            }
            // Preserve the rotation test's initial-orientation settling and identity boundary.
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
            waitUntil("streaming camera and analyzer before stop", cameraFailure) {
                var streaming = false
                activityScenario.onActivity {
                    streaming = scanner?.cameraControl != null &&
                        preview.previewStreamState.value == PreviewView.StreamState.STREAMING
                }
                streaming && frames.get() >= 2
            }
            activityScenario.onActivity {
                assertSame("Initial camera activity changed", original, it)
                assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
            }

            // CREATED drives ON_PAUSE and ON_STOP. STARTED alone leaves CameraX active.
            activityScenario.moveToState(Lifecycle.State.CREATED)
            assertEquals(Lifecycle.State.CREATED, activityScenario.state)
            var lastObservedFrames = frames.get()
            var quietSince = SystemClock.elapsedRealtime()
            waitUntil("idle preview and settled analyzer after stop", cameraFailure) {
                val now = SystemClock.elapsedRealtime()
                val currentFrames = frames.get()
                if (currentFrames != lastObservedFrames) {
                    lastObservedFrames = currentFrames
                    quietSince = now
                }
                var idle = false
                instrumentation.runOnMainSync {
                    assertEquals(Lifecycle.State.CREATED, original.lifecycle.currentState)
                    idle = preview.previewStreamState.value == PreviewView.StreamState.IDLE
                }
                // Drain callbacks already in flight; require both a stopped preview and
                // a full continuous quiet window within the unchanged 15-second bound.
                idle && now - quietSince >= 1_000L
            }
            val stoppedFrames = frames.get()
            val holdUntil = SystemClock.elapsedRealtime() + 1_000L
            while (SystemClock.elapsedRealtime() < holdUntil) {
                cameraFailure.get()?.let { throw AssertionError("Camera failed while stopped", it) }
                assertEquals("Analyzer restarted while activity remained stopped", stoppedFrames, frames.get())
                instrumentation.runOnMainSync {
                    assertEquals(Lifecycle.State.CREATED, original.lifecycle.currentState)
                    assertEquals(PreviewView.StreamState.IDLE, preview.previewStreamState.value)
                }
                SystemClock.sleep(50)
            }
            assertEquals("Analyzer emitted at end of stopped hold", stoppedFrames, frames.get())
            assertEquals("No capture was requested while stopped", 0, captureCallbacks.get())

            // No explicit rebind or new wrapper: recovery must come from the production binding.
            activityScenario.moveToState(Lifecycle.State.RESUMED)
            waitUntil("fresh streaming frames after resume", cameraFailure) {
                var streaming = false
                activityScenario.onActivity {
                    assertSame("Stop/resume recreated camera activity", original, it)
                    assertEquals(Lifecycle.State.RESUMED, it.lifecycle.currentState)
                    streaming = scanner?.cameraControl != null &&
                        preview.previewStreamState.value == PreviewView.StreamState.STREAMING
                }
                streaming && frames.get() >= stoppedFrames + 2
            }
            activityScenario.onActivity {
                assertEquals(Configuration.ORIENTATION_PORTRAIT, it.resources.configuration.orientation)
                assertTrue("Resumed preview must remain portrait", preview.height > preview.width)
            }
            val image = File.createTempFile("lifecycle-scanner-test-", ".jpg", context.cacheDir).also(files::add)
            val saved = CountDownLatch(1)
            val captureFailure = AtomicReference<ImageCaptureException?>(null)
            activityScenario.onActivity {
                scanner!!.takePicture(ImageCapture.OutputFileOptions.Builder(image).build(),
                    ContextCompat.getMainExecutor(context), object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            captureCallbacks.incrementAndGet()
                            saved.countDown()
                        }
                        override fun onError(exception: ImageCaptureException) {
                            captureCallbacks.incrementAndGet()
                            captureFailure.set(exception)
                            saved.countDown()
                        }
                    })
            }
            assertTrue("Fresh capture timed out after resume", saved.await(15, TimeUnit.SECONDS))
            cameraFailure.get()?.let { throw AssertionError("Resumed camera session failed", it) }
            assertNull("Fresh capture failed after resume", captureFailure.get())
            assertTrue("No fresh JPEG was saved after resume", image.length() > 0L)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(image.absolutePath, bounds)
            assertEquals("Fresh capture is not a JPEG", "image/jpeg", bounds.outMimeType)
            assertTrue("Invalid fresh JPEG dimensions", bounds.outWidth > 0 && bounds.outHeight > 0)
            val exif = ExifInterface(image.absolutePath)
            val swapsAxes = exif.rotationDegrees in setOf(90, 270)
            val width = if (swapsAxes) bounds.outHeight else bounds.outWidth
            val height = if (swapsAxes) bounds.outWidth else bounds.outHeight
            assertTrue("Resumed JPEG displays as ${width}x$height after EXIF", height > width)
            instrumentation.waitForIdleSync()
            assertEquals("Fresh capture must have one terminal callback", 1, captureCallbacks.get())
            activityScenario.onActivity {
                assertSame("Fresh capture recreated camera activity", original, it)
                assertFalse("Fresh capture closed camera activity", it.isFinishing || it.isDestroyed)
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
