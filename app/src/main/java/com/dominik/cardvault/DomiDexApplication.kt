package com.dominik.cardvault

import android.app.Application
import android.content.Context
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.dominik.cardvault.cache.DomiDexImageCachePolicy
import com.dominik.cardvault.security.DomiDexAppCheck

class DomiDexApplication : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        DomiDexAppCheck.initialize(BuildConfig.DOMIDEX_APP_CHECK_ENABLED)
    }

    override fun newImageLoader(context: Context): ImageLoader = ImageLoader.Builder(context)
        .diskCache { DomiDexImageCachePolicy.diskCache }
        .build()
}
