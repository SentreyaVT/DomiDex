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

/** Restricted to two opted-in synthetic Room methods in a disposable package. */
class HistoryProofRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName.matches(Regex("com\\.dominik\\.cardvault\\.historyproof\\.r[0-9a-f]{32}")))
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.HARDWARE == "ranchu" && Build.VERSION.SDK_INT == 36)
        check(!BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
        check(className == "com.dominik.cardvault.DomiDexApplication")
        check(context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
        check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
        check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        return super.newApplication(cl, className, context)
    }
    override fun onCreate(arguments: Bundle) {
        val run = checkNotNull(arguments.getString("historyProofRun"))
        check(run.matches(Regex("[0-9a-f]{32}")))
        check(targetContext.packageName == "com.dominik.cardvault.historyproof.r$run")
        check(arguments.getString("historyProofMode") == "isolated-room-only")
        check(arguments.getString("release27RoomScale") == "true")
        check(arguments.getString("release27RoomSizes") == "1000")
        check(arguments.getString("release27RoomRepeats") == "3")
        check(arguments.getString("release27RoomHistoryPostFix") == "phase-first-positive")
        val method = arguments.getString("class")
        val label = when (method) {
            "com.dominik.cardvault.Release27RoomScaleInstrumentedTest#localRoomPostFixHistoryPhasesAndFirstPositiveCounterfactual" -> "phase-$run"
            "com.dominik.cardvault.Release27RoomScaleInstrumentedTest#localRoomFirstPositiveCounterfactualEdgeParity" -> "edges-$run"
            else -> error("Unreviewed history proof method")
        }
        check(arguments.getString("release27RoomLabel") == label)
        val kind = if (label.startsWith("phase-")) "localRoomPostFixHistoryPhases" else "localRoomFirstPositiveEdgeParity"
        check(!java.io.File(targetContext.cacheDir, "release27-room-scale-$label-$kind.json").exists())
        for (key in listOf("package", "notClass", "annotation", "notAnnotation", "numShards", "shardIndex", "log", "coverage")) {
            check(!arguments.containsKey(key)) { "No alternate discovery, logging-only or coverage mode" }
        }
        super.onCreate(arguments)
    }
}
