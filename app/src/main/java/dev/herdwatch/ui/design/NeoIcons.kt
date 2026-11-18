package dev.herdwatch.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Glyphs drawn by hand on a 24×24 grid so every icon shares one stroke weight, one cap style and
 * one optical size. Material icons are visually heavier and rounder than the rest of this
 * language, and mixing them with a 1.7dp line in custom components is what makes an app look
 * assembled rather than designed.
 */
enum class NeoGlyph {
    Back,
    ChevronDown,
    ChevronRight,
    Terminal,
    Server,
    Tray,
    Sliders,
    More,
    Plus,
    Refresh,
    Copy,
    Check,
    Close,
    Search,
    Warning,
    Play,
    Stop,
    Key,
    Keyboard,
    Clock,
    Send,
    Edit,
}

@Composable
fun NeoIcon(
    glyph: NeoGlyph,
    modifier: Modifier = Modifier.size(20.dp),
    contentDescription: String? = null,
    tint: Color = ds().text,
    strokeWidth: Dp = 1.8.dp,
    filled: Boolean = false,
) {
    val a11y = if (contentDescription == null) {
        Modifier
    } else {
        Modifier.semantics {
            this.contentDescription = contentDescription
            role = Role.Image
        }
    }
    Canvas(modifier.then(a11y)) {
        glyph(glyph, tint, strokeWidth.toPx(), filled)
    }
}

