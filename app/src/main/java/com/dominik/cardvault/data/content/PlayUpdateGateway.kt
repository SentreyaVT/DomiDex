package com.dominik.cardvault.data.content

import android.app.Activity
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability

/** Google Play only: sideloaded/debug builds simply receive no update information. */
class PlayUpdateGateway(private val activity: Activity) {
    fun checkForPlayUpdate() {
        val manager = AppUpdateManagerFactory.create(activity)
        manager.appUpdateInfo
            .addOnSuccessListener { info ->
                if (info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE &&
                    info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE)
                ) {
                    @Suppress("DEPRECATION")
                    manager.startUpdateFlowForResult(info, AppUpdateType.FLEXIBLE, activity, REQUEST_CODE)
                }
            }
            // Expected for emulator/sideload installs and intentionally a no-op.
            .addOnFailureListener { }
    }

    private companion object { const val REQUEST_CODE = 4102 }
}
