package com.dominik.cardvault.ui

/** Filled only after the owner provides a public support identity. */
object AppSupportConfig {
    const val displayName: String = ""
    const val email: String = ""
    const val communityUrl: String = "https://discord.gg/BE6upBbJ3E"
    val isConfigured: Boolean get() = displayName.isNotBlank() && email.isNotBlank()
    val hasCommunity: Boolean get() = communityUrl.isNotBlank()
}

/** Deliberately empty until the owner selects and configures a lawful support destination. */
object ProjectSupportConfig {
    const val destination: String = ""
    val isConfigured: Boolean get() = destination.isNotBlank()
}

/** A backend-issued owner claim is mandatory; this client never decides who an owner is. */
object OwnerSupportConfig {
    val isSecureBackendConfigured: Boolean get() = false
}
