package com.dominik.cardvault.data.feedback

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

class FeedbackPathPolicyTest {
    @Test fun acceptsGeneratedAndSafeLegacyIds() {
        listOf(UUID.randomUUID().toString(), "feedback-1", "legacy_id2").forEach { assertEquals(it, FeedbackPathPolicy.validateId(it)) }
    }
    @Test fun rejectsTraversalAbsoluteUnicodeAndSeparators() {
        listOf("", "../escape", "a/../../escape", "/tmp/a", "C:\\a", "a\\b", "．．／a", "a\u0000b", "a.b", "а", "x".repeat(81)).forEach {
            try { FeedbackPathPolicy.validateId(it); fail("Accepted unsafe id") } catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun resolvesOnlyExpectedRootAndDirectory() {
        val root = Files.createTempDirectory("feedback-path-test").toFile()
        try {
            assertEquals(root.canonicalFile.resolve("metadata/safe.json"), FeedbackPathPolicy.file(root, "metadata", "safe", "json"))
            try { FeedbackPathPolicy.file(root, "../escape", "safe", "json"); fail() } catch (_: IllegalArgumentException) { }
        } finally { root.deleteRecursively() }
    }
}
