package com.dominik.cardvault.ui

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Start surfaces also react if animation accessibility changes while the app is open. */
@Composable
internal fun rememberStartupMotionEnabled(): Boolean {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var enabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver, lifecycle) {
        fun update() { enabled = ValueAnimator.areAnimatorsEnabled() }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = update()
        }
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) update()
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        lifecycle.addObserver(listener)
        update()
        onDispose {
            resolver.unregisterContentObserver(observer)
            lifecycle.removeObserver(listener)
        }
    }
    return enabled
}
