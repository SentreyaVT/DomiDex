package com.dominik.cardvault.data.sync

import androidx.room.withTransaction
import com.dominik.cardvault.data.local.AppMetadataEntity
import com.dominik.cardvault.data.local.CardVaultDatabase
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Local-only v2 metadata. It neither changes the cloud schema nor replaces the user's outbox. */
internal class PrivatePullRoomStore(
    private val database: CardVaultDatabase,
    private val isSessionCurrent: (PrivatePullSessionLease) -> Boolean,
    private val maximumDeferredPerGroup: Int = 2_000,
) : PrivatePullStorePort {
    init { require(maximumDeferredPerGroup in 1..20_000) }
    private val dao get() = database.cardVaultDao()

    suspend fun checkpoint(scope: PrivatePullScope): PrivatePullCheckpoint =
        PrivatePullCodec.decode(dao.metadata(checkpointKey(scope)), scope)

    /** Bounded paging prevents one permanently blocked dependency from starving later markers. */
    suspend fun deferredPage(
        scope: PrivatePullScope,
        afterDocumentId: String? = null,
        limit: Int = 50,
    ): List<PrivatePullDeferredMarker> {
        require(limit in 1..PRIVATE_PULL_PAGE_SIZE)
        val prefix = deferredPrefix(scope)
        val afterKey = afterDocumentId?.let { markerKey(scope, it) } ?: prefix
        return dao.metadataRangePage(prefix, prefix + "~", afterKey, limit).map { row ->
            val marker = PrivatePullCodec.decodeMarker(row.value, scope)
                ?: throw PrivatePullMetadataException()
            if (row.key != markerKey(scope, marker.documentId)) throw PrivatePullMetadataException()
            marker
        }
    }

    override suspend fun compareAndCommit(
        expected: PrivatePullCheckpoint,
        lease: PrivatePullSessionLease,
        expectedMarker: PrivatePullDeferredMarker?,
        changes: suspend PrivatePullTransaction.() -> PrivatePullState,
    ): PrivatePullCommitResult {
        val scope = expected.state.scope
        // A forged decoded state must not bypass the exact-raw CAS contract.
        require(PrivatePullCodec.decode(expected.raw, scope, expected.state.epoch) == expected)
        if (lease.ownerUid != scope.ownerUid || !isSessionCurrent(lease)) return PrivatePullCommitResult.STALE_SESSION
        if (expectedMarker != null) require(expectedMarker.scope == scope)
        return try {
            database.withTransaction {
                if (!isSessionCurrent(lease)) return@withTransaction PrivatePullCommitResult.STALE_SESSION
                if (dao.metadata(checkpointKey(scope)) != expected.raw) {
                    return@withTransaction PrivatePullCommitResult.STALE_CHECKPOINT
                }
                if (expectedMarker != null &&
                    dao.metadata(markerKey(scope, expectedMarker.documentId)) != PrivatePullCodec.encodeMarker(expectedMarker)) {
                    return@withTransaction PrivatePullCommitResult.STALE_MARKER
                }
                val expectedRevision = Math.addExact(expected.state.revision, 1L)
                val transaction = object : PrivatePullTransaction {
                    private var markerCount: Int? = null

                    override suspend fun putDeferred(marker: PrivatePullDeferredMarker) {
                        require(marker.scope == scope && marker.version == expectedRevision && marker.epoch == expected.state.epoch)
                        val key = markerKey(scope, marker.documentId)
                        if (dao.metadata(key) == null) {
                            val prefix = deferredPrefix(scope)
                            val count = markerCount ?: dao.metadataRangeCount(prefix, prefix + "~")
                            if (count >= maximumDeferredPerGroup) throw PrivatePullDeferredLimitException()
                            markerCount = count + 1
                        }
                        dao.putMetadata(AppMetadataEntity(key, PrivatePullCodec.encodeMarker(marker)))
                    }

                    override suspend fun removeDeferred(documentId: String) {
                        val removed = dao.deleteMetadata(markerKey(scope, documentId))
                        markerCount = markerCount?.minus(removed)
                    }
                }
                val next = transaction.changes()
                require(next.scope == scope && next.revision == expectedRevision && next.epoch == expected.state.epoch)
                // This exception must leave Room, not be swallowed inside its transaction.
                // That rolls back local payload writes, markers AND cursor on a session change.
                if (!isSessionCurrent(lease)) throw StalePrivatePullTransaction()
                dao.putMetadata(AppMetadataEntity(checkpointKey(scope), PrivatePullCodec.encode(next)))
                if (!isSessionCurrent(lease)) throw StalePrivatePullTransaction()
                PrivatePullCommitResult.COMMITTED
            }
        } catch (_: StalePrivatePullTransaction) {
            PrivatePullCommitResult.STALE_SESSION
        }
    }

    companion object {
        private fun prefix(scope: PrivatePullScope): String {
            val owner = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(scope.ownerUid.toByteArray(StandardCharsets.UTF_8))
            return "private-sync-v2:$owner:${scope.collection.name}:"
        }

        internal fun checkpointKey(scope: PrivatePullScope): String = prefix(scope) + "checkpoint"
        internal fun deferredPrefix(scope: PrivatePullScope): String = prefix(scope) + "deferred:"
        internal fun markerKey(scope: PrivatePullScope, documentId: String): String {
            require(documentId.matches(Regex("[A-Za-z0-9._-]{1,128}")))
            return deferredPrefix(scope) + documentId
        }
    }
}

private class StalePrivatePullTransaction : IllegalStateException()
internal class PrivatePullMetadataException : IllegalStateException("Invalid private sync metadata")
internal class PrivatePullDeferredLimitException : IllegalStateException("Private sync deferred limit reached")
