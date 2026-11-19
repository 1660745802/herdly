package dev.herdwatch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.LocalReducedMotion
import dev.herdwatch.ui.theme.statusPalette
import dev.herdwatch.ui.theme.statusColor
import dev.herdwatch.ui.theme.connStatusColor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Motion duration that respects the platform "remove animations" setting: 0 means snap.
 * Every animation in the app routes its duration through here.
 */
@Composable
fun motionMillis(base: Int): Int = if (LocalReducedMotion.current) 0 else base

/**
 * Subtle press feedback for buttons and tappable cards. Only observes the interaction
 * state of the supplied [interactionSource] and never consumes the click/tap.
 *
 * Pass the same [interactionSource] the child (e.g. a Material3 button) uses. Compose 1.7
 * removed `Modifier.interactionSource`, so the source must be shared by the child: this
 * modifier never attaches a node of its own, it only scales.
 */
fun Modifier.pressScale(
    enabled: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val target = if (enabled && pressed) 0.97f else 1f
    val scale by animateFloatAsState(
        targetValue = target,
        animationSpec = tween(if (pressed) motionMillis(90) else motionMillis(140), easing = FastOutSlowInEasing),
        label = "press-scale",
    )
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * Vertical expand/collapse used for detail sections, so disclosure never pops.
 * Durations collapse to 0 when reduced motion is requested.
 */
@Composable
fun ExpandableSection(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = expanded,
        modifier = modifier,
        enter = expandVertically(animationSpec = tween(motionMillis(220), easing = FastOutSlowInEasing)) +
            fadeIn(animationSpec = tween(motionMillis(160))),
        exit = shrinkVertically(animationSpec = tween(motionMillis(180), easing = FastOutSlowInEasing)) +
            fadeOut(animationSpec = tween(motionMillis(120))),
    ) {
        Column { content() }
    }
}

/** Chevron that turns instead of swapping glyphs when a section opens. */
@Composable
fun RotatingChevron(expanded: Boolean, contentDescription: String?, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(motionMillis(200), easing = FastOutSlowInEasing),
        label = "chevron-rotation",
    )
    NeoIcon(
        glyph = NeoGlyph.ChevronDown,
        contentDescription = contentDescription,
        tint = ds().textSoft,
        modifier = modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
    )
}

/** Counts that fade/slide by 6dp so totals do not jump while a snapshot refreshes. */
@Composable
fun AnimatedCount(
    count: Int,
    modifier: Modifier = Modifier,
    style: TextStyle = DsTypography.caption,
    color: Color = Color.Unspecified,
) {
    val slide = with(LocalDensity.current) { 6.dp.roundToPx() }
    val ms = motionMillis(160)
    val sizeMs = motionMillis(180)
    AnimatedContent(
        targetState = count,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(ms)) + slideInVertically(tween(ms)) { slide }) togetherWith
                (fadeOut(tween(ms / 2)) + slideOutVertically(tween(ms / 2)) { -slide }) using
                SizeTransform(clip = false) { _, _ -> tween(sizeMs) }
        },
        label = "count",
    ) { value ->
        Text(value.toString(), style = style, color = color, maxLines = 1, softWrap = false)
    }
}

/** List-item placement/fade for LazyColumn rows, with tween specs instead of springs. */
@Composable
fun LazyItemScope.animateItemMotion(): Modifier {
    val ms = motionMillis(200)
    val fadeOutMs = motionMillis(120)
    return Modifier.animateItem(
        fadeInSpec = if (ms == 0) null else tween(ms, easing = FastOutSlowInEasing),
        placementSpec = if (ms == 0) null else tween(ms, easing = FastOutSlowInEasing),
        fadeOutSpec = if (fadeOutMs == 0) null else tween(fadeOutMs),
    )
}

/** Connectivity dot that fades between connected/failed/idle colors instead of snapping. */
@Composable
fun AnimatedConnDot(state: ConnState, modifier: Modifier = Modifier, size: Dp = 8.dp) {
    val p = statusPalette()
    val colors = ds()
    val target = connStatusColor(state)
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(motionMillis(220)),
        label = "conn-dot",
    )
    Box(modifier.size(size).background(color, CircleShape))
}

@Composable
fun StatusGlyph(status: AgentStatus, modifier: Modifier = Modifier, size: Int = 16) {
    val p = statusPalette()
    // Idle, unknown and completed rows do not keep an animation clock alive. With animations
    // turned off the working arc stays a static ring instead of spinning forever.
    val angle = if (status == AgentStatus.WORKING && !LocalReducedMotion.current) {
        val spin = rememberInfiniteTransition(label = "status-spin")
        val rotation by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1600, easing = LinearEasing)),
            label = "angle",
        )
        rotation
    } else 0f
    val color = statusColor(status, p)
    val statusDesc = stringResource(R.string.components_status_a11y, stringResource(status.labelRes))
    Canvas(modifier.size(size.dp).semantics { contentDescription = statusDesc }) {
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
fun GlassBackdrop(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit = {}) {
    // One chassis, one light. The earlier version washed a blue diagonal across the whole screen,
    // which fought the panels for attention and made dark mode look like a wallpaper. Now it is
    // the base material with a barely-there top-left lift, matching the shadow direction.
    val colors = dev.herdwatch.ui.design.ds()
    Box(
        modifier.fillMaxSize().background(colors.base),
    ) {
        androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
            drawRect(
                Brush.radialGradient(
                    listOf(
                        colors.shadowLight.copy(alpha = if (colors.isDark) 0.035f else 0.55f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.12f, size.height * 0.02f),
                    radius = size.maxDimension * 0.85f,
                ),
            )
        }
        content()
    }
}

fun formatElapsed(since: Long, now: Long = System.currentTimeMillis()): String {
    val s = ((now - since).coerceAtLeast(0)) / 1000
    return when {
        s < 5 -> Str.get(R.string.components_just_now)
        s < 60 -> "0:${s.toString().padStart(2, '0')}"
        s < 3600 -> "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
        s < 86_400 -> Str.quantity(
            R.plurals.components_elapsed_hours,
            (s / 3600).toInt(),
            ((s % 3600) / 60).toInt(),
        )
        else -> Str.quantity(R.plurals.components_elapsed_days, (s / 86_400).toInt())
    }
}

fun formatAgo(at: Long?, now: Long = System.currentTimeMillis()): String {
    if (at == null) return Str.get(R.string.components_not_synced)
    val s = ((now - at).coerceAtLeast(0)) / 1000
    return when {
        s < 5 -> Str.get(R.string.components_just_now)
        s < 60 -> Str.quantity(R.plurals.components_seconds_ago, s.toInt())
        s < 3600 -> Str.quantity(R.plurals.components_minutes_ago, (s / 60).toInt())
        else -> formatClock(at)
    }
}

fun formatClock(at: Long?): String {
    if (at == null) return "—"
    val d = java.util.Date(at)
    return java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(d)
}

fun pct(v: Float): Int = (v * 100).roundToInt()
