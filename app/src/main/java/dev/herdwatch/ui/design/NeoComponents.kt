package dev.herdwatch.ui.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.ui.motionMillis
import dev.herdwatch.ui.theme.LocalReducedMotion

/**
 * The component library. Every control is drawn from the same three materials:
 * chassis (flat), lifted (raised pair shadow), milled (inset pair shadow), and the same accent.
 * Nothing here uses borders, elevation overlays or Material's tonal surfaces, so a screen made of
 * these parts has one continuous light.
 */

@Composable
fun NeoCard(
    modifier: Modifier = Modifier,
    corner: Dp = DsRadius.card,
    fill: Color = ds().base,
    depth: NeoDepth = DsElev.rest,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = ds()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(
        targetValue = if (pressed && onClick != null) 1f else 0f,
        animationSpec = tween(motionMillis(DsMotion.press)),
        label = "cardSink",
    )
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
    }
    Box(
        modifier = modifier
            .neoState(corner, depth, fill, colors, sink)
            .clip(RoundedCornerShape(corner))
            .then(clickable),
        content = content,
    )
}

/** A milled recess: inputs, tracks, terminal wells, anything the light falls into. */
@Composable
fun NeoWell(
    modifier: Modifier = Modifier,
    corner: Dp = DsRadius.well,
    fill: Color = ds().well,
    depth: NeoDepth = DsElev.inset,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = ds()
    Box(
        modifier = modifier
            .neoInset(corner, depth, fill, colors)
            .clip(RoundedCornerShape(corner)),
        content = content,
    )
}

enum class NeoButtonTone { Primary, Quiet, Danger, Ghost }

@Composable
fun NeoButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: NeoButtonTone = NeoButtonTone.Quiet,
    enabled: Boolean = true,
    icon: NeoGlyph? = null,
    compact: Boolean = false,
) {
    val colors = ds()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = tween(motionMillis(DsMotion.press)),
        label = "buttonSink",
    )
    val baseFill = when (tone) {
        NeoButtonTone.Primary -> colors.accent
        NeoButtonTone.Danger -> colors.danger
        NeoButtonTone.Quiet -> colors.base
        NeoButtonTone.Ghost -> Color.Transparent
    }
    val fill = if (!enabled) {
        when (tone) {
            NeoButtonTone.Ghost -> Color.Transparent
            else -> colors.base
        }
    } else {
        baseFill
    }
    val contentColor = when {
        !enabled -> if (tone == NeoButtonTone.Ghost) colors.textFaint else colors.textFaint
        tone == NeoButtonTone.Primary -> colors.onAccent
        tone == NeoButtonTone.Danger -> colors.onDanger
        else -> colors.text
    }
    val depth = when (tone) {
        NeoButtonTone.Ghost -> DsElev.flat
        NeoButtonTone.Primary, NeoButtonTone.Danger -> DsElev.soft
        else -> DsElev.soft
    }
    val horizontal = if (compact) DsSpace.l else DsSpace.xl
    val vertical = if (compact) 9.dp else 13.dp
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .then(
                if (depth == DsElev.flat) {
                    Modifier
                } else {
                    Modifier.neoState(DsRadius.control, depth, fill, colors, sink)
                },
            )
            .clip(RoundedCornerShape(DsRadius.control))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            // Without this a disabled button is indistinguishable from a broken one to TalkBack,
            // and the grey fill alone is easy to miss on a bright screen.
            .semantics { if (!enabled) disabled() }
            .padding(horizontal = horizontal, vertical = vertical),
        horizontalArrangement = Arrangement.spacedBy(DsSpace.s, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            NeoIcon(icon, modifier = Modifier.size(18.dp), tint = contentColor, contentDescription = null)
        }
        Text(
            text = label,
            style = DsTypography.bodyStrong,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NeoIconButton(
    glyph: NeoGlyph,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = ds().textSoft,
    enabled: Boolean = true,
) {
    val colors = ds()
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(DsRadius.control))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        NeoIcon(
            glyph,
            modifier = Modifier.size(20.dp),
            contentDescription = contentDescription,
            tint = if (enabled) tint else colors.textFaint,
        )
    }
}

