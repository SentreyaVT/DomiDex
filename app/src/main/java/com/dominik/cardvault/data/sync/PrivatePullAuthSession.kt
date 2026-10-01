package com.dominik.cardvault.data.sync

import com.dominik.cardvault.auth.canonicalCollectionOwnerUid
import com.google.firebase.auth.FirebaseAuth
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred

/**
 * One pull-run lease, invalidated by every observed token/account event after registration.
 * Comparing only the current UID would incorrectly accept an A -> B -> A round trip.
 * This does not grant entitlement: callers still perform the existing verified access check,
 * and Firestore Rules remain authoritative. No token value is read, retained or logged here.
 */
internal class PrivatePullAuthSession private constructor(
    val lease: PrivatePullSessionLease,
    private val generation: AtomicLong,
    private val closed: AtomicBoolean,
    private val currentOwner: () -> String?,
) {
    fun isCurrent(candidate: PrivatePullSessionLease = lease): Boolean =
        candidate == lease && !closed.get() && generation.get() == lease.generation &&
            currentOwner() == lease.ownerUid

    companion object {
        suspend fun <T> use(
            auth: FirebaseAuth,
            ownerUid: String,
            block: suspend (PrivatePullAuthSession) -> T,
        ): T {
            val generation = AtomicLong(0L)
            val closed = AtomicBoolean(false)
            val initial = CompletableDeferred<PrivatePullSessionLease?>()
            val listener = FirebaseAuth.IdTokenListener { changedAuth ->
                val currentGeneration = generation.incrementAndGet()
                initial.complete(
                    PrivatePullSessionLease(ownerUid, currentGeneration)
                        .takeIf { canonicalCollectionOwnerUid(changedAuth.currentUser) == ownerUid },
                )
            }
            auth.addIdTokenListener(listener)
            try {
                val lease = initial.await() ?: throw PrivatePullAccountChangedException()
                val session = PrivatePullAuthSession(lease, generation, closed) {
                    canonicalCollectionOwnerUid(auth.currentUser)
                }
                if (!session.isCurrent()) throw PrivatePullAccountChangedException()
                return block(session)
            } finally {
                closed.set(true)
                auth.removeIdTokenListener(listener)
            }
        }
    }
}

internal class PrivatePullAccountChangedException : IllegalStateException("Private sync session changed")
