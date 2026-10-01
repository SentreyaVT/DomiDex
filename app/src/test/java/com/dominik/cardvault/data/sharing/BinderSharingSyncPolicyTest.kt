package com.dominik.cardvault.data.sharing

import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import com.dominik.cardvault.data.sync.sharingSyncWorkName
import com.dominik.cardvault.data.sync.sharingSyncWorkPolicy
import org.junit.Assert.*
import org.junit.Test

class BinderSharingSyncPolicyTest {
    @Test fun activeOrFirstPendingChainIsKeptWithoutDuplicatePriorityWork() {
        for (states in listOf(emptyList(), listOf(WorkInfo.State.ENQUEUED to 0),
            listOf(WorkInfo.State.RUNNING to 0, WorkInfo.State.BLOCKED to 0),
            listOf(WorkInfo.State.RUNNING to 1, WorkInfo.State.ENQUEUED to 1))) {
            assertEquals(ExistingWorkPolicy.KEEP, sharingSyncWorkPolicy(states))
        }
    }

    @Test fun onlyNonRunningRetryQueueIsReplacedOnFreshManualTrigger() {
        assertEquals(ExistingWorkPolicy.REPLACE, sharingSyncWorkPolicy(listOf(WorkInfo.State.ENQUEUED to 1, WorkInfo.State.BLOCKED to 0)))
        assertEquals(ExistingWorkPolicy.REPLACE, sharingSyncWorkPolicy(listOf(WorkInfo.State.SUCCEEDED to 0, WorkInfo.State.ENQUEUED to 3)))
        assertEquals(ExistingWorkPolicy.KEEP, sharingSyncWorkPolicy(listOf(WorkInfo.State.FAILED to 1)))
        assertEquals(ExistingWorkPolicy.KEEP, sharingSyncWorkPolicy(listOf(WorkInfo.State.SUCCEEDED to 0)))
    }

    @Test fun queueIdentityIsOwnerAndBinderScopedWithoutPlaintextOwnerInName() {
        val name = sharingSyncWorkName("owner-a", "binder-a")
        assertEquals(name, sharingSyncWorkName("owner-a", "binder-a"))
        assertNotEquals(name, sharingSyncWorkName("owner-b", "binder-a"))
        assertNotEquals(name, sharingSyncWorkName("owner-a", "binder-b"))
        assertFalse(name.contains("owner-a"))
        assertFalse(name.contains("binder-a"))
        assertTrue(name.startsWith("domidex-sharing-sync-"))
    }
}
