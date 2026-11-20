package com.marti.stc1000.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colores de estado que Material no trae: conectado / atención. */
@Immutable
data class StatusColors(
    val ok: Color,
    val onOk: Color,
    val okContainer: Color,
    val warn: Color,
    val warnContainer: Color,
)

private val LightStatus = StatusColors(
    ok = Color(0xFF1B8A5A), onOk = Color.White, okContainer = Color(0xFFD7F2E3),
    warn = Color(0xFFB26A00), warnContainer = Color(0xFFFFE9C7),
)
private val DarkStatus = StatusColors(
    ok = Color(0xFF5FD39B), onOk = Color(0xFF00391F), okContainer = Color(0xFF12382A),
    warn = Color(0xFFFFB74D), warnContainer = Color(0xFF3D2C0A),
)

val LocalStatusColors = compositionLocalOf { LightStatus }

val MaterialTheme.status: StatusColors
    @Composable @ReadOnlyComposable get() = LocalStatusColors.current

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5FA5),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E6FA),
    onPrimaryContainer = Color(0xFF00315A),
    secondary = Color(0xFF00796B),
    tertiary = Color(0xFFE0612B),
    background = Color(0xFFF4F7FB),
    onBackground = Color(0xFF111A24),
    surface = Color(0xFFF4F7FB),
    onSurface = Color(0xFF111A24),
    surfaceVariant = Color(0xFFE3EAF3),
    onSurfaceVariant = Color(0xFF47566A),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEAF0F7),
    outline = Color(0xFF8795A8),
    outlineVariant = Color(0xFFD5DEEA),
    error = Color(0xFFC62828),
    errorContainer = Color(0xFFFDE0DE),
    onErrorContainer = Color(0xFF5C0A0A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CC4FF),
    onPrimary = Color(0xFF00315A),
    primaryContainer = Color(0xFF14436F),
    onPrimaryContainer = Color(0xFFD3E6FA),
    secondary = Color(0xFF6FD3C4),
    tertiary = Color(0xFFFFA070),
    background = Color(0xFF0C1117),
    onBackground = Color(0xFFE6EDF5),
    surface = Color(0xFF0C1117),
    onSurface = Color(0xFFE6EDF5),
    surfaceVariant = Color(0xFF1E2833),
    onSurfaceVariant = Color(0xFF9FB0C4),
    surfaceContainerLow = Color(0xFF141C25),
    surfaceContainer = Color(0xFF141C25),
    surfaceContainerHigh = Color(0xFF1C2631),
    outline = Color(0xFF6B7C90),
    outlineVariant = Color(0xFF2A3644),
    error = Color(0xFFFF8A80),
    errorContainer = Color(0xFF4A1512),
    onErrorContainer = Color(0xFFFFDAD6),
)

private val AppTypography = Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, fontSize = 26.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun Stc1000Theme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalStatusColors provides if (dark) DarkStatus else LightStatus) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** Colores del degradé principal según la temperatura (frío -> azul, templado -> verde, caliente -> naranja/rojo). */
fun heroColors(temp: Double?): Pair<Color, Color> = when {
    temp == null -> Color(0xFF4A5B70) to Color(0xFF2E3B4B)
    temp < 5 -> Color(0xFF1E88E5) to Color(0xFF0D47A1)
    temp < 15 -> Color(0xFF00ACC1) to Color(0xFF00695C)
    temp < 25 -> Color(0xFF2E9E6B) to Color(0xFF14694A)
    temp < 32 -> Color(0xFFF08A24) to Color(0xFFC2511B)
    else -> Color(0xFFE53935) to Color(0xFF8E1B1B)
}
