package tools.wp2.sideloader.ui

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

val CmfOrange = Color(0xFFFB471F)
val CmfOrangeDark = Color(0xFFFF552E)
val SuccessGreen = Color(0xFF22C55E)
val SuccessGreenDark = Color(0xFF4ADE80)
val GlassSuccess = Color(0xFF6EE7B7)
val WarnAmber = Color(0xFFF59E0B)

enum class AppTheme(val id: String, val swatchColor: Color) {
    CMF_NOTHING("cmf", Color(0xFFFF552E)),
    LIQUID_GLASS("glass", Color(0xFFDCE3EE)),
    NOTHING_LIGHT("light", Color(0xFFFFFFFF)),
    AMOLED_STEALTH("oled", Color(0xFF000000));

    companion object {
        fun fromId(id: String?): AppTheme =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: CMF_NOTHING
    }
}

data class Wp2VisualStyle(
    val theme: AppTheme,
    val isLight: Boolean,
    val isGlass: Boolean,
    val monoTitle: Boolean,
    val hasDotMatrix: Boolean,
    val cardRadius: Dp,
    val bgTop: Color,
    val bgMid: Color,
    val bgBottom: Color,
    val glowPrimary: Color,
    val glowSecondary: Color,
    val dotColor: Color,
    val cardBgTop: Color,
    val cardBgBottom: Color,
    val cardBorderTop: Color,
    val cardBorderBottom: Color,
    val heroBgTop: Color,
    val heroBgBottom: Color,
    val heroBorderTop: Color,
    val heroBorderBottom: Color,
    val slotBg: Color,
    val slotBorder: Color,
    val badgeBg: Color,
    val badgeInk: Color,
    val accentDot: Color,
    val primaryBtnTop: Color,
    val primaryBtnBottom: Color,
    val primaryBtnInk: Color,
    val secondaryBtnBg: Color,
    val secondaryBtnBorder: Color,
    val secondaryBtnInk: Color,
    val success: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textMuted: Color,
)

private val CmfStyle = Wp2VisualStyle(
    theme = AppTheme.CMF_NOTHING,
    isLight = false,
    isGlass = false,
    monoTitle = true,
    hasDotMatrix = true,
    cardRadius = 18.dp,
    bgTop = Color(0xFF0A0A0D),
    bgMid = Color(0xFF0A0A0D),
    bgBottom = Color(0xFF0A0A0D),
    glowPrimary = Color(0x28FB471F),
    glowSecondary = Color.Transparent,
    dotColor = Color(0x22FFFFFF),
    cardBgTop = Color(0xFF141419),
    cardBgBottom = Color(0xFF141419),
    cardBorderTop = Color(0xFF26262F),
    cardBorderBottom = Color(0xFF26262F),
    heroBgTop = Color(0xFF191920),
    heroBgBottom = Color(0xFF191920),
    heroBorderTop = Color(0x73FB471F),
    heroBorderBottom = Color(0x45FB471F),
    slotBg = Color(0xFF101015),
    slotBorder = Color(0xFF22222B),
    badgeBg = Color(0x24FF552E),
    badgeInk = Color(0xFFFF7857),
    accentDot = Color(0xFFFF552E),
    primaryBtnTop = Color(0xFFFF552E),
    primaryBtnBottom = Color(0xFFEB4520),
    primaryBtnInk = Color.White,
    secondaryBtnBg = Color(0xFF202028),
    secondaryBtnBorder = Color(0xFF30303B),
    secondaryBtnInk = Color(0xFFE8E8EE),
    success = SuccessGreenDark,
    textPrimary = Color(0xFFF5F5F7),
    textSecondary = Color(0xFF9B9BA8),
    textMuted = Color(0xFF686876),
)

private val LiquidGlassStyle = Wp2VisualStyle(
    theme = AppTheme.LIQUID_GLASS,
    isLight = false,
    isGlass = true,
    monoTitle = false,
    hasDotMatrix = false,
    cardRadius = 22.dp,
    bgTop = Color(0xFF090C12),
    bgMid = Color(0xFF121722),
    bgBottom = Color(0xFF0B0E15),
    glowPrimary = Color(0x30D7E4FA),
    glowSecondary = Color(0x22A5B9DC),
    dotColor = Color.Transparent,
    cardBgTop = Color(0x1DFFFFFF),
    cardBgBottom = Color(0x0BFFFFFF),
    cardBorderTop = Color(0x42FFFFFF),
    cardBorderBottom = Color(0x16FFFFFF),
    heroBgTop = Color(0x2BFFFFFF),
    heroBgBottom = Color(0x12FFFFFF),
    heroBorderTop = Color(0x56FFFFFF),
    heroBorderBottom = Color(0x20FFFFFF),
    slotBg = Color(0x10FFFFFF),
    slotBorder = Color(0x1CFFFFFF),
    badgeBg = Color(0x24EEF2F8),
    badgeInk = Color(0xFFE4EAF4),
    accentDot = Color(0xFFEEF2F8),
    primaryBtnTop = Color(0xFFF5F8FC),
    primaryBtnBottom = Color(0xFFDCE3EE),
    primaryBtnInk = Color(0xFF0B0E14),
    secondaryBtnBg = Color(0x18FFFFFF),
    secondaryBtnBorder = Color(0x2EFFFFFF),
    secondaryBtnInk = Color(0xFFEDF2F9),
    success = GlassSuccess,
    textPrimary = Color(0xFFF4F7FC),
    textSecondary = Color(0xFFA8B3C5),
    textMuted = Color(0xFF6F7B90),
)

