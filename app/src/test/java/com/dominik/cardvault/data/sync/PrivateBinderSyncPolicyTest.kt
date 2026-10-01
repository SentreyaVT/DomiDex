package com.dominik.cardvault.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateBinderSyncPolicyTest {
    @Test fun blockedOrInvalidRemoteReplyMustNotDiscardCurrentMutation() {
        assertTrue(shouldRestorePrivateMutationAfterRemoteApply(PrivateRemoteApplyResult.DeferredLocal))
        assertTrue(shouldRestorePrivateMutationAfterRemoteApply(PrivateRemoteApplyResult.Invalid))
    }
    @Test fun completedParentContinuesImmediatelyButOfflineOrConflictedParentKeepsBackoff() {
        // The sidecar itself is still queued after its parent was acknowledged during the pull.
        assertEquals(PrivateSyncContinuation.APPEND, privateSyncContinuation(
            parentDependencyPending = false, hasMoreRemoteDocuments = false, hasPendingMutations = true))
        assertEquals(PrivateSyncContinuation.RETRY, privateSyncContinuation(
            parentDependencyPending = true, hasMoreRemoteDocuments = false, hasPendingMutations = true))
        assertEquals(PrivateSyncContinuation.RETRY, privateSyncContinuation(
            parentDependencyPending = true, hasMoreRemoteDocuments = true, hasPendingMutations = true))
        assertEquals(PrivateSyncContinuation.APPEND, privateSyncContinuation(
            parentDependencyPending = false, hasMoreRemoteDocuments = true, hasPendingMutations = false))
        assertEquals(PrivateSyncContinuation.COMPLETE, privateSyncContinuation(
            parentDependencyPending = false, hasMoreRemoteDocuments = false, hasPendingMutations = false))
    }

    @Test fun exactBaseRevisionAppliesButStaleOfflineEditCannotOverwriteRemote() {
        assertEquals(
            PrivatePushAction.APPLY,
            privatePushAction(4, 4, alreadyApplied = false, remoteDeleted = false, localDeleted = false),
        )
        assertEquals(
            PrivatePushAction.REMOTE_WINS,
            privatePushAction(5, 4, alreadyApplied = false, remoteDeleted = false, localDeleted = false),
        )
    }

    @Test fun tombstonesAreIdempotentAndCannotBeResurrected() {
        assertEquals(
            PrivatePushAction.ALREADY_APPLIED,
            privatePushAction(3, 2, alreadyApplied = true, remoteDeleted = true, localDeleted = true),
        )
        assertEquals(
            PrivatePushAction.REMOTE_WINS,
            privatePushAction(3, 3, alreadyApplied = false, remoteDeleted = true, localDeleted = false),
        )
        assertEquals(
            PrivatePushAction.APPLY,
            privatePushAction(3, 3, alreadyApplied = false, remoteDeleted = true, localDeleted = true),
        )
    }

    @Test fun successfulAckRebasesAReplacementButRemoteWinsNeverDoes() {
        assertEquals(
            PrivatePushCommitAction.REBASE_SUCCESSOR,
            privatePushCommitAction(remoteWins = false, sentMutationId = "sent", currentMutationId = "newer"),
        )
        assertEquals(
            PrivatePushCommitAction.KEEP_SUCCESSOR,
            privatePushCommitAction(remoteWins = true, sentMutationId = "sent", currentMutationId = "newer"),
        )
        assertEquals(
            PrivatePushCommitAction.COMPLETE_CURRENT,
            privatePushCommitAction(remoteWins = false, sentMutationId = "sent", currentMutationId = "sent"),
        )
        assertEquals(
            PrivatePushCommitAction.APPLY_REMOTE,
            privatePushCommitAction(remoteWins = true, sentMutationId = "sent", currentMutationId = "sent"),
        )
    }

    @Test fun remoteCursorStopsBeforeDocumentWhoseLocalDependencyHasNotArrived() {
        val afterFirst = privateRemoteCheckpointAfter(
            checkpoint = PrivateRemoteCheckpoint(cursor = "before", dependencyPending = false),
            documentId = "placement-1",
            result = PrivateRemoteApplyResult.Checkpoint,
        )
        val blocked = privateRemoteCheckpointAfter(
            checkpoint = afterFirst,
            documentId = "placement-2",
            result = PrivateRemoteApplyResult.RetryDependency(
                PrivateRemoteDependencyType.COLLECTION_ENTRY,
                "entry-2",
            ),
        )

        assertEquals("placement-1", blocked.cursor)
        assertEquals(true, blocked.dependencyPending)
    }

    @Test fun retryingDeferredDocumentAdvancesOnlyAfterItsDependencyExists() {
        val deferred = privateRemoteCheckpointAfter(
            checkpoint = PrivateRemoteCheckpoint(cursor = "placement-1", dependencyPending = false),
            documentId = "placement-2",
            result = PrivateRemoteApplyResult.RetryDependency(
                PrivateRemoteDependencyType.COLLECTION_ENTRY,
                "entry-2",
            ),
        )
        val retried = privateRemoteCheckpointAfter(
            checkpoint = PrivateRemoteCheckpoint(cursor = deferred.cursor, dependencyPending = false),
            documentId = "placement-2",
            result = PrivateRemoteApplyResult.Checkpoint,
        )

        assertEquals("placement-2", retried.cursor)
        assertEquals(false, retried.dependencyPending)
    }

    @Test fun remoteWinsKeepsItsDurableMutationWhileARequiredLocalRowIsMissing() {
        assertEquals(
            true,
            shouldRestorePrivateMutationAfterRemoteApply(
                PrivateRemoteApplyResult.RetryDependency(
                    PrivateRemoteDependencyType.COLLECTION_ENTRY,
                    "entry-2",
                ),
            ),
        )
        assertEquals(
            false,
            shouldRestorePrivateMutationAfterRemoteApply(PrivateRemoteApplyResult.Checkpoint),
        )
    }

    @Test fun periodicPrivateSyncIsStableOwnerScopedAndUsesWorkManagerMinimumInterval() {
        assertEquals(15L, PRIVATE_BINDER_PERIODIC_SYNC_INTERVAL_MINUTES)
        assertEquals(
            privateBinderPeriodicSyncWorkName("owner-a"),
            privateBinderPeriodicSyncWorkName("owner-a"),
        )
        assertNotEquals(
            privateBinderPeriodicSyncWorkName("owner-a"),
            privateBinderPeriodicSyncWorkName("owner-b"),
        )
        assertNotEquals(
            privateBinderSyncWorkName("owner-a"),
            privateBinderPeriodicSyncWorkName("owner-a"),
        )
    }
}
