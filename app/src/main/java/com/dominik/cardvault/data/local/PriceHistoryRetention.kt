package com.dominik.cardvault.data.local

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import com.dominik.cardvault.data.sync.CardmarketSyncContract
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Unwired maintenance primitive, not a migration, storage cap, or background job.
 *
 * The caller supplies one fixed UTC window boundary. Every observation at/after that boundary
 * and the last observation before it survive, including null/zero/negative prices and every
 * original field/date. Only Cardmarket's public-download history can be deleted.
 * With collectionHistoryOnly=true, unowned cards retain only their latest complete observation.
 * Every local owner (including guest, tombstones and unsynced entries) protects history through
 * its direct catalog ID, exact external price link, and stored Cardmarket price selection. This
 * protection is read anew INSIDE every batch transaction, never cached for a whole pass.
 * Adding a card cannot recover history already pruned before its ownership was committed.
 *
 * A pass visits primary keys up to its captured high-water mark. New auto-generated IDs (also
 * same-timestamp REPLACE corrections) above it are deliberately deferred to the next pass.
 * Cursors belong to this instance and are NOT durable checkpoints. A process restart starts a
 * new, idempotent pass; it may rescan earlier keys. A completed pass is not a promise that a
 * concurrent importer cannot subsequently add more obsolete history.
 *
 * Each batch bounds examined candidate rows, not merely deletion hits. The indexed EXISTS probe
 * may read a newer predecessor for each candidate; this is not a bound on SQLite VM instructions,
 * disk bytes, wall time, WAL size, or total database size. No VACUUM/checkpoint is performed.
 */
internal class PriceHistoryRetention(private val database: CardVaultDatabase) {
    internal class Cursor internal constructor(
        internal val owner: PriceHistoryRetention,
        val cutoffEpochMillis: Long,
        val upperId: Long?,
        val lastExaminedId: Long?,
        val complete: Boolean,
        val collectionHistoryOnly: Boolean,
    )

    internal data class Batch(
        val examinedRows: Int,
        val deletedRows: Int,
        val next: Cursor,
    )

    suspend fun beginPass(cutoffEpochMillis: Long, collectionHistoryOnly: Boolean = false): Cursor = database.withTransaction {
        currentCoroutineContext().ensureActive()
        val upper = database.openHelper.writableDatabase.query(
            "SELECT id FROM raw_price_snapshots ORDER BY id DESC LIMIT 1",
        ).use { if (it.moveToFirst()) it.getLong(0) else null }
        currentCoroutineContext().ensureActive()
        Cursor(this, cutoffEpochMillis, upper, null, upper == null, collectionHistoryOnly)
    }

    /**
     * One Room write transaction: bounded PK read, then at most ONE range DELETE. No DELETE LIMIT
     * extension, window functions, or >999-parameter IN list is needed on API26 SQLite.
     *
     * Room's transaction boundary installs invalidation triggers and refreshes observers on commit;
     * raw openHelper transactions must not replace it. If nested in a caller transaction, use the
     * returned cursor only after that outer transaction commits. On failure/cancellation retry the
     * input cursor (or start again); immutable progress is never persisted ahead of a commit.
     */
    suspend fun pruneBatch(cursor: Cursor, maxRows: Int = MAX_EXAMINED_ROWS): Batch {
        require(cursor.owner === this) { "Retention cursor belongs to a different helper" }
        require(maxRows in 1..MAX_EXAMINED_ROWS) { "Retention batch must examine 1..1024 rows" }
        currentCoroutineContext().ensureActive()
        if (cursor.complete) return Batch(0, 0, cursor)
        val upper = requireNotNull(cursor.upperId)
        return database.withTransaction {
            currentCoroutineContext().ensureActive()
            val db = database.openHelper.writableDatabase
            val query = if (cursor.lastExaminedId == null) {
                SimpleSQLiteQuery(
                    "SELECT id FROM raw_price_snapshots WHERE id <= ? ORDER BY id LIMIT ?",
                    arrayOf<Any?>(upper, maxRows),
                )
            } else {
                SimpleSQLiteQuery(
                    "SELECT id FROM raw_price_snapshots WHERE id > ? AND id <= ? ORDER BY id LIMIT ?",
                    arrayOf<Any?>(cursor.lastExaminedId, upper, maxRows),
                )
            }
            val ids = db.query(query).use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        currentCoroutineContext().ensureActive()
                        add(rows.getLong(0))
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val deleteSql = if (cursor.collectionHistoryOnly) DELETE_COLLECTION_RANGE else DELETE_RANGE
            val deleted = if (ids.isEmpty()) 0 else db.compileStatement(deleteSql).use { statement ->
                // No other writer can add an unexamined key inside this range while this Room
                // write transaction is active. Unrelated sources count toward the scan bound too.
                statement.bindLong(1, ids.first())
                statement.bindLong(2, ids.last())
                statement.bindString(3, CardmarketSyncContract.SOURCE)
                statement.bindLong(4, cursor.cutoffEpochMillis)
                statement.bindLong(5, cursor.cutoffEpochMillis)
                currentCoroutineContext().ensureActive()
                statement.executeUpdateDelete()
            }
            currentCoroutineContext().ensureActive() // Roll back even if cancellation arrived in SQL.
            check(deleted in 0..ids.size) { "Retention deleted outside the examined batch" }
            Batch(ids.size, deleted, Cursor(
                this, cursor.cutoffEpochMillis, upper, ids.lastOrNull() ?: cursor.lastExaminedId,
                ids.size < maxRows || ids.lastOrNull() == upper,
                cursor.collectionHistoryOnly,
            ))
        }
    }

