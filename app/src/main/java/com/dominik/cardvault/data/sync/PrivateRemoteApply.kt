package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.*
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID

internal sealed interface PrivateRemoteApplyOutcome {
    data object Completed : PrivateRemoteApplyOutcome
    data object DeferredLocal : PrivateRemoteApplyOutcome
    data object Invalid : PrivateRemoteApplyOutcome
    data class RetryDependency(val type: PrivateRemoteDependencyType, val id: String) : PrivateRemoteApplyOutcome
}

/**
 * Common Room apply seam for full pulls, dependency reads and push remote-wins replies.
 * Caller MUST run it inside its existing Room transaction (including any tentative removal
 * of the exactly-current outbox mutation). No network, auth, cursor or scheduler work occurs here.
 * confirmedServer means BOTH !isFromCache AND !hasPendingWrites, not merely Source.SERVER.
 * Deferred/Invalid never changes business rows/outboxes; receipt corruption throws to roll back.
 */
internal suspend fun applyPrivateRemoteRecord(
    dao: CardVaultDao,
    ownerUid: String,
    recordType: String,
    documentId: String,
    data: Map<String, Any?>,
    confirmedServer: Boolean,
): PrivateRemoteApplyOutcome {
    val parsed = parsePrivateRemoteRecord(ownerUid, recordType, documentId, data)
        ?: return PrivateRemoteApplyOutcome.Invalid
    if (!confirmedServer) return PrivateRemoteApplyOutcome.DeferredLocal
    if (dao.privateBinderSyncOutbox("$ownerUid:$recordType:$documentId") != null) {
        return PrivateRemoteApplyOutcome.DeferredLocal
    }
    return when (val body = parsed.body) {
        is PrivateRemoteBody.Binder -> applyBinder(dao, parsed, body)
        is PrivateRemoteBody.Placement -> applyPlacement(dao, parsed, body)
        is PrivateRemoteBody.Layout -> {
            val entry = dao.getCollectionEntryBySyncId(ownerUid, documentId)
                ?: return PrivateRemoteApplyOutcome.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, documentId)
            collectionParentDisposition(dao, entry)?.let { return it }
            val existing = dao.collectionLayout(entry.id, ownerUid)
            if (existing == null || existing.remoteRevision < parsed.revision) {
                dao.upsertCollectionLayout(CollectionLayoutEntity(entry.id, ownerUid, body.visible,
                    parsed.updatedAt, SYNC_CLEAN, parsed.revision))
            }
            PrivateRemoteApplyOutcome.Completed
        }
        is PrivateRemoteBody.Selection -> {
            val entry = dao.getCollectionEntryBySyncId(ownerUid, documentId)
                ?: return PrivateRemoteApplyOutcome.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, documentId)
            collectionParentDisposition(dao, entry)?.let { return it }
            val existing = dao.priceSelection(ownerUid, documentId)
            if (existing == null || existing.remoteRevision < parsed.revision) {
                // Retain complete revision even if local printing identity changed; evaluation
                // rejects the mismatch. An older selection must not be resurrected instead.
                dao.upsertPriceSelection(body.value.copy(collectionEntryId = entry.id))
            }
            PrivateRemoteApplyOutcome.Completed
        }
    }
}

private suspend fun applyBinder(
    dao: CardVaultDao, remote: ParsedPrivateRemote, body: PrivateRemoteBody.Binder,
): PrivateRemoteApplyOutcome {
    val receipt = tombstoneRevision(dao, remote)
    if (!remote.deleted && receipt != null && remote.revision <= receipt) return PrivateRemoteApplyOutcome.Completed
    val existing = dao.privateBinder(remote.id, remote.owner)
    if (existing != null && existing.remoteRevision >= remote.revision) return PrivateRemoteApplyOutcome.Completed
    if (remote.deleted) rememberTombstone(dao, remote, receipt)
    if (remote.deleted && existing == null) return PrivateRemoteApplyOutcome.Completed
    dao.upsertPrivateBinder(PrivateBinderEntity(
        id = remote.id, ownerUid = remote.owner, name = body.name, description = body.description,
        coverStyle = body.cover, minimumPageCount = body.pages,
        createdAtEpochMillis = remote.createdAt ?: existing?.createdAtEpochMillis ?: remote.updatedAt,
        updatedAtEpochMillis = remote.updatedAt, lastOpenedAtEpochMillis = body.lastOpened,
        syncState = SYNC_CLEAN, remoteRevision = remote.revision,
        deletedAtEpochMillis = remote.updatedAt.takeIf { remote.deleted },
    ))
    return PrivateRemoteApplyOutcome.Completed
}

