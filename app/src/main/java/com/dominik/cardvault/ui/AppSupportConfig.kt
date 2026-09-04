package com.dominik.cardvault.ui

/** Public contact details supplied by the operator for support and privacy requests. */
object AppSupportConfig {
    const val displayName: String = "DomiDex · Dominik"
    const val email: String = "Dominik.muxifeld@gmail.com"
    const val privacyUrl: String = "https://domidex.web.app/datenschutz"
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
