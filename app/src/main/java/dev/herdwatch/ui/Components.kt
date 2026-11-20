package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.ConnState
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.LocalReducedMotion
import dev.herdwatch.ui.theme.statusPalette
import dev.herdwatch.ui.theme.connStatusColor

/**
 * Motion duration that respects the platform "remove animations" setting: 0 means snap.
 * Every animation in the app routes its duration through here.
 */
@Composable
fun motionMillis(base: Int): Int = if (LocalReducedMotion.current) 0 else base

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

fun formatElapsed(since: Long, now: Long): String {
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

fun formatAgo(at: Long?, now: Long): String {
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
