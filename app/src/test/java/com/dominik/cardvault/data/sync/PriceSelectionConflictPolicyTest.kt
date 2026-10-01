package com.dominik.cardvault.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The production worker's commit policy, not a Firestore/Worker end-to-end fixture. */
class PriceSelectionConflictPolicyTest {
    @Test fun conflictMarkerAcceptsOnlyExactBoundedRevision() {
        assertEquals(0L, priceSelectionConflictRevision("price-selection-conflict:0"))
        assertEquals(3L, priceSelectionConflictRevision("price-selection-conflict:3"))
        for (reason in listOf(null, "", "remote-revision-3", "price-selection-conflict:-1", "price-selection-conflict:03",
            "price-selection-conflict:3.0", "price-selection-conflict: 3", "price-selection-conflict:9007199254740992")) {
            assertNull(priceSelectionConflictRevision(reason))
        }
    }
    @Test fun divergentPriceChoiceMustRemainForExplicitDecision() {
        assertEquals(PrivatePushCommitAction.MARK_PRICE_CONFLICT,
            privatePushCommitAction(true, "sent", "sent", PRIVATE_SYNC_PRICE_SELECTION))
    }

    @Test fun nonPricePoliciesStayUnchanged() {
        for (type in listOf(PRIVATE_SYNC_BINDER, PRIVATE_SYNC_PLACEMENT, PRIVATE_SYNC_LAYOUT)) {
            assertEquals(PrivatePushCommitAction.APPLY_REMOTE, privatePushCommitAction(true, "sent", "sent", type))
        }
    }

    @Test fun successfulAcknowledgementIsNotAConflict() {
        assertEquals(PrivatePushCommitAction.COMPLETE_CURRENT,
            privatePushCommitAction(false, "sent", "sent", PRIVATE_SYNC_PRICE_SELECTION))
    }

    @Test fun newerPriceMutationIsNeitherMarkedNorDiscarded() {
        assertEquals(PrivatePushCommitAction.KEEP_SUCCESSOR,
            privatePushCommitAction(true, "sent", "new", PRIVATE_SYNC_PRICE_SELECTION))
        assertEquals(PrivatePushCommitAction.REBASE_SUCCESSOR,
            privatePushCommitAction(false, "sent", "new", PRIVATE_SYNC_PRICE_SELECTION))
    }

    @Test fun absentMutationIsNotRecreated() {
        assertEquals(PrivatePushCommitAction.NONE,
            privatePushCommitAction(true, "sent", null, PRIVATE_SYNC_PRICE_SELECTION))
    }
}
