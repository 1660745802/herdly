package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import dev.herdwatch.R
import dev.herdwatch.term.VtRun
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoDialog
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoMenu
import dev.herdwatch.ui.design.NeoMenuDivider
import dev.herdwatch.ui.design.NeoMenuItem
import dev.herdwatch.ui.design.NeoModeTag
import dev.herdwatch.ui.design.NeoSpinner
import dev.herdwatch.ui.design.NeoShadowBleed
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.term.VtSnapshot
import dev.herdwatch.ui.theme.LocalTerminalTokens
import kotlin.math.roundToInt
import dev.herdwatch.ui.motionMillis

/**
 * A real interactive SSH shell. Herdr is not involved: this renders the PTY stream directly,
 * so any SSH host works here. Supervision (agent status, approvals, snapshots) stays on the
 * Herdr pages.
 */
/** Glyph run used to derive the monospace advance width without per-glyph rounding. */
private const val PROBE_COLUMNS = 40

/** How long the grid must hold still before the PTY is told about a new size. */
private const val RESIZE_SETTLE_MS = 220L

@Composable
fun TerminalScreen(
    state: TerminalUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onInput: (String, Boolean) -> Unit,
    onKey: (String) -> Unit,
    onResize: (Int, Int) -> Unit,
    onFontSize: (Float) -> Unit,
    onFollow: () -> Unit,
    onTrustFingerprint: () -> Unit,
) {
    val term = LocalTerminalTokens.current
    val clipboard = LocalClipboardManager.current
    // The system keyboard is the terminal keyboard. The extra Esc/Ctrl/arrows strip starts out of
    // the way and can be opened when needed; it is not a replacement command composer.
    var keyboardExpanded by rememberSaveable { mutableStateOf(false) }
    var gridScrolledUp by remember { mutableStateOf(false) }
    var terminalInputView by remember { mutableStateOf<TerminalInputView?>(null) }
    val requestTerminalInput = { if (state.connected) terminalInputView?.requestInput() }
    val showKeyboardLabel = stringResource(R.string.terminal_show_keyboard)
    val hideKeysDesc = stringResource(R.string.terminal_keys_collapse_desc)
    val showKeysDesc = stringResource(R.string.terminal_keys_expand_desc)

    // Clipboard paste goes out through the ordinary key/write path. When the remote turned on
    // bracketed paste (DECSET 2004) a multi-line payload is wrapped so editors receive it
    // atomically instead of running each line as its own command.
    val pasteFromClipboard = paste@{
        val text = clipboard.getText()?.text ?: return@paste
        if (text.isEmpty() || !state.connected) return@paste
        onKey(bracketedPastePayload(text, state.snapshot?.bracketedPaste == true))
        onFollow()
    }

    // The status bar inset belongs to this page, not to the app scaffold: the terminal paints its
    // own background edge to edge, so insetting the whole tree would leave a light band above it.
    Column(
        Modifier.fillMaxSize().background(term.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        TerminalBar(
            state = state,
            onBack = onBack,
            onReconnect = onReconnect,
            onFontSize = onFontSize,
            onPaste = pasteFromClipboard,
            keysExpanded = keyboardExpanded,
            onToggleKeys = { keyboardExpanded = !keyboardExpanded },
        )
        Box(
            Modifier.weight(1f).fillMaxWidth().clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClickLabel = showKeyboardLabel,
                onClick = requestTerminalInput,
            ),
        ) {
            val snapshot = state.snapshot
            if (snapshot != null && snapshot.lines.isNotEmpty()) {
                TerminalGrid(
                    snapshot = snapshot,
                    fontSize = state.fontSize,
                    followRequests = state.followRequests,
                    onResize = onResize,
                    onFollow = onFollow,
                    onScrolledUp = { gridScrolledUp = it },
                )
            } else if (state.error == null || state.connected) {
                // With a failure card in the middle, the empty-state text behind it printed the
                // same target twice and left a stray cursor block at the top of the screen.
                EmptyTerminal(state)
            }
            if (state.connecting) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .background(term.muted.copy(alpha = 0.16f), RoundedCornerShape(DsRadius.key))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeoSpinner(Modifier.size(12.dp), color = term.accent, strokeWidth = 1.6.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.terminal_connecting), color = term.text, style = DsTypography.caption)
                }
            }
            // This transparent field is only an IME bridge. The remote PTY draws the actual cursor
            // and input, so there must be no local command field, Send button, or duplicated text.
            TerminalImeCapture(
                enabled = state.connected,
                sessionNonce = state.sessionNonce,
                applicationCursorKeys = state.snapshot?.applicationCursorKeys == true,
                onKey = { onKey(it); onFollow() },
                onView = { terminalInputView = it },
                modifier = Modifier.align(Alignment.BottomStart),
            )
            state.error?.takeIf { !state.connected }?.let { message ->
                Column(
                    Modifier.align(Alignment.Center).padding(20.dp)
                        .clip(RoundedCornerShape(DsRadius.card))
                        .background(term.background)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.terminal_connection_lost), color = term.risk, style = DsTypography.headline)
                    Text(message, color = term.text, style = DsTypography.body)
                    NeoButton(
                        label = stringResource(R.string.common_reconnect),
                        onClick = onReconnect,
                        tone = NeoButtonTone.Primary,
                    )
                }
            }
        }
        // The two terminal-only controls overlay the bottom edge instead of permanently taking a
        // row away from the PTY. The system keyboard itself opens by tapping anywhere in the grid.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = DsSpace.s, vertical = DsSpace.xxs),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TerminalScrollPill(visible = gridScrolledUp, onFollow = onFollow)
            Spacer(Modifier.width(DsSpace.xxs))
            TerminalOverlayPill(
                glyph = NeoGlyph.Keyboard,
                description = if (keyboardExpanded) hideKeysDesc else showKeysDesc,
                onClick = { keyboardExpanded = !keyboardExpanded; requestTerminalInput() },
                active = keyboardExpanded,
            )
        }
        Box(Modifier.fillMaxWidth().background(term.background)) {
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 10.dp),
            ) {
                // The key bar toggle lives in the overflow menu and a small floating action sits
                // on the grid itself. A whole row above the keys ("Hide keys · 47×28") spent 48dp
                // on a debug readout and pushed the terminal up for no benefit.
                AnimatedVisibility(
                    visible = keyboardExpanded,
                    enter = fadeIn(tween(motionMillis(120))) + expandVertically(tween(motionMillis(160))),
                    exit = fadeOut(tween(motionMillis(100))) + shrinkVertically(tween(motionMillis(140))),
                ) {
                    TerminalKeys(
                        onKey = onKey,
                        enabled = state.connected,
                        // Both rows, always: ^C / ^D / arrows are not an option the user should
                        // have to discover, and a one-row bar hid the second row behind a default.
                        secondRow = true,
                        applicationCursorKeys = state.snapshot?.applicationCursorKeys == true,
                    )
                }
                Spacer(Modifier.size(6.dp))
            }
        }
    }

    state.pendingFingerprint?.let { fingerprint ->
        HostFingerprintDialog(
            fingerprint = fingerprint,
            keyType = state.pendingKeyType,
            hostLabel = state.hostLabel.ifBlank { state.target },
            onCancel = onBack,
            onTrust = onTrustFingerprint,
        )
    }
}

