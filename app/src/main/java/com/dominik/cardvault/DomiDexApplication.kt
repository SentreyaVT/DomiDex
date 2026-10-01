package com.dominik.cardvault

import android.app.Application
import com.dominik.cardvault.security.DomiDexAppCheck

class DomiDexApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        DomiDexAppCheck.initialize(BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
    }
}
