package com.dominik.cardvault.data.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class CollectionCloudStateTest {
    @Test fun signedInDoesNotImplySyncActive() {
        assertEquals(CollectionCloudStatus.CHECKING, collectionCloudStatus(true, false, false, false, false, false))
        assertEquals(CollectionCloudStatus.NO_ACCESS, collectionCloudStatus(false, false, true, false, false, false))
        assertEquals(CollectionCloudStatus.LOCAL_ONLY, collectionCloudStatus(false, false, false, false, false, false))
    }
    @Test fun activeNeedsServerSnapshotAndEmptyOutbox() {
        assertEquals(CollectionCloudStatus.LOCAL_ONLY, collectionCloudStatus(false, true, false, false, false, false))
        assertEquals(CollectionCloudStatus.PENDING, collectionCloudStatus(false, true, false, true, true, false))
        assertEquals(CollectionCloudStatus.ERROR, collectionCloudStatus(false, true, false, true, true, true))
        assertEquals(CollectionCloudStatus.ACTIVE, collectionCloudStatus(false, true, false, true, false, false))
    }
    @Test fun revocationOverridesCachedActiveState() {
        assertEquals(CollectionCloudStatus.NO_ACCESS, collectionCloudStatus(false, true, true, true, false, false))
    }
}
