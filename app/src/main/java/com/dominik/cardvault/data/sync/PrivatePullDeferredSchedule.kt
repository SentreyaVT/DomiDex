package com.dominik.cardvault.data.sync

import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.Collections

internal const val PRIVATE_PULL_DEFERRED_BATCH_SIZE = 50
private val deferredId = Regex("[A-Za-z0-9._-]{1,128}")

/** Fixed ID horizon for one local scheduling sweep; never a remote timestamp/checkpoint. */
internal data class PrivatePullDeferredSweep(val upperId: String? = null, val afterId: String? = null) {
    init {
        require(upperId == null || deferredId.matches(upperId))
        require(afterId == null || deferredId.matches(afterId))
        require(afterId == null || (upperId != null && afterId <= upperId))
    }
}

/** Explicit four fields avoid a caller-owned mutable map of scheduling state. */
internal data class PrivatePullDeferredSchedule(
    val ownerUid: String,
    val nextGroup: PrivatePullCollection = PrivatePullCollection.BINDERS,
    val binders: PrivatePullDeferredSweep = PrivatePullDeferredSweep(),
    val placements: PrivatePullDeferredSweep = PrivatePullDeferredSweep(),
    val layouts: PrivatePullDeferredSweep = PrivatePullDeferredSweep(),
    val selections: PrivatePullDeferredSweep = PrivatePullDeferredSweep(),
) {
    init { PrivatePullScope(ownerUid, nextGroup) }

    fun sweep(group: PrivatePullCollection): PrivatePullDeferredSweep = when (group) {
        PrivatePullCollection.BINDERS -> binders
        PrivatePullCollection.PLACEMENTS -> placements
        PrivatePullCollection.LAYOUTS -> layouts
        PrivatePullCollection.PRICE_SELECTIONS -> selections
    }

    fun advance(group: PrivatePullCollection, sweep: PrivatePullDeferredSweep): PrivatePullDeferredSchedule {
        val next = PrivatePullCollection.entries[(group.ordinal + 1) % PrivatePullCollection.entries.size]
        return when (group) {
            PrivatePullCollection.BINDERS -> copy(nextGroup = next, binders = sweep)
            PrivatePullCollection.PLACEMENTS -> copy(nextGroup = next, placements = sweep)
            PrivatePullCollection.LAYOUTS -> copy(nextGroup = next, layouts = sweep)
            PrivatePullCollection.PRICE_SELECTIONS -> copy(nextGroup = next, selections = sweep)
        }
    }

    companion object { fun empty(ownerUid: String) = PrivatePullDeferredSchedule(ownerUid) }
}

internal sealed interface PrivatePullDeferredBatch {
    data object NoBudget : PrivatePullDeferredBatch
    data object Empty : PrivatePullDeferredBatch
    data object StaleSession : PrivatePullDeferredBatch

    /** Immutable snapshot, not a lock, ACK, trust envelope or grant to apply a remote record. */
    class Batch(val scope: PrivatePullScope, markers: List<PrivatePullDeferredMarker>) : PrivatePullDeferredBatch {
        val markers: List<PrivatePullDeferredMarker> = Collections.unmodifiableList(ArrayList(markers))
        init {
            require(this.markers.size in 1..PRIVATE_PULL_DEFERRED_BATCH_SIZE)
            require(this.markers.all { it.scope == scope })
        }
    }
}

/** IDs are restricted to ASCII. '!' sorts after the exact ID and before every valid extension. */
internal fun privatePullDeferredUpperExclusive(documentId: String): String {
    require(deferredId.matches(documentId))
    return documentId + "!"
}

/** Only scheduling state may be reset on corruption; no marker/business data is decoded here. */
internal object PrivatePullDeferredScheduleCodec {
    private fun encodedOwner(owner: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(owner.toByteArray(StandardCharsets.UTF_8))

    fun key(ownerUid: String): String {
        PrivatePullScope(ownerUid, PrivatePullCollection.BINDERS)
        return "private-sync-v2:${encodedOwner(ownerUid)}:deferred-schedule"
    }

    fun encode(state: PrivatePullDeferredSchedule): String = buildList {
        add("1"); add(encodedOwner(state.ownerUid)); add(state.nextGroup.name)
        for (group in PrivatePullCollection.entries) {
            val sweep = state.sweep(group)
            add(sweep.upperId.orEmpty()); add(sweep.afterId.orEmpty())
        }
    }.joinToString("|")

    fun decode(raw: String?, ownerUid: String): PrivatePullDeferredSchedule {
        val empty = PrivatePullDeferredSchedule.empty(ownerUid)
        if (raw == null) return empty
        return runCatching {
            require(raw.length <= 2_048)
            val parts = raw.split('|')
            require(parts.size == 11 && parts[0] == "1" && parts[1] == encodedOwner(ownerUid))
            fun sweep(index: Int) = PrivatePullDeferredSweep(parts[index].ifEmpty { null }, parts[index + 1].ifEmpty { null })
            PrivatePullDeferredSchedule(ownerUid, PrivatePullCollection.valueOf(parts[2]),
                sweep(3), sweep(5), sweep(7), sweep(9)).also { require(encode(it) == raw) }
        }.getOrDefault(empty)
    }
}
