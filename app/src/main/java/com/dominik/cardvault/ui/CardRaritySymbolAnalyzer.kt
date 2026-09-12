package com.dominik.cardvault.ui

import android.graphics.Bitmap
import android.graphics.Color

/**
 * Conservative lower-border colour evidence for modern German card layouts.
 *
 * This deliberately does not try to infer a rarity from artwork or a single
 * bright pixel.  It samples the lower-left metadata area, requires a small
 * cluster of consistent pixels and reports only low-confidence corroboration.
 * Shape/star counting is left unknown unless a future source-backed layout
 * model can make it reliable. Source rarity remains the authority.
 */
internal object CardRaritySymbolAnalyzer {
    fun analyze(bitmap: Bitmap): RaritySymbolEvidence {
        if (bitmap.width < 32 || bitmap.height < 32) return RaritySymbolEvidence(SymbolColor.UNKNOWN, null, 0f)
        val left = (bitmap.width * .03f).toInt()
        val right = (bitmap.width * .42f).toInt().coerceAtLeast(left + 1)
        val top = (bitmap.height * .76f).toInt()
        val bottom = (bitmap.height * .98f).toInt().coerceAtLeast(top + 1)
        var gold = 0
        var silver = 0
        var dark = 0
        var colored = 0
        var samples = 0
        val hsv = FloatArray(3)
        val step = (minOf(bitmap.width, bitmap.height) / 96).coerceAtLeast(2)
        for (y in top until bottom step step) for (x in left until right step step) {
            val pixel = bitmap.getPixel(x, y)
            Color.colorToHSV(pixel, hsv)
            val hue = hsv[0]; val saturation = hsv[1]; val value = hsv[2]
            samples++
            when {
                value < .24f && saturation < .50f -> dark++
                value > .52f && saturation < .20f -> silver++
                value > .42f && saturation > .26f && hue in 32f..63f -> gold++
                value > .42f && saturation > .42f -> colored++
            }
        }
        if (samples == 0) return RaritySymbolEvidence(SymbolColor.UNKNOWN, null, 0f)
        val ranked = listOf(
            SymbolColor.GOLD to gold,
            SymbolColor.SILVER to silver,
            SymbolColor.BLACK to dark,
            SymbolColor.SPECIAL_MULTICOLOR to colored,
        ).maxBy { it.second }
        // Reflections and printed text share this ROI. Do not report anything
        // unless the colour covers a meaningful fraction, and cap confidence so
        // it can only validate source metadata, never override it.
        val share = ranked.second.toFloat() / samples
        return if (share < .16f) RaritySymbolEvidence(SymbolColor.UNKNOWN, null, 0f)
        else RaritySymbolEvidence(ranked.first, null, (share * .42f).coerceAtMost(.38f))
    }
}
