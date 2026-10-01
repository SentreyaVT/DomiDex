package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.CardVaultDatabase

/**
 * Local-only, not wired into the Firebase worker. The metadata store and business DAO always
 * belong to the same Room database, so remote apply, generated outbox entries/tombstone receipts,
 * deferred markers and checkpoint either commit together or roll back together.
 *
 * The caller supplies an already captured session lease and an explicitly trusted read envelope.
 * This adapter establishes neither authentication/entitlements nor server timestamp provenance.
 * In particular, Source.SERVER alone does not prove a watermark is trustworthy. Missing point
 * reads and failed network requests are not payloads: never manufacture an empty map for them.
 * There is no network, ACK, scheduler, dependency fetch or conflict-resolution decision here.
 */
internal class PrivatePullRemoteRoomAdapter(
    database: CardVaultDatabase,
    isSessionCurrent: (PrivatePullSessionLease) -> Boolean,
    maximumDeferredPerGroup: Int = 2_000,
) {
    private val dao = database.cardVaultDao()
    private val store = PrivatePullRoomStore(database, isSessionCurrent, maximumDeferredPerGroup)

    suspend fun checkpoint(scope: PrivatePullScope): PrivatePullCheckpoint = store.checkpoint(scope)

    suspend fun deferredPage(
        scope: PrivatePullScope,
        afterDocumentId: String? = null,
        limit: Int = 50,
    ): List<PrivatePullDeferredMarker> = store.deferredPage(scope, afterDocumentId, limit)

    suspend fun commitFence(
        expected: PrivatePullCheckpoint,
        lease: PrivatePullSessionLease,
        read: PrivatePullFenceRead,
    ): PrivatePullCommitResult = privatePullCommitFence(store, expected, lease, read)

    suspend fun commitPage(
        expected: PrivatePullCheckpoint,
        lease: PrivatePullSessionLease,
        page: PrivatePullPage<Map<String, Any?>>,
    ): PrivatePullCommitResult {
        // Snapshot the caller-owned list and flat payload maps before our first suspension.
        // The existing parsers consume scalar Firestore fields; unknown nested fields are ignored.
        val stablePage = page.copy(documents = page.documents.map { it.copy(payload = it.payload.toMap()) })
        return privatePullCommitPage(store, expected, lease, stablePage) { document ->
            applyConfirmedDocument(expected.state.scope, document)
        }
    }

    suspend fun commitRecheck(
        expected: PrivatePullCheckpoint,
        lease: PrivatePullSessionLease,
        marker: PrivatePullDeferredMarker,
        document: PrivatePullDocument<Map<String, Any?>>,
        trust: PrivatePullReadTrust,
    ): PrivatePullCommitResult {
        val stableDocument = document.copy(payload = document.payload.toMap())
        return privatePullCommitRecheck(store, expected, lease, marker, stableDocument, trust) {
            applyConfirmedDocument(expected.state.scope, it)
        }
    }

    /** Invoked only inside the store transaction, after the protocol checked trust/pending data. */
    private suspend fun applyConfirmedDocument(
        scope: PrivatePullScope,
        document: PrivatePullDocument<Map<String, Any?>>,
    ): PrivatePullApplyDecision {
        val recordType = when (scope.collection) {
            PrivatePullCollection.BINDERS -> PRIVATE_SYNC_BINDER
            PrivatePullCollection.PLACEMENTS -> PRIVATE_SYNC_PLACEMENT
            PrivatePullCollection.LAYOUTS -> PRIVATE_SYNC_LAYOUT
            PrivatePullCollection.PRICE_SELECTIONS -> PRIVATE_SYNC_PRICE_SELECTION
        }
        return when (val result = applyPrivateRemoteRecord(
            dao, scope.ownerUid, recordType, document.documentId, document.payload, confirmedServer = true,
        )) {
            // Completed may itself create a placement-resolution outbox; never delete/ACK it.
            PrivateRemoteApplyOutcome.Completed,
            // Explicit invalid payload/immutable identity, not a missing parent or a read failure.
            PrivateRemoteApplyOutcome.Invalid,
            -> PrivatePullApplyDecision.Complete
            PrivateRemoteApplyOutcome.DeferredLocal -> PrivatePullApplyDecision.Defer(PrivatePullDeferReason.LOCAL_MUTATION)
            is PrivateRemoteApplyOutcome.RetryDependency -> PrivatePullApplyDecision.Defer(
                when (result.type) {
                    PrivateRemoteDependencyType.BINDER -> PrivatePullDeferReason.BINDER_DEPENDENCY
                    PrivateRemoteDependencyType.COLLECTION_ENTRY -> PrivatePullDeferReason.COLLECTION_DEPENDENCY
                },
                result.id,
            )
        }
    }
}