/**
 * Segmented control: a milled track with a lifted thumb that slides. The thumb is a separate
 * surface rather than a tinted rectangle so the selection reads as a part that moved.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NeoSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ds()
    if (options.isEmpty()) return
    var trackWidth by remember { mutableStateOf(0) }
    // GraphicsLayer translates in pixels, layout sizes in dp: keep both, never mix the units.
    val density = LocalDensity.current
    val segmentPx = trackWidth.toFloat() / options.size
    val segmentDp = with(density) { segmentPx.toDp() }
    val thumbOffset by animateFloatAsState(
        targetValue = selectedIndex.coerceIn(0, options.lastIndex) * segmentPx,
        animationSpec = tween(motionMillis(DsMotion.base)),
        label = "segmentThumb",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .neoInset(DsRadius.control, DsElev.inset, colors.well, colors)
            .clip(RoundedCornerShape(DsRadius.control))
            .padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .onSizeChanged { trackWidth = it.width },
        ) {
            if (segmentPx > 0f) {
                Box(
                    modifier = Modifier
                        .width(segmentDp)
                        .height(48.dp)
                        .graphicsLayer { translationX = thumbOffset }
                        .neoRaised(DsRadius.control - 4.dp, DsElev.soft, colors.baseRaised, colors),
                )
            }
            Row(Modifier.fillMaxWidth().height(48.dp)) {
                options.forEachIndexed { index, label ->
                    val selected = index == selectedIndex
                    val textColor by animateColorAsState(
                        targetValue = if (selected) colors.text else colors.textSoft,
                        animationSpec = tween(motionMillis(DsMotion.quick)),
                        label = "segmentText",
                    )
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(DsRadius.control - 3.dp))
                            .clickable(role = Role.Tab) { onSelect(index) }
                            .semantics { this.selected = selected },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            label,
                            style = if (selected) DsTypography.bodyStrong else DsTypography.body,
                            color = textColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Track is milled, knob is lifted; the knob's shadow flips as it travels. */
@Composable
fun NeoToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    enabled: Boolean = true,
) {
    val colors = ds()
    val progress by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(motionMillis(DsMotion.base)),
        label = "toggleProgress",
    )
    val trackFill by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.well,
        animationSpec = tween(motionMillis(DsMotion.base)),
        label = "toggleTrack",
    )
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(DsRadius.control))
            .clickable(enabled = enabled, role = Role.Switch) { onCheckedChange(!checked) }
            .padding(vertical = DsSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.m),
    ) {
        if (label != null) {
            Text(
                label,
                style = DsTypography.body,
                color = if (enabled) colors.text else colors.textFaint,
                modifier = Modifier.weight(1f),
            )
        }
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .neoInset(15.dp, DsElev.inset, trackFill, colors),
        ) {
            Box(
                modifier = Modifier
                    .padding(3.dp)
                    .size(24.dp)
                    .graphicsLayer { translationX = progress * 22.dp.toPx() }
                    .neoRaised(12.dp, NeoDepth(2.dp, 4.dp), colors.baseRaised, colors),
            )
        }
    }
}

/** Text field sunk into the chassis. Label sits above, hint inside, no boxes, no underlines. */
@Composable
fun NeoField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    hint: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    textStyle: TextStyle = DsTypography.body,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ds()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
        if (label != null) {
            // The visible caption is decoration for sighted users; the field itself carries the
            // label, so a screen reader announces "name, edit box" instead of a stray caption.
            Text(label, style = DsTypography.caption, color = colors.textSoft, modifier = Modifier.clearAndSetSemantics { })
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            textStyle = textStyle.copy(color = if (enabled) colors.text else colors.textFaint),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { if (label != null) contentDescription = label }
                .neoInset(DsRadius.control, DsElev.inset, colors.well, colors)
                .clip(RoundedCornerShape(DsRadius.control))
                .padding(horizontal = DsSpace.l, vertical = DsSpace.m),
            decorationBox = { inner ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && hint.isNotEmpty()) {
                            Text(hint, style = textStyle, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                    if (trailing != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.xs), verticalAlignment = Alignment.CenterVertically, content = trailing)
                    }
                }
            },
        )
    }
}

