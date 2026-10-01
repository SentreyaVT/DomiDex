package com.dominik.cardvault.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.dominik.cardvault.ui.theme.*
import org.junit.Assert.assertTrue
import org.junit.Test

class DomiDexPaletteTest {
    private fun contrast(a: Color, b: Color): Float {
        val first = a.luminance()
        val second = b.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }

    @Test fun smallBodyAndAccentTextRemainReadableOnRaisedSurfaces() {
        listOf(VaultNight, VaultNightRaised, VaultPlumSurface, VaultPlumSurfaceHigh, VaultGlassHigh).forEach { background ->
            listOf(VaultText, VaultTextMuted, VaultTextFaint, MewRose, MewRoseSoft, MewPlum, MewIce, MewPeach, MewMint).forEach { foreground ->
                assertTrue("Insufficient text contrast: $foreground / $background", contrast(foreground, background) >= 4.5f)
            }
        }
    }

    @Test fun primaryButtonUsesReadableDarkInkOnLavender() {
        assertTrue(contrast(VaultOnAccent, MewPlum) >= 4.5f)
    }

    @Test fun materialAccentActionsKeepReadableInk() {
        listOf(
            VaultOnAccent to MewPlum,
            Color(0xFF061D26) to MewIce,
            VaultOnAccent to MewRose,
            VaultText to MewPlumDeep,
            Color(0xFFD7F9FF) to MewIceDeep,
            Color(0xFFF1E6FF) to MewPeachDeep,
        ).forEach { (text, background) ->
            assertTrue("Material action loses contrast: $text / $background", contrast(text, background) >= 4.5f)
        }
    }

    @Test fun brandedAccentContainersKeepReadableLabels() {
        listOf(MewRose to MewPlumDeep, MewIce to MewIceDeep, MewPeach to MewPeachDeep, MewMint to MewMintDeep).forEach { (text, background) ->
            assertTrue("Accent label loses contrast: $text / $background", contrast(text, background) >= 4.5f)
        }
    }

    @Test fun deepBrandVioletsKeepSilverWhiteLabelsReadable() {
        listOf(MewPlumDeep, DomiDexVioletDeep, DomiDexViolet).forEach { background ->
            assertTrue("Brand violet loses readable foreground: $background", contrast(VaultText, background) >= 4.5f)
        }
    }

    @Test fun selectedNavigationGradientKeepsLabelsAndIconsReadable() {
        listOf(DomiDexVioletDeep, MewPlumDeep).forEach { background ->
            listOf(VaultText, MewRose).forEach { foreground ->
                assertTrue("Selected navigation loses contrast: $foreground / $background", contrast(foreground, background) >= 4.5f)
            }
        }
    }
}