private val NothingLightStyle = Wp2VisualStyle(
    theme = AppTheme.NOTHING_LIGHT,
    isLight = true,
    isGlass = false,
    monoTitle = true,
    hasDotMatrix = true,
    cardRadius = 18.dp,
    bgTop = Color(0xFFF3F3F6),
    bgMid = Color(0xFFF3F3F6),
    bgBottom = Color(0xFFF3F3F6),
    glowPrimary = Color(0x18FB471F),
    glowSecondary = Color.Transparent,
    dotColor = Color(0x19111115),
    cardBgTop = Color(0xFFFFFFFF),
    cardBgBottom = Color(0xFFFFFFFF),
    cardBorderTop = Color(0xFFE4E4EA),
    cardBorderBottom = Color(0xFFE4E4EA),
    heroBgTop = Color(0xFFFFFFFF),
    heroBgBottom = Color(0xFFFFFFFF),
    heroBorderTop = Color(0x59FB471F),
    heroBorderBottom = Color(0x38FB471F),
    slotBg = Color(0xFFF7F7FA),
    slotBorder = Color(0xFFE7E7EE),
    badgeBg = Color(0x1CFB471F),
    badgeInk = Color(0xFFE03A14),
    accentDot = Color(0xFFFB471F),
    primaryBtnTop = Color(0xFF141418),
    primaryBtnBottom = Color(0xFF141418),
    primaryBtnInk = Color.White,
    secondaryBtnBg = Color(0xFFECECF1),
    secondaryBtnBorder = Color(0xFFDCDCE4),
    secondaryBtnInk = Color(0xFF18181D),
    success = Color(0xFF16A34A),
    textPrimary = Color(0xFF111115),
    textSecondary = Color(0xFF5A5A66),
    textMuted = Color(0xFF8B8B98),
)

private val AmoledStyle = Wp2VisualStyle(
    theme = AppTheme.AMOLED_STEALTH,
    isLight = false,
    isGlass = false,
    monoTitle = false,
    hasDotMatrix = false,
    cardRadius = 16.dp,
    bgTop = Color(0xFF000000),
    bgMid = Color(0xFF000000),
    bgBottom = Color(0xFF000000),
    glowPrimary = Color.Transparent,
    glowSecondary = Color.Transparent,
    dotColor = Color.Transparent,
    cardBgTop = Color(0xFF0B0B0D),
    cardBgBottom = Color(0xFF0B0B0D),
    cardBorderTop = Color(0xFF1E1E24),
    cardBorderBottom = Color(0xFF1E1E24),
    heroBgTop = Color(0xFF0F0F13),
    heroBgBottom = Color(0xFF0F0F13),
    heroBorderTop = Color(0xFF32323C),
    heroBorderBottom = Color(0xFF282832),
    slotBg = Color(0xFF070709),
    slotBorder = Color(0xFF1B1B20),
    badgeBg = Color(0x1AFFFFFF),
    badgeInk = Color(0xFFD8D8E0),
    accentDot = Color(0xFFFFFFFF),
    primaryBtnTop = Color(0xFFFFFFFF),
    primaryBtnBottom = Color(0xFFEAEAF0),
    primaryBtnInk = Color(0xFF000000),
    secondaryBtnBg = Color(0xFF141418),
    secondaryBtnBorder = Color(0xFF282830),
    secondaryBtnInk = Color(0xFFE6E6EC),
    success = SuccessGreenDark,
    textPrimary = Color(0xFFFFFFFF),
    textSecondary = Color(0xFF8E8E9A),
    textMuted = Color(0xFF585864),
)

fun styleFor(theme: AppTheme): Wp2VisualStyle = when (theme) {
    AppTheme.CMF_NOTHING -> CmfStyle
    AppTheme.LIQUID_GLASS -> LiquidGlassStyle
    AppTheme.NOTHING_LIGHT -> NothingLightStyle
    AppTheme.AMOLED_STEALTH -> AmoledStyle
}

val LocalWp2Style = staticCompositionLocalOf { CmfStyle }