@Composable
fun NeoChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dot: Color? = null,
) {
    val colors = ds()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(
        targetValue = if (pressed) 1f else if (selected) 0.65f else 0f,
        animationSpec = tween(motionMillis(DsMotion.press)),
        label = "chipSink",
    )
    // Selected has to be visible at a glance: two shades of the chassis fill (base vs baseRaised)
    // differ by a few percent in dark mode, so a chosen workspace looked exactly like an unchosen
    // one and the "create" button appeared to have no target.
    val fill by animateColorAsState(
        targetValue = if (selected) colors.accentWash else colors.base,
        animationSpec = tween(motionMillis(DsMotion.quick)),
        label = "chipFill",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) colors.accent else colors.textSoft,
        animationSpec = tween(motionMillis(DsMotion.quick)),
        label = "chipText",
    )
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .neoState(DsRadius.pill, DsElev.soft, fill, colors, sink)
            .clip(RoundedCornerShape(DsRadius.pill))
            .then(
                if (selected) {
                    Modifier.border(1.dp, colors.accent.copy(alpha = 0.55f), RoundedCornerShape(DsRadius.pill))
                } else {
                    Modifier
                },
            )
            .clickable(interactionSource = interaction, indication = null, role = Role.Tab) { onClick() }
            .padding(horizontal = DsSpace.l, vertical = 10.dp)
            .semantics { this.role = Role.Tab; this.selected = selected },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
    ) {
        if (dot != null) {
            Box(
                Modifier
                    .size(7.dp)
                    .background(dot, RoundedCornerShape(DsRadius.pill)),
            )
        }
        Text(label, style = DsTypography.caption, color = textColor, maxLines = 1)
    }
}

/** Soft lamp for state: a lifted bead with a wash of its own colour behind it. */
@Composable
fun NeoLamp(color: Color, modifier: Modifier = Modifier, pulse: Boolean = false, size: Dp = 9.dp) {
    val colors = ds()
    val alpha by animateFloatAsState(
        targetValue = if (pulse) 0.45f else 0.22f,
        animationSpec = tween(motionMillis(DsMotion.page)),
        label = "lampWash",
    )
    Box(modifier.size(size * 2.4f), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(size * 2.2f)
                .background(color.copy(alpha = alpha), RoundedCornerShape(DsRadius.pill)),
        )
        Box(
            Modifier
                .size(size)
                .neoRaised(DsRadius.pill, DsElev.flat, color, colors)
                .clip(RoundedCornerShape(DsRadius.pill)),
        )
    }
}

/** Key cap for the terminal row: a lifted cap that sinks while held. */
@Composable
fun NeoKey(
    label: String,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
    wide: Boolean = false,
    stretch: Boolean = false,
    active: Boolean = false,
) {
    val colors = ds()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val sink by animateFloatAsState(
        targetValue = if (pressed || active) 1f else 0f,
        animationSpec = tween(motionMillis(DsMotion.press)),
        label = "keySink",
    )
    val fill by animateColorAsState(
        targetValue = if (active) colors.accentWash else colors.base,
        animationSpec = tween(motionMillis(DsMotion.quick)),
        label = "keyFill",
    )
    Box(
        modifier = modifier
            .height(44.dp)
            // A key bar is a compromise with the keyboard it replaces: the target stays above the
            // 40dp floor while the row costs 44dp of terminal rows instead of 48.
            // Free-standing keys keep a fixed width (56dp when wide); inside a weighted row the
            // caller passes stretch so the key fills its slot instead of leaving dead space.
            .then(if (stretch) Modifier else Modifier.width(if (wide) 56.dp else 44.dp))
            .neoState(DsRadius.key, DsElev.soft, fill, colors, sink)
            .clip(RoundedCornerShape(DsRadius.key))
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onPress),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = DsTypography.monoStrong.copy(fontSize = DsTypography.micro.fontSize),
            color = if (active) colors.accent else colors.text,
            maxLines = 1,
        )
    }
}

/** Section label: small caps feel without tracking tricks — weight and colour only. */
@Composable
fun NeoSection(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ds()
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = DsSpace.xs, vertical = DsSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = DsTypography.caption, color = colors.textFaint, modifier = Modifier.weight(1f))
        if (trailing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
        }
    }
}

/** Loading placeholder shaped like the content it replaces; never a spinner in a list. */
@Composable
fun NeoSkeleton(modifier: Modifier = Modifier, height: Dp = 14.dp, corner: Dp = 7.dp) {
    val colors = ds()
    Box(
        modifier
            .height(height)
            .neoInset(corner, NeoDepth(1.dp, 3.dp), colors.well, colors)
            .clip(RoundedCornerShape(corner)),
    )
}