@Composable
private fun TerminalBar(
    state: TerminalUiState,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    onFontSize: (Float) -> Unit,
    onPaste: () -> Unit,
    keysExpanded: Boolean,
    onToggleKeys: () -> Unit,
) {
    val term = LocalTerminalTokens.current
    var menu by remember { mutableStateOf(false) }
    val fontSizeLabel = stringResource(R.string.terminal_font_size, state.fontSize.roundToInt())
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconButton(
            glyph = NeoGlyph.Back,
            onClick = onBack,
            contentDescription = stringResource(R.string.terminal_back_desc),
            tint = term.text,
        )
        // The title column owns all the leftover width and everything inside it ellipsises.
        // Letting the column shrink to its content instead gave the target ~60dp ("li…"), because
        // the pip and the two icon buttons were measured first.
        Column(Modifier.weight(1f)) {
            Text(
                state.hostLabel.ifBlank { state.target },
                color = term.text,
                style = DsTypography.headline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeoModeTag(stringResource(R.string.mode_terminal_tag))
                Spacer(Modifier.width(DsSpace.xs))
                ConnectionPip(state, Modifier.weight(1f, fill = false))
            }
        }
        // Icon only: the word "Reconnect" between the pip and the overflow menu had no room at
        // 360dp and wrapped into two overlapping lines.
        NeoIconButton(
            glyph = NeoGlyph.Refresh,
            onClick = onReconnect,
            contentDescription = stringResource(R.string.terminal_reconnect_short),
            tint = term.text,
            enabled = !state.connecting,
        )
        Box {
            NeoIconButton(
                glyph = NeoGlyph.More,
                onClick = { menu = true },
                contentDescription = stringResource(R.string.terminal_options_desc),
                tint = term.text,
            )
            NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                NeoMenuItem(
                    label = stringResource(R.string.terminal_font_larger),
                    onClick = { onFontSize(state.fontSize + 1f) },
                )
                NeoMenuItem(
                    label = stringResource(R.string.terminal_font_smaller),
                    onClick = { onFontSize(state.fontSize - 1f) },
                )
                NeoMenuItem(
                    label = fontSizeLabel,
                    onClick = { onFontSize(TerminalUiState.DEFAULT_FONT_SIZE) },
                    checked = state.fontSize == TerminalUiState.DEFAULT_FONT_SIZE,
                )
                NeoMenuItem(
                    label = stringResource(
                        if (keysExpanded) R.string.terminal_keys_hide_always else R.string.terminal_keys_show_always,
                    ),
                    onClick = { menu = false; onToggleKeys() },
                    checked = keysExpanded,
                )
                NeoMenuItem(
                    label = stringResource(R.string.terminal_paste),
                    onClick = { menu = false; onPaste() },
                )
            }
        }
    }
}

