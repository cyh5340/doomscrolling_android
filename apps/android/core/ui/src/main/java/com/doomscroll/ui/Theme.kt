package com.doomscroll.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Ember (limits, "doom") on ink, with mint for "under the limit". Brand colors on purpose, not dynamic color.
private val Dark = darkColorScheme(
    primary = Color(0xFFFF7A45), onPrimary = Color(0xFF2A0E00),
    primaryContainer = Color(0xFF4A1E0A), onPrimaryContainer = Color(0xFFFFDBCB),
    secondary = Color(0xFFB4A7FF), onSecondary = Color(0xFF1E1557),
    secondaryContainer = Color(0xFF2E2766), onSecondaryContainer = Color(0xFFE4DFFF),
    tertiary = Color(0xFF4ADE9B), onTertiary = Color(0xFF00391F),
    tertiaryContainer = Color(0xFF0F3D2A), onTertiaryContainer = Color(0xFFB6F5D3),
    error = Color(0xFFFF6B7A), onError = Color(0xFF3D0009),
    errorContainer = Color(0xFF4A1219), onErrorContainer = Color(0xFFFFDADC),
    background = Color(0xFF0D0E12), onBackground = Color(0xFFECEDF2),
    surface = Color(0xFF0D0E12), onSurface = Color(0xFFECEDF2),
    surfaceVariant = Color(0xFF22252E), onSurfaceVariant = Color(0xFF9BA1B0),
    surfaceContainerLowest = Color(0xFF08090C), surfaceContainerLow = Color(0xFF13151A),
    surfaceContainer = Color(0xFF181A20), surfaceContainerHigh = Color(0xFF1F2229),
    surfaceContainerHighest = Color(0xFF272A33),
    outline = Color(0xFF3A3E4A), outlineVariant = Color(0xFF2A2D36),
)

private val Light = lightColorScheme(
    primary = Color(0xFFD9480F), onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE1D3), onPrimaryContainer = Color(0xFF3A1100),
    secondary = Color(0xFF5B4DD6), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E1FF), onSecondaryContainer = Color(0xFF1A1060),
    tertiary = Color(0xFF0E8F5B), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFF5E1), onTertiaryContainer = Color(0xFF00301A),
    error = Color(0xFFC8243A), onError = Color.White,
    errorContainer = Color(0xFFFFDADC), onErrorContainer = Color(0xFF410009),
    background = Color(0xFFFAF8F6), onBackground = Color(0xFF16171B),
    surface = Color(0xFFFAF8F6), onSurface = Color(0xFF16171B),
    surfaceVariant = Color(0xFFEDE9E5), onSurfaceVariant = Color(0xFF5E616B),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF5F2EF),
    surfaceContainer = Color(0xFFF0ECE8), surfaceContainerHigh = Color(0xFFEAE6E2),
    surfaceContainerHighest = Color(0xFFE4DFDA),
    outline = Color(0xFFBDB8B2), outlineVariant = Color(0xFFDEDAD5),
)

private val base = Typography()
private val AppTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1.5).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun DoomscrollTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = AppTypography, shapes = AppShapes, content = content)
}
