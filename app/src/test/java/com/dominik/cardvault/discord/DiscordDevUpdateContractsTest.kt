package com.dominik.cardvault.discord

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscordDevUpdateContractsTest {
    @Test(expected = IllegalArgumentException::class)
    fun `payload rejects more than five screenshots`() {
        DiscordDevUpdatePayload(
            version = "1.0",
            buildNumber = 1,
            note = "Test",
            screenshotUris = List(6) { "content://image/$it" },
            videoUri = null,
            timestampEpochMillis = 1,
        )
    }

    @Test
    fun `unconfigured publisher never sends a fallback request`() = runBlocking {
        val result = UnconfiguredDiscordDevUpdatePublisher.publish(
            DiscordDevUpdatePayload("1.0", 1, "Test", emptyList(), null, 1),
        )
        assertTrue(result is DiscordDevUpdatePublishResult.Unavailable)
    }
}
