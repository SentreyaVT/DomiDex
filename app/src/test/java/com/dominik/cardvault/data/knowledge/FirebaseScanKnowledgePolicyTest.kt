package com.dominik.cardvault.data.knowledge

import androidx.work.ExistingWorkPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class FirebaseScanKnowledgePolicyTest {
    @Test fun aWakeupDuringRunningUploadIsAppendedInsteadOfDropped() {
        assertEquals(ExistingWorkPolicy.APPEND_OR_REPLACE, ScanKnowledgeScheduler.existingWorkPolicy)
    }
}
