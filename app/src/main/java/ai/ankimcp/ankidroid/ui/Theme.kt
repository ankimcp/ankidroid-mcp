package ai.ankimcp.ankidroid.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// Brand colours from ankimcp.ai (favicon and site); contrast ratios are in docs/DECISIONS.md D46.
internal val OwlNavy = Color(0xFF1A2550)
internal val OwlCream = Color(0xFFF0E4C5)
internal val OwlGold = Color(0xFFD4A84A)

private val LightColors = lightColorScheme(
    primary = OwlNavy,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE6FA),
    onPrimaryContainer = OwlNavy,
    secondary = Color(0xFF0A5BB8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4FF),
    onSecondaryContainer = OwlNavy,
    tertiary = Color(0xFF6B4E0E),
    onTertiary = Color.White,
    tertiaryContainer = OwlGold,
    onTertiaryContainer = OwlNavy,
    background = Color(0xFFFBF8F0),
    onBackground = Color(0xFF1E293B),
    surface = Color(0xFFFBF8F0),
    onSurface = Color(0xFF1E293B),
    surfaceVariant = OwlCream,
    onSurfaceVariant = Color(0xFF334155),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF8F3E6),
    surfaceContainer = Color(0xFFF6EFDD),
    surfaceContainerHigh = Color(0xFFF4EDDA),
    surfaceContainerHighest = Color(0xFFF3EBD6),
    surfaceBright = Color(0xFFFBF8F0),
    surfaceDim = Color(0xFFE6DCC2),
    inverseSurface = OwlNavy,
    inverseOnSurface = OwlCream,
    inversePrimary = OwlGold,
    outline = Color(0xFF7A7461),
    outlineVariant = Color(0xFFD9CBA6),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9EC2FF),
    onPrimary = Color(0xFF0B1D3A),
    primaryContainer = Color(0xFF2A3F6B),
    onPrimaryContainer = Color(0xFFDCE6FA),
    secondary = Color(0xFFA8C8FF),
    onSecondary = Color(0xFF0B1D3A),
    secondaryContainer = Color(0xFF2A3F6B),
    onSecondaryContainer = Color(0xFFDCE6FA),
    tertiary = OwlGold,
    onTertiary = OwlNavy,
    tertiaryContainer = Color(0xFF5A4410),
    onTertiaryContainer = OwlCream,
    background = Color(0xFF152136),
    onBackground = Color(0xFFE3E7EF),
    surface = Color(0xFF152136),
    onSurface = Color(0xFFE3E7EF),
    surfaceVariant = Color(0xFF26344F),
    onSurfaceVariant = Color(0xFFB9C2D3),
    surfaceContainerLowest = Color(0xFF0F1A2C),
    surfaceContainerLow = Color(0xFF1A273D),
    surfaceContainer = Color(0xFF1E2B43),
    surfaceContainerHigh = Color(0xFF223049),
    surfaceContainerHighest = Color(0xFF26344F),
    surfaceBright = Color(0xFF2E3D5A),
    surfaceDim = Color(0xFF152136),
    inverseSurface = OwlCream,
    inverseOnSurface = OwlNavy,
    inversePrimary = Color(0xFF0A5BB8),
    outline = Color(0xFF8A94A8),
    outlineVariant = Color(0xFF3A4864),
)

/** Tunnel and log state colours that Material's scheme has no slot for; each passes AA on cards. */
@Immutable
data class StatusColors(val connected: Color, val warning: Color)

private val LightStatus = StatusColors(connected = Color(0xFF1B6E2A), warning = Color(0xFF8A5300))
private val DarkStatus = StatusColors(connected = Color(0xFF7BD88F), warning = Color(0xFFFFB95C))

val LocalStatusColors = staticCompositionLocalOf { LightStatus }

/** Fixed brand colours in both themes; dynamic colour is deliberately off (D46). */
@Composable
fun AnkiMcpTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, content = content)
    }
}
