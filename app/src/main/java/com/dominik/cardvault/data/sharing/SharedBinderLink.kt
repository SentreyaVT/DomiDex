package com.dominik.cardvault.data.sharing

import android.net.Uri
import java.util.Base64

private val canonicalSharedBinderLink = Regex("https://domidex\\.web\\.app/share/([A-Za-z0-9_-]{43})")
private val canonicalSharedBinderToken = Regex("[A-Za-z0-9_-]{43}")

/** Preserve the encoded URI: decoding paths first would also accept aliases and extra segments. */
internal fun sharedBinderTokenFromUri(uri: Uri?): String? = sharedBinderTokenFromLink(uri?.toString())

/** A public bearer link is accepted only in the exact format produced by the sharing service. */
internal fun sharedBinderTokenFromLink(link: String?): String? {
    val token = link?.let { canonicalSharedBinderLink.matchEntire(it)?.groupValues?.get(1) } ?: return null
    return token.takeIf(::isCanonicalSharedBinderToken)
}

internal fun isCanonicalSharedBinderToken(token: String): Boolean {
    if (!canonicalSharedBinderToken.matches(token)) return false
    val decoded = try {
        Base64.getUrlDecoder().decode(token)
    } catch (_: IllegalArgumentException) {
        return false
    }
    return decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == token
}
