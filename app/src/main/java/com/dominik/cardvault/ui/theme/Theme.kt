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
    primary = MewPlum,
    onPrimary = VaultOnAccent,
    primaryContainer = MewPlumDeep,
    onPrimaryContainer = VaultText,
    secondary = MewIce,
    onSecondary = Color(0xFF061D26),
    secondaryContainer = MewIceDeep,
    onSecondaryContainer = Color(0xFFD7F9FF),
    tertiary = MewRose,
    onTertiary = VaultOnAccent,
    tertiaryContainer = MewPeachDeep,
    onTertiaryContainer = Color(0xFFF1E6FF),
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
    onError = Color(0xFF2E0714),
)

private val DomiDexShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = CardVaultShapes.thumbnail,
    medium = CardVaultShapes.field,
    large = CardVaultShapes.card,
    extraLarge = RoundedCornerShape(28.dp),
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
