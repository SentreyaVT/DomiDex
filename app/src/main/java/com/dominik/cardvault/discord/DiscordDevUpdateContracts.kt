package com.dominik.cardvault.discord

/**
 * Client-side facts for a developer-approved Discord update.
 * Media is selected explicitly by the developer and referenced locally until a future
 * authenticated backend uploads it. This model deliberately contains no Discord token,
 * endpoint, account detail, diagnostics, or collection data.
 */
data class DiscordDevUpdatePayload(
    val version: String,
    val buildNumber: Long,
    val note: String,
    val screenshotUris: List<String>,
    val videoUri: String?,
    val timestampEpochMillis: Long,
    // Reserved factual fields for the authenticated backend integration.
    val features: List<String> = emptyList(),
    val improvements: List<String> = emptyList(),
    val fixes: List<String> = emptyList(),
    val scannerChanges: List<String> = emptyList(),
    val binderChanges: List<String> = emptyList(),
    val uiChanges: List<String> = emptyList(),
) {
    init {
        require(version.isNotBlank()) { "A version is required." }
        require(buildNumber >= 0) { "A non-negative build number is required." }
        require(note.length <= MAX_NOTE_LENGTH) { "The note is too long." }
        require(screenshotUris.size <= MAX_SCREENSHOTS) { "At most $MAX_SCREENSHOTS screenshots are allowed." }
        require(screenshotUris.none(String::isBlank)) { "Screenshot references must not be blank." }
        require(videoUri?.isNotBlank() != false) { "Video reference must not be blank." }
    }

    companion object {
        const val MAX_SCREENSHOTS = 5
        const val MAX_NOTE_LENGTH = 600
    }
}

sealed interface DiscordDevUpdatePublishResult {
    data object Published : DiscordDevUpdatePublishResult
    data class Unavailable(val reason: String) : DiscordDevUpdatePublishResult
    data class Failed(val safeMessage: String) : DiscordDevUpdatePublishResult
}

/**
 * Future implementation requirements:
 * - authenticate the current developer with a server-issued owner/admin claim;
 * - upload the explicitly selected media using short-lived upload URLs or multipart HTTPS;
 * - forward the structured payload to the server-side Discord bot integration;
 * - authorize again on the backend before posting to #app-stand.
 */
interface DiscordDevUpdatePublisher {
    suspend fun publish(payload: DiscordDevUpdatePayload): DiscordDevUpdatePublishResult
}

/** Phase 1 safety boundary: there is intentionally no client endpoint or fallback webhook. */
object UnconfiguredDiscordDevUpdatePublisher : DiscordDevUpdatePublisher {
    override suspend fun publish(payload: DiscordDevUpdatePayload): DiscordDevUpdatePublishResult =
        DiscordDevUpdatePublishResult.Unavailable(
            "Discord-Veröffentlichung ist noch nicht verbunden. Es wurde nichts hochgeladen oder gesendet.",
        )
}
