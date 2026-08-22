package com.dominik.cardvault.ui

/** Filled only after the owner provides a public support identity. */
object AppSupportConfig {
    const val displayName: String = ""
    const val email: String = ""
    val isConfigured: Boolean get() = displayName.isNotBlank() && email.isNotBlank()
}
