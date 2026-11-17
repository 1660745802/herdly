package dev.herdwatch.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

data class StatusPalette(
    val waiting: Color,
    val working: Color,
    val review: Color,
    val idle: Color,
    val unknown: Color,
    val waitingWash: Color,
    val danger: Color,
)

val LocalStatusPalette = staticCompositionLocalOf {
    StatusPalette(
        waiting = Color(0xFFC2410C),
        working = Color(0xFFA16207),
        review = Color(0xFF15803D),
        idle = Color(0xFF626773),
        unknown = Color(0xFF626773),
        waitingWash = Color(0xFFFBEFE7),
        danger = Color(0xFFB42318),
    )
}

private val Light = lightColorScheme(
    primary = Color(0xFF2F4BE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE7EAFF),
    onPrimaryContainer = Color(0xFF1B2A8F),
    secondary = Color(0xFF2F4BE0),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE7EAFF),
    onSecondaryContainer = Color(0xFF1B2A8F),
    tertiary = Color(0xFF2F4BE0),
    onTertiary = Color.White,
    background = Color(0xFFF7F7F4),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFEFEFEA),
    onSurfaceVariant = Color(0xFF5B5F68),
    outline = Color(0xFFCFCFC7),
    outlineVariant = Color(0xFFE3E3DC),
    error = Color(0xFFB42318),
    onError = Color.White,
    errorContainer = Color(0xFFFCEAE8),
    onErrorContainer = Color(0xFF781B14),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF93A4FF),
    onPrimary = Color(0xFF0B1230),
    primaryContainer = Color(0xFF26315C),
    onPrimaryContainer = Color(0xFFDDE2FF),
    secondary = Color(0xFF93A4FF),
    onSecondary = Color(0xFF0B1230),
    secondaryContainer = Color(0xFF26315C),
    onSecondaryContainer = Color(0xFFDDE2FF),
    tertiary = Color(0xFF93A4FF),
    onTertiary = Color(0xFF0B1230),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE9EAEE),
    surface = Color(0xFF161920),
    onSurface = Color(0xFFE9EAEE),
    surfaceVariant = Color(0xFF1D2129),
    onSurfaceVariant = Color(0xFFA5AAB6),
    outline = Color(0xFF3A404C),
    outlineVariant = Color(0xFF252A33),
    error = Color(0xFFF87171),
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF3B2024),
    onErrorContainer = Color(0xFFFFC5BF),
)

private val AppTypography = androidx.compose.material3.Typography(
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.5.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 17.sp),
)

val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 18.sp)

@Composable
fun HerdwatchTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val status = if (darkTheme) {
        StatusPalette(
            waiting = Color(0xFFFB923C),
            working = Color(0xFFFACC15),
            review = Color(0xFF4ADE80),
            idle = Color(0xFFACB2C0),
            unknown = Color(0xFFACB2C0),
            waitingWash = Color(0xFF2B1D15),
            danger = Color(0xFFF87171),
        )
    } else {
        LocalStatusPalette.current
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusPalette provides status) {
        MaterialTheme(
            colorScheme = if (darkTheme) Dark else Light,
            typography = AppTypography,
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
fun statusPalette(): StatusPalette = LocalStatusPalette.current
