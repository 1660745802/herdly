package dev.herdwatch.ui.theme

import android.animation.ValueAnimator
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.ui.design.DsLight
import dev.herdwatch.ui.design.DsColors
import dev.herdwatch.ui.design.DsDark
import dev.herdwatch.ui.design.DsSpacing
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.LocalDs
import dev.herdwatch.ui.design.LocalSpacing

data class StatusPalette(
    val waiting: Color,
    val working: Color,
    val review: Color,
    val idle: Color,
    val unknown: Color,
    val waitingWash: Color,
    val danger: Color,
)

/** Status colours of the dark chassis. Public so previews can show the real thing. */
val DarkStatusPalette = StatusPalette(
    waiting = Color(0xFFFB923C),
    working = Color(0xFFFACC15),
    review = Color(0xFF4ADE80),
    idle = Color(0xFFACB2C0),
    unknown = Color(0xFFACB2C0),
    waitingWash = Color(0xFF2B1D15),
    danger = Color(0xFFF87171),
)

data class TerminalTokens(val background: Color, val text: Color, val muted: Color, val accent: Color, val risk: Color)

/**
 * The one mapping from agent state to color.
 *
 * Every lamp, dot and row tint in the app reads this, so "waiting" is the same amber on the
 * queue row, on the machine tree and inside the agent header. Danger red stays reserved for
 * failures and destructive actions.
 */
fun statusColor(status: AgentStatus, p: StatusPalette): Color = when (status) {
    AgentStatus.WAITING -> p.waiting
    AgentStatus.WORKING -> p.working
    AgentStatus.REVIEW -> p.review
    AgentStatus.IDLE -> p.idle
    AgentStatus.UNKNOWN -> p.unknown
}

/**
 * Motion durations in milliseconds. Kept restrained on purpose: nothing exceeds [slow],
 * and there are no bouncing specs, so state changes read as "settled" rather than playful.
 */
data class MotionTokens(val quick: Int = 120, val medium: Int = 200, val slow: Int = 280)

val LocalMotion = staticCompositionLocalOf { MotionTokens() }
/**
 * True when the platform reports animations as disabled ("Remove animations" accessibility
 * setting, developer option, or an animator duration scale of 0). Components read this to
 * snap instead of animate.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
private fun rememberReducedMotion(): Boolean = remember {
    runCatching { !ValueAnimator.areAnimatorsEnabled() }.getOrDefault(false)
}

val LocalTerminalTokens = staticCompositionLocalOf {
    TerminalTokens(Color(0xFF13151A), Color(0xFFE4E7EE), Color(0xFFA8B0C0), Color(0xFFB6C3FF), Color(0xFFFF9A8B))
}

data class GlassTokens(
    val canvas: Color,
    val panel: Color,
    val panelStrong: Color,
    val border: Color,
    val highlight: Color,
    val accentWash: Color,
)

val LocalGlassTokens = staticCompositionLocalOf {
    GlassTokens(
        canvas = Color(0xFFF4F6FB),
        panel = Color.White.copy(alpha = 0.68f),
        panelStrong = Color.White.copy(alpha = 0.84f),
        border = Color.White.copy(alpha = 0.82f),
        highlight = Color.White.copy(alpha = 0.92f),
        accentWash = Color(0xFFE9EDFF).copy(alpha = 0.72f),
    )
}

/** Status colours of the light chassis. */
val LightStatusPalette = StatusPalette(
    waiting = Color(0xFFC2410C),
    working = Color(0xFFA16207),
    review = Color(0xFF15803D),
    idle = Color(0xFF626773),
    unknown = Color(0xFF626773),
    waitingWash = Color(0xFFFBEFE7),
    danger = Color(0xFFA9322F),
)

val LocalStatusPalette = staticCompositionLocalOf { LightStatusPalette }

private val LightGlass = GlassTokens(
    canvas = Color(0xFFF3F5FA),
    panel = Color(0xFFE7EDF9).copy(alpha = 0.72f),
    panelStrong = Color(0xFFF7F9FD).copy(alpha = 0.88f),
    border = Color(0xFFB9C7E2).copy(alpha = 0.56f),
    highlight = Color.White.copy(alpha = 0.94f),
    accentWash = Color(0xFFDEE6F7).copy(alpha = 0.74f),
)

private val DarkGlass = GlassTokens(
    canvas = Color(0xFF0D111A),
    panel = Color(0xFF202734).copy(alpha = 0.78f),
    panelStrong = Color(0xFF252D3C).copy(alpha = 0.92f),
    border = Color.White.copy(alpha = 0.14f),
    highlight = Color.White.copy(alpha = 0.18f),
    accentWash = Color(0xFF243A63).copy(alpha = 0.72f),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2E5AAC),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE6F7),
    onPrimaryContainer = Color(0xFF17315F),
    secondary = Color(0xFF2E5AAC),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDEE6F7),
    onSecondaryContainer = Color(0xFF17315F),
    tertiary = Color(0xFF2E5AAC),
    onTertiary = Color.White,
    background = Color(0xFFF3F5FA),
    onBackground = Color(0xFF16181D),
    surface = Color(0xFFFDFEFF),
    onSurface = Color(0xFF16181D),
    surfaceVariant = Color(0xFFE1E5EC),
    onSurfaceVariant = Color(0xFF565E6A),
    outline = Color(0xFFB9C0CC),
    outlineVariant = Color(0xFFC9D0DA),
    error = Color(0xFFA9322F),
    onError = Color.White,
    errorContainer = Color(0xFFFBE7E6),
    onErrorContainer = Color(0xFF6E1F1D),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7B9BEA),
    onPrimary = Color(0xFF0F1116),
    primaryContainer = Color(0xFF1F2C48),
    onPrimaryContainer = Color(0xFFDCE5F8),
    secondary = Color(0xFF7B9BEA),
    onSecondary = Color(0xFF0F1116),
    secondaryContainer = Color(0xFF1F2C48),
    onSecondaryContainer = Color(0xFFDCE5F8),
    tertiary = Color(0xFF7B9BEA),
    onTertiary = Color(0xFF0F1116),
    background = Color(0xFF0D111A),
    onBackground = Color(0xFFE9EAEE),
    surface = Color(0xFF161C28),
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