@Composable
fun NeoDivider(modifier: Modifier = Modifier, inset: Dp = 0.dp) {
    val colors = ds()
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = inset)
            .height(1.dp)
            .background(colors.shadowDark.copy(alpha = if (colors.isDark) 0.35f else 0.18f)),
    )
}

@Composable
fun SpacerHeight(height: Dp) = Spacer(Modifier.height(height))

/**
 * The one loading indicator.
 *
 * Material's spinner is a bright accent ring that reads like a different product dropped next to
 * the matte chassis, and it kept spinning even when the system asked for reduced motion. This is
 * a thin arc in the ink of the surrounding panel, and it holds still when motion is reduced.
 *
 * It is decorative on purpose: the text next to it ("正在读取屏幕") is what a screen reader
 * should announce.
 */
@Composable
fun NeoSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 16.dp,
    color: Color? = null,
    strokeWidth: Dp = 2.dp,
) {
    val colors = ds()
    val ink = color ?: colors.textSoft
    val angle = if (!LocalReducedMotion.current) {
        val spin = rememberInfiniteTransition(label = "spinner")
        val rotation by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
            label = "angle",
        )
        rotation
    } else {
        0f
    }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val stroke = strokeWidth.toPx()
        val r = s / 2 - stroke / 2
        val c = Offset(s / 2, s / 2)
        drawCircle(ink.copy(alpha = 0.22f), radius = r, center = c, style = Stroke(stroke))
        drawArc(
            color = ink,
            startAngle = -90f + angle,
            sweepAngle = 260f,
            useCenter = false,
            topLeft = Offset(c.x - r, c.y - r),
            size = Size(r * 2, r * 2),
            style = Stroke(stroke, cap = StrokeCap.Round),
        )
    }
}

/**
 * The one empty state.
 *
 * Every screen that has nothing to show used to draw its own: a well here, a bare column there,
 * a different icon, different padding. The result was four slightly different voices for the same
 * message ("nothing here yet"). This is that message, drawn once: a quiet well, one glyph, a title,
 * one line of guidance, and an optional action row.
 */
@Composable
fun NeoEmptyState(
    icon: NeoGlyph,
    title: String,
    body: String? = null,
    modifier: Modifier = Modifier,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = ds()
    NeoWell(
        modifier = modifier.fillMaxWidth().padding(vertical = DsSpace.s),
        corner = DsRadius.card,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(DsSpace.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DsSpace.s),
        ) {
            NeoIcon(
                glyph = icon,
                modifier = Modifier.size(32.dp),
                tint = colors.textSoft,
                strokeWidth = 1.6.dp,
            )
            Text(
                title,
                style = DsTypography.bodyStrong,
                color = colors.text,
                textAlign = TextAlign.Center,
            )
            if (!body.isNullOrBlank()) {
                Text(
                    body,
                    style = DsTypography.body,
                    color = colors.textSoft,
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(DsSpace.xs))
                action()
            }
        }
    }
}

/**
 * Bottom navigation drawn from the same chassis as everything else.
 *
 * Material's NavigationBar arrived as a flat tinted slab with a pill behind the active icon, which
 * read as a second design language bolted under the page. Here the bar is the chassis itself: no
 * fill of its own, one hairline where the page ends, and the selected tab is a shallow milled well
 * with an accent glyph. Selection moves; nothing else animates.
 */