@Composable
private fun ConnectionPip(state: TerminalUiState, modifier: Modifier = Modifier) {
    val term = LocalTerminalTokens.current
    val color = when {
        state.connecting -> term.muted
        state.connected -> ds().ok
        else -> term.risk
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, RoundedCornerShape(DsRadius.pill)))
        Spacer(Modifier.width(5.dp))
        Text(
            when {
                state.connecting -> stringResource(R.string.terminal_state_connecting)
                state.connected -> stringResource(R.string.terminal_state_connected)
                else -> stringResource(R.string.terminal_state_disconnected)
            },
            color = term.muted,
            style = DsTypography.micro,
            maxLines = 1,
        )
    }
}

@Composable
private fun EmptyTerminal(state: TerminalUiState) {
    val term = LocalTerminalTokens.current
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(state.target, color = term.text, style = DsTypography.body)
        Spacer(Modifier.size(8.dp))
        Text(
            state.error ?: if (state.connecting) {
                stringResource(R.string.terminal_ssh_starting)
            } else {
                stringResource(R.string.terminal_no_output)
            },
            color = term.muted,
            style = DsTypography.body,
        )
    }
}

@Composable
private fun TerminalGrid(
    snapshot: VtSnapshot,
    fontSize: Float,
    followRequests: Long,
    onResize: (Int, Int) -> Unit,
    onFollow: () -> Unit,
    onScrolledUp: (Boolean) -> Unit,
) {
    // No bottom reserve for controls: the screen already lays out its own strip above this grid,
    // and reserving a second one here would spend that height twice.
    val term = LocalTerminalTokens.current
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    val lineHeightSp = fontSize * 1.3f
    val lineHeight = with(density) { lineHeightSp.sp.toDp() }
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        lineHeight = lineHeightSp.sp,
        color = term.text,
    )

    // Measure the real advance width once per font size, and measure a full run of glyphs: the
    // single-glyph result is rounded to whole pixels, and that ~1% error accumulates across 46
    // columns until the right edge becomes unreachable.
    val probeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSize) {
        (measurer.measure("M".repeat(PROBE_COLUMNS), probeStyle).size.width.toFloat() / PROBE_COLUMNS)
            .coerceAtLeast(1f)
    }

    // Inset OUTSIDE the measuring box so maxWidth is already the usable width, and the same inset
    // is not applied again inside the scroller (doing both hid the last columns permanently).
    BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        val charWidth = with(density) { charWidthPx.toDp() }
        // No horizontal inset here: any inset has to be panned past, and a fractional column left
        // over would clip the final character. Edge breathing room comes from the glyph box itself.
        // One column of slack: glyph advances are fractional, so the measured estimate can be a
        // hair short and the last character would sit past the end of the pan range.
        val cols = (maxWidth / charWidth).toInt().coerceAtLeast(20)
        val rows = (maxHeight / lineHeight).toInt()
        // Debounced: while the keyboard slides in, height changes one row at a time and each
        // change would emit a TIOCSWINSZ. A full-screen TUI redraws on every one of those, and the
        // resize notices pile up in scrollback. Waiting for the layout to settle sends exactly one.
        LaunchedEffect(cols, rows) {
            if (cols <= 0 || rows <= 0) return@LaunchedEffect
            delay(RESIZE_SETTLE_MS)
            onResize(cols, rows)
        }

        val gridScroll = rememberScrollState()
        val stickToBottom by remember { derivedStateOf { !listState.canScrollForward } }
        LaunchedEffect(stickToBottom) { onScrolledUp(!stickToBottom) }

        // Follow new output only while the user is already at the bottom.
        //
        // A full-screen program (vim, less, htop, tmux) owns the viewport: its grid is exactly the
        // PTY size, and "scroll to the last row" pushed the whole screen off the top so the app
        // looked blank. While the alternate screen is up we therefore render ONLY that grid, with
        // no scrollback above it — which is what a real terminal does — and keep the top row pinned.
        val fullScreen = snapshot.alternateScreen
        val visibleLines = if (fullScreen) snapshot.lines.takeLast(snapshot.rows) else snapshot.lines
        val visibleLast = visibleLines.lastIndex
        LaunchedEffect(snapshot.revision, stickToBottom, fullScreen) {
            if (fullScreen) {
                if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            } else if (stickToBottom && visibleLast >= 0) {
                listState.scrollToItem(visibleLast)
            }
        }
        LaunchedEffect(followRequests, fullScreen) {
            if (fullScreen) listState.scrollToItem(0) else if (visibleLast >= 0) listState.scrollToItem(visibleLast)
        }

        // Long lines must stay readable: the grid is as wide as the PTY, which on a phone is wider
        // than the viewport, so the whole grid pans horizontally instead of clipping the right edge.
        // One shared scroll state keeps every row aligned — per-row scrolling would tear columns.
        // Width comes from the measured font metrics, not the sizing estimate: a few percent of
        // drift over 47 columns would leave the last character permanently clipped.
        val gridWidth = charWidthPx * cols
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().horizontalScroll(gridScroll),
            ) {
                itemsIndexed(visibleLines) { _, line ->
                    Text(
                        text = line.annotated(term.text, term.background),
                        style = textStyle,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.width(with(density) { gridWidth.toDp() }),
                    )
                }
            }
        }


    }
}

