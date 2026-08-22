package com.dominik.cardvault.support

import kotlinx.coroutines.flow.Flow

/**
 * Network-facing contracts for a future authenticated support backend.
 * Authorization decisions must come from verified server claims, never from client fields.
 */
data class SupportIdentity(
    val subjectId: String,
    val displayName: String?,
    val role: SupportRole,
)

enum class SupportRole { USER, SUPPORT_AGENT, ADMIN }
enum class SupportCaseState { OPEN, WAITING_FOR_USER, WAITING_FOR_SUPPORT, RESOLVED, CLOSED }
enum class SupportAuthorKind { USER, SUPPORT_AGENT, SYSTEM }

/**
 * Presentation hints derived from a role returned by the authenticated backend.
 * They only hide or show UI; every backend operation must independently authorize the token.
 */
data class SupportUiCapabilities(
    val showTeamNavigation: Boolean,
    val showSupportInbox: Boolean,
    val showCaseManagement: Boolean,
    val showAdministration: Boolean,
) {
    companion object {
        val SignedOut = SupportUiCapabilities(false, false, false, false)

        fun fromServerIdentity(identity: SupportIdentity?): SupportUiCapabilities = when (identity?.role) {
            SupportRole.SUPPORT_AGENT -> SupportUiCapabilities(true, true, true, false)
            SupportRole.ADMIN -> SupportUiCapabilities(true, true, true, true)
            SupportRole.USER, null -> SignedOut
        }
    }
}

data class SupportCase(
    val id: String,
    val ownerSubjectId: String,
    val subject: String,
    val state: SupportCaseState,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class SupportMessage(
    val id: String,
    val caseId: String,
    val authorSubjectId: String,
    val authorKind: SupportAuthorKind,
    val body: String,
    val createdAtEpochMillis: Long,
)

data class SupportConsent(
    val includeAppVersion: Boolean,
    val includeDiagnostics: Boolean,
)

interface SupportBackend {
    val identity: Flow<SupportIdentity?>
    fun observeMyCases(): Flow<List<SupportCase>>
    fun observeCase(caseId: String): Flow<Pair<SupportCase, List<SupportMessage>>>
    suspend fun createCase(subject: String, firstMessage: String, consent: SupportConsent): String
    suspend fun sendMessage(caseId: String, body: String)
    suspend fun updateCaseState(caseId: String, state: SupportCaseState)
    suspend fun registerPushToken(token: String, consentGranted: Boolean)
    suspend fun unregisterPushToken(token: String)
}