private suspend fun applyPlacement(
    dao: CardVaultDao, remote: ParsedPrivateRemote, body: PrivateRemoteBody.Placement,
): PrivateRemoteApplyOutcome {
    val receipt = tombstoneRevision(dao, remote)
    if (!remote.deleted && receipt != null && remote.revision <= receipt) return PrivateRemoteApplyOutcome.Completed
    val existing = dao.privateBinderPlacement(remote.id, remote.owner)
    if (existing != null && existing.remoteRevision >= remote.revision) return PrivateRemoteApplyOutcome.Completed
    if (remote.deleted) {
        // A compact revision receipt needs neither an invented card nor a missing FK parent.
        rememberTombstone(dao, remote, receipt)
        if (existing != null) dao.updatePrivateBinderPlacement(existing.copy(
            updatedAtEpochMillis = remote.updatedAt, syncState = SYNC_CLEAN,
            remoteRevision = remote.revision, deletedAtEpochMillis = remote.updatedAt,
        ))
        return PrivateRemoteApplyOutcome.Completed
    }
    val binder = dao.privateBinder(body.binderId, remote.owner)
        ?: return PrivateRemoteApplyOutcome.RetryDependency(PrivateRemoteDependencyType.BINDER, body.binderId)
    if (binder.syncState == SYNC_ERROR || binder.syncState == SYNC_CONFLICT) return PrivateRemoteApplyOutcome.DeferredLocal
    if (binder.deletedAtEpochMillis != null) {
        val pending = dao.privateBinderSyncOutbox("${remote.owner}:$PRIVATE_SYNC_BINDER:${binder.id}") != null
        return if (binder.syncState == SYNC_CLEAN && binder.remoteRevision > 0 && !pending) {
            PrivateRemoteApplyOutcome.Completed
        } else PrivateRemoteApplyOutcome.DeferredLocal
    }
    val entry = dao.getCollectionEntryBySyncId(remote.owner, body.entrySyncId)
        ?: return PrivateRemoteApplyOutcome.RetryDependency(PrivateRemoteDependencyType.COLLECTION_ENTRY, body.entrySyncId)
    collectionParentDisposition(dao, entry)?.let { return it }
    if (existing != null && (existing.binderId != binder.id || existing.collectionEntryId != entry.id)) {
        return PrivateRemoteApplyOutcome.Invalid // Cloud placement generations have immutable parents.
    }

    // Guard every conflicting membership, not only the LIMIT-1 winner: an older pending
    // duplicate must not be overwritten just because a newer CLEAN duplicate sorts first.
    val memberships = dao.activePrivateBinderPlacementsForEntry(entry.id, remote.owner)
        .filter { it.binderId == binder.id && it.id != remote.id }
    for (membership in memberships) {
        if (membership.syncState != SYNC_CLEAN ||
            dao.privateBinderSyncOutbox("${remote.owner}:$PRIVATE_SYNC_PLACEMENT:${membership.id}") != null) {
            return PrivateRemoteApplyOutcome.DeferredLocal
        }
    }
    val membership = dao.privateBinderPlacementForEntry(binder.id, entry.id, remote.owner)
        ?.takeIf { it.id != remote.id && it.deletedAtEpochMillis == null }
    if (membership != null) {
        val now = System.currentTimeMillis()
        // Keep the established lexicographic generation winner, never a device-clock winner.
        if (membership.id < remote.id) {
            val loser = PrivateBinderPlacementEntity(
                remote.id, remote.owner, binder.id, entry.id, body.slot,
                createdAtEpochMillis = remote.createdAt ?: remote.updatedAt,
                updatedAtEpochMillis = now, syncState = SYNC_PENDING,
                remoteRevision = remote.revision, deletedAtEpochMillis = now,
            )
            dao.upsertPrivateBinderPlacement(loser)
            queuePlacementResolution(dao, loser, PRIVATE_SYNC_DELETE)
            return PrivateRemoteApplyOutcome.Completed
        }
        val loser = membership.copy(updatedAtEpochMillis = now, syncState = SYNC_PENDING, deletedAtEpochMillis = now)
        dao.updatePrivateBinderPlacement(loser)
        queuePlacementResolution(dao, loser, PRIVATE_SYNC_DELETE)
    }
    val position = remotePrivatePlacementPosition(dao, remote.owner, binder.id, remote.id, body.slot)
    val resolvedLocally = position != body.slot
    val placement = PrivateBinderPlacementEntity(
        remote.id, remote.owner, binder.id, entry.id, position, body.effect, body.intensity,
        remote.createdAt ?: existing?.createdAtEpochMillis ?: remote.updatedAt, remote.updatedAt,
        if (resolvedLocally) SYNC_PENDING else SYNC_CLEAN, remote.revision,
    )
    dao.upsertPrivateBinderPlacement(placement)
    if (resolvedLocally) queuePlacementResolution(dao, placement, PRIVATE_SYNC_UPSERT)
    return PrivateRemoteApplyOutcome.Completed
}

