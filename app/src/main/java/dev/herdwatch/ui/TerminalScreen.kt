package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll


import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import android.widget.Toast
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import dev.herdwatch.R
import dev.herdwatch.term.VtRun
import dev.herdwatch.term.VtProgramStatus
import dev.herdwatch.ui.design.PageBarSpec
import dev.herdwatch.ui.design.DsMono
import dev.herdwatch.ui.design.NeoSheet
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
import dev.herdwatch.ui.design.NeoMenuItem
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

@OptIn(ExperimentalFoundationApi::class)
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
    val selectLabel = stringResource(R.string.terminal_select_text)
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

    // Ctrl is latched for the next key, whichever keyboard sends it. Putting the latch inside the
    // key bar meant "Ctrl then c" only worked for the on-screen key — the system keyboard, which is
    // the one most people actually type on, ignored the latch entirely.
    var ctrlLatched by rememberSaveable { mutableStateOf(false) }
    var altLatched by rememberSaveable { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    // Search state. The buffer is the whole snapshot the app already holds, so a lookup is a scan
    // of rows we are drawing anyway — no request, no cursor, and it works offline.
    var findOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var matchAt by remember { mutableIntStateOf(0) }
    val searchLines = state.snapshot?.let { snap ->
        if (snap.alternateScreen) snap.lines.takeLast(snap.rows) else snap.lines
    }.orEmpty()
    val matches = remember(searchLines, query) {
        if (query.isBlank()) emptyList() else searchLines.indices.filter { index ->
            searchLines[index].runs.joinToString("").contains(query, ignoreCase = true)
        }
    }
    val activeLine = if (matches.isEmpty()) -1 else matches[matchAt.coerceIn(0, matches.lastIndex)]
    // One funnel for every key source: the on-screen bar, the system keyboard and hardware keys.
    // Ctrl+C has to mean the same thing whichever of them produced the "c".
    // A BEL is the program asking for attention. A phone cannot beep politely, so it ticks —
    // the same signal a key press gives, which is enough to look up from whatever else you were
    // reading without becoming a notification.
    val bellFeedback = LocalHapticFeedback.current
    var lastBell by remember { mutableLongStateOf(state.snapshot?.bellCount ?: 0L) }
    LaunchedEffect(state.snapshot?.bellCount) {
        val now = state.snapshot?.bellCount ?: 0L
        if (now > lastBell) bellFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
        lastBell = now
    }

    val sendKey: (String) -> Unit = { sequence ->
        val ch = sequence.singleOrNull()?.lowercaseChar()
        val wasCtrl = ctrlLatched
        val wasAlt = altLatched
        // Both latches clear on the next key, whatever that key was: a modifier that stayed latched
        // after an arrow key would quietly corrupt the following command.
        ctrlLatched = false
        altLatched = false
        val body = if (wasCtrl && ch != null && ch in 'a'..'z') {
            (ch - 'a' + 1).toChar().toString()
        } else {
            sequence
        }
        // Alt is a single ESC prefix — the same thing a desktop terminal sends for `Alt+b`.
        onKey(if (wasAlt) "\u001B$body" else body)
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
            onFind = { findOpen = true },
        )
        // A tap opens the keyboard; a double tap sends Tab, the key a shell needs most and the one
        // no phone keyboard offers by default. Termius and Blink both teach this pair.
        var lastTapAt by remember { mutableLongStateOf(0L) }
        val tapHaptics = LocalHapticFeedback.current
        Box(
            Modifier.weight(1f).fillMaxWidth().combinedClickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClickLabel = showKeyboardLabel,
                onLongClickLabel = selectLabel,
                // Selection inside a LazyColumn that also answers taps never reliably won the gesture
                // round in testing, so a long press does the one thing a reader wants there — opens
                // the buffer as selectable text — instead of starting a selection nobody could drag.
                onLongClick = {
                    tapHaptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    selecting = true
                },
                onClick = {
                    val now = System.currentTimeMillis()
                    if (now - lastTapAt in 1..280L) {
                        lastTapAt = 0L
                        if (state.connected) {
                            tapHaptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            onKey("	")
                            onFollow()
                        }
                    } else {
                        lastTapAt = now
                        requestTerminalInput()
                    }
                },
            ),
        ) {
            val snapshot = state.snapshot
            if (snapshot != null && snapshot.lines.isNotEmpty()) {
                // Two-finger pinch resizes the text, exactly as it does in every mature terminal on
                // a phone. One finger must keep scrolling the buffer, so the gesture only takes
                // over once a second pointer is down.
                val currentFont = rememberUpdatedState(state.fontSize)
                val pinch = Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        var base = 0f
                        var accumulated = 1f
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                if (base == 0f) {
                                    base = currentFont.value
                                    accumulated = 1f
                                }
                                accumulated *= event.calculateZoom()
                                onFontSize((base * accumulated).coerceIn(9f, 22f))
                                event.changes.forEach { it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                Box(pinch) {
                TerminalGrid(
                    snapshot = snapshot,
                    fontSize = state.fontSize,
                    followRequests = state.followRequests,
                    onResize = onResize,
                    onFollow = onFollow,
                    onScrolledUp = { gridScrolledUp = it },
                    query = if (findOpen) query else "",
                    activeLine = if (findOpen) activeLine else -1,
                )
                }
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
                bracketedPaste = state.snapshot?.bracketedPaste == true,
                onKey = { sendKey(it); onFollow() },
                onView = { terminalInputView = it },
                modifier = Modifier.align(Alignment.BottomStart),
            )
            state.error?.takeIf { !state.connected }?.let { message ->
                Column(
                    // A card painted with the page's own background was invisible: only the text
                    // and the button floated. A raised ink wash plus a hairline makes it a panel.
                    Modifier.align(Alignment.Center).padding(20.dp)
                        .clip(RoundedCornerShape(DsRadius.card))
                        .background(term.text.copy(alpha = 0.05f))
                        .border(1.dp, term.muted.copy(alpha = 0.22f), RoundedCornerShape(DsRadius.card))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(stringResource(R.string.terminal_connection_lost), color = term.risk, style = DsTypography.headline)
                    Text(message, color = term.text, style = DsTypography.body)
                    if (state.retrying) {
                        // Say that the app is already working on it: a bare error plus a button makes
                        // the user think nothing happens until they tap.
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NeoSpinner(Modifier.size(12.dp), color = term.accent, strokeWidth = 1.6.dp)
                            Spacer(Modifier.width(DsSpace.s))
                            Text(
                                stringResource(R.string.terminal_retrying, state.retryAttempt),
                                color = term.muted,
                                style = DsTypography.caption,
                            )
                        }
                    }
                    NeoButton(
                        label = stringResource(
                            if (state.retrying) R.string.common_reconnect_now else R.string.common_reconnect,
                        ),
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
        if (findOpen) {
            TerminalFindBar(
            query = query,
            onQuery = { query = it; matchAt = 0 },
            matches = matches.size,
            position = if (matches.isEmpty()) 0 else matchAt + 1,
            onPrev = { if (matches.isNotEmpty()) matchAt = (matchAt - 1 + matches.size) % matches.size },
            onNext = { if (matches.isNotEmpty()) matchAt = (matchAt + 1) % matches.size },
            onClose = { findOpen = false; query = "" },
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
                        onKey = sendKey,
                        enabled = state.connected,
                        ctrl = ctrlLatched,
                        onToggleCtrl = { ctrlLatched = !ctrlLatched },
                        alt = altLatched,
                        onToggleAlt = { altLatched = !altLatched },
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
    val uiContext = LocalContext.current
    // A terminal is watched, not tapped: keep the screen awake for as long as the session is up,
    // the same way an SSH client on a desktop never dims while a build is running.
    val hostView = LocalView.current
    DisposableEffect(state.connected) {
        hostView.keepScreenOn = state.connected
        onDispose { hostView.keepScreenOn = false }
    }
    if (selecting) {
        val buffer = snapshotTextForCopy(state.snapshot)
        TerminalSelectionSheet(
            text = buffer,
            fontSize = state.fontSize,
            onCopy = {
                clipboard.setText(AnnotatedString(it))
                Toast.makeText(uiContext, uiContext.getString(R.string.terminal_copied), Toast.LENGTH_SHORT).show()
            },
            onOpen = { link ->
                selecting = false
                openLink(uiContext, link)
            },
            onDismiss = { selecting = false },
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
    onFind: () -> Unit,
) {
    val term = LocalTerminalTokens.current
    val shareContext = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val fontSizeLabel = stringResource(R.string.terminal_font_size, state.fontSize.roundToInt())
    Row(
        Modifier.fillMaxWidth()
            .padding(start = PageBarSpec.start, end = PageBarSpec.end, top = PageBarSpec.top, bottom = 2.dp),
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
                // No mode tag: this page is the ssh terminal — a badge saying so spent the width
                // that the ssh target needed, and the agent page dropped its badge for the same
                // reason. What is left is state plus one identifying line.
                ConnectionPip(state)
                // The program's own status report (OSC 7501), beside the connection pip because both
                // answer "what is happening now". Shown only while a record exists: no chip means
                // the program said nothing, which is not the same as it saying "idle".
                val status = state.snapshot?.status
                if (status != null) {
                    Spacer(Modifier.width(DsSpace.xs))
                    // The chip replaces the name slot rather than sharing it. Three facts on a
                    // 360dp row cut whichever came last: first "working · …", then "已连". The
                    // program's state is the one worth the width; the machine is the line above.
                    ProgramStatusChip(status, Modifier.weight(1f, fill = false))
                } else {
                // One slot for two facts, because there is only room for one: what the program calls
                // itself (OSC 0/2 — vim, tmux, less all set it) when it has a name, otherwise the
                // ssh target. Printing both halved each and left "dev@…" and "vim model…".
                // Just the account: the machine name is the line above and the full target is one
                // tap away in the machine list. `user@192.0.2.10:22` never fit and always
                // ended in an ellipsis, which reads as a truncation bug rather than a choice.
                // bash publishes `user@host:cwd` as the title on every prompt, which is not a
                // program name and made this slot truncate itself ("dev@build-h…"). Titles
                // that look like a prompt are ignored; vim's `vim model.py` and tmux's session
                // name are what this slot is for.
                val programTitle = state.snapshot?.title
                    ?.takeIf { it.isNotBlank() && !SHELL_PROMPT_TITLE.containsMatchIn(it) }
                val subline = programTitle ?: state.target.substringBefore('@')
                Spacer(Modifier.width(DsSpace.xs))
                Text(
                    subline,
                    style = DsTypography.micro,
                    color = term.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                }
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
                NeoMenuItem(
                    label = stringResource(R.string.terminal_find),
                    onClick = { menu = false; onFind() },
                )
                NeoMenuItem(
                    label = stringResource(R.string.terminal_share),
                    onClick = {
                        menu = false
                        val body = snapshotTextForCopy(state.snapshot)
                        if (body.isNotBlank()) {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, body)
                            shareContext.startActivity(
                                android.content.Intent.createChooser(
                                    send,
                                    shareContext.getString(R.string.terminal_share),
                                ),
                            )
                        }
                    },
                )
            }
        }
    }
}

/**
 * The program's own words about itself, toned by consequence: `blocked`/`error` are the states
 * where the user is the blocker, `working` is the only one worth an accent.
 *
 * The state word is never translated — it comes from the program, and inventing a different word
 * on the client side is exactly the guessing this protocol exists to remove.
 */
@Composable
private fun ProgramStatusChip(status: VtProgramStatus, modifier: Modifier = Modifier) {
    val colors = ds()
    val ink = when (status.state) {
        "blocked", "error" -> colors.danger
        "working" -> colors.accent
        else -> colors.textSoft
    }
    val detail = status.progress?.let { stringResource(R.string.terminal_program_status_progress, it) }
        ?: status.kind
        ?: status.title?.takeIf { it.isNotBlank() }
        ?: status.message?.takeIf { it.isNotBlank() }
    val label = if (detail == null) {
        status.state
    } else {
        stringResource(R.string.terminal_program_status_detail, status.state, detail)
    }
    Text(
        label,
        style = DsTypography.micro,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .background(ink.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = DsSpace.s, vertical = DsSpace.xxs),
    )
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
    /** Text to mark inside the buffer; blank means no search is active. */
    query: String = "",
    /** Line the search is currently on, so the grid can jump to it. */
    activeLine: Int = -1,
) {
    // No bottom reserve for controls: the screen already lays out its own strip above this grid,
    // and reserving a second one here would spend that height twice.
    val term = LocalTerminalTokens.current
    val density = LocalDensity.current
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var viewportPx by remember { mutableIntStateOf(0) }
    val lineHeightSp = fontSize * 1.3f
    val lineHeight = with(density) { lineHeightSp.sp.toDp() }
    val textStyle = TextStyle(
        fontFamily = DsMono,
        fontSize = fontSize.sp,
        lineHeight = lineHeightSp.sp,
        color = term.text,
    )

    // Measure the real advance width once per font size, and measure a full run of glyphs: the
    // single-glyph result is rounded to whole pixels, and that ~1% error accumulates across 46
    // columns until the right edge becomes unreachable.
    val probeStyle = TextStyle(fontFamily = DsMono, fontSize = fontSize.sp)
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSize, probeStyle, measurer, density) {
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
        // The grid is sized by the largest window it has had, not the current one. Both the IME and
        // the key bar shrink this box, and every shrink used to send a TIOCSWINSZ; a shell answers
        // a window change by redrawing its prompt, so tapping the screen three times left three
        // copies of the prompt in scrollback. The PTY keeps one stable size and the visible window
        // is simply shorter while typing.
        var tallestHeight by remember(fontSize) { mutableStateOf(0.dp) }
        LaunchedEffect(maxHeight, fontSize) {
            if (maxHeight > tallestHeight) tallestHeight = maxHeight
        }
        val gridHeight = if (maxHeight > tallestHeight) maxHeight else tallestHeight
        val rows = (gridHeight / lineHeight).toInt()
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
        // Stable ordinal keys: scrollback prepends shift every index, and index keys would re-anchor
        // the first visible row to different content whenever history loads. Ordinals survive
        // prepends, so LazyColumn keeps the reader on the same line.
        val identityState = remember { mutableStateOf(emptyLineIndex<dev.herdwatch.term.VtLine>()) }
        val lineIndex = remember(visibleLines) {
            advanceLineIndex(identityState.value, visibleLines).also { identityState.value = it }
        }
        val visibleLast = visibleLines.lastIndex
        // Aligning the LAST row to the TOP of the viewport leaves the rest of the screen empty.
        // Once the grid is taller than the visible area — which is exactly what happens when the
        // keyboard opens over a fixed-size grid — that reads as "the terminal went blank". Keep the
        // last row at the bottom instead, by scrolling to the first row that is still on screen.
        val lineHeightPx = with(density) { lineHeight.toPx() }
        val rowsInView = if (lineHeightPx > 0f && viewportPx > 0) {
            (viewportPx / lineHeightPx).toInt().coerceAtLeast(1)
        } else {
            Int.MAX_VALUE
        }
        // Follow the CURSOR, not the last row. The grid is deliberately taller than the visible
        // area (that is what keeps the PTY from resizing when the keyboard opens), so its trailing
        // rows are padding: scrolling to the bottom of the buffer hides a prompt that sits at the
        // top of a mostly empty screen. The cursor is where the shell is attending; below it there
        // is nothing to read.
        val focusRow = terminalFocusRow(snapshot, fullScreen)
            .coerceIn(0, visibleLast.coerceAtLeast(0))
        val bottomTarget = (focusRow - rowsInView + 1).coerceAtLeast(0)
        LaunchedEffect(snapshot.revision, stickToBottom, fullScreen, rowsInView) {
            if (fullScreen) {
                if (listState.firstVisibleItemIndex != 0) listState.scrollToItem(0)
            } else if (stickToBottom && visibleLast >= 0) {
                listState.scrollToItem(bottomTarget)
            }
        }
        LaunchedEffect(activeLine, fullScreen) {
            if (activeLine >= 0 && activeLine <= visibleLast) {
                listState.animateScrollToItem(activeLine)
            }
        }
        LaunchedEffect(followRequests, fullScreen) {
            if (fullScreen) listState.scrollToItem(0) else if (visibleLast >= 0) listState.scrollToItem(bottomTarget)
        }

        // Long lines must stay readable: the grid is as wide as the PTY, which on a phone is wider
        // than the viewport, so the whole grid pans horizontally instead of clipping the right edge.
        // One shared scroll state keeps every row aligned — per-row scrolling would tear columns.
        // Width comes from the measured font metrics, not the sizing estimate: a few percent of
        // drift over 47 columns would leave the last character permanently clipped. Wide glyphs
        // (CJK, emoji) occupy two cells, so a row can outgrow the viewport-derived column count;
        // the grid must cover the widest row or its tail is clipped. Limitation (deliberate): rows
        // are one Text each, not a positioned cell grid, so alignment relies on the monospace font
        // painting wide glyphs at exactly twice the ASCII advance.
        val widestCells = remember(visibleLines) {
            visibleLines.maxOfOrNull { line -> line.runs.sumOf { terminalCells(it.text) } } ?: 0
        }
        val gridWidth = charWidthPx * maxOf(cols, widestCells)
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .onSizeChanged { viewportPx = it.height }
                    .horizontalScroll(gridScroll),
            ) {
                itemsIndexed(visibleLines, key = { index, _ -> lineIndex.keys[index] }) { index, line ->
                    Text(
                        text = line.annotated(
                            defaultText = term.text,
                            defaultBackground = term.background,
                            query = query,
                            strong = index == activeLine,
                            mark = term.accent,
                        ),
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
 * Find inside the buffer the app already holds.
 *
 * Deliberately not a remote search: the app has every row it draws, so matching is instant, works
 * offline, and cannot disturb the session. Position is shown as "3 / 12" because "3 matches" leaves
 * the user wondering where they are.
 */
@Composable
private fun TerminalFindBar(
    query: String,
    onQuery: (String) -> Unit,
    matches: Int,
    position: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
) {
    val term = LocalTerminalTokens.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DsSpace.s, vertical = DsSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f)
                .clip(RoundedCornerShape(DsRadius.key))
                .background(term.text.copy(alpha = 0.07f))
                .padding(horizontal = DsSpace.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIcon(NeoGlyph.Search, Modifier.size(14.dp), tint = term.muted)
            Spacer(Modifier.width(DsSpace.xs))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = DsTypography.body.copy(color = term.text),
                cursorBrush = SolidColor(term.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onNext() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(
                            stringResource(R.string.terminal_find_hint),
                            color = term.muted,
                            style = DsTypography.body,
                        )
                    }
                    inner()
                },
            )
        }
        if (query.isNotEmpty()) {
            Text(
                if (matches == 0) stringResource(R.string.terminal_find_none) else "$position / $matches",
                color = term.muted,
                style = DsTypography.caption,
                modifier = Modifier.padding(horizontal = DsSpace.xs),
            )
        }
        NeoIconButton(
            glyph = NeoGlyph.ChevronUp,
            onClick = onPrev,
            contentDescription = stringResource(R.string.terminal_find_prev),
            tint = term.text,
            enabled = matches > 0,
        )
        NeoIconButton(
            glyph = NeoGlyph.ChevronDown,
            onClick = onNext,
            contentDescription = stringResource(R.string.terminal_find_next),
            tint = term.text,
            enabled = matches > 0,
        )
        NeoIconButton(
            glyph = NeoGlyph.Close,
            onClick = onClose,
            contentDescription = stringResource(R.string.terminal_find_close),
            tint = term.text,
        )
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
    ctrl: Boolean,
    onToggleCtrl: () -> Unit,
    alt: Boolean,
    onToggleAlt: () -> Unit,
) {
    val ctrlDesc = stringResource(R.string.terminal_key_ctrl)
    val shiftTabLabel = stringResource(R.string.terminal_key_shift_tab)
    // A key bar that scrolls sideways hid PgDn behind the screen edge with no hint that anything
    // was there. Keys now share the row width, so every key is visible and tappable at once.

    // Split so each row fits a 360dp screen without scrolling: row 1 edits the line,
    // row 2 sends signals and moves through history/scrolling. Ctrl lives at the head of row 1.
    val primary = listOf(
        TerminalKey("Esc", "\u001B", R.string.terminal_key_escape, repeatable = false),
        TerminalKey("Tab", "	", R.string.terminal_key_tab, repeatable = false),
        // Shift+Tab (back-tab): cycles permission modes in agent CLIs, tabs in tmux.
        TerminalKey(shiftTabLabel, "\u001B[Z", R.string.terminal_key_shift_tab_a11y, repeatable = false),
        TerminalKey("⌫", "", R.string.terminal_key_backspace),
        TerminalKey("←", arrowKeySequence('D', applicationCursorKeys), R.string.terminal_key_left),
        TerminalKey("→", arrowKeySequence('C', applicationCursorKeys), R.string.terminal_key_right),
    )
    val secondary = listOf(
        TerminalKey("^C", "\u0003", R.string.terminal_key_ctrl_c, repeatable = false),
        TerminalKey("^D", "\u0004", R.string.terminal_key_ctrl_d, repeatable = false),
        TerminalKey("^Z", "\u001A", R.string.terminal_key_ctrl_z, repeatable = false),
        TerminalKey("↑", arrowKeySequence('A', applicationCursorKeys), R.string.terminal_key_up),
        TerminalKey("↓", arrowKeySequence('B', applicationCursorKeys), R.string.terminal_key_down),
        TerminalKey("PgUp", "\u001B[5~", R.string.terminal_key_page_up, repeatable = false),
        TerminalKey("PgDn", "\u001B[6~", R.string.terminal_key_page_down, repeatable = false),
    )

    // Ctrl is applied once, on the page, so the bar and the system keyboard cannot disagree.
    val fire: (String) -> Unit = onKey

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
                onPress = onToggleCtrl,
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
                // Alt prefixes the next key with ESC, which is how vim, tmux and readline reach
                // their second layer (`Alt+b`, `Alt+.`). It sits on row 2 because row 1 is full.
                TerminalKeyButton(
                    label = "Alt",
                    description = stringResource(R.string.terminal_key_alt),
                    enabled = enabled,
                    latched = alt,
                    repeatable = false,
                    onPress = onToggleAlt,
                    modifier = Modifier.weight(1f),
                )
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
    // A key bar is a keyboard replacement, and keyboards tick. Without feedback a thumb cannot tell
    // a missed press from a slow remote, which on a 48dp row is the difference between typing and
    // guessing.
    val haptics = LocalHapticFeedback.current
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
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress()
                },
                onLongClick = {
                    if (repeatable) {
                        scope.launch {
                            // A long press is a repeat, not a burst: people hold ← to cross a long
                            // line, and six cells left them tapping. Capped so a stuck finger
                            // cannot flood the PTY.
                            repeat(60) {
                                onPress()
                                delay(55)
                            }
                        }
                    }
                },
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
internal fun terminalFocusRow(snapshot: VtSnapshot, fullScreen: Boolean): Int =
    if (fullScreen) 0 else snapshot.scrollbackCount + snapshot.cursorRow

internal fun arrowKeySequence(final: Char, applicationCursorKeys: Boolean): String {
    require(final in "ABCD") { "unsupported cursor key: $final" }
    return if (applicationCursorKeys) "\u001bO$final" else "\u001b[$final"
}

@Composable
private fun TerminalImeCapture(
    enabled: Boolean,
    sessionNonce: Long,
    applicationCursorKeys: Boolean,
    bracketedPaste: Boolean,
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
            view.bracketedPaste = { bracketedPaste }
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
    query: String = "",
    strong: Boolean = false,
    mark: Color = defaultText,
): AnnotatedString {
    val plain = runs.joinToString("") { it.text }
    return buildAnnotatedString {
        runs.forEach { run ->
            withStyle(run.spanStyle(defaultText, defaultBackground)) { append(run.text) }
        }
        if (query.isNotBlank()) {
            // Search has to be visible on the screen it searches: the hit keeps the program's own
            // colour and gains a wash behind it; the line the user is on is the bright one.
            var from = 0
            while (true) {
                val at = plain.indexOf(query, from, ignoreCase = true)
                if (at < 0) break
                addStyle(
                    SpanStyle(background = mark.copy(alpha = if (strong) 0.45f else 0.18f)),
                    at,
                    at + query.length,
                )
                from = at + query.length
            }
        }
    }
}

internal fun VtRun.spanStyle(defaultText: Color, defaultBackground: Color = Color.Transparent): SpanStyle {
    val foreground = fg?.let { xtermColor(it, defaultText) } ?: defaultText
    val background = bg?.let { xtermColor(it, defaultBackground) } ?: defaultBackground
    val effectiveForeground = if (inverse) background else foreground
    val effectiveBackground = if (inverse) foreground else background
    // Same floor as the snapshot renderer: keep the remote program's hue, fix the lightness it
    // assumed a different background for.
    val legible = if (effectiveBackground.alpha == 0f) {
        effectiveForeground
    } else {
        ensureContrast(effectiveForeground, effectiveBackground)
    }
    return SpanStyle(
        color = if (dim) legible.copy(alpha = 0.7f) else legible,
        background = effectiveBackground,
        fontWeight = if (bold) androidx.compose.ui.text.font.FontWeight.Bold else null,
        fontStyle = if (italic) androidx.compose.ui.text.font.FontStyle.Italic else null,
        textDecoration = if (underline) androidx.compose.ui.text.style.TextDecoration.Underline else null,
    )
}

/**
 * The buffer as one block of selectable text.
 *
 * Every row is kept, including blank ones — a copy that silently drops empty lines stops being the
 * screen. Trailing padding is trimmed per line, because a 120-column PTY pads every row.
 */
/** `user@host:cwd`, the title bash republishes on every prompt. */
private val SHELL_PROMPT_TITLE = Regex("^[^\\s@]+@[^\\s:]+:")

internal fun snapshotTextForCopy(snapshot: dev.herdwatch.term.VtSnapshot?): String {
    val rows = snapshot?.lines
        ?.map { line -> line.runs.joinToString("") { run -> run.text }.trimEnd() }
        ?.dropLastWhile { it.isEmpty() }
        .orEmpty()
    // A 24-row screen is mostly padding. A run of empty rows is layout, not content, so it collapses
    // to a single blank line — otherwise a copied prompt arrives with half a page of nothing after it.
    val kept = ArrayList<String>(rows.size)
    var blanks = 0
    for (row in rows) {
        if (row.isEmpty()) {
            blanks++
            if (blanks > 1) continue
        } else {
            blanks = 0
        }
        kept.add(row)
    }
    return kept.joinToString("\n")
}

@Composable
private fun TerminalSelectionSheet(
    text: String,
    fontSize: Float,
    onCopy: (String) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val term = LocalTerminalTokens.current
    NeoSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = DsSpace.xl, end = DsSpace.xl, bottom = DsSpace.xxl),
        ) {
            Text(stringResource(R.string.terminal_select_title), style = DsTypography.title, color = ds().text)
            Spacer(Modifier.size(DsSpace.s))
            // The buffer is shown on the terminal's own surface: white-on-white mono is unreadable,
            // and the point of picking text out of a terminal is that it still looks like one.
            val lineHeightDp = fontSize * 1.32f
            // Height follows the content up to a ceiling. A scroll container measured with an
            // unbounded child fills whatever it is given, which left a tall dark panel holding two
            // lines of prompt.
            val bufferHeight = (text.lines().size * lineHeightDp + DsSpace.m.value * 2).dp
                .coerceIn(56.dp, 360.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(bufferHeight)
                    .clip(RoundedCornerShape(DsRadius.control))
                    .background(term.background)
                    .padding(DsSpace.m),
            ) {
                SelectionContainer {
                    Text(
                        text,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = fontSize.sp,
                            lineHeight = lineHeightDp.sp,
                        ),
                        color = term.text,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
            // Links that appear on a terminal are usually the reason the screen matters (a review,
            // a CI job). Opening one must not require selecting the text by hand.
            val links = remember(text) { urlsIn(text) }
            links.forEach { link ->
                Spacer(Modifier.size(DsSpace.s))
                NeoButton(
                    label = link,
                    onClick = { onOpen(link) },
                    tone = NeoButtonTone.Quiet,
                    modifier = Modifier.fillMaxWidth(),
                    icon = NeoGlyph.Link,
                )
            }
            Spacer(Modifier.size(DsSpace.m))
            NeoButton(
                label = stringResource(R.string.terminal_copy_all),
                onClick = { onCopy(text) },
                tone = NeoButtonTone.Quiet,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
