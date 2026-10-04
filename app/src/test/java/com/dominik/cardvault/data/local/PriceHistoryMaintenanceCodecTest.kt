package com.dominik.cardvault.data.local

import com.dominik.cardvault.data.sync.CardmarketHistoryReference
import com.dominik.cardvault.model.PriceHistoryWindowPolicy
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import org.junit.Assert.*
import org.junit.Test

class PriceHistoryMaintenanceCodecTest {
    private val reference = Instant.parse("2024-02-29T12:34:56Z").toEpochMilli()
    private val cutoff = Instant.parse("2023-02-28T12:34:56Z").toEpochMilli()
    private val ticket = PriceHistoryMaintenanceCodec.Ticket("a".repeat(32), reference, cutoff)

    @Test fun exactVersionedTicketUsesUtcCalendarBoundaryAndRoundTrips() {
        val raw = "pht1|${"a".repeat(32)}|$reference|$cutoff|collection-only-12m"
        assertEquals(raw, PriceHistoryMaintenanceCodec.encodeTicket(ticket))
        assertEquals(ticket, PriceHistoryMaintenanceCodec.decodeTicket(raw))
        assertEquals(cutoff, PriceHistoryWindowPolicy.startEpochMillis(reference))
        assertTrue(raw.length < PriceHistoryMaintenanceCodec.MAX_LENGTH)
    }

    @Test fun sameVerifiedResponseProducesDistinctTicketsWithoutUsingCurrentTime() {
        val header = DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(reference).atZone(ZoneOffset.UTC))
        val verified = requireNotNull(CardmarketHistoryReference.fromPriceGuideResponse(header, reference))
        val first = PriceHistoryMaintenanceCodec.freshTicket(verified)
        val second = PriceHistoryMaintenanceCodec.freshTicket(verified)
        assertNotEquals(first.id, second.id)
        for (value in listOf(first, second)) {
            assertEquals(reference, value.referenceEpochMillis)
            assertEquals(cutoff, value.cutoffEpochMillis)
            assertEquals(value, PriceHistoryMaintenanceCodec.decodeTicket(PriceHistoryMaintenanceCodec.encodeTicket(value)))
        }
    }

    @Test fun malformedTicketVersionPolicyNumbersAndBoundsFailClosed() {
        val valid = PriceHistoryMaintenanceCodec.encodeTicket(ticket)
        val invalid = listOf(null, "", "x".repeat(257), "$valid|", "$valid\n", valid.replace("pht1", "pht2"),
            valid.replace("collection-only-12m", "all-history"), valid.replace("a".repeat(32), "A".repeat(32)),
            valid.replace("a".repeat(32), "a".repeat(31)), valid.replace("$reference", "+$reference"),
            valid.replace("$reference", "0$reference"), valid.replace("$reference", "9223372036854775808"),
            valid.replace("$reference", "-1"), valid.replace("$cutoff", "${cutoff + 1}"))
        invalid.forEach { assertNull(it, PriceHistoryMaintenanceCodec.decodeTicket(it)) }
    }

    @Test fun signedPrimaryKeysAndNullsRoundTripWithoutSentinelConfusion() {
        for ((upper, last) in listOf(null to null, 0L to null, 0L to 0L, -1L to Long.MIN_VALUE,
            Long.MIN_VALUE to Long.MIN_VALUE, Long.MAX_VALUE to Long.MAX_VALUE, 42L to 20L)) {
            val state = PriceHistoryMaintenanceCodec.State(ticket, upper, last)
            assertEquals(state, PriceHistoryMaintenanceCodec.decodeState(PriceHistoryMaintenanceCodec.encodeState(state)))
        }
    }

    @Test fun corruptCursorAndIncompatiblePolicyNeverBecomeAUsableCheckpoint() {
        val raw = PriceHistoryMaintenanceCodec.encodeState(PriceHistoryMaintenanceCodec.State(ticket, 42L, 20L))
        val invalid = listOf(null, "", "x".repeat(257), "$raw|", "$raw\n", raw.replace("phc1", "phc2"),
            raw.replace("collection-only-12m", "collection-only-6m"), raw.replace("|42|20", "||20"),
            raw.replace("|42|20", "|19|20"), raw.replace("|42|20", "|042|20"),
            raw.replace("|42|20", "|42|-0"), raw.replace("|42|20", "|42|9223372036854775808"),
            raw.replace("$cutoff", "${cutoff - 1}"))
        invalid.forEach { assertNull(it, PriceHistoryMaintenanceCodec.decodeState(it)) }
    }

    @Test fun encoderRejectsInvalidAuthorityOrProgressRatherThanSerializingIt() {
        fun rejected(block: () -> Unit) {
            try { block(); fail("Expected strict encoder rejection") } catch (_: IllegalArgumentException) { }
        }
        rejected { PriceHistoryMaintenanceCodec.encodeTicket(ticket.copy(cutoffEpochMillis = cutoff + 1)) }
        rejected { PriceHistoryMaintenanceCodec.encodeTicket(ticket.copy(id = "unknown")) }
        rejected { PriceHistoryMaintenanceCodec.encodeState(PriceHistoryMaintenanceCodec.State(ticket, null, 0L)) }
        rejected { PriceHistoryMaintenanceCodec.encodeState(PriceHistoryMaintenanceCodec.State(ticket, 1L, 2L)) }
    }
}
