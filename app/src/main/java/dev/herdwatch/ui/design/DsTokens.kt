package dev.herdwatch.ui.design

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
    textSoft = Color(0xFF565E6A),
    textFaint = Color(0xFF575F6D),
    // Sapphire rather than blurple: the accent has to survive next to a dark terminal and read
    // as instrumentation, not as a consumer-AI gradient.
    accent = Color(0xFF2E5AAC),
    accentPressed = Color(0xFF26497F),
    onAccent = Color(0xFFFFFFFF),
    accentWash = Color(0x292E5AAC),
    danger = Color(0xFFA9322F),
    onDanger = Color(0xFFFFF6F5),
    ok = Color(0xFF1F6650),
    warn = Color(0xFF7C5210),
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

/**
 * Page rhythm.
 *
 * Only the space *between* blocks follows this scale. Spacing inside a control (button padding,
 * chip gaps, key bar padding) stays on [DsSpace] so that touch targets and text legibility never
 * change with the density preference.
 */
@Immutable
data class Spacing(
    /** Side margin of a page. */
    val gutter: Dp,
    /** Gap between two blocks of a page. */
    val block: Dp,
    /** Vertical padding of one list row. */
    val row: Dp,
)

/**
 * The bar at the top of every pushed page: back, identity, what it is, and the actions.
 *
 * Agent detail and the SSH terminal used to compute their own paddings and title styles, which is
 * how one page ended up with a 20sp title over a 12sp breadcrumb and the other with 16sp next to a
 * tag. Two screens, one set of numbers.
 */
object PageBarSpec {
    val start = DsSpace.xxs
    val end = DsSpace.xs
    val top = DsSpace.xs
    val gap = DsSpace.s
}

object DsSpacing {
    /**
     * One rhythm for the whole app. There is deliberately no compact variant: rhythm is a design
     * decision, and handing it to a switch means shipping two half-tuned layouts instead of one
     * that reads well. 16dp gutter / 12dp between blocks / 12dp inside a row fits a phone at 1.0x
     * and still holds at 1.3x font scale, which is the stress case that matters.
     */
    val base = Spacing(gutter = 16.dp, block = 12.dp, row = 12.dp)
}

/** Rhythm in effect. A composition local so pages read `spacing().gutter` and never hardcode. */
val LocalSpacing = staticCompositionLocalOf { DsSpacing.base }

@Composable
@androidx.compose.runtime.ReadOnlyComposable
fun spacing(): Spacing = LocalSpacing.current

private val Sans = FontFamily.SansSerif

/**
 * The terminal face, bundled rather than borrowed from the system.
 *
 * `FontFamily.Monospace` has no CJK coverage, so the platform silently substituted system fonts
 * whose ideographs advance about 1em against our 0.5em ASCII cells: every column after the first
 * Chinese character drifted, and box-drawing frames broke. This face is exactly 0.5em / 1.0em
 * (LXGW WenKai Mono, subset to GB2312 + symbols, 3.4 MB), so two cells are always one ideograph.
 * Glyphs it does not carry (rare characters, dingbats) still fall back to the system font, which is
 * the right trade: a missed glyph is a cosmetic gap, a drifted column is a corrupted screen.
 */
val DsMono: FontFamily = FontFamily(
    androidx.compose.ui.text.font.Font(dev.herdwatch.R.font.terminal_mono, weight = FontWeight.Normal),
)
private val Mono = DsMono

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
    // Reading text gets 1.55x line height: agent prose arrives in long paragraphs of mixed
    // Chinese and Latin, and 14/20 was tight enough that three lines read as one block.
    val body: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.Normal,
        lineHeight = 23.sp, letterSpacing = 0.1.sp,
    ),
    val bodyStrong: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
        lineHeight = 23.sp, letterSpacing = 0.1.sp,
    ),
    val caption: TextStyle = TextStyle(
        fontFamily = Sans, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        lineHeight = 17.sp, letterSpacing = 0.2.sp,
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
