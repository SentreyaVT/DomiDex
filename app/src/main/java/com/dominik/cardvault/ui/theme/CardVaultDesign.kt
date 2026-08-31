package com.dominik.cardvault.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/** Central extension points for the evolving CardVault brand and layout system. */
object CardVaultSpacing {
    val screen = 18.dp
    val screenCompact = 14.dp
    val section = 20.dp
    val card = 14.dp
    val compact = 6.dp
    val micro = 4.dp
    val navHorizontal = 10.dp
    val navVertical = 7.dp
    val heroHeight = 242.dp
    val heroArt = 164.dp
    val buttonHeight = 52.dp
    val bottomNavHeight = 68.dp
}

object CardVaultShapes {
    val card = RoundedCornerShape(18.dp)
    val cardCompact = RoundedCornerShape(13.dp)
    val feature = RoundedCornerShape(24.dp)
    val thumbnail = RoundedCornerShape(10.dp)
    val field = RoundedCornerShape(14.dp)
    val pill = RoundedCornerShape(999.dp)
    val sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)
}

object DomiDexAlpha {
    const val RestingBorder = .42f
    const val QuietBorder = .22f
    const val Disabled = .38f
    const val Glass = .88f
    const val SecondaryText = .78f
}
