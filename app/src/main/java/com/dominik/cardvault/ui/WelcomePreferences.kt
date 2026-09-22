package com.dominik.cardvault.ui

import android.content.Context
import android.content.SharedPreferences

/** Presentation only: the existing backup-safe flags are not permissions or consent. */
internal class WelcomePreferences(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("cardvault_ui", Context.MODE_PRIVATE))

    fun shouldShow(): Boolean = needsDomiDexWelcome(
        preferences.getBoolean("arrival_seen", false),
        preferences.getBoolean("relaxed_tutorial_answered_v1", false),
    )

    fun complete(): Boolean = preferences.edit()
        .putBoolean("arrival_seen", true)
        .putBoolean("relaxed_tutorial_answered_v1", true)
        .commit()
}

internal fun needsDomiDexWelcome(arrivalSeen: Boolean, tutorialAnswered: Boolean): Boolean =
    !arrivalSeen && !tutorialAnswered
