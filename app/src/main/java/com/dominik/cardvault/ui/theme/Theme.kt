package com.dominik.cardvault.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = MewRose,
    onPrimary = Color(0xFF361524),
    primaryContainer = MewRoseSoft,
    onPrimaryContainer = Color(0xFFFFD9E6),
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
    outlineVariant = Color(0xFF443B52),
    error = VaultError,
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
        content = content
    )
}
