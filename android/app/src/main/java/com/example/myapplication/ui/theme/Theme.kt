package com.example.myapplication.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.example.myapplication.data.model.ThemeMode

// kakusan0/qr-order public/style.css のカラーシステムを忠実に再現
val BrandGreen = Color(0xFF0A6B50)
val BrandGreenBright = Color(0xFF0C8262)
val LightBg = Color(0xFFF4F5F2)
val LightCard = Color(0xFFFFFFFF)
val LightText = Color(0xFF171916)
val LightMuted = Color(0xFF5F645C)
val LightLine = Color(0xFFE2E4DD)
val LightWarn = Color(0xFFA8401C)
val LightPrimaryContainer = Color(0xFFD6F2E7)
val LightWarnContainer = Color(0xFFFCE8E1)

val DarkBg = Color(0xFF101311)
val DarkCard = Color(0xFF1A1E1B)
val DarkText = Color(0xFFEEF0EA)
val DarkMuted = Color(0xFFA2A89E)
val DarkLine = Color(0xFF2C322D)
val DarkAccent = Color(0xFF55D6A8)
val DarkOnAccent = Color(0xFF06140F)
val DarkWarn = Color(0xFFFF9D7A)
val DarkPrimaryContainer = Color(0xFF143C2E)
val DarkWarnContainer = Color(0xFF422116)

private val QrOrderLightColors = lightColorScheme(
    primary = BrandGreen,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = BrandGreen,
    secondary = BrandGreenBright,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE5EAE4),
    onSecondaryContainer = LightText,
    background = LightBg,
    onBackground = LightText,
    surface = LightCard,
    onSurface = LightText,
    surfaceVariant = Color(0xFFECEEE8),
    onSurfaceVariant = LightMuted,
    outline = Color(0xFFC8CCC2),
    outlineVariant = LightLine,
    error = LightWarn,
    onError = Color.White,
    errorContainer = LightWarnContainer,
    onErrorContainer = LightWarn
)

private val QrOrderDarkColors = darkColorScheme(
    primary = DarkAccent,
    onPrimary = DarkOnAccent,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = DarkAccent,
    secondary = BrandGreenBright,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF242B26),
    onSecondaryContainer = DarkText,
    background = DarkBg,
    onBackground = DarkText,
    surface = DarkCard,
    onSurface = DarkText,
    surfaceVariant = Color(0xFF232925),
    onSurfaceVariant = DarkMuted,
    outline = Color(0xFF464F48),
    outlineVariant = DarkLine,
    error = DarkWarn,
    onError = Color(0xFF290F05),
    errorContainer = DarkWarnContainer,
    onErrorContainer = DarkWarn
)

@Composable
fun QrOrderStaffTheme(
    themeMode: ThemeMode = ThemeMode.AUTO,
    content: @Composable () -> Unit
) {
    val useDark = when (themeMode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (useDark) QrOrderDarkColors else QrOrderLightColors,
        content = content
    )
}
