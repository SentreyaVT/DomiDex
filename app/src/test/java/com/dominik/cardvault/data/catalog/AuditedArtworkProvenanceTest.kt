package com.dominik.cardvault.data.catalog

import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class AuditedArtworkProvenanceTest {
    @Test fun completeRegistryMatchesFrozenDecodedSourceReceipts() {
        val entries = auditedPokemonCardArtworkOverrides()
        assertEquals(843, entries.size)
        val canonical = entries.map { entry ->
            listOf(entry.sourceSetId, entry.sourceCardId, entry.sourceLocalId,
                requireNotNull(entry.replacement).smallImageUrl, entry.replacement.largeImageUrl,
                entry.unavailableUrls.sorted().joinToString(","),
                entry.replaceableUrls.sorted().joinToString(",")).joinToString("|")
        }.sorted().joinToString("\n", postfix = "\n")
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        // Pinned independently from retained decoded-image receipts, not generated from production Kotlin.
        assertEquals("dc729c34dbe7590c65d8fd1234c76cdd2200656a9160562d9c1466140c7039a6", digest)
    }
}