@Composable
fun NeoTabBar(
    tabs: List<Pair<String, NeoGlyph>>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ds()
    Column(modifier = modifier.fillMaxWidth().background(colors.base)) {
        NeoDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .height(64.dp)
                .padding(horizontal = DsSpace.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, (label, glyph) ->
                val selected = index == selectedIndex
                val tint by animateColorAsState(
                    targetValue = when {
                        selected -> colors.accent
                        else -> colors.textSoft
                    },
                    animationSpec = tween(motionMillis(DsMotion.quick)),
                    label = "tabTint",
                )
                // Selection is carried by colour and weight only. A milled well behind one third
                // of the bar looked like a button that had been left pressed.
                val wash by animateFloatAsState(
                    targetValue = if (selected) 1f else 0f,
                    animationSpec = tween(motionMillis(DsMotion.quick)),
                    label = "tabWash",
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                        .background(
                            colors.accentWash.copy(alpha = colors.accentWash.alpha * wash),
                            RoundedCornerShape(DsRadius.control),
                        )
                        .clip(RoundedCornerShape(DsRadius.control))
                        .clickable(role = Role.Tab, onClick = { onSelect(index) })
                        .semantics { this.selected = selected },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        NeoIcon(glyph, modifier = Modifier.size(21.dp), tint = tint, contentDescription = null)
                        Spacer(Modifier.height(3.dp))
                        Text(
                            label,
                            style = if (selected) DsTypography.micro.copy(fontWeight = FontWeight.SemiBold) else DsTypography.micro,
                            color = tint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Overlay primitives.
 *
 * The component library covered cards, buttons and fields but not the surfaces that sit above the
 * page — dialogs, menus, sheets and the snackbar. Every one of those was therefore a stock Material
 * surface: a 28dp-corner white card with M3 type, floating over a milled chassis. These four exist
 * so an overlay is drawn from the same materials as the page beneath it.
 */

/**
 * Confirmation / input dialog on the lifted material.
 *
 * `confirm` renders as a primary NeoButton unless [destructive] is set, in which case it is a
 * danger button; `dismiss` is always a ghost button. Both stay 48dp so the pair is thumb-safe.
 */
@Composable
fun NeoDialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    confirmLabel: String? = null,
    onConfirm: (() -> Unit)? = null,
    dismissLabel: String? = null,
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val colors = ds()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier.fillMaxWidth().imePadding().padding(horizontal = DsSpace.xl),
            contentAlignment = Alignment.Center,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                // Short landscape windows get a proportionally smaller dialog instead of a fixed
                // 560dp slab that would run under the keyboard or off the bottom of the screen.
                val maxDialogHeight = maxHeight * 0.85f
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .heightIn(max = maxDialogHeight)
                        .neoRaised(DsRadius.card, DsElev.deep, colors.baseRaised, colors)
                        .clip(RoundedCornerShape(DsRadius.card))
                        .padding(DsSpace.xl),
                    verticalArrangement = Arrangement.spacedBy(DsSpace.m),
                ) {
                    Text(title, style = DsTypography.title, color = colors.text)
                    if (subtitle != null) {
                        Text(subtitle, style = DsTypography.body, color = colors.textSoft)
                    }
                    // Only the body scrolls; the action row stays pinned to the footer.
                    Column(
                        Modifier.fillMaxWidth()
                            .heightIn(max = (maxDialogHeight - 160.dp).coerceAtLeast(64.dp))
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(DsSpace.m),
                    ) {
                        content?.invoke(this)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(top = DsSpace.xs),
                        horizontalArrangement = Arrangement.spacedBy(DsSpace.s, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (dismissLabel != null) {
                            NeoButton(
                                label = dismissLabel,
                                onClick = onDismissRequest,
                                tone = NeoButtonTone.Ghost,
                                compact = true,
                            )
                        }
                        if (confirmLabel != null && onConfirm != null) {
                            NeoButton(
                                label = confirmLabel,
                                onClick = onConfirm,
                                tone = if (destructive) NeoButtonTone.Danger else NeoButtonTone.Primary,
                                enabled = confirmEnabled,
                                compact = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Dropdown menu on the lifted material.
 *
 * Items are separated by milled dividers only when the caller groups them; pass a [NeoMenuItem]
 * with `danger = true` for a destructive entry. Text is always `DsTypography.body` so a menu
 * cannot drift to Material's larger `bodyLarge`.
 */
@Composable
fun NeoMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ds()
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier
            .neoRaised(DsRadius.control, DsElev.deep, colors.baseRaised, colors)
            .clip(RoundedCornerShape(DsRadius.control)),
        containerColor = colors.baseRaised,
        shape = RoundedCornerShape(DsRadius.control),
        offset = DpOffset(0.dp, DsSpace.xs),
    ) {
        Column(Modifier.padding(vertical = DsSpace.xs), content = content)
    }
}

/** One row of a [NeoMenu]. 48dp tall, body type, optional trailing check for toggles. */
@Composable
fun NeoMenuItem(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
    checked: Boolean? = null,
    icon: NeoGlyph? = null,
) {
    val colors = ds()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DsSpace.l, vertical = DsSpace.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.m),
    ) {
        if (icon != null) {
            NeoIcon(
                icon,
                modifier = Modifier.size(18.dp),
                tint = if (!enabled) colors.textFaint else if (danger) colors.danger else colors.textSoft,
                contentDescription = null,
            )
        }
        Text(
            label,
            style = DsTypography.body,
            color = if (!enabled) colors.textFaint else if (danger) colors.danger else colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (checked == true) {
            NeoIcon(NeoGlyph.Check, modifier = Modifier.size(16.dp), tint = colors.accent, contentDescription = null)
        }
    }
}

/** Hairline divider for use inside [NeoMenu] groups. */
@Composable
fun NeoMenuDivider() {
    NeoDivider(inset = DsSpace.l)
}

/**
 * Bottom sheet on the chassis material, with a milled drag handle.
 *
 * Callers keep their own content layout; this only guarantees the container colour, corner, handle
 * and content colours match the page behind the scrim.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NeoSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    skipPartiallyExpanded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = ds()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = skipPartiallyExpanded)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = colors.base,
        contentColor = colors.text,
        scrimColor = Color.Black.copy(alpha = 0.32f),
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(vertical = DsSpace.m), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(width = 40.dp, height = 4.dp)
                        .clip(RoundedCornerShape(DsRadius.pill))
                        .background(colors.textFaint.copy(alpha = 0.35f)),
                )
            }
        },
        modifier = modifier,
    ) {
        Column(content = content)
    }
}

/**
 * Snackbar body on the lifted material, used as `SnackbarHost { NeoSnackbarBody(...) }`.
 *
 * Replaces the M3 inverse-surface slab, which was the only place in the app that turned into a
 * near-black plate on the light chassis.
 */
@Composable
fun NeoSnackbarBody(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val colors = ds()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = DsSpace.xl, vertical = DsSpace.s)
            .neoRaised(DsRadius.control, DsElev.deep, colors.baseRaised, colors)
            .clip(RoundedCornerShape(DsRadius.control))
            .padding(start = DsSpace.l, end = if (actionLabel != null) DsSpace.s else DsSpace.l, top = DsSpace.m, bottom = DsSpace.m),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.m),
    ) {
        Text(message, style = DsTypography.body, color = colors.text, modifier = Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            NeoButton(label = actionLabel, onClick = onAction, tone = NeoButtonTone.Ghost, compact = true)
        }
    }
}

/** A quiet tag that makes the current interaction mode unmistakable (agent decisions vs SSH). */
@Composable
fun NeoModeTag(text: String, modifier: Modifier = Modifier) {
    val colors = ds()
    Text(
        text,
        style = DsTypography.micro,
        color = colors.accent,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(colors.accentWash, RoundedCornerShape(DsRadius.key))
            .padding(horizontal = DsSpace.s, vertical = DsSpace.xxs),
    )
}

/**
 * One header for every page, so the top of the screen has the same height and rhythm everywhere.
 *
 * Before this, each root page hand-rolled its own Row: the inbox had a brand mark and a 28sp title,
 * hosts had a 28sp title with no bottom gap, settings had a back chevron it did not need (it is a
 * root tab, reachable from the tab bar) and a 20sp title. Switching tabs therefore moved the whole
 * body by ~12dp and changed the title weight, which is exactly what "内容一会多一会少" looked like.
 *
 * Rules encoded here:
 * - roots (no [onBack]) never show a chevron; sub-pages do;
 * - title is always [DsTypography.display] unless the page is compact or has a back button;
 * - subtitle is one caption line, ellipsised, so the header height cannot grow with long host lists;
 * - actions sit in a single 48dp row, so a page with three actions is not taller than one with none.
 */
@Composable
fun NeoPageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    compact: Boolean = false,
    titleModifier: Modifier = Modifier,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ds()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = spacing().gutter,
                end = spacing().gutter,
                top = DsSpace.m,
                bottom = DsSpace.s,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
    ) {
        if (onBack != null) {
            NeoIconButton(
                glyph = NeoGlyph.Back,
                onClick = onBack,
                contentDescription = stringResource(R.string.common_back),
                modifier = Modifier.size(48.dp),
            )
        }
        if (leading != null) {
            leading()
            Spacer(Modifier.width(DsSpace.xs))
        }
        Column(
            modifier = titleModifier.weight(1f),
        ) {
            Text(
                title,
                style = if (compact || onBack != null) DsTypography.title else DsTypography.display,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (actions != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpace.xs),
                content = actions,
            )
        }
    }
}
