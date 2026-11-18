package dev.herdwatch.ui.design

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shadow physics for the design system.
 *
 * Compose only offers a single drop shadow, which is why most neumorphic implementations look
 * muddy. Both modifiers below instead synthesise the two halves of a single top-left light:
 * a stack of concentric rounded rects for the outer case, and a clipped gradient stroke for the
 * recessed case. It is deterministic, cheap on API 26 hardware and gives us the same falloff in
 * light and dark.
 */

/** Lifted from the chassis: dark shade bottom-right, light highlight top-left, then the fill. */
fun Modifier.neoRaised(
    cornerRadius: Dp,
    depth: NeoDepth = DsElev.rest,
    fill: Color,
    colors: DsColors,
): Modifier = drawBehind {
    val corner = cornerRadius.toPx()
    val offset = depth.offset.toPx()
    val blur = depth.blur.toPx()
    if (offset > 0f || blur > 0f) {
        drawSoftShadow(corner, offset * 0.9f, blur * 1.05f, colors.shadowDark.copy(alpha = colors.shadowDark.alpha * 0.62f * DsMaterialIntensity), 1f, 1f)
        drawSoftShadow(corner, offset * 0.35f, blur * 1.25f, colors.shadowLight.copy(alpha = colors.shadowLight.alpha * 0.34f * DsMaterialIntensity), -1f, -1f)
    }
    drawRoundRect(
        color = fill,
        topLeft = Offset.Zero,
        size = Size(size.width, size.height),
        cornerRadius = CornerRadius(corner, corner),
    )
    if (colors.isDark) {
        // A backlit panel still catches the light on its top edge.
        drawRoundRect(
            brush = Brush.verticalGradient(
                listOf(colors.rim, Color.Transparent),
                startY = 0f,
                endY = size.height * 0.34f,
            ),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = 0.9.dp.toPx()),
        )
    }
}

/** Milled into the chassis: the light now has to fall *into* the well. */
fun Modifier.neoInset(
    cornerRadius: Dp,
    depth: NeoDepth = DsElev.inset,
    fill: Color,
    colors: DsColors,
): Modifier = drawBehind {
    val corner = cornerRadius.toPx()
    val offset = depth.offset.toPx()
    val blur = depth.blur.toPx()
    drawRoundRect(
        color = fill,
        topLeft = Offset.Zero,
        size = Size(size.width, size.height),
        cornerRadius = CornerRadius(corner, corner),
    )
    if (blur <= 0f) return@drawBehind
    val path = Path().apply {
        addRoundRect(
            androidx.compose.ui.geometry.RoundRect(
                left = 0f,
                top = 0f,
                right = size.width,
                bottom = size.height,
                radiusX = corner,
                radiusY = corner,
            ),
        )
    }
    clipPath(path) {
        // Dark edge along the top-left, highlight along the bottom-right.
        val width = blur * 1.7f
        drawRecessEdge(corner, blur, offset, width, colors.shadowDark.copy(alpha = colors.shadowDark.alpha * DsMaterialIntensity), topLeft = true)
        drawRecessEdge(corner, blur, offset, width, colors.shadowLight.copy(alpha = colors.shadowLight.alpha * DsMaterialIntensity), topLeft = false)
    }
}

private fun DrawScope.drawRecessEdge(
    corner: Float,
    blur: Float,
    offset: Float,
    strokeWidth: Float,
    color: Color,
    topLeft: Boolean,
) {
    val sign = if (topLeft) -1f else 1f
    val start = if (topLeft) Offset.Zero else Offset(size.width, size.height)
    val end = if (topLeft) Offset(size.width * 0.85f, size.height * 0.85f) else Offset(size.width * 0.15f, size.height * 0.15f)
    val brush = Brush.linearGradient(
        colors = listOf(color, Color.Transparent),
        start = start,
        end = end,
    )
    val pad = strokeWidth / 2f
    drawRoundRect(
        brush = brush,
        topLeft = Offset(-pad + sign * offset, -pad + sign * offset),
        size = Size(size.width + strokeWidth, size.height + strokeWidth),
        cornerRadius = CornerRadius(corner + pad, corner + pad),
        style = Stroke(width = strokeWidth),
    )
}

private fun DrawScope.drawSoftShadow(
    corner: Float,
    offset: Float,
    blur: Float,
    color: Color,
    dirX: Float,
    dirY: Float,
) {
    if (color.alpha <= 0.01f) return
    val steps = 9
    for (i in steps downTo 1) {
        val f = i / steps.toFloat()
        val grow = blur * f
        val alpha = (1f - f) * (1f - f) * 0.62f * color.alpha
        if (alpha <= 0.004f) continue
        drawRoundRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(dirX * offset - grow, dirY * offset - grow),
            size = Size(size.width + grow * 2f, size.height + grow * 2f),
            cornerRadius = CornerRadius(corner + grow, corner + grow),
        )
    }
}

/**
 * Continuous depth state for interactive surfaces: `sink` 0 = resting on the chassis,
 * 1 = pressed into it. Animating the fraction (rather than swapping two drawables) is what makes
 * a press read as light moving over a physical part.
 */
fun Modifier.neoState(
    cornerRadius: Dp,
    depth: NeoDepth,
    fill: Color,
    colors: DsColors,
    sink: Float,
): Modifier = drawBehind {
    val corner = cornerRadius.toPx()
    val offset = depth.offset.toPx()
    val blur = depth.blur.toPx()
    val lift = (1f - sink).coerceIn(0f, 1f)
    if (lift > 0.02f) {
        drawSoftShadow(corner, offset * 0.9f * lift, blur * 0.92f * lift, colors.shadowDark.copy(alpha = colors.shadowDark.alpha * 0.82f * lift), 1f, 1f)
        drawSoftShadow(corner, offset * 0.4f * lift, blur * 1.1f * lift, colors.shadowLight.copy(alpha = colors.shadowLight.alpha * 0.42f * lift), -1f, -1f)
    }
    drawRoundRect(fill, Offset.Zero, Size(size.width, size.height), CornerRadius(corner, corner))
    if (colors.isDark && lift > 0.02f) {
        drawRoundRect(
            brush = Brush.verticalGradient(listOf(colors.rim.copy(alpha = colors.rim.alpha * lift), Color.Transparent), 0f, size.height * 0.5f),
            topLeft = Offset.Zero,
            size = Size(size.width, size.height),
            cornerRadius = CornerRadius(corner, corner),
            style = Stroke(width = 0.9.dp.toPx()),
        )
    }
    val press = sink.coerceIn(0f, 1f)
    if (press > 0.02f && blur > 0f) {
        val path = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    left = 0f,
                    top = 0f,
                    right = size.width,
                    bottom = size.height,
                    radiusX = corner,
                    radiusY = corner,
                ),
            )
        }
        clipPath(path) {
            val width = blur * 1.7f
            drawRecessEdge(corner, blur, offset * press, width, colors.shadowDark.copy(alpha = colors.shadowDark.alpha * press), topLeft = true)
            drawRecessEdge(corner, blur, offset * press, width, colors.shadowLight.copy(alpha = colors.shadowLight.alpha * press), topLeft = false)
        }
    }
}
