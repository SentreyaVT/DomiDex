package com.dominik.cardvault.data.content

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/** Google Play only: sideloaded/debug builds simply receive no update information. */
class PlayUpdateGateway(
    private val activity: Activity,
    private val manager: AppUpdateManager = AppUpdateManagerFactory.create(activity),
) : AutoCloseable {
    private var flowStarted = false
    private var closed = false
    private val installListener = InstallStateUpdatedListener { state ->
        if (state.installStatus() == InstallStatus.DOWNLOADED) manager.completeUpdate()
    }

    init { manager.registerListener(installListener) }

    fun checkForPlayUpdate() {
        if (closed) return
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE) &&
                    !flowStarted
                ) {
                    flowStarted = true
                    @Suppress("DEPRECATION")
                    manager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, activity, REQUEST_CODE)
                } else if (info.installStatus() == InstallStatus.DOWNLOADED) {
                    manager.completeUpdate()
                }
            }
            // Expected for emulator/sideload installs and intentionally a no-op.
            .addOnFailureListener { }
    }

    /** Completes a flexible download even after the activity was paused/recreated. */
    fun onResume() {
        if (closed) return
        manager.appUpdateInfo.addOnSuccessListener { info ->
            if (info.installStatus() == InstallStatus.DOWNLOADED) manager.completeUpdate()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        manager.unregisterListener(installListener)
    }

    private companion object { const val REQUEST_CODE = 4102 }
}
