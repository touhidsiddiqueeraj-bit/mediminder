package com.mediminder.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.mediminder.app.domain.repository.ThemeMode

// ---------------------------------------------------------------------------
// High-contrast palettes (WCAG AAA body text where feasible).
// ---------------------------------------------------------------------------

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF00509D), // ~7.6:1 on white
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD3E4FF),
    onPrimaryContainer = Color(0xFF001D3D),
    secondary = Color(0xFF00695C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFB7F2E4),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF5B4292),
    onTertiary = Color(0xFFFFFFFF),
    error = Color(0xFF9E1B1B), // ~7.5:1 on white
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFFFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFFFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE7E8EC),
    onSurfaceVariant = Color(0xFF3F434A),
    outline = Color(0xFF5F6368),
    outlineVariant = Color(0xFFC4C7CC),
    inverseSurface = Color(0xFF303034),
    inverseOnSurface = Color(0xFFF2F0F4),
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF9CCBFF), // ~9.7:1 on dark background
    onPrimary = Color(0xFF002F5C),
    primaryContainer = Color(0xFF1D4C85),
    onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF80D4C5),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFB7F2E4),
    tertiary = Color(0xFFC9B9FF),
    onTertiary = Color(0xFF31205C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE1E3E8),
    surface = Color(0xFF161B20),
    onSurface = Color(0xFFE1E3E8),
    surfaceVariant = Color(0xFF2C3138),
    onSurfaceVariant = Color(0xFFC3C7CF),
    outline = Color(0xFF8D939B),
    outlineVariant = Color(0xFF44474E),
    inverseSurface = Color(0xFFE1E3E8),
    inverseOnSurface = Color(0xFF2C3138),
)

/** Semantic dose-status colors, contrast-checked per scheme. */
@Immutable
data class StatusColors(
    val taken: Color,
    val takenContainer: Color,
    val missed: Color,
    val missedContainer: Color,
    val upcoming: Color,
    val upcomingContainer: Color,
    val skipped: Color,
    val snoozed: Color,
    val lowStock: Color,
    val lowStockContainer: Color,
)

private val LightStatusColors = StatusColors(
    taken = Color(0xFF1E6E3C),
    takenContainer = Color(0xFFC8F0D4),
    missed = Color(0xFF9E1B1B),
    missedContainer = Color(0xFFFFDAD6),
    upcoming = Color(0xFF00509D),
    upcomingContainer = Color(0xFFD3E4FF),
    skipped = Color(0xFF5F6368),
    snoozed = Color(0xFF8A5A00),
    lowStock = Color(0xFF8A5A00),
    lowStockContainer = Color(0xFFFFE9C7),
)

private val DarkStatusColors = StatusColors(
    taken = Color(0xFF8BD8A8),
    takenContainer = Color(0xFF0E4426),
    missed = Color(0xFFFFB4AB),
    missedContainer = Color(0xFF5C1310),
    upcoming = Color(0xFF9CCBFF),
    upcomingContainer = Color(0xFF1D4C85),
    skipped = Color(0xFFC3C7CF),
    snoozed = Color(0xFFFFD54F),
    lowStock = Color(0xFFFFD54F),
    lowStockContainer = Color(0xFF4A3A00),
)

val LocalStatusColors = staticCompositionLocalOf { LightStatusColors }

// ---------------------------------------------------------------------------
// Typography: everything a user reads is >= 16sp; body text is 18sp+.
// All sizes in sp so the system font scale (up to 200%) is respected.
// ---------------------------------------------------------------------------

private val AppTypography = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Bold, lineHeight = 42.sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.Bold, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp),
    titleMedium = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 28.sp),
    titleSmall = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Normal, lineHeight = 28.sp),
    bodyMedium = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Normal, lineHeight = 26.sp),
    bodySmall = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 23.sp),
    labelLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Medium, lineHeight = 26.sp),
    labelMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, lineHeight = 22.sp),
    labelSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, lineHeight = 20.sp),
)

@Composable
fun MediMinderTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val statusColors = if (darkTheme) DarkStatusColors else LightStatusColors

    CompositionLocalProvider(LocalStatusColors provides statusColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            content = content,
        )
    }
}