/** Null means a stable live parent exists; ordinary live PENDING edits do not block presentation. */
private suspend fun collectionParentDisposition(dao: CardVaultDao, entry: CollectionEntryEntity): PrivateRemoteApplyOutcome? {
    val outbox = dao.syncOutbox("${entry.ownerUid}:${entry.syncId}")
    if (entry.syncState == SYNC_ERROR || entry.syncState == SYNC_CONFLICT ||
        outbox?.state == SYNC_ERROR || outbox?.state == SYNC_CONFLICT) return PrivateRemoteApplyOutcome.DeferredLocal
    if (entry.deletedAtEpochMillis != null) {
        return if (entry.syncState == SYNC_CLEAN && entry.remoteRevision > 0 && outbox == null) {
            PrivateRemoteApplyOutcome.Completed
        } else PrivateRemoteApplyOutcome.DeferredLocal
    }
    return null
}

private suspend fun queuePlacementResolution(dao: CardVaultDao, placement: PrivateBinderPlacementEntity, operation: String) {
    dao.upsertPrivateBinderSyncOutbox(PrivateBinderSyncOutboxEntity(
        "${placement.ownerUid}:$PRIVATE_SYNC_PLACEMENT:${placement.id}", UUID.randomUUID().toString(),
        placement.ownerUid, PRIVATE_SYNC_PLACEMENT, placement.id, operation, placement.remoteRevision,
        placement.updatedAtEpochMillis, System.currentTimeMillis(),
    ))
}

/** Receipts are local sync evidence, not content, grants or portable backup data. No expiry. */
internal fun privateRemoteTombstoneReceiptKey(ownerUid: String, recordType: String, documentId: String): String {
    require(validRemoteOwner(ownerUid) && validRemoteRecordId(documentId))
    require(recordType == PRIVATE_SYNC_BINDER || recordType == PRIVATE_SYNC_PLACEMENT)
    val owner = Base64.getUrlEncoder().withoutPadding().encodeToString(ownerUid.toByteArray(StandardCharsets.UTF_8))
    return "private-remote-tombstone-v1:$owner:$recordType:$documentId"
}

private suspend fun tombstoneRevision(dao: CardVaultDao, remote: ParsedPrivateRemote): Long? {
    val raw = dao.metadata(privateRemoteTombstoneReceiptKey(remote.owner, remote.type, remote.id)) ?: return null
    val revision = raw.removePrefix("1|").toLongOrNull()
    if (revision == null || revision <= 0 || raw != "1|$revision") throw PrivateRemoteReceiptException()
    return revision
}

private suspend fun rememberTombstone(dao: CardVaultDao, remote: ParsedPrivateRemote, previous: Long?) {
    if (previous == null || previous < remote.revision) {
        dao.putMetadata(AppMetadataEntity(privateRemoteTombstoneReceiptKey(remote.owner, remote.type, remote.id), "1|${remote.revision}"))
    }
}

internal class PrivateRemoteReceiptException : IllegalStateException("Invalid private remote tombstone receipt")

private data class ParsedPrivateRemote(
    val owner: String, val type: String, val id: String, val revision: Long, val updatedAt: Long,
    val createdAt: Long?, val deleted: Boolean, val body: PrivateRemoteBody,
)

