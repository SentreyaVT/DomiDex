package com.dominik.cardvault.data.sync

import com.dominik.cardvault.data.local.CardVaultDao
import com.dominik.cardvault.data.local.PrivateBinderPlacementEntity

/** Called within the applying Room transaction; remote slot conflict policy is unchanged. */
internal suspend fun remotePrivatePlacementPosition(
    dao: CardVaultDao,
    ownerUid: String,
    binderId: String,
    documentId: String,
    requestedPosition: Long,
): Long {
    require(requestedPosition >= 0L)
    // Most imported placements have a free slot. Use the existing owner/binder/slot index
    // instead of materializing all N placements for every one of N remote documents.
    if (!dao.hasOtherActivePrivatePlacement(ownerUid, binderId, requestedPosition, documentId)) {
        return requestedPosition
    }
    val used = dao.activePrivateBinderPlacements(binderId, ownerUid).asSequence()
        .filter { it.id != documentId && it.deletedAtEpochMillis == null && it.slotPosition >= 0L }
        .map(PrivateBinderPlacementEntity::slotPosition)
        .toHashSet()
    var candidate = 0L
    while (candidate in used) candidate++
    return candidate
}