/**
 * The terminal key bar. Row one is the everyday set; row two holds the destructive/rare keys so they
 * are harder to hit by accident. Holding a repeatable key emits a short burst rather than a stream,
 * so a long hold cannot flood a remote prompt.
 */
@OptIn(ExperimentalFoundationApi::class)
/** Jump-to-latest, shown only while the user is reading further up. */
@Composable
private fun TerminalScrollPill(visible: Boolean, onFollow: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(motionMillis(120))),
        exit = fadeOut(tween(motionMillis(100))),
    ) {
        TerminalOverlayPill(
            glyph = NeoGlyph.ChevronDown,
            description = stringResource(R.string.terminal_scroll_to_bottom),
            onClick = onFollow,
        )
    }
}

/**
 * One 40dp control in the strip under the grid: icon only, translucent terminal ink.
 *
 * Icon-only is safe because both actions also exist in the overflow menu with words, and the pill
 * carries a full contentDescription for TalkBack.
 */
@Composable
private fun TerminalOverlayPill(
    glyph: NeoGlyph,
    description: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val tokens = LocalTerminalTokens.current
    Box(
        Modifier
            .size(40.dp)
            .clip(RoundedCornerShape(DsRadius.key))
            .background(tokens.text.copy(alpha = if (active) 0.14f else 0.07f))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        NeoIcon(glyph = glyph, modifier = Modifier.size(18.dp), tint = if (active) tokens.accent else tokens.muted)
    }
}

