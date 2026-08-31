package com.dominik.cardvault.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF7442B8),
    onPrimary = VaultText,
    primaryContainer = MewPlumDeep,
    onPrimaryContainer = VaultText,
    secondary = MewPlum,
    onSecondary = Color(0xFF2E1747),
    secondaryContainer = MewPlumDeep,
    onSecondaryContainer = Color(0xFFEAD9FF),
    tertiary = MewIce,
    onTertiary = Color(0xFF003547),
    tertiaryContainer = MewIceDeep,
    onTertiaryContainer = Color(0xFFC6EEFF),
    background = VaultNight,
    onBackground = VaultText,
    surface = VaultNightRaised,
    onSurface = VaultText,
    surfaceVariant = VaultPlumSurface,
    onSurfaceVariant = VaultTextMuted,
    outline = VaultOutline,
    outlineVariant = VaultOutlineSoft,
    surfaceContainerLowest = VaultNight,
    surfaceContainerLow = VaultNightRaised,
    surfaceContainer = VaultPlumSurface,
    surfaceContainerHigh = VaultPlumSurfaceHigh,
    surfaceContainerHighest = VaultGlassHigh,
    error = VaultError,
)

private val DomiDexShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = CardVaultShapes.thumbnail,
    medium = CardVaultShapes.field,
    large = CardVaultShapes.card,
    extraLarge = CardVaultShapes.sheet,
)

@Composable
fun CardVaultTheme(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    SideEffect {
        val window = (context as Activity).window
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = Typography,
        shapes = DomiDexShapes,
        content = content
    )
}
