package com.dominik.cardvault.ui

import com.dominik.cardvault.data.catalog.additionalEnglishArtworkUrls
import java.net.URI
import java.util.Locale

private const val englishArtworkNotice = "Ersatzbild auf Englisch"
private val englishTcgdexCardPath = Regex(
    "/en/[A-Za-z0-9_!.-]+/[A-Za-z0-9_!.-]+/[A-Za-z0-9_!.-]+/(?:low|high)\\.(?:webp|png|jpg)",
)
private val englishPokemonTcgCardPath = Regex("/[A-Za-z0-9_.-]+/[A-Za-z0-9_!.-]+\\.(?:png|jpg|jpeg|webp)")
private val reviewedEnglishSerebiiPaths = setOf(
    "/card/megapromos/96.jpg", "/card/megapromos/97.jpg", "/card/megapromos/98.jpg",
    "/card/megapromos/104.jpg", "/card/megapromos/105.jpg", "/card/megapromos/106.jpg",
    "/card/30thcelebration/rgb1.jpg", "/card/30thcelebration/rgb2.jpg", "/card/30thcelebration/rgb3.jpg",
)

/**
 * Display-only source-language hint, not a claim that no German image exists.
 * Unknown sources and private photos are never inferred from names or URL substrings.
 */
internal fun cardArtworkLanguageNotice(imageUrl: String?): String? {
    if (imageUrl.isNullOrBlank()) return null
    val uri = try { URI(imageUrl) } catch (_: Exception) { return null }
    if (!uri.scheme.equals("https", ignoreCase = true) || uri.isOpaque || uri.rawUserInfo != null ||
        (uri.port != -1 && uri.port != 443) || uri.rawFragment != null
    ) return null
    val host = uri.host?.lowercase(Locale.ROOT) ?: return null
    val path = uri.rawPath ?: return null
    if (path.split('/').any { it == "." || it == ".." }) return null
    val english = "https://$host$path" in additionalEnglishArtworkUrls || when (host) {
        "assets.tcgdex.net" -> englishTcgdexCardPath.matches(path)
        "images.pokemontcg.io" -> englishPokemonTcgCardPath.matches(path)
        // Serebii also publishes Japanese cards: only the existing, reviewed
        // English supplement images are known here (catalog-30th-supplement-20260922.md).
        "www.serebii.net" -> path in reviewedEnglishSerebiiPaths
        else -> false
    }
    return englishArtworkNotice.takeIf { english }
}
