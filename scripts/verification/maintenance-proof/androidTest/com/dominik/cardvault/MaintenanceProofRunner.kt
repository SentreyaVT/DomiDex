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

class MaintenanceProofRunner : AndroidJUnitRunner() {
    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application {
        check(context.packageName.matches(Regex("com\\.dominik\\.cardvault\\.maintenanceproof\\.r[0-9a-f]{32}")))
        check(className == "android.app.Application" && !BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(Build.VERSION.SDK_INT == 26 && Build.MODEL == "Android SDK built for x86_64")
        check(Build.SUPPORTED_ABIS.firstOrNull() == "x86_64")
        check(Build.FINGERPRINT == "Android/sdk_gphone_x86_64/generic_x86_64:8.0.0/OSR1.180418.026/6741039:userdebug/dev-keys")
        check(context.checkSelfPermission(android.Manifest.permission.INTERNET) == PackageManager.PERMISSION_DENIED)
        check(context.checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_DENIED)
        check(Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, -1) == 1)
        check(Settings.Global.getInt(context.contentResolver, "wifi_on", -1) == 0)
        check(context.getSystemService(ConnectivityManager::class.java).activeNetwork == null)
        return super.newApplication(cl, className, context)
    }

    override fun onCreate(arguments: Bundle) {
        val run = checkNotNull(arguments.getString("maintenanceProofRun"))
        check(run.matches(Regex("[0-9a-f]{32}")))
        check(targetContext.packageName == "com.dominik.cardvault.maintenanceproof.r$run")
        check(arguments.getString("maintenanceInvocation")?.matches(Regex("[0-9a-f]{32}")) == true)
        check(arguments.keySet() == setOf("class", "maintenanceProofRun", "maintenanceInvocation"))
        // Exact allowlist generated from reviewed contracts.py, packaged into this
        // unique test APK, hash-bound by the build/install receipt. No discovery.
        val bytes = context.assets.open("maintenance-selection.txt").use { it.readBytes() }
        check(bytes.size in 1..12000)
        val text = bytes.toString(Charsets.US_ASCII)
        check(text.endsWith("\n") && !text.contains('\r'))
        val expected = text.dropLast(1).split('\n')
        check(expected.size == 44 && expected.toSet().size == 44)
        check(expected.all { it.matches(Regex("com\\.dominik\\.cardvault\\.[A-Za-z0-9_.]+#[A-Za-z][A-Za-z0-9_]*")) })
        check(arguments.getString("class") == expected.joinToString(","))
        super.onCreate(arguments)
    }
}
