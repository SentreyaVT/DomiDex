package com.dominik.cardvault.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanPipelineStateTest {
    @Test fun `pipeline follows capture to match in order`() {
        assertTrue(ScanPipelineStage.CAPTURED.canTransitionTo(ScanPipelineStage.PREPROCESSING))
        assertTrue(ScanPipelineStage.PREPROCESSING.canTransitionTo(ScanPipelineStage.OCR))
        assertTrue(ScanPipelineStage.OCR.canTransitionTo(ScanPipelineStage.SET_ANALYSIS))
        assertTrue(ScanPipelineStage.CANDIDATE_SEARCH.canTransitionTo(ScanPipelineStage.ARTWORK_VERIFICATION))
        assertTrue(ScanPipelineStage.PRICE_LOOKUP.canTransitionTo(ScanPipelineStage.MATCHED))
    }

    @Test fun `terminal scans cannot be overwritten by late callbacks`() {
        assertFalse(ScanPipelineStage.MATCHED.canTransitionTo(ScanPipelineStage.OCR))
        assertFalse(ScanPipelineStage.FAILED.canTransitionTo(ScanPipelineStage.MATCHED))
        assertFalse(ScanPipelineStage.SAVED.canTransitionTo(ScanPipelineStage.CANDIDATE_SEARCH))
    }
}