    companion object {
        const val MAX_EXAMINED_ROWS = 1024

        private const val DELETE_RANGE = """
            DELETE FROM raw_price_snapshots
            WHERE id >= ? AND id <= ? AND source = ? AND effectiveAtEpochMillis < ?
              AND EXISTS (
                SELECT 1 FROM raw_price_snapshots AS newer
                WHERE newer.cardId = raw_price_snapshots.cardId
                  AND newer.source = raw_price_snapshots.source
                  AND newer.effectiveAtEpochMillis > raw_price_snapshots.effectiveAtEpochMillis
                  AND newer.effectiveAtEpochMillis < ?
              )
        """

        // Uncorrelated UNION/IN builds one protection list per statement, not one collection
        // scan for each price row. Catalog externalSource+externalProductId is a unique index;
        // each newer-price EXISTS uses the cardId+source+effectiveAtEpochMillis index. The small
        // owner/selection list is NOT bounded by MAX_EXAMINED_ROWS: only raw candidate rows are.
        // No owner, sync-state, cleared-selection or tombstone filter: deliberately conservative.
        private const val DELETE_COLLECTION_RANGE = """
            WITH protected_cards(cardId) AS (
                SELECT cardId FROM collection_entries
                UNION
                SELECT c.id FROM collection_entries AS e
                JOIN catalog_cards AS c ON c.externalSource = e.externalProductSource
                  AND c.externalProductId = e.externalProductId
                UNION
                SELECT c.id FROM collection_price_selections AS s
                JOIN catalog_cards AS c ON c.externalSource = 'CARDMARKET_PUBLIC_DOWNLOAD'
                  AND c.externalProductId = s.productId
            )
            DELETE FROM raw_price_snapshots
            WHERE id >= ? AND id <= ? AND source = ?
              AND CASE WHEN cardId IN (SELECT cardId FROM protected_cards)
                THEN effectiveAtEpochMillis < ? AND EXISTS (
                    SELECT 1 FROM raw_price_snapshots AS newer
                    WHERE newer.cardId = raw_price_snapshots.cardId
                      AND newer.source = raw_price_snapshots.source
                      AND newer.effectiveAtEpochMillis > raw_price_snapshots.effectiveAtEpochMillis
                      AND newer.effectiveAtEpochMillis < ?
                )
                ELSE EXISTS (
                    SELECT 1 FROM raw_price_snapshots AS newer
                    WHERE newer.cardId = raw_price_snapshots.cardId
                      AND newer.source = raw_price_snapshots.source
                      AND newer.effectiveAtEpochMillis > raw_price_snapshots.effectiveAtEpochMillis
                )
              END
        """
    }
}
