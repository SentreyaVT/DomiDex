package com.dominik.cardvault.ui

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ArtworkBatchVerificationTest {
    @Test fun `candidate variants share one download with at most two concurrent references`() = runBlocking {
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val requests = AtomicInteger()
        val results = verifyArtworkCandidates(emptyList(), mapOf(
            "normal" to "art-A", "reverse" to "art-A", "B" to "art-B", "C" to "art-C", "missing" to null,
        )) {
            requests.incrementAndGet()
            val count = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, count) }
            try { delay(20); null } finally { active.decrementAndGet() }
        }
        assertEquals(3, requests.get())
        assertEquals(2, maxActive.get())
        assertEquals(5, results.size)
        assertTrue(results.values.all { it.match == ArtworkMatch.UNAVAILABLE })
    }

    @Test fun `parent cancellation stops active downloads and prevents queued requests`() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val requests = AtomicInteger()
        val cancelled = AtomicInteger()
        val job = launch {
            verifyArtworkCandidates(emptyList(), (1..4).associate { "$it" to "art-$it" }) {
                if (requests.incrementAndGet() == 2) started.complete(Unit)
                try { delay(60_000); null } finally { cancelled.incrementAndGet() }
            }
        }
        withTimeout(1_000) { started.await() }
        job.cancelAndJoin()
        assertEquals(2, requests.get())
        assertEquals(2, cancelled.get())
    }

    @Test fun `reuse key never mixes rotated crops preprocessing or coordinates`() {
        val key = OcrReadKey(CardRoi(0f, .8f, 1f, .95f), "original", AnalysisOrientation.DEG_0, 1200, 1680)
        assertEquals(key, key.copy())
        assertNotEquals(key, key.copy(orientation = AnalysisOrientation.DEG_180))
        assertNotEquals(key, key.copy(variant = "inverted"))
        assertNotEquals(key, key.copy(roi = CardRoi(0f, .7f, 1f, .95f)))
        assertNotEquals(key, key.copy(sourceWidth = 900))
    }
}
