package com.dominik.cardvault.data.sync

import android.content.Context
import com.dominik.cardvault.data.knowledge.ScanKnowledgeScheduler

/** External scheduling effects only; the session still owns all access and owner checks. */
internal interface CollectionSessionScheduling {
    fun scheduleCollection(ownerUid: String, userInitiated: Boolean)
    fun schedulePrivateBinder(ownerUid: String, userInitiated: Boolean)
    fun ensurePrivateBinderPeriodic(ownerUid: String)
    fun cancelPrivateBinderPeriodic(ownerUid: String)
    fun cancelAllPrivateBinderPeriodic()
    fun scheduleScanKnowledge()
}

/** Same production calls and arguments as the original FirebaseCollectionSession. */
internal class DefaultCollectionSessionScheduling(
    private val context: Context,
) : CollectionSessionScheduling {
    override fun scheduleCollection(ownerUid: String, userInitiated: Boolean) {
        CollectionSyncScheduler.schedule(context, ownerUid, userInitiated)
    }

    override fun schedulePrivateBinder(ownerUid: String, userInitiated: Boolean) {
        PrivateBinderSyncScheduler.schedule(context, ownerUid, userInitiated)
    }

    override fun ensurePrivateBinderPeriodic(ownerUid: String) {
        PrivateBinderSyncScheduler.ensurePeriodic(context, ownerUid)
    }

    override fun cancelPrivateBinderPeriodic(ownerUid: String) {
        PrivateBinderSyncScheduler.cancelPeriodic(context, ownerUid)
    }

    override fun cancelAllPrivateBinderPeriodic() {
        PrivateBinderSyncScheduler.cancelAllPeriodic(context)
    }

    override fun scheduleScanKnowledge() {
        ScanKnowledgeScheduler.schedule(context)
    }
}
