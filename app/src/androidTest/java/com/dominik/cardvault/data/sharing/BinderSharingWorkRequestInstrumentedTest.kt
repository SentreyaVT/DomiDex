package com.dominik.cardvault.data.sharing

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import com.dominik.cardvault.data.sync.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Builds requests only; never enqueues a worker or touches Firebase/user data. */
@RunWith(AndroidJUnit4::class)
class BinderSharingWorkRequestInstrumentedTest {
    @Test fun priorityChainTargetsExistingWorkersAndExactBinderWithNoInitialDelay() {
        val (collection, binder) = BinderSharingSyncScheduler.requests("fixture-owner", "fixture-binder", sdk = 31)
        assertEquals(CollectionSyncWorker::class.java.name, collection.workSpec.workerClassName)
        assertEquals(PrivateBinderSyncWorker::class.java.name, binder.workSpec.workerClassName)
        assertEquals("fixture-owner", collection.workSpec.input.getString(CollectionSyncScheduler.INPUT_OWNER_UID))
        assertEquals("fixture-owner", binder.workSpec.input.getString(PrivateBinderSyncScheduler.INPUT_OWNER_UID))
        for (request in listOf(collection, binder)) {
            assertEquals("fixture-binder", request.workSpec.input.getString(SHARING_BINDER_ID))
            assertEquals(0L, request.workSpec.initialDelay)
            assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
            assertTrue(request.workSpec.expedited)
            assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, request.workSpec.outOfQuotaPolicy)
        }
    }

    @Test fun preAndroid12RequestsDoNotRequireNewForegroundServiceBehavior() {
        for (sdk in listOf(26, 30)) {
            val requests = BinderSharingSyncScheduler.requests("fixture-owner", "fixture-binder", sdk)
            assertFalse(requests.first.workSpec.expedited)
            assertFalse(requests.second.workSpec.expedited)
            assertEquals(0L, requests.first.workSpec.initialDelay)
        }
    }
}