private sealed interface PrivateRemoteBody {
    data class Binder(val name: String, val description: String?, val cover: String, val pages: Int, val lastOpened: Long?) : PrivateRemoteBody
    data class Placement(val binderId: String, val entrySyncId: String, val slot: Long, val effect: String?, val intensity: Float) : PrivateRemoteBody
    data class Layout(val visible: Boolean) : PrivateRemoteBody
    data class Selection(val value: CollectionPriceSelectionEntity) : PrivateRemoteBody
}

private fun parsePrivateRemoteRecord(owner: String, type: String, id: String, data: Map<String, Any?>): ParsedPrivateRemote? {
    if (!validRemoteOwner(owner) || !validRemoteRecordId(id) || data["ownerUid"] != owner) return null
    val identityField = when (type) {
        PRIVATE_SYNC_BINDER -> "binderId"
        PRIVATE_SYNC_PLACEMENT -> "placementId"
        PRIVATE_SYNC_LAYOUT, PRIVATE_SYNC_PRICE_SELECTION -> "entrySyncId"
        else -> return null
    }
    if (data[identityField] != id) return null
    val revision = remoteInteger(data["revision"])?.takeIf { it > 0 } ?: return null
    val updated = remoteInteger(data["updatedAtClient"])?.takeIf { it > 0 } ?: return null
    val created = remoteInteger(data["createdAtClient"])?.takeIf { it > 0 }
    val deleted = if (type == PRIVATE_SYNC_BINDER || type == PRIVATE_SYNC_PLACEMENT) data["deleted"] as? Boolean ?: return null else false
    val body = when (type) {
        PRIVATE_SYNC_BINDER -> {
            val name = (data["name"] as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= 120 } ?: return null
            val description = when (val value = data["description"]) {
                null -> null
                is String -> value.takeIf { it.length <= 1_000 }
                else -> return null
            }
            val cover = (data["coverStyle"] as? String)?.takeIf { it.isNotBlank() && it.length <= 80 } ?: "DOMIDEX"
            val pages = remoteInteger(data["minimumPageCount"])?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt() ?: 1
            val lastOpened = when (val value = data["lastOpenedAtClient"]) {
                null -> null
                else -> remoteInteger(value) ?: return null
            }
            PrivateRemoteBody.Binder(name, description, cover, pages, lastOpened)
        }
        PRIVATE_SYNC_PLACEMENT -> {
            val binder = (data["binderId"] as? String)?.takeIf(::validRemoteRecordId) ?: return null
            val entry = (data["entrySyncId"] as? String)?.takeIf(::validRemoteRecordId) ?: return null
            val slot = remoteInteger(data["slotPosition"])?.takeIf { it >= 0 } ?: return null
            val effect = when (val value = data["holoEffectId"]) {
                null -> null
                is String -> value.trim().takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,79}")) } ?: return null
                else -> return null
            }
            val intensity = when (val value = data["holoIntensity"]) {
                null -> 0f
                is Number -> value.toFloat().takeIf { it.isFinite() && it in 0f..1f } ?: return null
                else -> return null
            }
            if (effect == null && intensity != 0f) return null
            PrivateRemoteBody.Placement(binder, entry, slot, effect, intensity)
        }
        PRIVATE_SYNC_LAYOUT -> PrivateRemoteBody.Layout(data["visibleInSetBinder"] as? Boolean ?: return null)
        PRIVATE_SYNC_PRICE_SELECTION -> PrivateRemoteBody.Selection(parsePriceSelectionSidecar(data, owner, id, 0L) ?: return null)
        else -> return null
    }
    return ParsedPrivateRemote(owner, type, id, revision, updated, created, deleted, body)
}

private fun validRemoteOwner(value: String) = value.isNotBlank() && value.length <= 128 && '\u0000' !in value
private fun validRemoteRecordId(value: String) = value.matches(Regex("[A-Za-z0-9._-]{1,128}"))
private fun remoteInteger(value: Any?): Long? = when (value) {
    is Long -> value
    is Int -> value.toLong()
    is Short -> value.toLong()
    is Byte -> value.toLong()
    is Double -> value.takeIf { it.isFinite() && it >= Long.MIN_VALUE.toDouble() && it < Long.MAX_VALUE.toDouble() }
        ?.toLong()?.takeIf { it.toDouble() == value }
    is Float -> remoteInteger(value.toDouble())
    else -> null
}