@Composable
private fun TerminalKeys(
    onKey: (String) -> Unit,
    enabled: Boolean,
    secondRow: Boolean,
    applicationCursorKeys: Boolean,
) {
    var ctrl by rememberSaveable { mutableStateOf(false) }
    val ctrlDesc = stringResource(R.string.terminal_key_ctrl)
    val shiftTabLabel = stringResource(R.string.terminal_key_shift_tab)
    // A key bar that scrolls sideways hid PgDn behind the screen edge with no hint that anything
    // was there. Keys now share the row width, so every key is visible and tappable at once.

    // Split so each row fits a 360dp screen without scrolling: row 1 edits the line,
    // row 2 sends signals and moves through history/scrolling. Ctrl lives at the head of row 1.
    val primary = listOf(
        TerminalKey("Esc", "", R.string.terminal_key_escape, repeatable = false),
        TerminalKey("Tab", "	", R.string.terminal_key_tab, repeatable = false),
        // Shift+Tab (back-tab): cycles permission modes in agent CLIs, tabs in tmux.
        TerminalKey(shiftTabLabel, "[Z", R.string.terminal_key_shift_tab_a11y, repeatable = false),
        TerminalKey("⌫", "", R.string.terminal_key_backspace),
        TerminalKey("←", arrowKeySequence('D', applicationCursorKeys), R.string.terminal_key_left),
        TerminalKey("→", arrowKeySequence('C', applicationCursorKeys), R.string.terminal_key_right),
    )
    val secondary = listOf(
        TerminalKey("^C", "", R.string.terminal_key_ctrl_c, repeatable = false),
        TerminalKey("^D", "", R.string.terminal_key_ctrl_d, repeatable = false),
        TerminalKey("^Z", "", R.string.terminal_key_ctrl_z, repeatable = false),
        TerminalKey("↑", arrowKeySequence('A', applicationCursorKeys), R.string.terminal_key_up),
        TerminalKey("↓", arrowKeySequence('B', applicationCursorKeys), R.string.terminal_key_down),
        TerminalKey("PgUp", "[5~", R.string.terminal_key_page_up, repeatable = false),
        TerminalKey("PgDn", "[6~", R.string.terminal_key_page_down, repeatable = false),
    )

    val fire: (String) -> Unit = { sequence ->
        if (ctrl) {
            ctrl = false
            val ch = sequence.firstOrNull()?.lowercaseChar()
            if (ch != null && ch in 'a'..'z') onKey((ch - 'a' + 1).toChar().toString()) else onKey(sequence)
        } else {
            onKey(sequence)
        }
    }

    // Keys are lifted surfaces sitting side by side. With a 4dp gap the next key's fill painted
    // over the previous key's highlight, so the second row looked cut off; the gap now includes the
    // shadow's own bleed.
    Column(
        Modifier.fillMaxWidth().padding(vertical = NeoShadowBleed),
        verticalArrangement = Arrangement.spacedBy(NeoShadowBleed + 2.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeoShadowBleed),
        ) {
            TerminalKeyButton(
                label = "Ctrl",
                description = ctrlDesc,
                enabled = enabled,
                latched = ctrl,
                repeatable = false,
                onPress = { ctrl = !ctrl },
                modifier = Modifier.weight(1f),
            )
            primary.forEach { key ->
                val description = stringResource(key.descriptionRes)
                TerminalKeyButton(
                    label = key.label,
                    description = description,
                    enabled = enabled,
                    latched = false,
                    repeatable = key.repeatable,
                    onPress = { fire(key.sequence) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (secondRow) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(NeoShadowBleed),
            ) {
                secondary.forEach { key ->
                    val description = stringResource(key.descriptionRes)
                    TerminalKeyButton(
                        label = key.label,
                        description = description,
                        enabled = enabled,
                        latched = false,
                        repeatable = key.repeatable,
                        onPress = { fire(key.sequence) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TerminalKeyButton(
    label: String,
    description: String,
    enabled: Boolean,
    latched: Boolean,
    repeatable: Boolean,
    onPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val term = LocalTerminalTokens.current
    val scope = rememberCoroutineScope()
    val fill = if (latched) term.accent.copy(alpha = 0.24f) else term.text.copy(alpha = if (enabled) 0.07f else 0.03f)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 40.dp)
            .clip(RoundedCornerShape(DsRadius.key))
            .background(fill)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .combinedClickable(
                enabled = enabled,
                onLongClick = {
                    if (repeatable) {
                        scope.launch {
                            repeat(6) {
                                onPress()
                                delay(70)
                            }
                        }
                    }
                },
                onClick = onPress,
            )
            .padding(horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = DsTypography.micro,
            color = if (enabled) term.text else term.muted,
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** A key-bar button: the label stays a terminal term, the description is localized. */
private data class TerminalKey(
    val label: String,
    val sequence: String,
    val descriptionRes: Int,
    val repeatable: Boolean = true,
)

/** DECCKM switches cursor keys from CSI to SS3 sequences. */
internal fun arrowKeySequence(final: Char, applicationCursorKeys: Boolean): String {
    require(final in "ABCD") { "unsupported cursor key: $final" }
    return if (applicationCursorKeys) "\u001bO$final" else "\u001b[$final"
}

/** What a terminal line editor erases with: DEL (0x7f), not backspace (0x08). */
private const val DIRECT_DELETE = "\u007f"

/**
 * How much of the line the composer keeps as a mirror of the remote one.
 *
 * The mirror only exists so the next edit has something to diff against; past a few dozen
 * characters the extra copy buys nothing and only grows the IME's editing buffer. When the mirror
 * exceeds this budget the field and the watermark are cleared in the same step, so the following
 * edit diffs against an empty mirror (a shorter, safer epoch rather than an unbounded one).
 */
internal const val DIRECT_MIRROR_BUDGET = 24

/**
 * One direct-mode edit translated into PTY bytes.
 *
 * [text] is what the connection must write (DEL bytes for removed characters, then new text),
 * [sent] is the watermark to remember for the next edit — the text the PTY now holds — and
 * [clearField] says the caller must reset both the field and the watermark to empty.
 */
internal data class DirectInputEdit(
    val text: String,
    val sent: String,
    val clearField: Boolean,
)

/**
 * Length of the shared leading run of two strings, in UTF-16 code units.
 *
 * The result never lands between the halves of a supplementary code point (an emoji, for one): a
 * shared prefix that stops after a lone high surrogate would leave half a character on each side,
 * and the PTY has no way to erase that half by itself.
 */
private fun commonPrefixLength(a: String, b: String): Int {
    val limit = minOf(a.length, b.length)
    var index = 0
    while (index < limit && a[index] == b[index]) index++
    if (index > 0 && index < limit &&
        Character.isHighSurrogate(a[index - 1]) && Character.isLowSurrogate(a[index])
    ) {
        index--
    }
    return index
}

/** Code points in [text]: a supplementary character is one character on the remote line. */
private fun codePointLength(text: String): Int = text.codePointCount(0, text.length)

/**
 * The bytes that make the PTY mirror [text] given the [sent] watermark: erase what the watermark
 * holds beyond the shared prefix, then append the new suffix.
 *
 * Erasure counts code points, not UTF-16 code units — deleting one emoji must emit one DEL, not two.
 */
private fun mirrorDiff(sent: String, text: String): String {
    val shared = commonPrefixLength(sent, text)
    val erase = codePointLength(sent.substring(shared))
    return DIRECT_DELETE.repeat(erase) + text.substring(shared)
}

/**
 * Direct-mode transmission is a diff, never a replay.
 *
 * [sent] is the watermark: the text the PTY already holds for the current field epoch. Only what
 * the field now shows beyond the longest common prefix with [sent] is new, and a shortened field
 * means that many characters have to be erased first. An IME that re-delivers a value we already
 * transmitted therefore produces an empty diff instead of a second copy of those characters — the
 * old sentinel code sent the whole committed prefix on every edit, so any repeat of an already
 * transmitted value was transmitted again.
 *
 * Nothing here rewrites the field's text, which is what kept the IME's editing session intact:
 * replacing the value with a truncated one made the IME re-deliver its pre-reset buffer.
 *
 * An unfinished composition is never transmitted: while `next.composition != null` the watermark
 * stays put and the committed characters go out on the edit that clears the composition.
 */
internal fun directInputEdit(sent: String, next: TextFieldValue): DirectInputEdit {
    if (next.composition != null) return DirectInputEdit(text = "", sent = sent, clearField = false)
    val text = next.text
    val outgoing = mirrorDiff(sent, text)
    return if (text.length > DIRECT_MIRROR_BUDGET) {
        DirectInputEdit(text = outgoing, sent = "", clearField = true)
    } else {
        DirectInputEdit(text = outgoing, sent = text, clearField = false)
    }
}

/** The committed part of a field value: everything the IME is not still composing. */
private fun committedInputText(value: TextFieldValue): String {
    val composition = value.composition ?: return value.text
    val start = composition.min.coerceIn(0, value.text.length)
    val end = composition.max.coerceIn(start, value.text.length)
    return value.text.removeRange(start, end)
}

/**
 * Enter in direct mode: flush only what the user actually committed, then press return.
 *
 * Unfinished pinyin is not text the user has chosen yet, so with a live composition nothing is
 * sent and the composition is kept for the next Enter. The committed part is diffed like any other
 * edit, so a mirror that already matches cannot duplicate it, and an empty mirror still sends the
 * bare carriage return.
 */
internal fun directSubmitEdit(sent: String, next: TextFieldValue): DirectInputEdit {
    val committed = committedInputText(next)
    val flushed = mirrorDiff(sent, committed)
    return if (next.composition != null) {
        DirectInputEdit(text = flushed, sent = committed, clearField = false)
    } else {
        DirectInputEdit(text = flushed + "\r", sent = "", clearField = true)
    }
}

@Composable
private fun TerminalImeCapture(
    enabled: Boolean,
    sessionNonce: Long,
    applicationCursorKeys: Boolean,
    onKey: (String) -> Unit,
    onView: (TerminalInputView) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context -> TerminalInputView(context).also(onView) },
        update = { view ->
            view.isEnabled = enabled
            view.onBytes = onKey
            view.applicationCursorKeys = { applicationCursorKeys }
            // A new SSH channel must not inherit the previous line, composition, or a commit the
            // keyboard queued for the old session.
            if (view.tag != sessionNonce) {
                view.tag = sessionNonce
                view.resetSession()
            }
            onView(view)
        },
        // The bridge is a transport, not content: TalkBack must not stop on an invisible editor.
        modifier = modifier.size(1.dp).alpha(0.01f).clearAndSetSemantics { },
    )
}

/**
 * Clipboard payload as it must be written to the PTY.
 *
 * With bracketed paste on (DECSET 2004, mirrored on [VtSnapshot.bracketedPaste]) every paste is
 * wrapped in ESC[200~ .. ESC[201~ so the remote application can distinguish pasted text from keys.
 */
internal fun bracketedPastePayload(text: String, bracketedPaste: Boolean): String =
    if (bracketedPaste) "\u001b[200~" + text + "\u001b[201~" else text

/**
 * First connection to a host without a saved pin: non-dismissible until the user decides.
 *
 * The guard is deliberate: a scrim tap or the back key may neither trust an unknown key nor drop
 * the session quietly. [NeoDialog] routes both of those through `onDismissRequest` — and so does
 * its own `dismissLabel` button — which is why that callback stays a no-op and the two actions are
 * rendered explicitly here. Cancel is then the only way out and Trust the only way forward.
 */
@Composable
private fun HostFingerprintDialog(
    fingerprint: String,
    keyType: String?,
    hostLabel: String,
    onCancel: () -> Unit,
    onTrust: () -> Unit,
) {
    val colors = ds()
    NeoDialog(
        onDismissRequest = {},
        title = stringResource(R.string.terminal_fingerprint_title),
    ) {
        Text(
            stringResource(R.string.terminal_fingerprint_body, hostLabel),
            style = DsTypography.body,
            color = colors.textSoft,
        )
        Text(
            // Name the algorithm: a host has several keys and ssh-keygen prints this word too.
            dev.herdwatch.ssh.hostKeyLabel(keyType).takeIf { it.isNotBlank() }?.let {
                stringResource(R.string.terminal_fingerprint_label_typed, it)
            } ?: stringResource(R.string.terminal_fingerprint_fingerprint_label),
            style = DsTypography.micro,
            color = colors.textSoft,
        )
        NeoWell(fill = colors.well, depth = DsElev.flat, corner = DsRadius.control) {
            SelectionContainer {
                Text(
                    fingerprint,
                    style = DsTypography.mono,
                    color = colors.text,
                    modifier = Modifier.padding(horizontal = DsSpace.m, vertical = DsSpace.s),
                )
            }
        }
        Text(
            stringResource(R.string.terminal_fingerprint_security),
            style = DsTypography.body,
            color = colors.textSoft,
        )
        // Same rule as the key-change dialog: a security decision gets full-width buttons so the
        // label is never ellipsised in English or at a large font scale.
        Column(
            Modifier.fillMaxWidth().padding(top = DsSpace.xs),
            verticalArrangement = Arrangement.spacedBy(DsSpace.xs),
        ) {
            NeoButton(
                label = stringResource(R.string.terminal_fingerprint_trust),
                onClick = onTrust,
                tone = NeoButtonTone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            NeoButton(
                label = stringResource(R.string.terminal_fingerprint_cancel),
                onClick = onCancel,
                tone = NeoButtonTone.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Maps a run's xterm colour index (or packed truecolour) to a Compose colour. */
internal fun xtermColor(value: Int, default: Color): Color {
    if (value and 0x01000000 != 0) {
        return Color(0xFF000000L or (value and 0xFFFFFF).toLong())
    }
    return when (value) {
        in 0..15 -> TERM_PALETTE[value]
        in 16..231 -> {
            val i = value - 16
            val r = i / 36
            val g = (i % 36) / 6
            val b = i % 6
            Color(0xFF000000L or (cube(r) shl 16) or (cube(g) shl 8) or cube(b))
        }
        in 232..255 -> {
            val level = 8 + (value - 232) * 10
            Color(0xFF000000L or (level.toLong() shl 16) or (level.toLong() shl 8) or level.toLong())
        }
        else -> default
    }
}

private fun cube(v: Int): Long = if (v == 0) 0 else (55 + v * 40).toLong()

private val TERM_PALETTE = listOf(
    Color(0xFF1B1F27), Color(0xFFE06C75), Color(0xFF98C379), Color(0xFFE5C07B),
    Color(0xFF61AFEF), Color(0xFFC678DD), Color(0xFF56B6C2), Color(0xFFC7CCD6),
    Color(0xFF5C6370), Color(0xFFFF7B86), Color(0xFFB5E890), Color(0xFFFFD68A),
    Color(0xFF7CC4FF), Color(0xFFDDA0F0), Color(0xFF6FD3DE), Color(0xFFFFFFFF),
)

/** Renders one terminal line, preserving per-run colour and emphasis. */
internal fun dev.herdwatch.term.VtLine.annotated(
    defaultText: Color,
    defaultBackground: Color,
): AnnotatedString = buildAnnotatedString {
    runs.forEach { run ->
        withStyle(run.spanStyle(defaultText, defaultBackground)) { append(run.text) }
    }
}

internal fun VtRun.spanStyle(defaultText: Color, defaultBackground: Color = Color.Transparent): SpanStyle {
    val foreground = fg?.let { xtermColor(it, defaultText) } ?: defaultText
    val background = bg?.let { xtermColor(it, defaultBackground) } ?: defaultBackground
    val effectiveForeground = if (inverse) background else foreground
    val effectiveBackground = if (inverse) foreground else background
    return SpanStyle(
        color = if (dim) effectiveForeground.copy(alpha = 0.7f) else effectiveForeground,
        background = effectiveBackground,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
    )
}
