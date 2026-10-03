package com.dominik.cardvault.data.sync

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner
import com.dominik.cardvault.BuildConfig

/** Only the separate, run-scoped proof APK may execute this instrumentation. */
class WorkManagerProofRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName.matches(Regex("com\\.dominik\\.cardvault\\.wmproof\\.r[0-9a-f]{32}")))
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE == "ranchu")
        check(!BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
        check(className == "com.dominik.cardvault.DomiDexApplication")
        return super.newApplication(cl, className, context)
    }

    override fun onCreate(arguments: Bundle) {
        val run = checkNotNull(arguments.getString("wmProofRun"))
        check(run.matches(Regex("[0-9a-f]{32}")))
        check(arguments.getString("wmProofMode") == "demo-loopback-only")
        check(targetContext.packageName == "com.dominik.cardvault.wmproof.r$run")
        check(arguments.getString("class") in setOf(
            "com.dominik.cardvault.data.sync.PrivateBinderWorkManagerInstrumentedTest#actualPrivateWorkerRetriesAndAcknowledgesWithoutBypassingScheduler",
            "com.dominik.cardvault.data.sync.PrivateBinderWorkManagerInstrumentedTest#actualPrivateWorkerAutomaticallyContinuesFullBudgetWithoutManualEnqueue",
        ))
        super.onCreate(arguments)
    }
}
