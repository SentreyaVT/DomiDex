package com.dominik.cardvault.data.sharing

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedBinderLinkTest {
    private val token = "A".repeat(43)
    private val link = "https://domidex.web.app/share/$token"

    @Test fun acceptsCanonicalServiceLinksOnly() {
        for (bytes in listOf(ByteArray(32), ByteArray(32) { it.toByte() }, ByteArray(32) { 255.toByte() })) {
            val expected = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
            assertEquals(expected, sharedBinderTokenFromLink("https://domidex.web.app/share/$expected"))
        }
    }

    @Test fun rejectsForeignOriginsSchemesPortsAndCredentials() {
        for (invalid in listOf(
            link.replace("https:", "http:"), link.replace("https:", "domidex:"),
            link.replace("domidex.web.app", "evil.invalid"),
            link.replace("domidex.web.app", "domidex.web.app.evil.invalid"),
            link.replace("domidex.web.app", "domidex.web.app:443"),
            link.replace("domidex.web.app", "user@domidex.web.app"),
            link.replace("domidex.web.app", "domidex.web.app@evil.invalid"),
            link.replace("domidex.web.app", "DOMIDEX.web.app"),
            link.replace("https:", "HTTPS:"), "//$link", " $link", "$link\n",
        )) assertNull(invalid, sharedBinderTokenFromLink(invalid))
    }

    @Test fun rejectsQueriesFragmentsExtraPathsAndEncodedAliases() {
        for (invalid in listOf(
            "$link?", "$link?owner=other", "$link#", "$link#fragment", "$link/", "$link/extra",
            link.replace("/share/", "/share//"), link.replace("/share/", "/other/../share/"),
            link.replace("/share/", "/%73hare/"), link.replace("/share/", "/share%2F"),
            link.dropLast(1) + "%41", link.dropLast(1) + "%2F", "$link%00", "$link\u0000",
        )) assertNull(invalid, sharedBinderTokenFromLink(invalid))
    }

    @Test fun rejectsNonCanonicalTokensIncludingNonZeroPaddingBits() {
        for (invalidToken in listOf("", "A".repeat(42), "A".repeat(44), "$token=", "A".repeat(42) + "B",
            "A".repeat(42) + "+", "A".repeat(42) + "/", "A".repeat(42) + "ä")) {
            assertNull(sharedBinderTokenFromLink("https://domidex.web.app/share/$invalidToken"))
        }
        assertNull(sharedBinderTokenFromLink(null))
        assertNull(sharedBinderTokenFromLink(""))
    }
}