private fun DrawScope.glyph(kind: NeoGlyph, tint: Color, strokeWidth: Float, filled: Boolean) {
    val s = size.minDimension
    val u = s / 24f
    val stroke = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(vararg points: Float): Path = Path().apply {
        moveTo(points[0] * u, points[1] * u)
        var i = 2
        while (i + 1 < points.size) {
            lineTo(points[i] * u, points[i + 1] * u)
            i += 2
        }
    }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
        drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth, StrokeCap.Round)

    fun circle(cx: Float, cy: Float, r: Float, filledCircle: Boolean = false) {
        if (filledCircle) {
            drawCircle(tint, r * u, Offset(cx * u, cy * u))
        } else {
            drawCircle(tint, r * u, Offset(cx * u, cy * u), style = stroke)
        }
    }

    fun roundRect(x: Float, y: Float, w: Float, h: Float, radius: Float, filledRect: Boolean = false) {
        val sizeL = Size(w * u, h * u)
        if (filledRect) {
            drawRoundRect(tint, Offset(x * u, y * u), sizeL, CornerRadius(radius * u, radius * u))
        } else {
            drawRoundRect(
                tint,
                Offset(x * u, y * u),
                sizeL,
                CornerRadius(radius * u, radius * u),
                style = stroke,
            )
        }
    }

    when (kind) {
        NeoGlyph.Back -> drawPath(p(15f, 5f, 9f, 12f, 15f, 19f), tint, style = stroke)
        NeoGlyph.ChevronDown -> drawPath(p(6f, 9.5f, 12f, 15.5f, 18f, 9.5f), tint, style = stroke)
        NeoGlyph.ChevronRight -> drawPath(p(9.5f, 6f, 15.5f, 12f, 9.5f, 18f), tint, style = stroke)
        NeoGlyph.Terminal -> {
            roundRect(3.5f, 4.5f, 17f, 15f, 3.5f)
            drawPath(p(7.5f, 10f, 10.5f, 12.5f, 7.5f, 15f), tint, style = stroke)
            line(13f, 15.5f, 16.5f, 15.5f)
        }
        NeoGlyph.Server -> {
            roundRect(4f, 4.5f, 16f, 6.5f, 2.5f)
            roundRect(4f, 13f, 16f, 6.5f, 2.5f)
            circle(7.2f, 7.75f, 0.9f, filledCircle = true)
            circle(7.2f, 16.25f, 0.9f, filledCircle = true)
        }
        NeoGlyph.Tray -> {
            drawPath(p(4f, 13.5f, 4f, 19.5f, 20f, 19.5f, 20f, 13.5f), tint, style = stroke)
            line(12f, 4.5f, 12f, 13f)
            drawPath(p(8.5f, 9.5f, 12f, 13f, 15.5f, 9.5f), tint, style = stroke)
        }
        NeoGlyph.Sliders -> {
            line(4f, 7f, 20f, 7f); line(4f, 12f, 20f, 12f); line(4f, 17f, 20f, 17f)
            circle(9f, 7f, 2.2f, filledCircle = true)
            circle(15f, 12f, 2.2f, filledCircle = true)
            circle(8f, 17f, 2.2f, filledCircle = true)
        }
        NeoGlyph.More -> {
            circle(12f, 6f, 1.6f, filledCircle = true)
            circle(12f, 12f, 1.6f, filledCircle = true)
            circle(12f, 18f, 1.6f, filledCircle = true)
        }
        NeoGlyph.Plus -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
        NeoGlyph.Refresh -> {
            drawArc(
                color = tint,
                startAngle = 40f,
                sweepAngle = 280f,
                useCenter = false,
                topLeft = Offset(5f * u, 5f * u),
                size = Size(14f * u, 14f * u),
                style = stroke,
            )
            drawPath(p(15.5f, 4.5f, 19f, 6.5f, 16.5f, 9.5f), tint, style = stroke)
        }
        NeoGlyph.Copy -> {
            roundRect(8.5f, 3.5f, 12f, 12f, 3f)
            drawPath(p(15.5f, 19.5f, 5.5f, 19.5f, 4.5f, 18.5f, 4.5f, 9f), tint, style = stroke)
        }
        NeoGlyph.Check -> drawPath(p(5.5f, 12.5f, 10f, 17f, 18.5f, 7f), tint, style = stroke)
        NeoGlyph.Close -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
        NeoGlyph.Search -> {
            circle(10.8f, 10.8f, 5.4f)
            line(15f, 15f, 19f, 19f)
        }
        NeoGlyph.Warning -> {
            drawPath(p(12f, 4f, 20.5f, 19.5f, 3.5f, 19.5f, 12f, 4f), tint, style = stroke)
            line(12f, 10f, 12f, 14f)
            circle(12f, 16.8f, 0.9f, filledCircle = true)
        }
        NeoGlyph.Play -> if (filled) {
            drawPath(p(8f, 5.5f, 19f, 12f, 8f, 18.5f, 8f, 5.5f), tint)
        } else {
            drawPath(p(8f, 5.5f, 19f, 12f, 8f, 18.5f, 8f, 5.5f), tint, style = stroke)
        }
        NeoGlyph.Stop -> roundRect(7f, 7f, 10f, 10f, 2.5f, filledRect = filled)
        NeoGlyph.Key -> {
            circle(8.5f, 12f, 3.2f)
            line(11.5f, 12f, 19.5f, 12f)
            line(16.5f, 12f, 16.5f, 15f)
        }
        NeoGlyph.Keyboard -> {
            roundRect(3f, 7f, 18f, 10f, 2.5f)
            line(7f, 11f, 7f, 11.2f)
            line(11f, 11f, 11f, 11.2f)
            line(15f, 11f, 15f, 11.2f)
            line(9f, 14f, 15f, 14f)
        }
        NeoGlyph.Clock -> {
            circle(12f, 12f, 7.5f)
            drawPath(p(12f, 7.5f, 12f, 12f, 15.5f, 14f), tint, style = stroke)
        }
        NeoGlyph.Send -> {
            drawPath(p(4f, 12f, 20f, 5f, 14.5f, 20f, 11.5f, 13.5f, 4f, 12f), tint, style = stroke)
            line(11.5f, 13.5f, 20f, 5f)
        }
        NeoGlyph.Edit -> {
            drawPath(p(5f, 19f, 5.8f, 15.4f, 15.6f, 5.6f, 18.4f, 8.4f, 8.6f, 18.2f, 5f, 19f), tint, style = stroke)
            line(13.8f, 7.4f, 16.6f, 10.2f)
        }
    }
}
