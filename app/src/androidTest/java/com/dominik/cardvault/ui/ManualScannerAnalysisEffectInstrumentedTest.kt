package com.dominik.cardvault.ui

import android.graphics.Bitmap
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.dominik.cardvault.model.CatalogSearchResult
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The production ScannerScreen effect with synthetic identities and controlled async boundaries.
 * No camera, ML Kit, HTTP, Firebase, account, photos or collection database is used. */
@RunWith(AndroidJUnit4::class)
class ManualScannerAnalysisEffectInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val visible = mutableStateOf(true)
    private val selected = mutableStateOf(ManualScannerAnalysisContext(null, false, null, null))
    private val recompositionTick = mutableStateOf(0)
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private val bitmaps = mutableListOf<Bitmap>()
    @Volatile private var observed = Observed()
    @Volatile private var lastComposedTick = 0
    private var clearOnAccepted = false

    private data class Observed(
        val started: List<String> = emptyList(),
        val resolved: List<String> = emptyList(),
        val artwork: String? = null,
        val candidatesLoading: Boolean = false,
        val artworkLoading: Boolean = false,
        val artworkStarted: List<String> = emptyList(),
        val artworkFinished: List<String> = emptyList(),
        val resolverFinished: List<String> = emptyList(),
        val decision: ScannerDecision? = null,
        val accepted: List<String> = emptyList(),
        val rejected: Int = 0,
        val writes: Int = 0,
    )

    @After fun releaseSyntheticWork() {
        compose.runOnIdle {
            visible.value = false
            gates.forEach { it.complete(Unit) }
        }
        compose.waitForIdle()
        // The fake verifier never reads bitmap pixels; the effect only checks non-nullness.
        bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    }

    @Test fun lateResolverFromAIsNotAppliedAfterBHasResolved() {
        val a = analysis("A")
        val b = analysis("B")
        val releaseA = gate()
        show(resolve = { current ->
            if (current === a) withContext(NonCancellable) { releaseA.await() }
            resolution(current)
        })
        select(a)
        await { "A" in it.started }
        select(b)
        await { it.resolved == listOf("B") }
        complete(releaseA)
        await { "A" in it.resolverFinished }
        compose.runOnIdle { assertEquals(listOf("B"), observed.resolved) }
    }

    @Test fun lateArtworkFromAIsNotAppliedToB() {
        val a = analysis("A", artwork = true)
        val b = analysis("B", artwork = true)
        val releaseA = gate()
        show(artwork = { current, _ ->
            if (current === a) withContext(NonCancellable) { releaseA.await() }
            artwork(current)
        })
        select(a)
        await { "A" in it.artworkStarted }
        select(b)
        await { it.artwork == "B" }
        complete(releaseA)
        await { "A" in it.artworkFinished }
        compose.runOnIdle { assertEquals("B", observed.artwork) }
    }

    @Test fun lateArtworkFinallyFromACannotClearBLoading() {
        val a = analysis("A", artwork = true)
        val b = analysis("B", artwork = true)
        val releaseA = gate()
        val releaseB = gate()
        show(artwork = { current, _ ->
            withContext(NonCancellable) { (if (current === a) releaseA else releaseB).await() }
            artwork(current)
        })
        select(a)
        await { "A" in it.artworkStarted }
        select(b)
        await { "B" in it.artworkStarted && it.artworkLoading }
        complete(releaseA)
        await { "A" in it.artworkFinished }
        compose.runOnIdle {
            assertTrue("B still owns its loading state", observed.artworkLoading)
            assertNull(observed.artwork)
        }
    }

    @Test fun invalidFrameClearsPreviousLoadingAndRejectsLateArtwork() {
        val a = analysis("A", artwork = true)
        val invalidB = analysis("B").copy(imageQuality = CardImageQuality(reason = "card_frame_missing"))
        val releaseA = gate()
        show(artwork = { current, _ ->
            withContext(NonCancellable) { releaseA.await() }
            artwork(current)
        })
        select(a)
        await { "A" in it.artworkStarted && it.artworkLoading }
        select(invalidB)
        await { it.rejected == 1 }
        compose.runOnIdle {
            assertFalse("Invalid B must not retain A's artwork loading indicator", observed.artworkLoading)
            assertFalse(observed.candidatesLoading)
            assertTrue(observed.resolved.isEmpty())
        }
        val beforeLateA = observed.writes
        complete(releaseA)
        await { "A" in it.artworkFinished }
        compose.runOnIdle {
            assertEquals(beforeLateA, observed.writes)
            assertNull(observed.artwork)
        }
    }

    @Test fun observedAToBToAInvalidatesTheOriginalAGeneration() {
        val a = analysis("A")
        val b = analysis("B")
        val releaseOriginalA = gate()
        var aCalls = 0
        show(resolve = { current ->
            val label = if (current === a) {
                if (++aCalls == 1) {
                    withContext(NonCancellable) { releaseOriginalA.await() }
                    "original-A"
                } else "current-A"
            } else "B"
            resolution(current).let { result ->
                result.copy(candidates = result.candidates.map { it.copy(card = it.card.copy(name = label)) })
            }
        })
        select(a)
        await { it.started == listOf("A") }
        select(b)
        await { it.resolved == listOf("B") }
        select(a) // The very same analysis object and selection key; only the effect generation changed.
        await { it.resolved == listOf("current-A") && it.started.count { name -> name == "A" } == 2 }
        complete(releaseOriginalA)
        await { it.resolverFinished.count { name -> name == "A" } == 2 }
        compose.runOnIdle { assertEquals(listOf("current-A"), observed.resolved) }
    }

    @Test fun sameAnalysisEnteringAnotherQueueReviewStopsManualResolver() {
        val a = analysis("A")
        val releaseA = gate()
        show(resolve = { current ->
            withContext(NonCancellable) { releaseA.await() }
            resolution(current)
        })
        select(a)
        await { "A" in it.started }
        compose.runOnIdle {
            selected.value = selected.value.copy(queueReviewMode = true, selectedScanId = "other-queue-scan")
            observed = observed.copy(resolved = listOf("queue-owned"), decision = ScannerDecision.ACCEPTED)
        }
        compose.waitForIdle()
        complete(releaseA)
        await { "A" in it.resolverFinished }
        compose.runOnIdle {
            assertEquals(listOf("queue-owned"), observed.resolved)
            assertEquals(ScannerDecision.ACCEPTED, observed.decision)
            assertFalse(observed.candidatesLoading)
            assertFalse(observed.artworkLoading)
        }
    }

    @Test fun liveQueueContextChangeBeforeRecompositionPreventsOldResultPublication() {
        show(resolve = { current ->
            // The async result and a queued-review selection meet in the same Main callback.
            // Compose has not yet had an opportunity to cancel/restart the old effect.
            selected.value = selected.value.copy(queueReviewMode = true, selectedScanId = "queued-B")
            observed = observed.copy(resolved = listOf("queue-owned"), decision = ScannerDecision.ACCEPTED)
            resolution(current)
        })
        select(analysis("A"))
        await { "A" in it.resolverFinished }
        compose.runOnIdle {
            assertEquals(listOf("queue-owned"), observed.resolved)
            assertEquals(ScannerDecision.ACCEPTED, observed.decision)
        }
    }

    @Test fun closingTheScreenSuppressesLateResolverWrites() {
        val a = analysis("A")
        val releaseA = gate()
        show(resolve = { current ->
            withContext(NonCancellable) { releaseA.await() }
            resolution(current)
        })
        select(a)
        await { "A" in it.started }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val before = observed.writes
        complete(releaseA)
        await { "A" in it.resolverFinished }
        compose.runOnIdle { assertEquals(before, observed.writes) }
    }

    @Test fun closingTheScreenSuppressesLateArtworkIncludingFinally() {
        val a = analysis("A", artwork = true)
        val releaseA = gate()
        show(artwork = { current, _ ->
            withContext(NonCancellable) { releaseA.await() }
            artwork(current)
        })
        select(a)
        await { "A" in it.artworkStarted }
        compose.runOnIdle { visible.value = false }
        compose.waitForIdle()
        val before = observed.writes
        complete(releaseA)
        await { "A" in it.artworkFinished }
        compose.runOnIdle { assertEquals(before, observed.writes) }
    }

    @Test fun acceptedResultIsAppliedImmediatelyAndClearingAnalysisPreventsLateArtwork() {
        val a = analysis("A", artwork = true)
        val releaseArtwork = gate()
        clearOnAccepted = true
        show(resolve = { resolution(it, ScannerDecision.ACCEPTED) }, artwork = { current, _ ->
            withContext(NonCancellable) { releaseArtwork.await() }
            artwork(current)
        })
        select(a)
        await { it.accepted == listOf("A") }
        compose.runOnIdle {
            assertFalse("Acceptance must not await artwork", releaseArtwork.isCompleted)
            assertNull(selected.value.analysis)
            assertEquals(ScannerDecision.ACCEPTED, observed.decision)
            assertEquals(listOf("A"), observed.resolved)
            assertFalse(observed.candidatesLoading)
            assertFalse(observed.artworkLoading)
        }
        complete(releaseArtwork)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf("A"), observed.accepted)
            assertEquals(listOf("A"), observed.resolved)
            assertNull("The ended analysis cannot publish artwork into the next scan", observed.artwork)
        }
    }

    @Test fun ordinaryResolverFailureKeepsManualReviewFallback() {
        show(resolve = { throw IOException("synthetic-unavailable") })
        select(analysis("A"))
        await { it.rejected == 1 }
        compose.runOnIdle {
            assertEquals(ScannerDecision.REJECTED, observed.decision)
            assertTrue(observed.resolved.isEmpty())
            assertTrue(observed.accepted.isEmpty())
        }
    }

    @Test fun unchangedContextRecompositionDoesNotStartAnotherLookup() {
        val a = analysis("A")
        show()
        select(a)
        await { it.resolved == listOf("A") }
        compose.runOnIdle {
            selected.value = selected.value.copy()
            recompositionTick.value++
        }
        compose.waitUntil(5_000) { lastComposedTick == 1 }
        compose.runOnIdle { assertEquals(listOf("A"), observed.started) }
    }

    private fun show(
        resolve: suspend (CardTextAnalysis) -> ScanCandidateResolution = { resolution(it) },
        artwork: suspend (CardTextAnalysis, List<ScanCandidate>) -> Map<String, ArtworkVerification> = { current, _ -> artwork(current) },
    ) {
        compose.setContent {
            val tick = recompositionTick.value
            SideEffect { lastComposedTick = tick }
            if (visible.value) {
                ManualScannerAnalysisEffect(selected.value, { selected.value },
                    resolve = { current ->
                        try { resolve(current) }
                        finally { observed = observed.copy(resolverFinished = observed.resolverFinished + id(current)) }
                    },
                    verifyArtwork = { current, candidates ->
                        observed = observed.copy(artworkStarted = observed.artworkStarted + id(current))
                        try { artwork(current, candidates) }
                        finally { observed = observed.copy(artworkFinished = observed.artworkFinished + id(current)) }
                    },
                    callbacks = ManualScannerAnalysisCallbacks(
                        inactive = { write { copy(candidatesLoading = false, artworkLoading = false) } },
                        // Mirrors the existing ScannerScreen invalid-frame callback: the new
                        // regression must prove its missing artworkLoading reset before fixing it.
                        invalidFrame = { write { copy(rejected = rejected + 1, resolved = emptyList(), candidatesLoading = false) } },
                        started = { current -> write { copy(started = started + id(current), candidatesLoading = true, artwork = null, artworkLoading = false) } },
                        resolved = { candidates -> write { copy(resolved = candidates.map { it.card.name }, candidatesLoading = false) } },
                        artworkLoading = { value -> write { copy(artworkLoading = value) } },
                        artwork = { result, _ -> write { copy(artwork = result.keys.single()) } },
                        decision = { _, result -> write { copy(decision = result.decision) } },
                        accepted = { current, _, _ ->
                            write { copy(accepted = accepted + id(current)) }
                            if (clearOnAccepted) selected.value = selected.value.copy(analysis = null, queueReviewMode = false)
                        },
                        rejected = { write { copy(rejected = rejected + 1) } },
                    ),
                )
            }
        }
        compose.waitForIdle()
    }

    private fun write(change: Observed.() -> Observed) { observed = observed.change().copy(writes = observed.writes + 1) }
    private fun gate() = CompletableDeferred<Unit>().also(gates::add)
    private fun complete(gate: CompletableDeferred<Unit>) { compose.runOnIdle { gate.complete(Unit) }; compose.waitForIdle() }
    private fun await(predicate: (Observed) -> Boolean) { compose.waitUntil(5_000) { predicate(observed) }; compose.waitForIdle() }
    private fun select(analysis: CardTextAnalysis) {
        compose.runOnIdle { selected.value = ManualScannerAnalysisContext(analysis, false, id(analysis), "synthetic-${id(analysis)}") }
        compose.waitForIdle()
    }
    private fun analysis(id: String, artwork: Boolean = false) = CardTextAnalysis(
        listOf(id), "001/100", "synthetic", null, emptyList(),
        imageQuality = CardImageQuality(cardDetected = true),
        normalizedCard = if (artwork) Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888).also(bitmaps::add) else null,
    )
    private fun id(analysis: CardTextAnalysis) = analysis.lines.single()
    private fun artwork(analysis: CardTextAnalysis) = mapOf(id(analysis) to ArtworkVerification(ArtworkMatch.CORROBORATES))
    private fun resolution(analysis: CardTextAnalysis, decision: ScannerDecision = ScannerDecision.AMBIGUOUS): ScanCandidateResolution {
        val card = CatalogSearchResult(1, id(analysis), "synthetic", null, null,
            imageUrl = "https://example.invalid/synthetic-not-requested", sourceCardId = id(analysis))
        return ScanCandidateResolution(listOf(ScanCandidate(card, 90)), ScannerDecisionResult(decision, 90, reason = "synthetic"))
    }
}
