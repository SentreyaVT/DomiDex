package com.dominik.cardvault.data.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Inspects requests without enqueueing work or accessing Firebase/user data. */
@RunWith(AndroidJUnit4::class)
class CollectionSyncWorkRequestInstrumentedTest {
    @Test fun localEditsPrioritizeBothOwnerScopedWorkersOnAndroid12AndLater() {
        for (sdk in listOf(31, 37)) {
            val collection = CollectionSyncScheduler.request("fixture-owner", userInitiated = true, sdk = sdk)
            val binder = PrivateBinderSyncScheduler.request("fixture-owner", userInitiated = true, sdk = sdk)
            assertEquals(CollectionSyncWorker::class.java.name, collection.workSpec.workerClassName)
            assertEquals(PrivateBinderSyncWorker::class.java.name, binder.workSpec.workerClassName)
            assertEquals("fixture-owner", collection.workSpec.input.getString(CollectionSyncScheduler.INPUT_OWNER_UID))
            assertEquals("fixture-owner", binder.workSpec.input.getString(PrivateBinderSyncScheduler.INPUT_OWNER_UID))
            for (request in listOf(collection, binder)) {
                assertNull(request.workSpec.input.getString(SHARING_BINDER_ID))
                assertEquals(0L, request.workSpec.initialDelay)
                assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
                assertTrue(request.workSpec.expedited)
                assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, request.workSpec.outOfQuotaPolicy)
            }
        }
    }

    @Test fun olderAndroidDoesNotRequireForegroundServiceForLocalEdits() {
        for (sdk in listOf(26, 30)) {
            val requests = listOf(
                CollectionSyncScheduler.request("fixture-owner", userInitiated = true, sdk = sdk),
                PrivateBinderSyncScheduler.request("fixture-owner", userInitiated = true, sdk = sdk),
            )
            for (request in requests) {
                assertFalse(request.workSpec.expedited)
                assertEquals(0L, request.workSpec.initialDelay)
            }
        }
    }

    @Test fun backgroundContinuationDoesNotConsumeExpeditedQuota() {
        val requests = listOf(
            CollectionSyncScheduler.request("fixture-owner", userInitiated = false, sdk = 37),
            PrivateBinderSyncScheduler.request("fixture-owner", userInitiated = false, sdk = 37),
        )
        for (request in requests) {
            assertFalse(request.workSpec.expedited)
            assertEquals(0L, request.workSpec.initialDelay)
            assertEquals(NetworkType.CONNECTED, request.workSpec.constraints.requiredNetworkType)
        }
    }
}
