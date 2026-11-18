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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import dev.herdwatch.R
import dev.herdwatch.data.InputMode
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
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.term.VtSnapshot
import dev.herdwatch.ui.theme.LocalTerminalTokens
import kotlin.math.roundToInt

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
    /** LINE buffers a whole line in the composer; DIRECT streams every committed character. */
    inputMode: InputMode = InputMode.LINE,
    /** Reports a mode change; the host screen persists it (see App.kt wiring). */
    onInputMode: (InputMode) -> Unit = {},
    /** 1 = the classic single row; 2 adds a second row with ^C / ^D / ^Z / PgUp / PgDn. */
    keyBarRows: Int = 1,
    /** Hold an arrow key to repeat it (a short burst, not a stream). */
    longPressRepeat: Boolean = true,
) {
    val term = LocalTerminalTokens.current
    val clipboard = LocalClipboardManager.current
    var keyboardExpanded by rememberSaveable { mutableStateOf(true) }
    val collapseKeysLabel = stringResource(R.string.terminal_keys_collapse)
    val expandKeysLabel = stringResource(R.string.terminal_keys_expand)
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
            inputMode = inputMode,
            onInputMode = onInputMode,
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val snapshot = state.snapshot
            if (snapshot != null && snapshot.lines.isNotEmpty()) {
                TerminalGrid(
                    snapshot = snapshot,
                    fontSize = state.fontSize,
                    followRequests = state.followRequests,
                    onResize = onResize,
                    onFollow = onFollow,
                )
            } else {
                EmptyTerminal(state)
            }
            if (state.connecting) {
                Row(
                    Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                        .background(term.muted.copy(alpha = 0.16f), RoundedCornerShape(DsRadius.key))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = term.accent, strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.terminal_connecting), color = term.text, style = DsTypography.caption)
                }
            }
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
        Box(Modifier.fillMaxWidth().background(term.background)) {
            Column(
                Modifier.fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                    .padding(horizontal = 10.dp),
            ) {
                // The key bar toggle lives in the overflow menu and a small floating action sits
                // on the grid itself. A whole row above the keys ("Hide keys · 47×28") spent 48dp
                // on a debug readout and pushed the terminal up for no benefit.
                Box(Modifier.fillMaxWidth()) {
                    TerminalKeysToggle(
                        expanded = keyboardExpanded,
                        label = if (keyboardExpanded) collapseKeysLabel else expandKeysLabel,
                        description = if (keyboardExpanded) hideKeysDesc else showKeysDesc,
                        onClick = { keyboardExpanded = !keyboardExpanded },
                    )
                }
                AnimatedVisibility(
                    visible = keyboardExpanded,
                    enter = fadeIn(tween(120)) + expandVertically(tween(160)),
                    exit = fadeOut(tween(100)) + shrinkVertically(tween(140)),
                ) {
                    TerminalKeys(
                        onKey = onKey,
                        enabled = state.connected,
                        secondRow = keyBarRows >= 2,
                        longPressRepeat = longPressRepeat,
                    )
                }
                if (inputMode == InputMode.DIRECT) {
                    Text(
                        stringResource(R.string.terminal_direct_hint),
                        color = term.muted,
                        style = DsTypography.micro,
                        modifier = Modifier.padding(bottom = 2.dp),
                    )
                }
                TerminalInputBar(
                    enabled = state.connected,
                    inputMode = inputMode,
                    onInput = onInput,
                    onKey = onKey,
                    onFollow = onFollow,
                )
                Spacer(Modifier.size(6.dp))
            }
        }
    }

    state.pendingFingerprint?.let { fingerprint ->
        HostFingerprintDialog(
            fingerprint = fingerprint,
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
    inputMode: InputMode,
    onInputMode: (InputMode) -> Unit,
) {
    val term = LocalTerminalTokens.current
    var menu by remember { mutableStateOf(false) }
    var showKeys by remember { mutableStateOf(false) }
    val alternateScreenSuffix = stringResource(R.string.terminal_subtitle_alt_screen, state.target)
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
        Column(Modifier.weight(1f)) {
            Text(
                state.hostLabel.ifBlank { state.target },
                color = term.text,
                style = DsTypography.headline,
                maxLines = 1,
            )
            Text(
                if (state.snapshot?.alternateScreen == true) alternateScreenSuffix else state.target,
                color = term.muted,
                style = DsTypography.micro,
                maxLines = 1,
            )
        }
        ConnectionPip(state)
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
                        if (showKeys) R.string.terminal_keys_hide_always else R.string.terminal_keys_show_always,
                    ),
                    onClick = { showKeys = !showKeys; menu = false },
                    checked = showKeys,
                )
                NeoMenuItem(
                    label = stringResource(R.string.terminal_paste),
                    onClick = { menu = false; onPaste() },
                )
                NeoMenuDivider()
                NeoMenuItem(
                    label = stringResource(R.string.terminal_input_line),
                    onClick = { menu = false; onInputMode(InputMode.LINE) },
                    checked = inputMode == InputMode.LINE,
                )
                NeoMenuItem(
                    label = stringResource(R.string.terminal_input_direct),
                    onClick = { menu = false; onInputMode(InputMode.DIRECT) },
                    checked = inputMode == InputMode.DIRECT,
                )
            }
        }
    }
}

