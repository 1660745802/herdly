package dev.herdwatch.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.ui.theme.statusPalette
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun StatusGlyph(status: AgentStatus, modifier: Modifier = Modifier, size: Int = 16) {
    val p = statusPalette()
    // Idle, unknown and completed rows do not keep an animation clock alive.
    val angle = if (status == AgentStatus.WORKING) {
        val spin = rememberInfiniteTransition(label = "status-spin")
        val rotation by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "angle",
        )
        rotation
    } else 0f
    val color = when (status) {
        AgentStatus.WAITING -> p.waiting
        AgentStatus.WORKING -> p.working
        AgentStatus.REVIEW -> p.review
        AgentStatus.IDLE -> p.idle
        AgentStatus.UNKNOWN -> p.unknown
    }
    Canvas(modifier.size(size.dp)) {
        val s = this.size.minDimension
        val stroke = min(1.6f * density, s / 8f)
        val c = Offset(s / 2, s / 2)
        val r = s / 2 - stroke
        when (status) {
            AgentStatus.WAITING -> {
                drawCircle(color, radius = r * 0.58f, center = c)
                drawCircle(color.copy(alpha = 0.28f), radius = r, center = c, style = Stroke(stroke))
            }
            AgentStatus.WORKING -> {
                drawCircle(color.copy(alpha = 0.22f), radius = r, center = c, style = Stroke(stroke))
                drawArc(
                    color = color,
                    startAngle = -90f + angle,
                    sweepAngle = 280f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
            AgentStatus.REVIEW -> {
                drawCircle(color.copy(alpha = 0.28f), radius = r, center = c, style = Stroke(stroke))
                drawLine(
                    color = color,
                    start = Offset(s * 0.30f, s * 0.52f),
                    end = Offset(s * 0.45f, s * 0.67f),
                    strokeWidth = stroke * 1.2f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = color,
                    start = Offset(s * 0.45f, s * 0.67f),
                    end = Offset(s * 0.72f, s * 0.36f),
                    strokeWidth = stroke * 1.2f,
                    cap = StrokeCap.Round,
                )
            }
            AgentStatus.IDLE -> drawCircle(color.copy(alpha = 0.55f), radius = r, center = c, style = Stroke(stroke))
            AgentStatus.UNKNOWN -> {
                drawCircle(color.copy(alpha = 0.5f), radius = r, center = c, style = Stroke(stroke, pathEffect = dashed()))
            }
        }
    }
}

private fun dashed() = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(3f, 4f))

@Composable
fun StatusChip(status: AgentStatus, modifier: Modifier = Modifier, withLabel: Boolean = true) {
    val p = statusPalette()
    val color = when (status) {
        AgentStatus.WAITING -> p.waiting
        AgentStatus.WORKING -> p.working
        AgentStatus.REVIEW -> p.review
        AgentStatus.IDLE -> p.idle
        AgentStatus.UNKNOWN -> p.unknown
    }
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusGlyph(status, size = 11)
        if (withLabel) {
            Text(status.label, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int, status: AgentStatus? = null, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = 22.dp, bottom = 8.dp).semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (status != null) {
            StatusGlyph(status, size = 12)
            Spacer(Modifier.width(8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.width(8.dp))
        Text(
            count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        androidx.compose.foundation.layout.Box(
            Modifier.width(40.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
fun EmptyBlock(title: String, body: String, action: (@Composable () -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(20.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (action != null) {
            Spacer(Modifier.height(14.dp))
            action()
        }
    }
}

fun formatElapsed(since: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - since).coerceAtLeast(0)) / 1000
    return when {
        s < 5 -> "刚刚"
        s < 60 -> "0:${s.toString().padStart(2, '0')}"
        s < 3600 -> "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        s < 86_400 -> "${s / 3600}h ${(s % 3600) / 60}m"
        else -> "${s / 86_400}d"
    }
}

fun formatAgo(at: Long?, now: Long = System.currentTimeMillis()): String {
    if (at == null) return "未同步"
    val s = ((now - at).coerceAtLeast(0)) / 1000
    return when {
        s < 5 -> "刚刚"
        s < 60 -> "${s}s 前"
        s < 3600 -> "${s / 60}m 前"
        else -> formatClock(at)
    }
}

fun formatClock(at: Long?): String {
    if (at == null) return "—"
    val d = java.util.Date(at)
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(d)
}

fun pct(v: Float): Int = (v * 100).roundToInt()

val Accent = Color(0xFF2F4BE0)