private fun colorSchemeFor(style: Wp2VisualStyle) = if (style.isLight) {
    lightColorScheme(
        primary = CmfOrange,
        onPrimary = Color.White,
        primaryContainer = Color(0xFFFFE4DD),
        onPrimaryContainer = Color(0xFF471104),
        secondary = Color(0xFF52525B),
        onSecondary = Color.White,
        secondaryContainer = style.secondaryBtnBg,
        onSecondaryContainer = style.secondaryBtnInk,
        background = style.bgTop,
        onBackground = style.textPrimary,
        surface = style.cardBgTop,
        onSurface = style.textPrimary,
        surfaceVariant = style.slotBg,
        onSurfaceVariant = style.textSecondary,
        outline = style.secondaryBtnBorder,
        outlineVariant = style.cardBorderTop,
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = Color(0xFF7F1D1D),
    )
} else {
    val primaryCol = when (style.theme) {
        AppTheme.CMF_NOTHING -> CmfOrangeDark
        AppTheme.LIQUID_GLASS -> Color(0xFFEEF2F8)
        AppTheme.AMOLED_STEALTH -> Color.White
        AppTheme.NOTHING_LIGHT -> CmfOrange
    }
    val onPrimaryCol = if (style.theme == AppTheme.CMF_NOTHING) Color.White else Color(0xFF0A0D12)
    val surfaceSolid = when (style.theme) {
        AppTheme.LIQUID_GLASS -> Color(0xFF171C28)
        AppTheme.AMOLED_STEALTH -> Color(0xFF0E0E12)
        else -> Color(0xFF16161C)
    }
    darkColorScheme(
        primary = primaryCol,
        onPrimary = onPrimaryCol,
        primaryContainer = style.badgeBg.copy(alpha = 0.28f),
        onPrimaryContainer = style.textPrimary,
        secondary = style.textSecondary,
        onSecondary = Color(0xFF12141A),
        secondaryContainer = style.secondaryBtnBg,
        onSecondaryContainer = style.secondaryBtnInk,
        background = style.bgTop,
        onBackground = style.textPrimary,
        surface = surfaceSolid,
        onSurface = style.textPrimary,
        surfaceVariant = Color(0xFF1D202A),
        onSurfaceVariant = style.textSecondary,
        outline = style.secondaryBtnBorder,
        outlineVariant = style.cardBorderTop,
        error = Color(0xFFEF4444),
        onError = Color.White,
        errorContainer = Color(0xFF3B1215),
        onErrorContainer = Color(0xFFFECACA),
    )
}

@Composable
fun WP2Theme(
    appTheme: AppTheme = AppTheme.CMF_NOTHING,
    content: @Composable () -> Unit,
) {
    val style = styleFor(appTheme)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val ctrl = WindowCompat.getInsetsController(window, view)
            ctrl.isAppearanceLightStatusBars = style.isLight
            ctrl.isAppearanceLightNavigationBars = style.isLight
        }
    }
    CompositionLocalProvider(LocalWp2Style provides style) {
        MaterialTheme(
            colorScheme = colorSchemeFor(style),
            content = content,
        )
    }
}

@Composable
fun Wp2Backdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = LocalWp2Style.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(style.bgTop, style.bgMid, style.bgBottom),
                ),
            ),
    ) {
        if (style.glowPrimary != Color.Transparent || style.glowSecondary != Color.Transparent) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                if (style.glowPrimary != Color.Transparent) {
                    val center = if (style.isGlass) {
                        Offset(size.width * 0.18f, size.height * 0.10f)
                    } else {
                        Offset(size.width * 0.88f, size.height * 0.05f)
                    }
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(style.glowPrimary, Color.Transparent),
                            center = center,
                            radius = size.width * 0.78f,
                        ),
                        center = center,
                        radius = size.width * 0.78f,
                    )
                }
                if (style.glowSecondary != Color.Transparent) {
                    val center = Offset(size.width * 0.88f, size.height * 0.72f)
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(style.glowSecondary, Color.Transparent),
                            center = center,
                            radius = size.width * 0.82f,
                        ),
                        center = center,
                        radius = size.width * 0.82f,
                    )
                }
            }
        }
        content()
    }
}

@Composable
fun DotMatrixOverlay(modifier: Modifier = Modifier) {
    val style = LocalWp2Style.current
    if (!style.hasDotMatrix) return
    Canvas(modifier = modifier) {
        val step = 10.dp.toPx()
        val r = 1.dp.toPx()
        var y = step * 0.5f
        while (y < size.height) {
            var x = step * 0.5f
            while (x < size.width) {
                drawCircle(color = style.dotColor, radius = r, center = Offset(x, y))
                x += step
            }
            y += step
        }
    }
}

@Composable
fun Wp2ThemedCard(
    modifier: Modifier = Modifier,
    elevated: Boolean = false,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val style = LocalWp2Style.current
    val shape = RoundedCornerShape(style.cardRadius)
    val bgBrush = Brush.verticalGradient(
        if (elevated) listOf(style.heroBgTop, style.heroBgBottom)
        else listOf(style.cardBgTop, style.cardBgBottom),
    )
    val borderBrush = Brush.verticalGradient(
        if (elevated) listOf(style.heroBorderTop, style.heroBorderBottom)
        else listOf(style.cardBorderTop, style.cardBorderBottom),
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgBrush)
            .border(1.dp, borderBrush, shape)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun Wp2SlotSurface(
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val style = LocalWp2Style.current
    val shape = RoundedCornerShape(14.dp)
    val base = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(style.slotBg)
        .border(1.dp, borderColor ?: style.slotBorder, shape)
    Box(
        modifier = if (onClick != null) base.clickable(enabled = enabled, onClick = onClick) else base,
        content = content,
    )
}
