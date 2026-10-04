package com.dominik.cardvault.data.local

import androidx.room.withTransaction
import com.dominik.cardvault.data.sync.CardmarketHistoryReference
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Transactional local adapter: no network, UI, singleton opening, VACUUM or database close.
 * Scheduling is separate in PriceHistoryMaintenanceWorker.
 * A fresh successful import owns the pending ticket; wall time is never consulted here.
 * The active pass keeps its original cutoff/high-water mark while newer imports coalesce only
 * the pending ticket. Ownership protection is still queried by Retention inside EACH batch.
 *
 * Cursor progress and deletion share one outer Room transaction. A restarted coordinator reads
 * the committed cursor, not a stale in-memory token. Invalid cursors can restart only while a
 * structurally valid import ticket exists. Missing/invalid tickets leave all state untouched.
 * This is not a cryptographic corruption detector, a byte cap or physical space reclamation.
 */
internal class PriceHistoryMaintenance(private val database: CardVaultDatabase) {
    enum class Status { NO_TICKET, INVALID_TICKET, MORE, PASS_COMPLETE }
    data class Batch(
        val status: Status,
        val examinedRows: Int = 0,
        val deletedRows: Int = 0,
        val ticketId: String? = null,
        val pendingAnotherPass: Boolean = false,
    )

    private val retention = PriceHistoryRetention(database)

    /** One bounded batch only. Call again from the committed result; never loop on a UI thread. */
    suspend fun runBatch(maxRows: Int = PriceHistoryRetention.MAX_EXAMINED_ROWS): Batch {
        require(maxRows in 1..PriceHistoryRetention.MAX_EXAMINED_ROWS)
        currentCoroutineContext().ensureActive()
        return database.withTransaction {
            val dao = database.cardVaultDao()
            val pendingRaw = dao.metadata(PriceHistoryMaintenanceCodec.PENDING_KEY)
                ?: return@withTransaction Batch(Status.NO_TICKET)
            val pending = PriceHistoryMaintenanceCodec.decodeTicket(pendingRaw)
                ?: return@withTransaction Batch(Status.INVALID_TICKET)
            // Both values originate in the SAME successful import transaction. Never promote
            // a standalone/corrupt ticket or a bare legacy clock into deletion authority.
            if (dao.metadata(CardmarketHistoryReference.METADATA_KEY) != pending.referenceEpochMillis.toString()) {
                return@withTransaction Batch(Status.INVALID_TICKET)
            }
            val oldState = dao.metadata(PriceHistoryMaintenanceCodec.CURSOR_KEY)
            val decoded = PriceHistoryMaintenanceCodec.decodeState(oldState)
            // The same ID cannot name different authority/policy. Treat that as damaged state.
            val active = decoded?.takeUnless { it.ticket.id == pending.id && it.ticket != pending }
            // A valid newer import can clamp its clock backwards. Pause the older, more
            // aggressive cutoff without replacing its cursor or deleting either ticket.
            // INVALID_TICKET also denotes this insufficient current time authority; a later
            // matching/ahead verified import can resume the exact committed active cursor.
            if (active != null && active.ticket.cutoffEpochMillis > pending.cutoffEpochMillis) {
                return@withTransaction Batch(Status.INVALID_TICKET)
            }
            val cursor = if (active == null) {
                retention.beginPass(pending.cutoffEpochMillis, collectionHistoryOnly = true)
            } else PriceHistoryRetention.Cursor(
                retention, active.ticket.cutoffEpochMillis, active.upperId, active.lastExaminedId,
                active.upperId == null || active.lastExaminedId == active.upperId, true,
            )
            val ticket = active?.ticket ?: pending
            val pruned = retention.pruneBatch(cursor, maxRows)
            currentCoroutineContext().ensureActive()
            val next = if (pruned.next.complete) null else PriceHistoryMaintenanceCodec.encodeState(
                PriceHistoryMaintenanceCodec.State(ticket, pruned.next.upperId, pruned.next.lastExaminedId),
            )
            compareAndSetState(oldState, next)
            if (pruned.next.complete) {
                // An old pass must NEVER remove a newer (even same-Date) import ticket.
                val removed = database.openHelper.writableDatabase.compileStatement(
                    "DELETE FROM app_metadata WHERE `key` = ? AND value = ?",
                ).use { statement ->
                    statement.bindString(1, PriceHistoryMaintenanceCodec.PENDING_KEY)
                    statement.bindString(2, PriceHistoryMaintenanceCodec.encodeTicket(ticket))
                    statement.executeUpdateDelete()
                }
                check(removed in 0..1)
            }
            currentCoroutineContext().ensureActive() // Includes cancellation during metadata SQL.
            Batch(if (pruned.next.complete) Status.PASS_COMPLETE else Status.MORE,
                pruned.examinedRows, pruned.deletedRows, ticket.id,
                pruned.next.complete && dao.metadata(PriceHistoryMaintenanceCodec.PENDING_KEY) != null)
        }
    }

    private suspend fun compareAndSetState(expected: String?, next: String?) {
        val db = database.openHelper.writableDatabase
        val key = PriceHistoryMaintenanceCodec.CURSOR_KEY
        val changed = when {
            expected == null && next == null -> {
                check(database.cardVaultDao().metadata(key) == null) { "Maintenance cursor changed" }
                return
            }
            expected == null -> db.compileStatement("INSERT OR IGNORE INTO app_metadata (`key`, value) VALUES (?, ?)").use {
                it.bindString(1, key); it.bindString(2, requireNotNull(next)); it.executeUpdateDelete()
            }
            next == null -> db.compileStatement("DELETE FROM app_metadata WHERE `key` = ? AND value = ?").use {
                it.bindString(1, key); it.bindString(2, expected); it.executeUpdateDelete()
            }
            else -> db.compileStatement("UPDATE app_metadata SET value = ? WHERE `key` = ? AND value = ?").use {
                it.bindString(1, next); it.bindString(2, key); it.bindString(3, expected); it.executeUpdateDelete()
            }
        }
        check(changed == 1) { "Maintenance cursor changed" }
    }
}
