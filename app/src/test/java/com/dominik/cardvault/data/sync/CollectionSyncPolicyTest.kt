package com.dominik.cardvault.data.sync

import com.dominik.cardvault.auth.FirebaseEntitlements
import com.dominik.cardvault.data.local.GUEST_OWNER_UID
import com.dominik.cardvault.data.local.SYNC_CLEAN
import com.dominik.cardvault.data.local.SYNC_CONFLICT
import com.dominik.cardvault.data.local.SYNC_PENDING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.dominik.cardvault.data.binderSetKey

class CollectionSyncPolicyTest {
    @Test fun missingAuthAlwaysUsesIsolatedGuestNamespace() {
        assertEquals(GUEST_OWNER_UID, collectionOwnerForUid(null))
        assertEquals(GUEST_OWNER_UID, collectionOwnerForUid(""))
        assertEquals("firebase-uid", collectionOwnerForUid("firebase-uid"))
    }

    @Test fun remoteSnapshotNeverOverwritesPendingOrConflictingLocalMutation() {
        assertTrue(shouldApplyRemote(SYNC_CLEAN, 2, 3))
        assertFalse(shouldApplyRemote(SYNC_CLEAN, 3, 3))
        assertFalse(shouldApplyRemote(SYNC_PENDING, 2, 99))
        assertFalse(shouldApplyRemote(SYNC_CONFLICT, 2, 99))
    }

    @Test fun cloudResolutionNeverRollsBackAListenerSnapshotAppliedDuringItsServerReread() {
        assertTrue(shouldApplyCloudResolutionSnapshot(localRevision = 3, remoteRevision = 3))
        assertTrue(shouldApplyCloudResolutionSnapshot(localRevision = 3, remoteRevision = 4))
        assertFalse(shouldApplyCloudResolutionSnapshot(localRevision = 4, remoteRevision = 3))
    }

    @Test fun remoteVersionSkippedWhilePendingIsApplicableAfterAckBeforeReread() {
        val committedLocalRevision = 3L
        val newerRemoteRevision = 4L
        assertFalse(shouldApplyRemote(SYNC_PENDING, 2L, newerRemoteRevision))
        assertTrue(shouldApplyRemote(SYNC_CLEAN, committedLocalRevision, newerRemoteRevision))
        assertTrue(shouldResumeServerReread(SYNC_CLEAN, SYNC_PENDING))
        assertFalse(shouldResumeServerReread(SYNC_PENDING, SYNC_PENDING))
    }

    @Test fun firestorePathIsStrictlyUidScoped() {
        assertEquals(
            "users/user-A/collectionEntries/entry-1",
            collectionEntryPath("user-A", "entry-1"),
        )
    }

    @Test fun workChainsAreStablePerOwnerAndNeverSharedAcrossOwners() {
        assertEquals(collectionSyncWorkName("user-A"), collectionSyncWorkName("user-A"))
        assertFalse(collectionSyncWorkName("user-A") == collectionSyncWorkName("user-B"))
        assertTrue(shouldRunCollectionSync("user-A", "user-A"))
        assertFalse(shouldRunCollectionSync("user-A", "user-B"))
        assertFalse(shouldRunCollectionSync(null, "user-A"))
    }

    @Test fun collectionCloudWorkRequiresAppAccessOrAdminForTheSameOwner() {
        assertTrue(shouldRunEntitledCollectionSync("user-A", "user-A", FirebaseEntitlements(appAccess = true)))
        assertTrue(shouldRunEntitledCollectionSync("user-A", "user-A", FirebaseEntitlements(admin = true)))
        assertFalse(shouldRunEntitledCollectionSync("user-A", "user-A", FirebaseEntitlements(pretester = true)))
        assertFalse(shouldRunEntitledCollectionSync("user-A", "user-B", FirebaseEntitlements(appAccess = true)))
    }

    @Test fun successfulFullBatchSchedulesContinuationWithoutFailureBackoff() {
        assertTrue(shouldScheduleCollectionSyncContinuation(processed = 200, limit = 200, hasPending = true))
        assertFalse(shouldScheduleCollectionSyncContinuation(processed = 199, limit = 200, hasPending = true))
        assertFalse(shouldScheduleCollectionSyncContinuation(processed = 200, limit = 200, hasPending = false))
    }

    @Test fun onlyStrictRevisionConflictReasonsCanBeExplicitlyRebased() {
        assertEquals(42L, parseRemoteConflictRevision(remoteConflictReason(42L)))
        assertEquals(null, parseRemoteConflictRevision(remoteDeletedConflictReason(42L)))
        assertEquals(null, parseRemoteConflictRevision("permission-denied"))
        assertEquals(null, parseRemoteConflictRevision("remote-revision--1"))
        assertEquals(null, parseRemoteConflictRevision("remote-revision-not-a-number"))
    }

    @Test fun firestoreLimitsRejectInsteadOfSilentlyTruncatingLocalData() {
        CollectionSyncLimits.requireText("Notiz", "a".repeat(CollectionSyncLimits.NOTES), CollectionSyncLimits.NOTES)
        runCatching {
            CollectionSyncLimits.requireText("Notiz", "a".repeat(CollectionSyncLimits.NOTES + 1), CollectionSyncLimits.NOTES)
        }.onSuccess { throw AssertionError("An oversized note must be rejected") }
        CollectionSyncLimits.requireQuantity(CollectionSyncLimits.QUANTITY)
        runCatching { CollectionSyncLimits.requireQuantity(CollectionSyncLimits.QUANTITY + 1) }
            .onSuccess { throw AssertionError("An oversized quantity must be rejected") }
    }

    @Test fun longTranslatedSetNamesKeepAStableBoundedInternalIdentity() {
        val visibleName = "Sehr langes deutsches Set ".repeat(20)
        val first = binderSetKey(null, visibleName)
        val second = binderSetKey(null, visibleName)
        assertEquals(first, second)
        assertTrue(first.length <= CollectionSyncLimits.SET_ID)
        assertTrue(visibleName.length > first.length)
    }
}
