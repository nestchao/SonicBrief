package dev.sonicbrief.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Indigo600,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDEAFF),
    onPrimaryContainer = Color(0xFF1B1075),
    inversePrimary = Color(0xFFBFB3FF),
    secondary = Color(0xFF6C6782),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0EEF8),
    onSecondaryContainer = Color(0xFF34314A),
    tertiary = Mint500,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCF7F0),
    onTertiaryContainer = Color(0xFF00382F),
    background = Color(0xFFF7F7FB),
    onBackground = Color(0xFF141319),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141319),
    surfaceVariant = Color(0xFFF1F0F7),
    onSurfaceVariant = Color(0xFF6B6880),
    surfaceTint = Indigo600,
    inverseSurface = Color(0xFF2F2E38),
    inverseOnSurface = Color(0xFFF3F1F9),
    error = Color(0xFFD92D20),
    onError = Color.White,
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF7A271A),
    outline = Color(0xFFD9D6E6),
    outlineVariant = Color(0xFFEBE9F3),
    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAFA2FF),
    onPrimary = Color(0xFF1A0E70),
    primaryContainer = Color(0xFF2A2166),
    onPrimaryContainer = Color(0xFFE6E0FF),
    inversePrimary = Indigo600,
    secondary = Color(0xFFB3ADC8),
    onSecondary = Color(0xFF2A2740),
    secondaryContainer = Color(0xFF232031),
    onSecondaryContainer = Color(0xFFE4DFF3),
    tertiary = Color(0xFF5FE3CE),
    onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF0B4A40),
    onTertiaryContainer = Color(0xFFB9F5E9),
    background = Color(0xFF08080C),
    onBackground = Color(0xFFF2F1F7),
    surface = Color(0xFF121218),
    onSurface = Color(0xFFF2F1F7),
    surfaceVariant = Color(0xFF1B1B24),
    onSurfaceVariant = Color(0xFF9C99B0),
    surfaceTint = Color(0xFFAFA2FF),
    inverseSurface = Color(0xFFF2F1F7),
    inverseOnSurface = Color(0xFF1B1B24),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5C0A06),
    errorContainer = Color(0xFF4A1410),
    onErrorContainer = Color(0xFFFFDAD5),
    outline = Color(0xFF2C2B38),
    outlineVariant = Color(0xFF232230),
    scrim = Color.Black,
)

/** Brand-first theme: the app keeps its indigo identity instead of borrowing wallpaper colors. */
@Composable
fun SonicBriefTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = SonicTypography,
        shapes = SonicShapes,
        content = content,
    )
}
