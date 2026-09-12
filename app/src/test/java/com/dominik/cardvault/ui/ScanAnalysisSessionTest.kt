package com.dominik.cardvault.ui

import com.dominik.cardvault.model.CatalogSearchResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAnalysisSessionTest {
    @Test fun `more than ten thousand scans retain lifetime totals inside configured memory bounds`() {
        val limits = ScanAnalysisSessionLimits(maxTrackedScanIds = 128, maxTrackedAddedScanIds = 64)
        val session = (1..25_000).fold(ScanAnalysisSession(limits)) { state, index ->
            state.recordTerminal("scan-$index", ScanPipelineStage.MATCHED, card(index).scanSessionIdentityKey())
        }

        assertEquals(25_000, session.analyzed)
        assertEquals(25_000, session.unique)
        assertEquals(0, session.duplicates)
        assertTrue(session.trackedScanCount <= limits.maxTrackedScanIds)
        assertTrue(session.trackedIdentityCount <= limits.maxTrackedScanIds)
        assertEquals(1_048_576, session.identityGuardBytes)
    }

    @Test fun `duplicate guard remembers identity after exact history eviction`() {
        val limits = ScanAnalysisSessionLimits(maxTrackedScanIds = 2, maxTrackedAddedScanIds = 2)
        val original = card(1).scanSessionIdentityKey()
        val recentDuplicate = ScanAnalysisSession(limits)
            .recordTerminal("old", ScanPipelineStage.MATCHED, original)
            .recordTerminal("recent-duplicate", ScanPipelineStage.MATCHED, original)

        assertTrue(recentDuplicate.isDuplicate("recent-duplicate", original))
        val originalStillRemembered = recentDuplicate
            .recordTerminal("different-1", ScanPipelineStage.MATCHED, card(2).scanSessionIdentityKey())
        assertTrue(originalStillRemembered.isDuplicate("recent-duplicate", original))
        val agedOut = originalStillRemembered
            .recordTerminal("different-2", ScanPipelineStage.MATCHED, card(3).scanSessionIdentityKey())

        assertTrue(agedOut.isDuplicate("new", original))
        val rescanned = agedOut.recordTerminal("new", ScanPipelineStage.MATCHED, original)
        assertEquals(5, rescanned.analyzed)
        assertEquals(3, rescanned.unique)
        assertEquals(2, rescanned.duplicates)
        assertEquals(2, rescanned.trackedScanCount)
    }

    @Test fun `ancient identity remains duplicate after more than ten thousand later scans`() {
        val ancientIdentity = card(1).scanSessionIdentityKey()
        val session = (2..12_500).fold(
            ScanAnalysisSession(ScanAnalysisSessionLimits(maxTrackedScanIds = 64, maxTrackedAddedScanIds = 32))
                .recordTerminal("ancient", ScanPipelineStage.MATCHED, ancientIdentity),
        ) { state, index ->
            state.recordTerminal("scan-$index", ScanPipelineStage.MATCHED, card(index).scanSessionIdentityKey())
        }

        assertTrue(session.isDuplicate("rescan", ancientIdentity))
        val rescanned = session.recordTerminal("rescan", ScanPipelineStage.MATCHED, ancientIdentity)
        assertEquals(12_501, rescanned.analyzed)
        assertEquals(12_500, rescanned.unique)
        assertEquals(1, rescanned.duplicates)
        assertEquals(64, rescanned.trackedScanCount)
    }

    @Test fun `queued result keeps exact self identity across an arbitrarily long session`() {
        val identity = card(1).scanSessionIdentityKey()
        val limits = ScanAnalysisSessionLimits(maxTrackedScanIds = 2, maxTrackedAddedScanIds = 2)
        val retained = (2..1_000).fold(
            ScanAnalysisSession(limits)
                .recordTerminal("open-result", ScanPipelineStage.MATCHED, identity)
                .retainExactScanIds(listOf("open-result")),
        ) { state, index ->
            state.recordTerminal("scan-$index", ScanPipelineStage.MATCHED, card(index).scanSessionIdentityKey())
        }

        assertFalse(retained.isDuplicate("open-result", identity))
        assertEquals(2, retained.trackedScanCount)
        val skipped = retained.recordSkipped("open-result")
        assertEquals(1_000, skipped.analyzed)
        assertEquals(999, skipped.unique)
        assertEquals(1, skipped.skipped)
    }

    @Test fun `added idempotence horizon is independently bounded while lifetime total survives`() {
        val limits = ScanAnalysisSessionLimits(maxTrackedScanIds = 2, maxTrackedAddedScanIds = 3)
        val session = (1..20).fold(ScanAnalysisSession(limits)) { state, index ->
            state.recordAdded("scan-$index")
        }

        assertEquals(20, session.added)
        assertEquals(3, session.trackedAddedScanCount)
        assertFalse(session.wasAdded("scan-1"))
        assertTrue(session.wasAdded("scan-20"))
    }

    @Test fun `terminal callbacks and successful adds are idempotent per scan`() {
        val identity = card(1).scanSessionIdentityKey()
        val terminal = ScanAnalysisSession()
            .recordTerminal("one", ScanPipelineStage.MATCHED, identity)
            .recordTerminal("one", ScanPipelineStage.FAILED)
        val added = terminal.recordAdded("one").recordAdded("one")

        assertEquals(1, added.analyzed)
        assertEquals(1, added.unique)
        assertEquals(0, added.skipped)
        assertEquals(1, added.added)
        assertTrue(added.wasAdded("one"))
        assertFalse(added.wasAdded("another"))
    }

    @Test fun `explicit skip reclassifies queued result without changing analyzed total`() {
        val identity = card(1).scanSessionIdentityKey()
        val session = ScanAnalysisSession()
            .recordTerminal("matched", ScanPipelineStage.MATCHED, identity)
            .recordTerminal("review", ScanPipelineStage.MANUAL_REVIEW)

        val skipped = session.recordSkipped("matched").recordSkipped("review").recordSkipped("review")

        assertEquals(2, skipped.analyzed)
        assertEquals(0, skipped.unique)
        assertEquals(0, skipped.review)
        assertEquals(2, skipped.skipped)
        assertTrue(skipped.isDuplicate("later", identity))
    }

    @Test fun `same print is duplicate while set print finish and variant remain distinct`() {
        val normal = card(1)
        val sameNormal = normal.copy(productId = 999, name = "Anderer OCR-Name")
        val reverse = normal.copy(finish = "Reverse Holo", variant = "Reverse Holo")
        val otherSet = normal.copy(sourceSetId = "sv-other", sourceCardId = "sv-other-001", expansionId = "other")
        val otherCollector = normal.copy(collectorNumber = "101", productId = 101)
        val session = ScanAnalysisSession()
            .recordTerminal("normal-1", ScanPipelineStage.MATCHED, normal.scanSessionIdentityKey())
            .recordTerminal("normal-2", ScanPipelineStage.MATCHED, sameNormal.scanSessionIdentityKey())
            .recordTerminal("reverse", ScanPipelineStage.MATCHED, reverse.scanSessionIdentityKey())
            .recordTerminal("other-set", ScanPipelineStage.MATCHED, otherSet.scanSessionIdentityKey())
            .recordTerminal("other-collector", ScanPipelineStage.MATCHED, otherCollector.scanSessionIdentityKey())

        assertEquals(5, session.analyzed)
        assertEquals(4, session.unique)
        assertEquals(1, session.duplicates)
        assertFalse(session.isDuplicate("normal-1", normal.scanSessionIdentityKey()))
        assertTrue(session.isDuplicate("normal-2", normal.scanSessionIdentityKey()))
    }

    @Test fun `manual review becomes an exact unique or duplicate identity after selection`() {
        val identity = card(1).scanSessionIdentityKey()
        val reviewed = ScanAnalysisSession()
            .recordTerminal("first", ScanPipelineStage.MATCHED, identity)
            .recordTerminal("manual", ScanPipelineStage.MANUAL_REVIEW)

        val registration = reviewed.registerIdentity("manual", identity)

        assertTrue(registration.isDuplicate)
        assertEquals(0, registration.session.review)
        assertEquals(1, registration.session.duplicates)
        assertEquals(2, registration.session.analyzed)
    }

    @Test fun `reclassifying first scan preserves duplicate warning for previously observed print`() {
        val firstIdentity = card(1).scanSessionIdentityKey()
        val correctedIdentity = card(2).scanSessionIdentityKey()
        val original = ScanAnalysisSession()
            .registerIdentity("first", firstIdentity).session
            .registerIdentity("second", firstIdentity).session

        val corrected = original.registerIdentity("first", correctedIdentity)

        assertFalse(corrected.isDuplicate)
        assertEquals(1, corrected.session.unique)
        assertEquals(1, corrected.session.duplicates)
        assertTrue(corrected.session.isDuplicate("second", firstIdentity))
        assertFalse(corrected.session.isDuplicate("first", correctedIdentity))
    }

    @Test fun `reclassifying scan onto occupied identity retains conservative session history`() {
        val firstIdentity = card(1).scanSessionIdentityKey()
        val secondIdentity = card(2).scanSessionIdentityKey()
        val session = ScanAnalysisSession()
            .registerIdentity("first", firstIdentity).session
            .registerIdentity("second", secondIdentity).session

        val corrected = session.registerIdentity("first", secondIdentity)

        assertTrue(corrected.isDuplicate)
        assertEquals(1, corrected.session.unique)
        assertEquals(1, corrected.session.duplicates)
        assertTrue(corrected.session.isDuplicate("new-scan", firstIdentity))
        assertTrue(corrected.session.isDuplicate("first", secondIdentity))
    }

    @Test fun `closing preview counts only unfinished scans as skipped without mutating live state`() {
        val live = ScanAnalysisSession().recordTerminal("done", ScanPipelineStage.FAILED)
        val summary = live.withSkipped(listOf("running", "running", "queued"))

        assertEquals(1, live.analyzed)
        assertEquals(1, live.skipped)
        assertEquals(3, summary.analyzed)
        assertEquals(3, summary.skipped)
    }

    @Test fun `closing preview is detached from subsequent live session updates`() {
        val live = ScanAnalysisSession().recordTerminal("done", ScanPipelineStage.FAILED)
        val summary = live.withSkipped(listOf("running"))
        val continued = live.recordTerminal("later", ScanPipelineStage.MANUAL_REVIEW)

        assertEquals(2, summary.analyzed)
        assertEquals(2, summary.skipped)
        assertEquals(2, continued.analyzed)
        assertEquals(1, continued.skipped)
        assertEquals(1, continued.review)
    }

    private fun card(number: Int) = CatalogSearchResult(
        productId = number.toLong(),
        name = "Karte $number",
        expansionId = "500",
        trendPriceEur = null,
        currency = "EUR",
        collectorNumber = number.toString().padStart(3, '0'),
        finish = "Normal",
        variant = "Standard",
        sourceSetId = "sv-test",
        sourceCardId = "sv-test-${number.toString().padStart(3, '0')}",
    )
}