@Composable
private fun ConnectionPip(state: TerminalUiState) {
    val term = LocalTerminalTokens.current
    val color = when {
        state.connecting -> term.muted
        state.connected -> ds().ok
        else -> term.risk
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
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
) {
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
        val lastIndex = snapshot.lines.lastIndex
        val stickToBottom by remember { derivedStateOf { !listState.canScrollForward } }

        suspend fun scrollToEnd() {
            if (lastIndex >= 0) listState.scrollToItem(lastIndex)
        }

        // Follow new output only while the user is already at the bottom.
        LaunchedEffect(snapshot.revision, stickToBottom) {
            if (stickToBottom && lastIndex >= 0) {
                listState.scrollToItem(lastIndex)
            }
        }
        LaunchedEffect(followRequests) { scrollToEnd() }

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
                itemsIndexed(snapshot.lines) { _, line ->
                    Text(
                        text = line.annotated(term.text),
                        style = textStyle,
                        softWrap = false,
                        maxLines = 1,
                        modifier = Modifier.width(with(density) { gridWidth.toDp() }),
                    )
                }
            }
        }

        if (!stickToBottom) {
            Box(Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
                TerminalChromeButton(
                    label = stringResource(R.string.terminal_scroll_to_bottom),
                    onClick = onFollow,
                )
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
/**
 * Small, quiet affordance that replaces the old full-width "Hide keys" row.
 *
 * It sits on the right edge of the dock, keeps a 44dp target and reads as part of the terminal
 * chrome instead of a page section.
 */
/** Quiet action drawn on the terminal surface: 44dp tall, terminal ink, no Material chrome. */
@Composable
private fun TerminalChromeButton(label: String, onClick: () -> Unit) {
    val tokens = LocalTerminalTokens.current
    Box(
        Modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(DsRadius.key))
            .background(tokens.background.copy(alpha = 0.88f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = DsSpace.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DsTypography.body, color = tokens.accent, maxLines = 1)
    }
}

@Composable
private fun TerminalKeysToggle(
    expanded: Boolean,
    label: String,
    description: String,
    onClick: () -> Unit,
) {
    val tokens = LocalTerminalTokens.current
    Row(Modifier.fillMaxWidth().padding(top = DsSpace.xxs), horizontalArrangement = Arrangement.End) {
        Row(
            modifier = Modifier
                .heightIn(min = 44.dp)
                .clip(RoundedCornerShape(DsRadius.key))
                .clickable(role = Role.Button, onClickLabel = description, onClick = onClick)
                .padding(horizontal = DsSpace.m)
                .semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpace.xs),
        ) {
            NeoIcon(
                glyph = if (expanded) NeoGlyph.ChevronDown else NeoGlyph.Keyboard,
                modifier = Modifier.size(16.dp),
                tint = tokens.muted,
            )
            Text(label, style = DsTypography.caption, color = tokens.muted, maxLines = 1)
        }
    }
}

@Composable
private fun TerminalKeys(
    onKey: (String) -> Unit,
    enabled: Boolean,
    secondRow: Boolean,
    longPressRepeat: Boolean,
) {
    var ctrl by rememberSaveable { mutableStateOf(false) }
    val ctrlDesc = stringResource(R.string.terminal_key_ctrl)
    val shiftTabLabel = stringResource(R.string.terminal_key_shift_tab)

    // Split so each row fits a 360dp screen without scrolling: row 1 edits the line,
    // row 2 sends signals and moves through history/scrolling. Ctrl lives at the head of row 1.
    val primary = listOf(
        TerminalKey("Esc", "", R.string.terminal_key_escape, repeatable = false),
        TerminalKey("Tab", "	", R.string.terminal_key_tab, repeatable = false),
        // Shift+Tab (back-tab): cycles permission modes in agent CLIs, tabs in tmux.
        TerminalKey(shiftTabLabel, "[Z", R.string.terminal_key_shift_tab_a11y, repeatable = false),
        TerminalKey("⌫", "", R.string.terminal_key_backspace),
        TerminalKey("←", "[D", R.string.terminal_key_left),
        TerminalKey("→", "[C", R.string.terminal_key_right),
    )
    val secondary = listOf(
        TerminalKey("^C", "", R.string.terminal_key_ctrl_c, repeatable = false),
        TerminalKey("^D", "", R.string.terminal_key_ctrl_d, repeatable = false),
        TerminalKey("^Z", "", R.string.terminal_key_ctrl_z, repeatable = false),
        TerminalKey("↑", "[A", R.string.terminal_key_up),
        TerminalKey("↓", "[B", R.string.terminal_key_down),
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

    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TerminalKeyButton(
                label = "Ctrl",
                description = ctrlDesc,
                enabled = enabled,
                latched = ctrl,
                repeatable = false,
                longPressRepeat = false,
                onPress = { ctrl = !ctrl },
            )
            primary.forEach { key ->
                val description = stringResource(key.descriptionRes)
                TerminalKeyButton(
                    label = key.label,
                    description = description,
                    enabled = enabled,
                    latched = false,
                    repeatable = key.repeatable,
                    longPressRepeat = longPressRepeat,
                    onPress = { fire(key.sequence) },
                )
            }
        }
        if (secondRow) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                secondary.forEach { key ->
                    val description = stringResource(key.descriptionRes)
                    TerminalKeyButton(
                        label = key.label,
                        description = description,
                        enabled = enabled,
                        latched = false,
                        repeatable = key.repeatable,
                        longPressRepeat = longPressRepeat,
                        onPress = { fire(key.sequence) },
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
    longPressRepeat: Boolean,
    onPress: () -> Unit,
) {
    val term = LocalTerminalTokens.current
    val scope = rememberCoroutineScope()
    val fill = if (latched) term.accent.copy(alpha = 0.24f) else term.text.copy(alpha = if (enabled) 0.07f else 0.03f)
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .widthIn(min = 44.dp)
            .clip(RoundedCornerShape(DsRadius.key))
            .background(fill)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .combinedClickable(
                enabled = enabled,
                onLongClick = {
                    if (repeatable && longPressRepeat) {
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
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DsTypography.micro, color = if (enabled) term.text else term.muted)
    }
}

/** A key-bar button: the label stays a terminal term, the description is localized. */
private data class TerminalKey(
    val label: String,
    val sequence: String,
    val descriptionRes: Int,
    val repeatable: Boolean = true,
)

@Composable
private fun TerminalInputBar(
    enabled: Boolean,
    inputMode: InputMode,
    onInput: (String, Boolean) -> Unit,
    onKey: (String) -> Unit,
    onFollow: () -> Unit,
) {
    val term = LocalTerminalTokens.current
    val direct = inputMode == InputMode.DIRECT
    // TextFieldValue (not String) so direct mode can see `composition` and hold back pinyin that
    // the IME has not committed yet: sending half-formed glyphs would garble the remote line.
    var entry by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    val inputDesc = stringResource(R.string.terminal_input_desc)
    val placeholder = stringResource(R.string.terminal_input_placeholder)
    val sendDesc = stringResource(R.string.terminal_send)

    fun submit() {
        // In direct mode the field is a clear-as-you-send buffer, so this is normally a bare Enter;
        // a leftover buffer (mode switched mid-line) ships in the same write to keep the order.
        // Text the IME is still composing is dropped rather than leaking pinyin to the PTY.
        if (direct) {
            val pending = if (entry.composition == null) entry.text else ""
            onInput(pending + "\r", false)
            entry = TextFieldValue("")
            onFollow()
            return
        }
        if (entry.text.isEmpty()) {
            onKey("\r")
            return
        }
        onInput(entry.text, true)
        entry = TextFieldValue("")
        onFollow()
    }

    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp)
            .background(term.muted.copy(alpha = 0.14f), RoundedCornerShape(DsRadius.key))
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = entry,
            onValueChange = onChange@{ next ->
                if (!direct) {
                    entry = next
                    return@onChange
                }
                // Direct mode: nothing is sent while the IME is composing, so no pinyin reaches
                // the PTY; once the composition commits the tail goes out character by character.
                if (next.composition != null) {
                    entry = next
                    return@onChange
                }
                val delta = next.text
                entry = TextFieldValue("")
                if (delta.isNotEmpty()) {
                    onInput(delta, false)
                    onFollow()
                }
            },
            enabled = enabled,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp)
                .semantics { contentDescription = inputDesc },
            textStyle = TextStyle(
                color = term.text,
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
            ),
            cursorBrush = SolidColor(term.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
            singleLine = true,
            decorationBox = { inner ->
                // Direct mode explains itself in the hint row; the line-input placeholder would lie.
                if (entry.text.isEmpty() && !direct) {
                    Text(placeholder, color = term.muted, style = DsTypography.body)
                }
                inner()
            },
        )
        NeoIconButton(
            glyph = NeoGlyph.Send,
            onClick = { submit() },
            contentDescription = sendDesc,
            tint = term.accent,
            enabled = enabled,
        )
    }
}

/**
 * Clipboard payload as it must be written to the PTY.
 *
 * With bracketed paste on (DECSET 2004, mirrored on [VtSnapshot.bracketedPaste]) a multi-line
 * text is wrapped in ESC[200~ .. ESC[201~ so the remote application inserts it atomically and
 * does not execute every line; single-line text and shells without the mode get it verbatim.
 */
internal fun bracketedPastePayload(text: String, bracketedPaste: Boolean): String =
    if (bracketedPaste && text.contains('\n')) "\u001b[200~" + text + "\u001b[201~" else text

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
            stringResource(R.string.terminal_fingerprint_fingerprint_label),
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
        Row(
            Modifier.fillMaxWidth().padding(top = DsSpace.xs),
            horizontalArrangement = Arrangement.spacedBy(DsSpace.s, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoButton(
                label = stringResource(R.string.terminal_fingerprint_cancel),
                onClick = onCancel,
                tone = NeoButtonTone.Ghost,
                compact = true,
            )
            NeoButton(
                label = stringResource(R.string.terminal_fingerprint_trust),
                onClick = onTrust,
                tone = NeoButtonTone.Primary,
                compact = true,
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
internal fun dev.herdwatch.term.VtLine.annotated(defaultText: Color): AnnotatedString =
    buildAnnotatedString {
        runs.forEach { run ->
            withStyle(run.spanStyle(defaultText)) { append(run.text) }
        }
    }

internal fun VtRun.spanStyle(defaultText: Color): SpanStyle {
    val base = if (inverse) bg?.let { xtermColor(it, defaultText) } ?: defaultText else fg?.let { xtermColor(it, defaultText) } ?: defaultText
    val bgColor = when {
        inverse -> bg?.let { xtermColor(it, defaultText) }
        bg != null -> xtermColor(bg, defaultText)
        else -> null
    }
    return SpanStyle(
        color = if (dim) base.copy(alpha = 0.7f) else base,
        background = bgColor ?: Color.Unspecified,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
    )
}
