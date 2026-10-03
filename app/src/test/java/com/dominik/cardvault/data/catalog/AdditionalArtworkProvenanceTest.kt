package com.dominik.cardvault.data.catalog

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class AdditionalArtworkProvenanceTest {
    @Test fun addedRegistryMatchesTheIndependentlyValidatedMissingSourceReceipts() {
        val entries = additionalPokemonCardArtworkOverrides()
        assertEquals(396, entries.size)
        val canonical = entries.map { entry ->
            listOf(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId,
                requireNotNull(entry.replacement).smallImageUrl, entry.replacement.largeImageUrl,
                entry.unavailableUrls.sorted().joinToString(","),
                entry.replaceableUrls.sorted().joinToString(",")).joinToString("|")
        }.sorted().joinToString("\n", postfix = "\n")
        // Frozen decoded-source receipts, not a digest obtained from this production table.
        // validated-additions.json SHA256: 2f948d38c8ba3720413e4f8fec6d7f3f8f3460f879ab001747868069a654aa00
        assertEquals("b5f3cf2f1b7f2af5776f128268ae6f51b1ebdd3f5efbc67c89a1c624094f0534", sha256(canonical))
    }

    @Test fun englishLabelSetMatchesOnlyTheIndependentlyReviewedEnglishFiles() {
        assertEquals(210, additionalEnglishArtworkUrls.size)
        val canonical = additionalEnglishArtworkUrls.sorted().joinToString("\n", postfix = "\n")
        assertEquals("3a702f271087fd5186db080c34e16747882db941e439180dc8cbd4f5c9216f5a", sha256(canonical))
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
