package dev.herdwatch.ui

import androidx.compose.ui.graphics.Color

/**
 * Keeps terminal ink visible, whatever colour the remote program asked for.
 *
 * A captured screen arrives with the colours the program chose, and terminal programs are written
 * against whatever background their author had in mind. A tool that paints `ESC[30m` (black) for a
 * separator assumes a light terminal; Claude Code's dim greys assume a dark one. Herdly draws every
 * terminal surface dark, so the light-terminal assumption turns into ink that is technically
 * present and practically invisible — the reader sees a blank row where a rule should be.
 *
 * The fix is the one mature terminals (iTerm's minimum contrast, VS Code's `minimumContrastRatio`)
 * already settled on: keep the hue, move the lightness until the text is legible against what it is
 * actually drawn on. It is applied per run, so a dim sentence stays dimmer than a bright one.
 */

private const val DEFAULT_MIN_RATIO = 3.0f

internal fun relativeLuminance(color: Color): Float {
    fun channel(v: Float): Float = if (v <= 0.03928f) v / 12.92f else Math.pow(((v + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    return 0.2126f * channel(color.red) + 0.7152f * channel(color.green) + 0.0722f * channel(color.blue)
}

internal fun contrastRatio(a: Color, b: Color): Float {
    val la = relativeLuminance(a)
    val lb = relativeLuminance(b)
    val lighter = maxOf(la, lb)
    val darker = minOf(la, lb)
    return (lighter + 0.05f) / (darker + 0.05f)
}

/**
 * Returns [foreground] unchanged when it already reads well on [background], otherwise lifts it
 * toward white (dark background) or sinks it toward black (light background) just far enough.
 *
 * Alpha is preserved for the caller; a fully transparent colour is returned untouched because
 * "no colour" means "inherit", not "black".
 */
internal fun ensureContrast(
    foreground: Color,
    background: Color,
    minimum: Float = DEFAULT_MIN_RATIO,
): Color {
    if (foreground.alpha == 0f) return foreground
    if (background.alpha == 0f) return foreground
    if (contrastRatio(foreground, background) >= minimum) return foreground
    val towardWhite = relativeLuminance(background) < 0.18f
    val target = if (towardWhite) Color.White else Color.Black
    // Sixteen steps of 1/16 is finer than any palette needs and still cheap per run.
    for (step in 1..16) {
        val t = step / 16f
        val blended = lerp(foreground, target, t)
        if (contrastRatio(blended, background) >= minimum) return blended
    }
    return target
}

private fun lerp(from: Color, to: Color, t: Float): Color = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
    alpha = from.alpha,
)
