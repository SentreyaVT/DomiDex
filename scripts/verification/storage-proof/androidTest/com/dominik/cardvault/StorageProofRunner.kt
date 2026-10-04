package com.dominik.cardvault

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.test.runner.AndroidJUnitRunner

class StorageProofRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName.matches(Regex("com\\.dominik\\.cardvault\\.storageproof\\.r[0-9a-f]{32}")))
        check(className == "android.app.Application" && !BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE == "ranchu" && Build.VERSION.SDK_INT == 36)
        check(context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        check(context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
        check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
        check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        return super.newApplication(cl, className, context)
    }

    override fun onCreate(arguments: Bundle) {
        val run = checkNotNull(arguments.getString("storageProofRun"))
        check(run.matches(Regex("[a-f0-9]{32}")))
        check(targetContext.packageName == "com.dominik.cardvault.storageproof.r$run")
        check(arguments.getString("storageInvocation")?.matches(Regex("[a-f0-9]{32}")) == true)
        val methods = mapOf("pilot" to "pilotRealPriceRows", "seed" to "seedThirteenGiB",
            "verify" to "verifySeededDatabase", "prune" to "pruneFirstBatches",
            "resume" to "resumeByFreshIdempotentPass", "reclaim" to "reclaimOwnedDatabase")
        if (arguments.getString("storagePhase") == "contracts") {
            val retention = listOf("utcLeapBoundaryKeepsExactLastStateAndEveryOriginalWindowField",
                "scanLimitCountsNonDeletableRowsAndEachNonemptyBatchUsesOneRangeDelete",
                "interruptedPassReplayFreshPassAndHighWaterAreIdempotent",
                "guestTwoOwnersPhotosOutboxesLayoutsPricePreferencesAndOtherTablesAreByteExact",
                "outerTransactionFailureRollsBackDeletesAndInputCursorRemainsReusable",
                "cancellationAtRealDeleteRollsBackAndCancelledInputCanBeRetried",
                "cancellationAfterNestedBatchBeforeOuterCommitAlsoRollsBack",
                "activeRoomHistoryFlowAndTrackerObserveCommittedPruningWithoutManualRefresh",
                "concurrentReplaceOnEitherSideOfRetentionPreservesCorrectedAnchorAndCurrentRow",
                "emptyAndSignedPrimaryKeysExtremeCutoffsAndInvalidInputsFailSafely",
                "collectionOnlyIsOptInAndUnownedLatestNullZeroNegativeStatesRemainByteExact",
                "collectionOnlyProtectsEveryOwnerTombstonesUnsyncedExactLinksAndStoredSelections",
                "collectionProtectionIsReevaluatedAfterCommittedEntryChangesBetweenBatches",
                "collectionPolicyUsesOneDeleteWithAnUncorrelatedProtectionListAndIndexedPriceProbes",
                "collectionOnlyCancellationAndOuterRollbackPreserveOwnershipAndAllRows")
            val window = listOf("compactWindowKeepsPriorStateInclusiveObservationsAndIndependentFinishBases",
                "unsafeQuantityUsesRealChronologicalFallbackWithTheSameWindowContract",
                "liveWindowAndOwnerChangesRecomputeWithoutSyntheticPointsOrPersistedWrites")
            val importer = listOf("nullTrendPreservesEveryAlternativeAndOriginalSourceTime",
                "sameSourceTimeReplacesFieldsWithoutDuplicatingPriceHistory",
                "lateSqliteFailureRollsBackAllChunksAndSuccessState",
                "contradictoryRowsCannotReplaceTheLastGoodRoomSnapshotInEitherOrder",
                "historyClockIsCommittedWithQuotesAndMissingReferenceKeepsPriorValue",
                "lateHistoryClockWriteFailureRollsBackQuotesAndSuccessfulState")
            val exact = retention.map { "com.dominik.cardvault.data.PriceHistoryRetentionInstrumentedTest#$it" } +
                window.map { "com.dominik.cardvault.PriceHistoryWindowRepositoryInstrumentedTest#$it" } +
                importer.map { "com.dominik.cardvault.data.sync.CardmarketImportRoomInstrumentedTest#$it" }
            check(arguments.getString("class") == exact.joinToString(","))
        } else {
            val method = checkNotNull(methods[arguments.getString("storagePhase")])
            check(arguments.getString("class") == "com.dominik.cardvault.PriceHistoryStorageScaleInstrumentedTest#$method")
        }
        check(arguments.keySet().none { it in setOf("package", "notClass", "annotation", "notAnnotation", "numShards", "shardIndex", "log", "coverage") })
        super.onCreate(arguments)
    }
}
