package com.dominik.cardvault

import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.test.runner.AndroidJUnitRunner

/** A separate package, seven unchanged synthetic test classes; no camera or normal app startup. */
class HardwareProofRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName.matches(Regex("com\\.dominik\\.cardvault\\.hardwareproof\\.r[0-9a-f]{32}")))
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(className == "android.app.Application")
        check(!BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
        check(Build.MODEL == "24115RA8EG" && Build.DEVICE == "amethyst" && Build.VERSION.SDK_INT == 36)
        check(Build.HARDWARE !in setOf("ranchu", "goldfish") && Build.SUPPORTED_ABIS.first() == "arm64-v8a")
        for (permission in listOf(android.Manifest.permission.INTERNET, android.Manifest.permission.CAMERA)) {
            check(context.checkSelfPermission(permission) == PackageManager.PERMISSION_DENIED)
        }
        return super.newApplication(cl, className, context)
    }

    override fun onCreate(arguments: Bundle) {
        val run = checkNotNull(arguments.getString("hardwareProofRun"))
        check(run.matches(Regex("[0-9a-f]{32}")))
        check(targetContext.packageName == "com.dominik.cardvault.hardwareproof.r$run")
        check(arguments.getString("hardwareProofMode") == "isolated-redmi33")
        check(arguments.getString("domidexRegressionRunId")?.matches(Regex("[0-9a-f]{32}")) == true)
        val selectors = listOf(
            "com.dominik.cardvault.PrivateBinderFirstPositiveInstrumentedTest",
            "com.dominik.cardvault.OwnedHistoryFlowInvalidationInstrumentedTest",
            "com.dominik.cardvault.BinderDataLoadingPerformanceInstrumentedTest",
            "com.dominik.cardvault.data.sync.PrivateSyncTransactionInstrumentedTest",
            "com.dominik.cardvault.ui.CameraQualityTest",
            "com.dominik.cardvault.ui.CardImageNormalizerInstrumentedTest",
            "com.dominik.cardvault.ui.ScannerSessionIsolationInstrumentedTest",
        )
        check(arguments.getString("class") == selectors.joinToString(","))
        check(arguments.keySet() == setOf("hardwareProofRun", "hardwareProofMode", "domidexRegressionRunId", "class"))
        // The preserved genuine provider must already have initialized bundled ML Kit.
        // Do not initialize Firebase or WorkManager, replace OCR, or create another application.
        check(com.google.mlkit.common.sdkinternal.MlKitContext.getInstance().applicationContext.packageName == targetContext.packageName)
        check(com.google.firebase.FirebaseApp.getApps(targetContext).isEmpty())
        super.onCreate(arguments)
    }
}
