package tools.wp2.sideloader.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val CmfOrange = Color(0xFFFB471F)
val CmfOrangeDark = Color(0xFFFF5A36)
val SuccessGreen = Color(0xFF22C55E)
val SuccessGreenDark = Color(0xFF4ADE80)
val WarnAmber = Color(0xFFF59E0B)

private val DarkColors = darkColorScheme(
    primary = CmfOrangeDark,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF3A160D),
    onPrimaryContainer = Color(0xFFFFDBD2),
    secondary = Color(0xFFA1A1AA),
    onSecondary = Color(0xFF18181B),
    secondaryContainer = Color(0xFF27272A),
    onSecondaryContainer = Color(0xFFE4E4E7),
    background = Color(0xFF0C0C0F),
    onBackground = Color(0xFFF4F4F5),
    surface = Color(0xFF141418),
    onSurface = Color(0xFFF4F4F5),
    surfaceVariant = Color(0xFF1D1D23),
    onSurfaceVariant = Color(0xFFA1A1AA),
    outline = Color(0xFF2E2E36),
    outlineVariant = Color(0xFF24242B),
    error = Color(0xFFEF4444),
    onError = Color.White,
    errorContainer = Color(0xFF3B1215),
    onErrorContainer = Color(0xFFFECACA),
)

private val LightColors = lightColorScheme(
    primary = CmfOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE4DD),
    onPrimaryContainer = Color(0xFF471104),
    secondary = Color(0xFF52525B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE4E4E7),
    onSecondaryContainer = Color(0xFF18181B),
    background = Color(0xFFF4F4F6),
    onBackground = Color(0xFF111114),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF111114),
    surfaceVariant = Color(0xFFECECEF),
    onSurfaceVariant = Color(0xFF52525B),
    outline = Color(0xFFD4D4D8),
    outlineVariant = Color(0xFFE4E4E7),
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

@Composable
fun WP2Theme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
