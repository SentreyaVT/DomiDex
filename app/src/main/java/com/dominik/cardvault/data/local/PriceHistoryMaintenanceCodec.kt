package com.dominik.cardvault.data.local

import com.dominik.cardvault.data.sync.CardmarketHistoryReference
import com.dominik.cardvault.model.PriceHistoryWindowPolicy
import java.util.UUID

/** Local-only, bounded metadata. This codec validates structure, not a new time authority. */
internal object PriceHistoryMaintenanceCodec {
    const val PENDING_KEY = "cardmarket_history_maintenance_pending_v1"
    const val CURSOR_KEY = "cardmarket_history_maintenance_cursor_v1"
    const val MAX_LENGTH = 256
    private const val POLICY = "collection-only-12m"
    // A validated provider Date >= 2000-01-01 can clamp to a device clock 48h behind it.
    private const val EARLIEST_REFERENCE = 946_512_000_000L
    private val token = Regex("[a-f0-9]{32}")

    data class Ticket(val id: String, val referenceEpochMillis: Long, val cutoffEpochMillis: Long)
    data class State(val ticket: Ticket, val upperId: Long?, val lastExaminedId: Long?)

    /** Called only with the fresh typed reference of the import being committed. */
    fun freshTicket(reference: CardmarketHistoryReference): Ticket = Ticket(
        UUID.randomUUID().toString().replace("-", ""), reference.epochMillis,
        PriceHistoryWindowPolicy.startEpochMillis(reference.epochMillis),
    )

    fun encodeTicket(ticket: Ticket): String {
        require(validTicket(ticket)) { "Invalid maintenance ticket" }
        return listOf("pht1", ticket.id, ticket.referenceEpochMillis, ticket.cutoffEpochMillis, POLICY).joinToString("|")
    }

    fun decodeTicket(raw: String?): Ticket? {
        val fields = fields(raw, 5) ?: return null
        if (fields[0] != "pht1" || fields[4] != POLICY) return null
        return ticket(fields[1], fields[2], fields[3])
    }

    fun encodeState(state: State): String {
        require(validTicket(state.ticket) && validRange(state.upperId, state.lastExaminedId)) { "Invalid maintenance cursor" }
        return listOf("phc1", state.ticket.id, state.ticket.referenceEpochMillis, state.ticket.cutoffEpochMillis,
            POLICY, state.upperId?.toString().orEmpty(), state.lastExaminedId?.toString().orEmpty()).joinToString("|")
    }

    fun decodeState(raw: String?): State? {
        val fields = fields(raw, 7) ?: return null
        if (fields[0] != "phc1" || fields[4] != POLICY) return null
        val ticket = ticket(fields[1], fields[2], fields[3]) ?: return null
        val upper = if (fields[5].isEmpty()) null else { canonicalLong(fields[5]) ?: return null }
        val last = if (fields[6].isEmpty()) null else { canonicalLong(fields[6]) ?: return null }
        return if (validRange(upper, last)) State(ticket, upper, last) else null
    }

    private fun fields(raw: String?, count: Int): List<String>? {
        if (raw == null || raw.length !in 1..MAX_LENGTH) return null
        val parts = raw.split('|')
        return parts.takeIf { it.size == count }
    }

    private fun canonicalLong(raw: String): Long? = raw.toLongOrNull()?.takeIf { it.toString() == raw }
    private fun ticket(id: String, reference: String, cutoff: String): Ticket? {
        val value = Ticket(id, canonicalLong(reference) ?: return null, canonicalLong(cutoff) ?: return null)
        return value.takeIf(::validTicket)
    }

    private fun validTicket(ticket: Ticket): Boolean = token.matches(ticket.id) &&
        ticket.referenceEpochMillis >= EARLIEST_REFERENCE &&
        runCatching { PriceHistoryWindowPolicy.startEpochMillis(ticket.referenceEpochMillis) }
            .getOrNull() == ticket.cutoffEpochMillis

    // Signed Room IDs remain legal; null is not conflated with 0 or Long.MIN_VALUE.
    private fun validRange(upper: Long?, last: Long?): Boolean =
        if (upper == null) last == null else last == null || last <= upper
}
