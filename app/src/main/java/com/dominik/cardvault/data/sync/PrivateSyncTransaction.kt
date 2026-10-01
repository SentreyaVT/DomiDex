package com.dominik.cardvault.data.sync

import androidx.room.withTransaction
import com.dominik.cardvault.data.local.CardVaultDatabase

/** Throw outside Room's commit boundary so a late account change rolls every write back. */
internal suspend fun <T> CardVaultDatabase.withPrivateSyncSession(
    isCurrent: () -> Boolean,
    block: suspend () -> T,
): T = withTransaction {
    if (!isCurrent()) throw PrivatePullAccountChangedException()
    val result = block()
    if (!isCurrent()) throw PrivatePullAccountChangedException()
    result
}

/** A SERVER request can still include a pending local overlay; it is not a confirmed parent. */
internal suspend fun applyConfirmedPrivateDependency(
    exists: Boolean,
    isFromCache: Boolean,
    hasPendingWrites: Boolean,
    apply: suspend () -> Unit,
) {
    if (exists && !isFromCache && !hasPendingWrites) apply()
}
