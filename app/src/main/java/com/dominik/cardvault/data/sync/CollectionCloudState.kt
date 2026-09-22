package com.dominik.cardvault.data.sync

/** Connection/access state is independent from local collection ownership. */
enum class CollectionCloudStatus { SIGNED_OUT, CHECKING, NO_ACCESS, LOCAL_ONLY, PENDING, ERROR, ACTIVE }

data class CollectionCloudState(
    val status: CollectionCloudStatus,
    val ownerUid: String? = null,
    val accessConfirmed: Boolean = false,
)

internal fun collectionCloudStatus(
    checking: Boolean,
    accessConfirmed: Boolean,
    denied: Boolean,
    serverSnapshot: Boolean,
    pending: Boolean,
    error: Boolean,
): CollectionCloudStatus = when {
    checking -> CollectionCloudStatus.CHECKING
    denied -> CollectionCloudStatus.NO_ACCESS
    error -> CollectionCloudStatus.ERROR
    !accessConfirmed -> CollectionCloudStatus.LOCAL_ONLY
    pending -> CollectionCloudStatus.PENDING
    !serverSnapshot -> CollectionCloudStatus.LOCAL_ONLY
    else -> CollectionCloudStatus.ACTIVE
}
