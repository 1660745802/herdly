package dev.herdwatch.ui.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Colour tokens for the "matte instrument" direction.
 *
 * Everything is expressed as a *material*, not as a hue: [base] is the chassis, [well] is a
 * milled recess, [shadowDark]/[shadowLight] are the two halves of one top-left light source.
 * Hierarchy is carried by depth and weight — never by borders or by filling a surface with a
 * different colour. One accent, one danger colour, everything else desaturated.
 */
@Immutable
data class DsColors(
    val base: Color,
    val baseRaised: Color,
    val well: Color,
    val wellDeep: Color,
    val shadowDark: Color,
    val shadowLight: Color,
    val text: Color,
    val textSoft: Color,
    val textFaint: Color,
    val accent: Color,
    val accentPressed: Color,
    val onAccent: Color,
    val accentWash: Color,
    val danger: Color,
    val onDanger: Color,
    val ok: Color,
    val warn: Color,
    val rim: Color,
    val isDark: Boolean,
)

val DsLight = DsColors(
    base = Color(0xFFE6EAF0),
    baseRaised = Color(0xFFEDF1F6),
    well = Color(0xFFDBE0E8),
    wellDeep = Color(0xFFD5DAE3),
    shadowDark = Color(0xFFAAB3C0),
    shadowLight = Color(0xFFFFFFFF),
    text = Color(0xFF333A46),
    textSoft = Color(0xFF5C6572),
    textFaint = Color(0xFF616A78),
    // Sapphire rather than blurple: the accent has to survive next to a dark terminal and read
    // as instrumentation, not as a consumer-AI gradient.
    accent = Color(0xFF2E5AAC),
    accentPressed = Color(0xFF26497F),
    onAccent = Color(0xFFFFFFFF),
    accentWash = Color(0x292E5AAC),
    danger = Color(0xFFB23A37),
    onDanger = Color(0xFFFFF6F5),
    ok = Color(0xFF24705A),
    warn = Color(0xFF8E5F14),
    rim = Color(0xB3FFFFFF),
    isDark = false,
)

/**
 * Dark is not literal neumorphism (a dark chassis has no usable light). Instead the panel is
 * *backlit*: raised parts get a faint top rim highlight, recesses go darker than the chassis,
 * and shadows collapse to a low-alpha black. Same geometry, same physics direction.
 */
val DsDark = DsColors(
    base = Color(0xFF15171C),
    baseRaised = Color(0xFF1B1E24),
    well = Color(0xFF101215),
    wellDeep = Color(0xFF0B0D10),
    shadowDark = Color(0x99000000),
    shadowLight = Color(0x2EFFFFFF),
    text = Color(0xFFE8EBF2),
    textSoft = Color(0xFF9AA3B2),
    textFaint = Color(0xFF89929F),
    accent = Color(0xFF7B9BEA),
    accentPressed = Color(0xFF6C8BE0),
    onAccent = Color(0xFF0F1116),
    accentWash = Color(0x337B9BEA),
    danger = Color(0xFFFF8B86),
    onDanger = Color(0xFF1A0F0F),
    ok = Color(0xFF45C7B0),
    warn = Color(0xFFE3AE5E),
    rim = Color(0x14FFFFFF),
    isDark = true,
)

val LocalDs = staticCompositionLocalOf { DsLight }

/**
 * How strongly the material illusion is rendered.
 *
 * 1.0 was the first pass and read as "over-engineered": every surface announced its own extrusion,
 * so a list of five rows looked like five separate objects glued to the screen. Real instruments
 * have one chassis and a few controls on it. The scale multiplies shadow alpha (and slightly the
 * offset), so hierarchy is still carried by depth — just quietly.
 */
const val DsMaterialIntensity = 0.5f

/** Depth specs: how far a surface is lifted or sunk, in dp. */
@Immutable
data class NeoDepth(val offset: Dp, val blur: Dp)

@Immutable
data class DsElevation(
    val flat: NeoDepth = NeoDepth(0.dp, 0.dp),
    val soft: NeoDepth = NeoDepth(1.5.dp, 3.dp),
    val rest: NeoDepth = NeoDepth(2.5.dp, 6.dp),
    val deep: NeoDepth = NeoDepth(4.dp, 10.dp),
    val inset: NeoDepth = NeoDepth(2.dp, 4.dp),
    val insetDeep: NeoDepth = NeoDepth(3.dp, 6.dp),
)

val DsElev = DsElevation()

object DsRadius {
    val card: Dp = 24.dp
    val control: Dp = 16.dp
    val well: Dp = 14.dp
    val key: Dp = 12.dp
    val pill: Dp = 999.dp
}

object DsSpace {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val s: Dp = 8.dp
    val m: Dp = 12.dp
    val l: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

object DsMotion {
    /** Shadow physics: pressed/raised transitions. */
    const val press: Int = 120

    /** Small state changes: chevrons, chips, counters. */
    const val quick: Int = 160

    /** Content appearing or leaving in place. */
    const val base: Int = 200

    /** Page level transitions. */
    const val page: Int = 240
}

private val Sans = FontFamily.SansSerif
private val Mono = FontFamily.Monospace

/**
 * Type scale. Hierarchy comes from weight and colour; sizes stay tight so a phone screen holds
 * a full triage card without scrolling.
 */
@Immutable
data class DsType(
    val display: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 33.sp, letterSpacing = (-0.4).sp,
    ),
    val title: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp, letterSpacing = (-0.2).sp,
    ),
    val headline: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, lineHeight = 22.sp,
    ),
    val body: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.Normal,
        lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    val bodyStrong: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        lineHeight = 16.sp, letterSpacing = 0.2.sp,
    ),
    val micro: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 11.sp, fontWeight = FontWeight.Medium,
        lineHeight = 14.sp, letterSpacing = 0.4.sp,
    ),
    val mono: TextStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp),
    val monoStrong: TextStyle = TextStyle(
        fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 18.sp,
        textAlign = TextAlign.Start,
    ),
)

val DsTypography = DsType()

/** Read the current material set. Call from composables only. */
@Composable
fun ds(): DsColors = LocalDs.current

@Composable
fun dsDarkPreferred(): Boolean = LocalDs.current.isDark

@Composable
fun rememberDsColors(dark: Boolean = isSystemInDarkTheme()): DsColors = if (dark) DsDark else DsLight