/**
 * Material color scheme is derived from the app palette instead of a second set of literals.
 *
 * Dropdowns, sheet internals and Material text fields read `MaterialTheme.colorScheme`. With the
 * stock Material defaults a menu appeared as a pure white slab (light) or a blue-grey panel (dark)
 * on top of the matte chassis - the same overlay looked like a different product. Mapping the app
 * inks onto the scheme keeps every Material surface on the same material.
 */
private fun appColorScheme(colors: DsColors, darkTheme: Boolean): androidx.compose.material3.ColorScheme {
    val base = if (darkTheme) Dark else Light
    return base.copy(
        primary = colors.accent,
        onPrimary = colors.onAccent,
        primaryContainer = colors.accentWash,
        onPrimaryContainer = colors.text,
        secondary = colors.accent,
        onSecondary = colors.onAccent,
        secondaryContainer = colors.accentWash,
        onSecondaryContainer = colors.text,
        tertiary = colors.accent,
        onTertiary = colors.onAccent,
        background = colors.base,
        onBackground = colors.text,
        surface = colors.baseRaised,
        onSurface = colors.text,
        surfaceVariant = colors.well,
        onSurfaceVariant = colors.textSoft,
        outline = colors.shadowDark,
        outlineVariant = colors.wellDeep,
        error = colors.danger,
        onError = colors.onDanger,
        errorContainer = colors.danger.copy(alpha = 0.12f),
        onErrorContainer = colors.danger,
    )
}

/**
 * Material typography is derived from [DsTypography] instead of being written twice.
 *
 * Every control the app draws itself uses the `Ds*` scale, but Material components (text fields
 * inside sheets, menu items, sheet drag handles) read `MaterialTheme.typography`. Two hand-written
 * scales drifted apart: a Material label ended up a hair smaller than the app label next to it.
 * Deriving one from the other makes the material set follow the app scale automatically.
 */
internal val AppTypography = androidx.compose.material3.Typography(
    displayLarge = DsTypography.display,
    displayMedium = DsTypography.display,
    displaySmall = DsTypography.title,
    headlineLarge = DsTypography.display,
    headlineMedium = DsTypography.title,
    headlineSmall = DsTypography.headline,
    titleLarge = DsTypography.title,
    titleMedium = DsTypography.headline,
    titleSmall = DsTypography.bodyStrong,
    bodyLarge = DsTypography.body,
    bodyMedium = DsTypography.body,
    bodySmall = DsTypography.caption,
    labelLarge = DsTypography.bodyStrong,
    labelMedium = DsTypography.caption,
    labelSmall = DsTypography.micro,
)

/** Mono ink for command blocks and read-only code. Same scale as [DsTypography.mono]. */
val MonoStyle: TextStyle = DsTypography.mono

@Composable
fun HerdwatchTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val status = if (darkTheme) DarkStatusPalette else LocalStatusPalette.current
    // Motion always follows the system accessibility switch. There is no in-app full/reduced
    // toggle: a user who asked the OS for less motion has already answered that question.
    val reducedMotion = rememberReducedMotion()
    androidx.compose.runtime.CompositionLocalProvider(
        LocalStatusPalette provides status,
        LocalMotion provides MotionTokens(),
        LocalReducedMotion provides reducedMotion,
        LocalDs provides if (darkTheme) DsDark else DsLight,
        LocalSpacing provides DsSpacing.base,
        LocalGlassTokens provides if (darkTheme) DarkGlass else LightGlass,
        LocalTerminalTokens provides if (darkTheme) {
            TerminalTokens(Color(0xFF090B0F), Color(0xFFE8EBF2), Color(0xFFA8B0C0), Color(0xFFB6C3FF), Color(0xFFFF9A8B))
        } else {
            TerminalTokens(Color(0xFF13151A), Color(0xFFE4E7EE), Color(0xFFA8B0C0), Color(0xFFB6C3FF), Color(0xFFFF9A8B))
        },
    ) {
        MaterialTheme(
            colorScheme = appColorScheme(if (darkTheme) DsDark else DsLight, darkTheme),
            typography = AppTypography,
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
fun statusPalette(): StatusPalette = LocalStatusPalette.current

/** Status color for the current theme; use this instead of mapping states by hand. */
@Composable
@ReadOnlyComposable
fun agentStatusColor(status: AgentStatus): Color = statusColor(status, LocalStatusPalette.current)

/** Connection state color: green when live, amber while dialing, red on failure. */
@Composable
@ReadOnlyComposable
fun connStatusColor(state: ConnState): Color {
    val p = LocalStatusPalette.current
    val d = LocalDs.current
    return when (state) {
        is ConnState.Connected -> p.review
        ConnState.Connecting -> p.working
        is ConnState.Failed -> d.danger
        ConnState.Idle -> d.textFaint
    }
}
