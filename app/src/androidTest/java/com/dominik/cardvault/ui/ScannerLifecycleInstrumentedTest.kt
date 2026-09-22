package com.dominik.cardvault.ui

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScannerLifecycleInstrumentedTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val input get() = File(context.cacheDir, "scanner-lifecycle-no-photo-needed")

    @Test fun normalizationFailureFinishesAndFollowingScanCanComplete() {
        val calls = AtomicInteger()
        val first = AtomicReference<Result<CardTextAnalysis>>()
        val firstDone = CountDownLatch(1)
        val nextDone = CountDownLatch(1)
        CardTextAnalyzer(context, normalize = { _, _ ->
            if (calls.incrementAndGet() == 1) error("Simulated preprocessing failure")
            NormalizedCardCapture(null, CardImageQuality(reason = "card_frame_missing"))
        }).use { analyzer ->
            analyzer.analyze(input) { first.set(it); firstDone.countDown() }
            assertTrue(firstDone.await(3, TimeUnit.SECONDS))
            assertTrue(first.get().isFailure)
            analyzer.analyze(input) { assertTrue(it.isSuccess); nextDone.countDown() }
            assertTrue(nextDone.await(3, TimeUnit.SECONDS))
            assertEquals(2, calls.get())
        }
    }

    @Test fun totalWatchdogWinsOnceAndLatePreprocessingCannotRedeliver() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val firstDone = CountDownLatch(1)
        val nextDone = CountDownLatch(1)
        val deliveries = AtomicInteger()
        val result = AtomicReference<Result<CardTextAnalysis>>()
        CardTextAnalyzer(context, analysisTimeoutMillis = 500, normalize = { _, _ ->
            entered.countDown()
            assertTrue(release.await(3, TimeUnit.SECONDS))
            NormalizedCardCapture(null, CardImageQuality(reason = "card_frame_missing"))
        }).use { analyzer ->
            try {
                analyzer.analyze(input) { deliveries.incrementAndGet(); result.set(it); firstDone.countDown() }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                assertTrue(firstDone.await(2, TimeUnit.SECONDS))
                assertTrue(result.get().exceptionOrNull() is TimeoutException)
            } finally { release.countDown() }
            // This callback is queued after the old preprocessing and its cleanup.
            analyzer.analyze(input) { nextDone.countDown() }
            assertTrue(nextDone.await(2, TimeUnit.SECONDS))
            assertEquals(1, deliveries.get())
        }
    }

    @Test fun explicitCancellationFinishesPendingScanExactlyOnce() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val deliveries = AtomicInteger()
        CardTextAnalyzer(context, normalize = { _, _ ->
            entered.countDown()
            release.await(3, TimeUnit.SECONDS)
            NormalizedCardCapture(null, CardImageQuality())
        }).use { analyzer ->
            try {
                val handle = analyzer.analyze(input) {
                    assertTrue(it.exceptionOrNull() is java.util.concurrent.CancellationException)
                    deliveries.incrementAndGet()
                    cancelled.countDown()
                }
                assertTrue(entered.await(2, TimeUnit.SECONDS))
                handle.close(); handle.close()
                assertTrue(cancelled.await(2, TimeUnit.SECONDS))
                assertEquals(1, deliveries.get())
            } finally { release.countDown() }
        }
    }

    @Test fun rejectedTemporalFramesReleaseTheirBitmapsAndPreserveSelectedOutput() {
        repeat(50) {
            val keep = Bitmap.createBitmap(20, 28, Bitmap.Config.ARGB_8888)
            val reject = Bitmap.createBitmap(20, 28, Bitmap.Config.ARGB_8888)
            val good = CardTextAnalysis(emptyList(), "052/131", "MEG", null, emptyList(),
                imageQuality = CardImageQuality(cardDetected = true), normalizedCard = keep)
            val bad = good.copy(imageQuality = CardImageQuality(cardDetected = false), normalizedCard = reject)
            val merged = TemporalRecognitionEvidence.merge(listOf(good, bad, good.copy()))!!
            assertSame(keep, merged.normalizedCard)
            assertFalse(keep.isRecycled)
            assertTrue(reject.isRecycled)
            keep.recycle()
        }
    }
}
