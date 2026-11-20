package dev.herdwatch.ui

import android.content.Context
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.herdwatch.R
import dev.herdwatch.domain.AgentTranscript
import dev.herdwatch.domain.TranscriptBlock
import dev.herdwatch.domain.TranscriptRole
import dev.herdwatch.domain.requiresStandingConfirmation
import dev.herdwatch.domain.AgentCli
import dev.herdwatch.domain.AgentCommand
import dev.herdwatch.ui.design.NeoSheet
import dev.herdwatch.domain.ApprovalScope
import dev.herdwatch.domain.approvalScopeOf
import dev.herdwatch.domain.cliOf
import dev.herdwatch.domain.curatedCommands
import dev.herdwatch.domain.discoverCommands
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.ui.SentMessage
import dev.herdwatch.model.Ask
import dev.herdwatch.model.AskConfidence
import dev.herdwatch.model.AskOption
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsMotion
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.LocalDs
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoCard
import dev.herdwatch.ui.design.NeoDialog
import dev.herdwatch.ui.design.NeoKey
import dev.herdwatch.ui.design.NeoDivider
import dev.herdwatch.ui.design.NeoField
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoLamp
import dev.herdwatch.ui.design.NeoModeTag
import dev.herdwatch.ui.design.NeoMenu
import dev.herdwatch.ui.design.NeoMenuDivider
import dev.herdwatch.ui.design.NeoMenuItem
import dev.herdwatch.ui.design.NeoSpinner
import dev.herdwatch.ui.design.NeoShadowBleed
import dev.herdwatch.ui.design.PageBarSpec
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.LocalReducedMotion
import dev.herdwatch.ui.theme.LocalTerminalTokens
import dev.herdwatch.ui.theme.MonoStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.herdwatch.ui.theme.agentStatusColor
import dev.herdwatch.ui.motionMillis
import dev.herdwatch.ui.design.NeoSegmented
import dev.herdwatch.ui.design.spacing

private val TerminalBackground: Color @Composable get() = LocalTerminalTokens.current.background
private val TerminalText: Color @Composable get() = LocalTerminalTokens.current.text
private val TerminalMuted: Color @Composable get() = LocalTerminalTokens.current.muted
@Composable
fun AgentScreen(
    detail: DetailState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onAutoRefresh: () -> Unit,
    onReadingPaused: (Boolean) -> Unit,
    onLoadMoreHistory: () -> Unit = {},
    onReadingPosition: (String, ReadingPosition) -> Unit,
    onChoose: (AskOption) -> Unit,
    onVerifyOperation: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onSendInput: (String) -> Unit = {},
    /**
     * Literal bytes for the key strip (escape sequences such as Shift+Tab). Separate from
     * [onSendInput] because that one submits a line: appending Enter to `ESC [ Z` would run the
     * half-typed command the mode switch was supposed to protect.
     */
    onSendRawBytes: (String) -> Unit = {},
    onStop: () -> Unit,
    onExplain: () -> Unit,
    onDismissExplain: () -> Unit,
    onRename: (String) -> Unit,
    onStartAgent: (() -> Unit)? = null,
) {
    val agent = detail.agent ?: return
    val colors = ds()
    var confirmStop by remember(agent.key) { mutableStateOf(false) }
    var renaming by remember(agent.key) { mutableStateOf(false) }
    var menu by remember(agent.key) { mutableStateOf(false) }
    var confirmVerification by remember(agent.key) { mutableStateOf(false) }
    var selecting by remember(agent.key) { mutableStateOf(false) }
    // A plain shell opens with its key rows: there is no agent banner or reply flow to explain them.
    var keyboardExpanded by rememberSaveable(agent.key) { mutableStateOf(!agent.hasAgent) }
    var ctrlLatched by remember(agent.key) { mutableStateOf(false) }
    // Wrapped by default: a phone has no room for a 120-column line and the snapshot view has no
    // horizontal scroller, so not wrapping means losing the right-hand side of every long line.
    // The overflow menu still turns it off for column-aligned output.
    var wrapOutput by rememberSaveable { mutableStateOf(true) }
    // Folded by default: a TUI snapshot arrives full of rules and blank runs, and the first
    // screen a person sees should be the agent's words, not its box drawing. The toggle still
    // reveals the raw bytes, and approvals always read the raw snapshot.
    // Default is the machine's own screen, colours and all. Folding decoration lines is a reading
    // aid the user can ask for, not the state the page opens in.
    var focusedOutput by rememberSaveable { mutableStateOf(false) }
    // Chat is the default reading layout for an agent pane; the terminal layout stays one tap away
    // and is forced whenever the snapshot cannot be segmented honestly.
    // A pane without an agent opens straight into the terminal: there is no turn to read yet.
    var chatLayout by rememberSaveable(agent.key) { mutableStateOf(agent.hasAgent) }
    var commandsOpen by rememberSaveable(agent.key) { mutableStateOf(false) }
    val paneOnly = !agent.hasAgent
    val initialPosition = remember(agent.key) { detail.readingPosition }
    val paneScroll = androidx.compose.runtime.key(agent.key) { rememberScrollState(initialPosition.paneOffset) }
    val pageScroll = androidx.compose.runtime.key(agent.key) { rememberScrollState(initialPosition.pageOffset) }
    var restoredPosition by rememberSaveable(agent.key) { mutableStateOf(false) }
    var stickToBottom by rememberSaveable(agent.key) { mutableStateOf(initialPosition.following) }
    var unseenOutput by remember(agent.key) { mutableStateOf(false) }
    // Bumped when the reader asks to go back to the newest line in terminal mode, where the grid
    // owns its own list state instead of the page's shared scroll state.
    var followTick by remember(agent.key) { mutableLongStateOf(0L) }
    var previousOutput by remember(agent.key) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val accessibility = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var userPaused by rememberSaveable(agent.key) { mutableStateOf(initialPosition.paused) }
    var accessibilityPaused by remember(agent.key) { mutableStateOf(accessibility.isTouchExplorationEnabled) }
    DisposableEffect(accessibility, agent.key) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled -> accessibilityPaused = enabled }
        accessibility.addTouchExplorationStateChangeListener(listener)
        onDispose { accessibility.removeTouchExplorationStateChangeListener(listener) }
    }
    // Scrolling up IS the pause. A separate "pause updates" switch made the user manage a mode
    // before they could read; now leaving the bottom holds the text and returning resumes it.
    val readingAway = !stickToBottom
    val pauseUpdates = userPaused || accessibilityPaused || readingAway
    val autoUpdateStateLabel = stringResource(
        when {
            accessibilityPaused -> R.string.agent_state_talkback_paused
            userPaused || readingAway -> R.string.agent_state_autoupdate_paused
            else -> R.string.agent_state_autoupdate_on
        },
    )
    val foldStateLabel = stringResource(if (focusedOutput) R.string.agent_state_on else R.string.agent_state_off)
    val wrapStateLabel = stringResource(if (wrapOutput) R.string.agent_state_on else R.string.agent_state_off)
    val foldDescription = stringResource(R.string.agent_cd_fold_decorations)
    val keyboardStateLabel = stringResource(
        if (keyboardExpanded) R.string.agent_state_expanded else R.string.agent_state_collapsed,
    )
    val ctrlStateLabel = stringResource(if (ctrlLatched) R.string.agent_ctrl_latched else R.string.agent_ctrl_idle)
    val replyDescription = stringResource(R.string.agent_cd_reply_to, agent.name)
    val sendDescription = stringResource(R.string.agent_cd_send_to, agent.name)
    val copiedToast = stringResource(R.string.common_copied)
    val copiedScreenToast = stringResource(R.string.agent_toast_copied_screen)
    val arrowUpDescription = stringResource(R.string.agent_cd_arrow_up)
    val arrowDownDescription = stringResource(R.string.agent_cd_arrow_down)
    val arrowLeftDescription = stringResource(R.string.agent_cd_arrow_left)
    val arrowRightDescription = stringResource(R.string.agent_cd_arrow_right)
    val latestPositionCallback by rememberUpdatedState(onReadingPosition)
    LaunchedEffect(agent.key, detail.loadedAt) {
        if (!restoredPosition && detail.loadedAt != null) {
            withFrameNanos { }
            pageScroll.scrollTo(initialPosition.pageOffset)
            paneScroll.scrollTo(if (initialPosition.following) paneScroll.maxValue else initialPosition.paneOffset)
            restoredPosition = true
        }
    }
    LaunchedEffect(agent.key) {
        snapshotFlow { restoredPosition to ReadingPosition(pageScroll.value, paneScroll.value, stickToBottom, userPaused) }
            .collect { (restored, position) -> if (restored) latestPositionCallback(agent.key, position) }
    }
    LaunchedEffect(agent.key, pauseUpdates, selecting) { onReadingPaused(pauseUpdates || selecting) }
    val latestRefresh by rememberUpdatedState(onAutoRefresh)
    val currentSending by rememberUpdatedState(detail.sending)
    val currentLoading by rememberUpdatedState(detail.loading)
    val currentPaused by rememberUpdatedState(pauseUpdates || selecting)
    val transcript = remember(detail.text) { AgentTranscript.parse(detail.text) }
    // A snapshot we cannot cut into turns is still readable as one turn: the agent's current
    // screen, shown as a single message. Refusing to open the chat was the wrong answer — the
    // reader came here to read the agent, not to be told the parser gave up.
    val chatBlocks = remember(transcript, detail.text) {
        if (transcript.reliable) {
            transcript.blocks
        } else {
            listOf(
                TranscriptBlock(
                    role = TranscriptRole.ASSISTANT,
                    text = focusTerminalText(stripAnsi(detail.text)),
                    raw = detail.text,
                    truncatedHead = false,
                ),
            )
        }
    }
    // Chat needs an agent, a trustworthy parse and the chat layout. Terminal is the evidence view
    // and the only mode for a plain shell pane; the dock below follows the same decision.
    val showChat = !paneOnly && chatLayout
    // Folding runs on stripped text (an escape code inside a rule would defeat the box-char test);
    // the verbatim view keeps the colour the remote program wrote.
    val visibleText = remember(detail.text, focusedOutput) {
        val trimmed = condenseScreen(trimTrailingSpaces(detail.text))
        if (focusedOutput) focusTerminalText(stripAnsi(trimmed)) else trimmed
    }
    LaunchedEffect(agent.key, paneScroll) {
        snapshotFlow {
            Triple(paneScroll.isScrollInProgress, paneScroll.canScrollForward, paneScroll.maxValue)
        }.collect { (scrolling, canForward, maxValue) ->
            if (scrolling) {
                stickToBottom = !canForward
                if (!canForward) unseenOutput = false
            } else if (maxValue == 0) {
                // A snapshot shorter than the viewport has nothing to scroll, so canScrollForward
                // is false for a reason that has nothing to do with the user reading further up.
                // Treating that as "reading away" paused updates on a page that never moved.
                stickToBottom = true
            }
        }
    }
    // Reaching the top asks the host for a bigger tail. The page keeps its place by measuring how
    // much taller the content got and shifting the offset by exactly that much — without it the
    // reader would be thrown back to the newest line the moment older text arrived.
    var anchorFromTop by remember(agent.key) { mutableStateOf<Int?>(null) }
    var heightBefore by remember(agent.key) { mutableIntStateOf(0) }
    LaunchedEffect(agent.key, paneScroll) {
        snapshotFlow { paneScroll.value to paneScroll.maxValue }
            .collect { (value, max) ->
                if (max > 0 && value <= 4 && anchorFromTop == null) {
                    anchorFromTop = value
                    heightBefore = max
                    onLoadMoreHistory()
                } else if (value > max / 4) {
                    anchorFromTop = null
                }
            }
    }
    LaunchedEffect(detail.text, detail.loading, detail.historyLines) {
        val anchor = anchorFromTop ?: return@LaunchedEffect
        // Wait for the taller content, but never forever: the host may already have given us its
        // whole buffer, and a stuck anchor would block every later attempt to read further back.
        val grew = withTimeoutOrNull(4_000) {
            snapshotFlow { paneScroll.maxValue }.first { it > heightBefore }
        }
        if (grew != null) paneScroll.scrollTo(anchor + (paneScroll.maxValue - heightBefore))
        // Cleared even when nothing grew. Keyed on historyLines as well as text so a request that
        // comes back with the same buffer still re-arms the trigger; otherwise one fruitless read
        // left the anchor set for the rest of the visit and scrolling to the top did nothing.
        anchorFromTop = null
    }
    LaunchedEffect(agent.key, detail.text, detail.loadedAt, focusedOutput) {
        // The first snapshot on re-entry establishes a baseline; display-only changes
        // such as folding decoration must not pretend the remote pane produced output.
        if (detail.loadedAt != null) {
            val changed = previousOutput?.let { it != detail.text } == true
            previousOutput = detail.text
            if (stickToBottom) {
                withFrameNanos { }
                paneScroll.scrollTo(paneScroll.maxValue)
            } else if (changed) unseenOutput = true
        }
    }
    LaunchedEffect(agent.key) {
        while (true) {
            delay(3000)
            if (!currentSending && !currentLoading && !currentPaused) latestRefresh()
        }
    }

    // Read before the page consumes the inset: `windowInsetsPadding(ime)` below zeroes the value
    // for every child, so asking inside the padded box always answered "no keyboard" and the key
    // strip stayed on screen underneath an open keyboard.
    val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // The IME inset already includes the nav bar height, so union them instead of stacking
    // padding: the composer must clear the gesture bar when the keyboard is closed.
    // Watching an agent work is a minutes-long activity with no touch input, so the screen stays
    // awake while something is happening or waiting for a decision — not while reading a finished
    // answer with the phone on the desk. Scoped to this page: leaving restores the system default.
    val localView = LocalView.current
    val keepAwake = agent.status == AgentStatus.WORKING || agent.status == AgentStatus.WAITING
    DisposableEffect(keepAwake) {
        localView.keepScreenOn = keepAwake
        onDispose { localView.keepScreenOn = false }
    }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ),
    ) {
    val compact = maxHeight < 640.dp || LocalDensity.current.fontScale >= 1.3f
    val minimal = maxHeight < 360.dp
    // The overflow stays reachable even in the minimal landscape header; only the status
    // chrome is allowed to collapse.
    val overflowMenu: @Composable () -> Unit = {
        Box {
            NeoIconButton(
                glyph = NeoGlyph.More,
                onClick = { menu = true },
                contentDescription = stringResource(R.string.agent_cd_more_actions),
            )
            NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                NeoMenuItem(
                    label = stringResource(R.string.agent_rename),
                    onClick = { menu = false; renaming = true },
                    enabled = !detail.sending,
                )
                // Wrapping is a question only for the terminal layout; the chat layout wraps by
                // definition, so offering it there was a switch that visibly did nothing.
                if (!showChat) {
                    NeoMenuItem(
                        label = stringResource(
                            if (wrapOutput) R.string.agent_menu_wrap_off else R.string.agent_menu_wrap_on,
                        ),
                        onClick = { menu = false; wrapOutput = !wrapOutput },
                        modifier = Modifier.semantics { stateDescription = wrapStateLabel },
                    )
                }
                // Terminal mode hides the layout switch to give the grid its full band, so the way
                // back to the conversation has to exist somewhere the user will look for it.
                if (!showChat && !paneOnly && transcript.reliable) {
                    NeoMenuItem(
                        label = stringResource(R.string.agent_menu_chat_view),
                        icon = NeoGlyph.Tray,
                        onClick = { menu = false; chatLayout = true },
                    )
                }
                // "Explain the status" is an agent question. A bare shell has no status to explain.
                if (!paneOnly) {
                    NeoMenuItem(
                        label = stringResource(R.string.agent_menu_status_source),
                        onClick = { menu = false; onExplain() },
                    )
                }
                NeoMenuDivider()
                // A shell pane is where an agent gets started. Keeping the entry on the pane itself
                // means you never have to leave the screen you are already looking at.
                if (onStartAgent != null) {
                    NeoMenuItem(
                        label = stringResource(R.string.agent_menu_start_agent),
                        icon = NeoGlyph.Play,
                        onClick = { menu = false; onStartAgent() },
                        enabled = detail.canSend,
                    )
                }
                NeoMenuItem(
                    label = stringResource(R.string.agent_menu_copy_screen),
                    onClick = {
                        menu = false
                        // "Whole screen" means the whole screen: the condensed form is for the
                        // selection sheet, a bug report needs the blank rows and rules intact.
                        // SGR is dropped because escapes paste as garbage into the next app.
                        clipboard.setText(AnnotatedString(stripAnsi(detail.text)))
                        Toast.makeText(context, copiedScreenToast, Toast.LENGTH_SHORT).show()
                    },
                    enabled = detail.text.isNotBlank(),
                )
                NeoMenuItem(
                    label = stringResource(R.string.agent_menu_select_copy),
                    onClick = {
                        menu = false
                        selecting = true
                    },
                    enabled = detail.text.isNotBlank(),
                )
                NeoMenuItem(
                    label = stringResource(R.string.terminal_share),
                    onClick = {
                        menu = false
                        // Same body as the copy above: the screen as a person reads it, with the
                        // SGR escapes removed so it pastes into a chat intact.
                        val body = condenseScreenText(stripAnsi(detail.text))
                        if (body.isNotBlank()) {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                                .setType("text/plain")
                                .putExtra(android.content.Intent.EXTRA_TEXT, body)
                            context.startActivity(
                                android.content.Intent.createChooser(
                                    send,
                                    context.getString(R.string.terminal_share),
                                ),
                            )
                        }
                    },
                    enabled = detail.text.isNotBlank(),
                )
                NeoMenuDivider()
                if (!paneOnly) {
                    NeoMenuItem(
                        label = stringResource(R.string.agent_menu_stop),
                        onClick = { menu = false; confirmStop = true },
                        enabled = !detail.sending,
                        danger = true,
                    )
                }
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().weight(1f)) {
        // Even in extreme landscape there must be a way back and a visible mode label.
        if (minimal) Row(
            Modifier.fillMaxWidth().padding(horizontal = DsSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(
                glyph = NeoGlyph.Back,
                onClick = onBack,
                contentDescription = stringResource(R.string.agent_cd_back),
            )
            Text(agent.name, style = DsTypography.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).semantics { heading() })
            NeoModeTag(stringResource(if (paneOnly) R.string.mode_shell_tag else R.string.mode_agent_tag))
            overflowMenu()
        } else Row(
            Modifier.fillMaxWidth()
                .padding(start = PageBarSpec.start, end = PageBarSpec.end, top = PageBarSpec.top),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(
                glyph = NeoGlyph.Back,
                onClick = onBack,
                contentDescription = stringResource(R.string.agent_cd_back),
            )
            Column(Modifier.weight(1f)) {
                // Same title style as the terminal bar: one page-header size for every pushed page,
                // so moving between an agent and its shell does not change the type ramp.
                Text(agent.name, style = DsTypography.headline, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    // No mode tag here: the page says agent or shell in its status word and in the
                    // layout switch below, and the tag was eating the width the breadcrumb needed.
                    val statusWord = if (paneOnly) stringResource(R.string.inbox_pane_shell) else stringResource(agent.status.labelRes)
                    NeoLamp(
                        color = if (paneOnly) (if (detail.hostConnected) colors.ok else colors.warn) else agentStatusColor(agent.status),
                        size = 7.dp,
                        // Decorative: the word beside it already says the status, and a lamp that
                        // also carried the word made TalkBack read it twice per row.
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    if (paneOnly || agent.status != AgentStatus.IDLE) {
                        Text(
                            statusWord,
                            style = DsTypography.caption,
                            color = colors.textSoft,
                            maxLines = 1,
                        )
                    }
                    if (!compact && !paneOnly && agent.status != AgentStatus.IDLE) {
                        Text(formatElapsed(agent.sinceMillis), style = DsTypography.mono, color = colors.textFaint, maxLines = 1)
                    }
                    // Where this pane lives, on the same line as the status. Two panes with the same
                    // agent name used to be the same page to the reader, but a dedicated row for it
                    // cost a band of height above the terminal for something read once per visit.
                    // The pane id is left out: the page IS that pane, and the extra "· p2" was what
                    // turned the trailing workspace into an ellipsis on a phone-width header.
                    Text(
                        listOfNotNull(
                            agent.hostLabel.takeIf { it.isNotBlank() },
                            agent.workspaceLabel ?: agent.repo,
                            agent.tabLabel,
                        ).distinct().joinToString(" · "),
                        style = DsTypography.micro,
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            NeoIconButton(
                glyph = NeoGlyph.Refresh,
                onClick = onRefresh,
                contentDescription = stringResource(R.string.agent_cd_reread_screen),
                enabled = !detail.loading && !detail.sending,
            )
            overflowMenu()
        }
        // One fixed-height status strip instead of a variable notice stack.
        //
        // Previously every message (offline, stale, error, operation result, "no safe option")
        // added a card above the terminal, so the terminal grew and shrank as the agent's state
        // changed and the page never settled. Now exactly one line is shown, the most actionable
        // one, and the rest live behind the strip's action.
        StatusStrip(
            detail = detail,
            agent = agent,
            userPaused = userPaused,
            readingAway = readingAway,
            accessibilityPaused = accessibilityPaused,
            autoUpdateStateLabel = autoUpdateStateLabel,
            onTogglePause = { userPaused = !userPaused },
            onRefresh = onRefresh,
            onReconnect = onReconnect,
            onVerify = { confirmVerification = true },
        )

        // The layout switch sits on the page surface, not inside the dark well: a light segmented
        // control floating on black read as a piece of another product pasted on top.
        //
        // Terminal mode has no switch row. A terminal is judged on how much of the screen the shell
        // gets, and this row cost a full 48dp band above the grid for a control the reader uses once
        // per visit. The way back to the chat layout lives in the overflow menu.
        if (!minimal && showChat) Row(
            Modifier.fillMaxWidth().padding(start = DsSpace.l, end = DsSpace.m, top = DsSpace.xs, bottom = DsSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
        ) {
            NeoSegmented(
                options = listOf(
                    stringResource(R.string.agent_layout_chat),
                    stringResource(R.string.agent_layout_terminal),
                ),
                selectedIndex = 0,
                onSelect = { index: Int ->
                    chatLayout = index == 0
                },
                modifier = Modifier.weight(1f),
            )
        }
        // While the reader is scrolled up the chip floats over the well; the content keeps a lane
        // free for it so the last line of output is never covered.
        val chipLane = if (stickToBottom) 0.dp else 56.dp
        NeoWell(
            // Terminal mode goes edge to edge. A rounded card inset from the page edges is a
            // document; the SSH page already paints its shell across the whole screen, and the two
            // pages should feel like the same instrument.
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = if (showChat) DsSpace.m else 0.dp),
            corner = if (showChat) DsRadius.well else 0.dp,
            fill = if (showChat) colors.baseRaised else TerminalBackground,
        ) {
            // fillMaxHeight, not fillMaxSize: the well is already width-constrained by its parent,
            // and asking for max size here re-measured it at the column's full width, which pushed
            // the whole page past the viewport.
            Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (detail.text.isBlank() && detail.loading) {
                            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                NeoSpinner(Modifier.size(20.dp), color = TerminalMuted)
                                Text(stringResource(R.string.agent_reading_screen), color = TerminalMuted)
                            }
                        } else if (showChat) {
                            TranscriptView(
                                modifier = Modifier.padding(bottom = chipLane),
                                onLongPress = { selecting = true },
                                blocks = chatBlocks,
                                sent = detail.sent.filterNot { it.echoed },
                                scroll = paneScroll,
                                loadingOlder = detail.loading && detail.text.isNotBlank(),
                                historyEnded = detail.historyEnded,
                                askPending = detail.ask != null,
                                working = agent.status == AgentStatus.WORKING,
                                changedAt = detail.textChangedAt,
                                aliveAt = detail.revisionChangedAt,
                            )
                        } else {
                            // A pane is a terminal, so it is drawn as one: fixed cells, no re-flow,
                            // the same ink as the SSH page. The old wrapped Text inside a padded
                            // column re-flowed column-aligned output and read as a document.
                            TerminalDump(
                                text = visibleText,
                                modifier = Modifier.padding(bottom = chipLane),
                                fontSize = TerminalUiState.DEFAULT_FONT_SIZE,
                                wrap = wrapOutput,
                                followTick = followTick,
                                onStickToBottom = { following ->
                                    stickToBottom = following
                                    if (following) unseenOutput = false
                                },
                                onReachedTop = onLoadMoreHistory,
                            )
                        }
                        if (detail.loading && detail.text.isNotBlank()) {
                            NeoSpinner(Modifier.align(Alignment.TopEnd).padding(12.dp).size(16.dp), color = TerminalMuted)
                        }
                        Box(Modifier.align(Alignment.BottomCenter).padding(DsSpace.s)) {
                            BackToBottomChip(
                                visible = !stickToBottom,
                                unseen = unseenOutput,
                                onClick = {
                                    stickToBottom = true
                                    unseenOutput = false
                                    followTick++
                                    scope.launch { paneScroll.animateScrollTo(paneScroll.maxValue) }
                                },
                            )
                        }
                    }
                }
            }
        }

        // Layout contract for this page (do not "simplify" it):
        //   BoxWithConstraints { Column(fillMaxSize) {
        //       Column(weight(1f)) { header; traversal; status strip; NeoWell(weight(1f)) }
        //       Column(dock)   // measured, never weighted
        //   } }
        // The dock must be the SECOND CHILD of the outer Column. When it ended up a sibling of that
        // Column inside the Box, Compose drew it first at the top-left and the weighted body took the
        // full height, so the composer covered the status bar. Keep the braces exactly as they are.
        Column(
            Modifier.fillMaxWidth().background(colors.base).padding(horizontal = spacing().gutter),
        ) {
            // The decision lives in the flow, not behind a button: the question, the exact words
            // the agent used, and every option stay on screen until answered. A bottom sheet hid
            // the very characters an approval is about, which is why it needed a second door back.
            AnimatedVisibility(
                visible = detail.ask != null && !typing,
                enter = fadeIn(tween(motionMillis(160))) + expandVertically(tween(motionMillis(200))),
                exit = fadeOut(tween(motionMillis(120))) + shrinkVertically(tween(motionMillis(160))),
            ) {
                val ask = detail.ask ?: return@AnimatedVisibility
                DecisionCard(
                    ask = ask,
                    sending = detail.sending,
                    enabled = detail.canChoose,
                    blockedReason = detail.error ?: detail.operationNotice ?: when {
                        detail.truncated -> stringResource(R.string.agent_blocked_truncated)
                        detail.operationUncertain -> stringResource(R.string.agent_blocked_unverified)
                        !detail.canChoose && !detail.sending -> stringResource(R.string.agent_blocked_cannot_approve)
                        else -> null
                    },
                    onChoose = onChoose,
                    onVerifyScreen = { chatLayout = false },
                    modifier = Modifier.padding(top = DsSpace.s),
                )
            }
            // The key bar is part of the terminal mode, not a switch: in 终端 you are driving a
            // shell, and the keys a shell needs are always the same. In 对话 there is nothing to
            // send keys to, so the bar is simply absent.
            AnimatedVisibility(!showChat) {
                Column {
                    // Same key material as the terminal key bar: NeoKey, not an outlined Material
                    // button, so a thumb learns one key shape across both pages. Two fixed rows
                    // instead of one sideways strip — scrolling hid y/n/Enter, the keys you need
                    // most when an agent asks a question.
                    // Lifted keys need their shadow's own room: at a 4dp gap the next key painted
                    // over the previous key's highlight and the row looked clipped.
                    Column(
                        Modifier.padding(vertical = NeoShadowBleed),
                        verticalArrangement = Arrangement.spacedBy(NeoShadowBleed + 2.dp),
                    ) {
                        // A shell has no y/n question to answer, so its second row carries the keys a
                        // shell actually needs: interrupts, EOF, suspend.
                        // With the keyboard up the second row is dropped: the terminal has half the
                        // screen left, and those keys still need a way to reach them without
                        // dismissing the keyboard. Escape and the arrows are the ones a shell needs
                        // while a line is being typed.
                        (if (typing) {
                            listOf(listOf("Esc" to "esc", "⇧Tab" to "__shift_tab", "^C" to "ctrl-c", "⌫" to "__backspace", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right"))
                        } else {
                            listOf(
                            listOf("Esc" to "esc", "⇧Tab" to "__shift_tab", "Tab" to "tab", "⌫" to "__backspace", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right"),
                            if (paneOnly) {
                                listOf("Ctrl" to "__ctrl", "^C" to "ctrl-c", "^D" to "ctrl-d", "PgUp" to "__page_up", "PgDn" to "__page_down", "Enter" to "enter")
                            } else {
                                // 中断 rides in the same row: on its own full-width line it cost a
                                // whole extra row of terminal output for one button.
                                listOf(
                                    "Ctrl" to "__ctrl",
                                    "y" to "y",
                                    "n" to "n",
                                    "Enter" to "enter",
                                    stringResource(R.string.agent_stop_short) to "__stop",
                                )
                            },
                            )
                        }).forEach { row ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(NeoShadowBleed),
                            ) {
                                row.forEach { (label, key) ->
                                    Box(
                                        Modifier.weight(1f).semantics {
                                            if (key == "__ctrl") stateDescription = ctrlStateLabel
                                            when (key) {
                                                "up" -> contentDescription = arrowUpDescription
                                                "down" -> contentDescription = arrowDownDescription
                                                "left" -> contentDescription = arrowLeftDescription
                                                "right" -> contentDescription = arrowRightDescription
                                            }
                                        },
                                    ) {
                                        NeoKey(
                                            label = label,
                                            onPress = {
                                                when {
                                                    key == "__stop" -> confirmStop = true
                                                    key == "__ctrl" -> ctrlLatched = !ctrlLatched
                                                    // Escape sequences go in as literal bytes through
                                                    // pane.send_input: the bridge's key vocabulary has
                                                    // no name for them, and a terminal sends bytes.
                                                    key == "__shift_tab" -> {
                                                        onSendRawBytes("\u001B[Z")
                                                        ctrlLatched = false
                                                    }
                                                    key == "__backspace" -> {
                                                        onSendRawBytes("")
                                                        ctrlLatched = false
                                                    }
                                                    key == "__page_up" -> {
                                                        onSendRawBytes("\u001B[5~")
                                                        ctrlLatched = false
                                                    }
                                                    key == "__page_down" -> {
                                                        onSendRawBytes("\u001B[6~")
                                                        ctrlLatched = false
                                                    }
                                                    ctrlLatched && key.length == 1 -> {
                                                        onSendKeys(listOf("ctrl-$key"))
                                                        ctrlLatched = false
                                                    }

                                                    else -> {
                                                        onSendKeys(listOf(key))
                                                        ctrlLatched = false
                                                    }
                                                }
                                            },
                                            modifier = Modifier.fillMaxWidth(),
                                            stretch = true,
                                            active = key == "__ctrl" && ctrlLatched,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            // One thing at a time in the dock: while the agent waits for a decision the composer
            // steps aside, so the options are never pushed below the keyboard or the gesture bar.
            AnimatedVisibility(visible = detail.ask == null || typing) {
            // A disabled Send button with no reason reads as a broken app. One line under the field
            // says which of the three blocks is in the way, so nobody sits typing into a composer
            // that silently refuses to submit.
            val sendHint = when {
                detail.draft.isBlank() || detail.canSend -> null
                detail.hostConnecting -> R.string.agent_send_blocked_connecting
                !detail.hostConnected -> R.string.agent_send_blocked_offline
                detail.canVerifyOperation -> R.string.agent_send_blocked_verify
                else -> R.string.agent_send_blocked_busy
            }
            Column(Modifier.fillMaxWidth()) {
            if (sendHint != null) {
                Text(
                    stringResource(sendHint),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    modifier = Modifier.padding(start = DsSpace.m, bottom = DsSpace.xxs),
                )
            }
            NeoWell(
                Modifier.fillMaxWidth().padding(bottom = DsSpace.xs),
                corner = DsRadius.control,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(start = DsSpace.l, end = DsSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = detail.draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = DsSpace.m).semantics { contentDescription = replyDescription },
                        textStyle = DsTypography.body.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = if (compact) 1 else 4,
                        decorationBox = { inner ->
                            if (detail.draft.isEmpty()) Text(
                                stringResource(
                                    when {
                                        showChat -> R.string.agent_reply_placeholder
                                        // A shell pane takes a command; an agent pane takes text
                                        // for whatever prompt box its TUI is drawing.
                                        paneOnly -> R.string.agent_input_placeholder
                                        else -> R.string.agent_pane_placeholder
                                    },
                                ),
                                style = DsTypography.body,
                                color = colors.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            inner()
                        },
                    )
                    if (showChat) {
                        // Slash commands differ per agent, so the list comes from the pane this
                        // very agent is painting (see AgentCommandCatalog); the button only puts
                        // text in the composer and never sends on its own.
                        NeoIconButton(
                            glyph = NeoGlyph.Command,
                            contentDescription = stringResource(R.string.agent_commands),
                            onClick = { commandsOpen = true },
                            modifier = Modifier.padding(end = DsSpace.s),
                        )
                    }
                    NeoButton(
                        label = stringResource(
                            when {
                                showChat -> R.string.agent_send_reply
                                paneOnly -> R.string.agent_run
                                else -> R.string.agent_pane_send
                            },
                        ),
                        onClick = { if (showChat) onSendPrompt(detail.draft) else onSendInput(detail.draft) },
                        tone = NeoButtonTone.Primary,
                        enabled = detail.draft.isNotBlank() && detail.canSend,
                        compact = true,
                        modifier = Modifier.semantics { contentDescription = sendDescription },
                    )
                }
            }
            }
            }
        }
    }
    }
    if (selecting) SelectionSheet(condenseScreenText(stripAnsi(detail.text)), onOpenLink = { link ->
        openLink(context, link)
    }, onCopy = {
        clipboard.setText(AnnotatedString(it))
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
    }, onDismiss = { selecting = false })
    if (commandsOpen) {
        val cli = cliOf(agent.kind)
        val discovered = remember(detail.text) { discoverCommands(detail.text) }
        CommandSheet(
            cli = cli,
            discovered = discovered,
            curated = curatedCommands(cli),
            onPick = { text ->
                commandsOpen = false
                onDraftChange(if (detail.draft.isBlank()) text else detail.draft.trimEnd() + " " + text)
            },
            onDismiss = { commandsOpen = false },
        )
    }
    detail.explain?.let { text ->
        // Read-only explainer: the only action is "close", so it is the ghost dismissal slot.
        NeoDialog(
            onDismissRequest = onDismissExplain,
            title = stringResource(R.string.agent_explain_title),
            dismissLabel = stringResource(R.string.common_close),
        ) {
            SelectionContainer {
                Text(
                    text,
                    style = MonoStyle,
                    color = colors.text,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
        }
    }
    if (confirmVerification) {
        NeoDialog(
            onDismissRequest = { confirmVerification = false },
            title = stringResource(R.string.agent_confirm_verify_title),
            subtitle = stringResource(R.string.agent_confirm_verify_body),
            confirmLabel = stringResource(R.string.agent_confirm_verify_action),
            onConfirm = { confirmVerification = false; onVerifyOperation() },
            dismissLabel = stringResource(R.string.agent_confirm_verify_dismiss),
            confirmEnabled = detail.canVerifyOperation,
        )
    }
    if (renaming) {
        var value by remember(agent.key) { mutableStateOf(agent.name) }
        NeoDialog(
            onDismissRequest = { renaming = false },
            title = stringResource(R.string.agent_rename_title),
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = { onRename(value.trim()); renaming = false },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmEnabled = value.isNotBlank() && value.trim() != agent.name,
        ) {
            NeoField(
                value = value,
                onValueChange = { value = it.take(40) },
                label = stringResource(R.string.agent_rename_label),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
    if (confirmStop) {
        NeoDialog(
            onDismissRequest = { confirmStop = false },
            title = stringResource(R.string.agent_confirm_stop_title, agent.name),
            subtitle = stringResource(R.string.agent_confirm_stop_body, agent.hostLabel, agent.paneId),
            confirmLabel = stringResource(R.string.agent_stop),
            onConfirm = { confirmStop = false; onStop() },
            dismissLabel = stringResource(R.string.common_cancel),
            destructive = true,
            confirmEnabled = detail.canSend,
        )
    }
}


/**
 * The only status line on the page: one row, one message, one action.
 *
 * Priority is deliberate — an error outranks an uncertain operation result, which outranks
 * staleness, which outranks the reading clock. Everything else stays reachable (refresh, pause,
 * reconnect, verify) without ever changing the height of the terminal below.
 */
@Composable
private fun StatusStrip(
    detail: DetailState,
    agent: Agent,
    userPaused: Boolean,
    readingAway: Boolean,
    accessibilityPaused: Boolean,
    autoUpdateStateLabel: String,
    onTogglePause: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onVerify: () -> Unit,
) {
    val colors = ds()
    val error = detail.error
    val notice = detail.operationNotice
    val lamp: Color
    val message: String
    var action: (@Composable () -> Unit)? = null
    when {
        error != null -> {
            lamp = colors.danger
            message = error
            if (detail.hostConnected) action = {
                NeoButton(
                    label = stringResource(R.string.agent_reread),
                    onClick = onRefresh,
                    tone = NeoButtonTone.Ghost,
                    enabled = !detail.loading,
                    compact = true,
                )
            }
        }

        notice != null -> {
            lamp = if (detail.operationUncertain) colors.warn else colors.ok
            message = notice
            if (detail.canVerifyOperation) action = {
                NeoButton(
                    label = stringResource(R.string.agent_verify_remote),
                    onClick = onVerify,
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                )
            }
        }

        !detail.hostConnected -> {
            lamp = colors.warn
            message = stringResource(R.string.agent_host_disconnected, agent.hostLabel)
            action = {
                NeoButton(
                    label = stringResource(
                        if (detail.hostConnecting) R.string.agent_reconnecting else R.string.agent_reconnect_and_read,
                    ),
                    onClick = onReconnect,
                    tone = NeoButtonTone.Ghost,
                    enabled = !detail.hostConnecting,
                    compact = true,
                )
            }
        }

        detail.stale -> {
            lamp = colors.warn
            message = stringResource(R.string.agent_evidence_not_synced)
            // The message used to be a dead end: evidence stays suspect until something re-reads
            // it, and while the reader has scrolled away nothing polls. Give it the same action the
            // error branch has.
            if (detail.hostConnected) action = {
                NeoButton(
                    label = stringResource(R.string.agent_reread),
                    onClick = onRefresh,
                    tone = NeoButtonTone.Ghost,
                    enabled = !detail.loading,
                    compact = true,
                )
            }
        }

        accessibilityPaused -> {
            lamp = colors.textFaint
            message = stringResource(R.string.agent_pause_talkback)
        }

        // A restored reading bookmark can carry a pause from an older version. Without this branch
        // the page simply stopped refreshing with no control to start it again. `readingAway` is
        // deliberately not shown here: returning to the bottom is its own resume.
        userPaused -> {
            lamp = colors.textFaint
            message = autoUpdateStateLabel
            action = {
                NeoButton(
                    label = stringResource(R.string.agent_resume_updates),
                    onClick = onTogglePause,
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                )
            }
        }

        else -> return
    }
    Row(
        Modifier.fillMaxWidth().padding(start = DsSpace.m, end = DsSpace.m, top = DsSpace.xs, bottom = DsSpace.xxs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
    ) {
        NeoLamp(color = lamp, size = 6.dp, modifier = Modifier.padding(top = 3.dp).clearAndSetSemantics { })
        Text(
            message,
            style = DsTypography.caption,
            color = if (error != null) colors.danger else colors.textSoft,
            // A failed read used to be cut to a single line, so the reason ("host closed the
            // connection: timeout") was exactly the part that never made it on screen.
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}


/**
 * The decision, in the conversation flow.
 *
 * Shape borrowed from the way agent CLIs ask on a desktop: a title line, the literal question, the
 * risk if the parser found one, then one row per option with its scope written out. Nothing here is
 * collapsed by default — an approval is the one thing on this page that must be read before acting.
 * A quiet footer offers the raw screen for anyone who wants to check the characters themselves.
 */
@Composable
private fun DecisionCard(
    ask: Ask,
    sending: Boolean,
    enabled: Boolean,
    blockedReason: String?,
    onChoose: (AskOption) -> Unit,
    onVerifyScreen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ds()
    NeoCard(
        modifier = modifier.fillMaxWidth(),
        depth = DsElev.rest,
        corner = DsRadius.control,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(DsSpace.m),
            verticalArrangement = Arrangement.spacedBy(DsSpace.s),
        ) {
            DecisionPrompt(ask)
            ApprovalActions(ask, sending, enabled, blockedReason, onChoose)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(
                        if (ask.confidence == AskConfidence.HIGH) R.string.agent_decision_from_screen
                        else R.string.agent_decision_check_screen,
                    ),
                    style = DsTypography.micro,
                    color = colors.textFaint,
                    modifier = Modifier.weight(1f),
                )
                NeoButton(
                    label = stringResource(R.string.agent_verify_screen_source),
                    onClick = onVerifyScreen,
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                )
            }
        }
    }
}

@Composable
private fun DecisionPrompt(ask: Ask) {
    val colors = ds()
    Column(
        Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(DsSpace.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
            Text(
                stringResource(R.string.agent_need_decision),
                style = DsTypography.caption,
                color = colors.accent,
                modifier = Modifier.semantics { heading() },
            )
            if (ask.confidence != AskConfidence.HIGH) Text(
                stringResource(R.string.agent_recognition_unverified),
                style = DsTypography.caption,
                color = colors.textSoft,
            )
        }
        SelectionContainer { Text(ask.question, style = DsTypography.headline, color = colors.text) }
        ask.risk?.let { risk ->
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s), verticalAlignment = Alignment.Top) {
                NeoIcon(
                    NeoGlyph.Warning,
                    modifier = Modifier.padding(top = DsSpace.xxs).size(14.dp),
                    tint = colors.danger,
                    contentDescription = null,
                )
                Text(risk, style = DsTypography.caption, color = colors.text, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ApprovalActions(ask: Ask, sending: Boolean, enabled: Boolean, blockedReason: String?, onChoose: (AskOption) -> Unit) {
    val colors = ds()
    var pendingStanding by remember(ask) { mutableStateOf<AskOption?>(null) }
    val request: (AskOption) -> Unit = { option ->
        if (requiresStandingConfirmation(option)) pendingStanding = option else onChoose(option)
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
        NeoDivider()
        Text(
            stringResource(R.string.agent_choose_action),
            style = DsTypography.caption,
            color = colors.textFaint,
            modifier = Modifier.semantics { heading() },
        )
        // Keep every option visible, including multiple ALLOW choices with different scopes.
        ask.options.forEach { option ->
            val scope = approvalScopeOf(option)
            ApprovalOption(
                label = if (sending && option == ask.allow) stringResource(R.string.agent_submitting) else option.label,
                scopeLabel = when (scope) {
                    ApprovalScope.ONCE -> stringResource(R.string.agent_scope_once)
                    ApprovalScope.SESSION -> stringResource(R.string.agent_scope_session)
                    ApprovalScope.STANDING -> stringResource(R.string.agent_scope_standing)
                    ApprovalScope.DENY, ApprovalScope.OTHER -> null
                },
                danger = requiresStandingConfirmation(option),
                enabled = enabled,
            ) { request(option) }
        }
        Text(blockedReason ?: stringResource(R.string.agent_approval_review_hint), style = DsTypography.caption, color = colors.textSoft)
    }
    pendingStanding?.let { option ->
        NeoDialog(
            onDismissRequest = { pendingStanding = null },
            title = stringResource(R.string.agent_standing_title),
            subtitle = stringResource(R.string.agent_standing_body, option.label),
            confirmLabel = stringResource(R.string.agent_standing_confirm),
            onConfirm = { pendingStanding = null; onChoose(option) },
            dismissLabel = stringResource(R.string.common_cancel),
            destructive = true,
            confirmEnabled = enabled,
        )
    }
}

/**
 * Full-width approval choice: the whole label must stay readable, so this is a lifted quiet slab
 * rather than a [NeoButton], which can only show one ellipsised line. Standing (long-lived)
 * authorisations keep the danger ink; everything else is text — no accent is added to approving.
 */
@Composable
private fun ApprovalOption(
    label: String,
    scopeLabel: String?,
    danger: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = ds()
    NeoCard(
        modifier = modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp),
        depth = if (enabled) DsElev.soft else DsElev.flat,
        onClick = if (enabled) onClick else null,
    ) {
        Row(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = DsSpace.l, vertical = DsSpace.m),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
        ) {
            Text(
                label,
                style = DsTypography.bodyStrong,
                color = when {
                    !enabled -> colors.textFaint
                    danger -> colors.danger
                    else -> colors.text
                },
                modifier = Modifier.weight(1f),
            )
            // Three buttons that say "Yes" in three different lifetimes are the one place where a
            // missing word costs the user something permanent.
            if (scopeLabel != null) {
                Text(
                    scopeLabel,
                    style = DsTypography.micro,
                    color = when {
                        !enabled -> colors.textFaint
                        danger -> colors.danger
                        else -> colors.textSoft
                    },
                    maxLines = 1,
                )
            }
        }
    }
}

/** Display-only folding. The original snapshot remains the source for copying and approval. */
private fun focusTerminalText(text: String): String {
    val result = mutableListOf<String>()
    var blanks = 0
    text.lineSequence().forEach { line ->
        val trimmed = line.trim()
        val decorative = trimmed.length >= 4 && trimmed.all { it in "─━═┌┐└┘├┤┬┴┼╭╮╰╯│┃╔╗╚╝╠╣╦╩╬+|-=_ " }
        if (decorative) return@forEach
        if (trimmed.isBlank()) {
            blanks++
            if (blanks <= 1) result.add("")
        } else { blanks = 0; result.add(line) }
    }
    // Trailing chrome: an agent TUI prints a status footer (cwd, token counters, model name) after
    // its prose. In a reading view those lines are the least useful thing on the screen.
    while (result.isNotEmpty() && isStatusFooter(result.last())) result.removeAt(result.lastIndex)
    return result.joinToString("\n").trim('\n')
}

/** Heuristic for an agent's own footer lines. Deliberately narrow: it must never eat prose. */
private fun isStatusFooter(line: String): Boolean {
    val t = line.trim()
    if (t.isEmpty()) return true
    if (t.startsWith("↑") || t.startsWith("↓")) return true
    if (t.startsWith("/") && t.none { it.isWhitespace() }) return true
    val tokens = t.split(" · ").map { it.trim() }
    if (tokens.size == 2 && tokens[1].lowercase() in setOf("high", "medium", "low")) return true
    return Regex("^[\\w.-]+/[\\w.-]+$").matches(t)
}

/**
 * A screen read with SGR is padded and full of holes: every row is padded to the PTY width and a
 * TUI leaves blank rows between its blocks. Copying that verbatim gives a page of spaces with a
 * sentence in the middle, so runs of blank lines collapse to one and trailing padding goes.
 */
internal fun condenseScreenText(text: String): String {
    val out = ArrayList<String>(text.length / 40 + 4)
    var blanks = 0
    var rules = 0
    for (raw in text.lines()) {
        val line = raw.trimEnd()
        val rule = line.length >= 8 && line.all { it in "─━═┄┈-—_ " }
        if (line.isBlank()) {
            blanks++
            if (blanks > 1) continue
            out.add("")
            rules = 0
            continue
        }
        if (rule) {
            // A TUI draws its frame with dozens of rule rows; one is a separator, twenty is a wall.
            rules++
            if (rules > 1) continue
        } else {
            blanks = 0
            rules = 0
        }
        out.add(line)
    }
    return out.joinToString("\n").trim()
}

@Composable
private fun SelectionSheet(
    text: String,
    onCopy: (String) -> Unit,
    onOpenLink: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(text) { mutableStateOf(TextFieldValue(text)) }
    val selection = value.selection
    val selected = if (!selection.collapsed) text.substring(selection.min.coerceIn(0, text.length), selection.max.coerceIn(0, text.length)) else ""
    val terminal = LocalTerminalTokens.current
    // The picker is drawn on the terminal material, so the design palette is remapped to the
    // terminal inks for everything inside it: a chassis-dark label on a dark well would be
    // unreadable in the light theme. Layout, callbacks and gating are unchanged.
    val colors = ds().copy(
        baseRaised = terminal.background,
        well = terminal.background,
        text = terminal.text,
        textSoft = terminal.muted,
        textFaint = terminal.muted,
        accent = terminal.accent,
    )
    CompositionLocalProvider(LocalDs provides colors) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.88f)
                    .background(terminal.background, RoundedCornerShape(DsRadius.card))
                    .padding(DsSpace.m),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_select_copy_title), style = DsTypography.title, color = colors.text, modifier = Modifier.weight(1f))
                    NeoButton(
                        label = stringResource(R.string.agent_select_all),
                        onClick = { value = value.copy(selection = TextRange(0, text.length)) },
                        tone = NeoButtonTone.Ghost,
                        compact = true,
                    )
                    NeoButton(
                        label = stringResource(R.string.common_copy),
                        onClick = { onCopy(selected) },
                        tone = NeoButtonTone.Ghost,
                        compact = true,
                        enabled = selected.isNotEmpty(),
                    )
                    NeoButton(
                        label = stringResource(R.string.common_close),
                        onClick = onDismiss,
                        tone = NeoButtonTone.Ghost,
                        compact = true,
                    )
                }
                Text(stringResource(R.string.agent_selection_hint), style = DsTypography.caption, color = colors.textSoft)
                // Same reason as the terminal sheet: a link on the screen is what the screen is
                // about, and selecting it by hand on a phone is not a reasonable price.
                val links = remember(text) { urlsIn(text) }
                if (links.isNotEmpty()) {
                    Row(Modifier.fillMaxWidth().padding(top = DsSpace.xs), horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
                        links.forEach { link ->
                            NeoButton(
                                label = link,
                                onClick = { onDismiss(); onOpenLink(link) },
                                tone = NeoButtonTone.Quiet,
                                compact = true,
                                icon = NeoGlyph.Link,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(DsSpace.s))
                // Open at the end of the screen: the newest thing the agent printed is the reason
                // anybody opens this, and the top of a PTY snapshot is usually the oldest.
                Box(
                    Modifier.weight(1f).fillMaxWidth()
                        .verticalScroll(rememberScrollState(initial = Int.MAX_VALUE))
                        .padding(DsSpace.s),
                ) {
                    BasicTextField(value = value, onValueChange = { value = it }, readOnly = true, modifier = Modifier.fillMaxWidth(), textStyle = MonoStyle.copy(color = colors.text), cursorBrush = SolidColor(Color.Transparent))
                }
            }
        }
    }
}

/** Floating "back to bottom" affordance. Kept at file level so it is not lexically inside a ColumnScope. */
@Composable
private fun BackToBottomChip(visible: Boolean, unseen: Boolean, onClick: () -> Unit) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(motionMillis(140))) + scaleIn(tween(motionMillis(160)), initialScale = 0.92f),
        exit = fadeOut(tween(motionMillis(110))) + scaleOut(tween(motionMillis(120)), targetScale = 0.92f),
    ) {
        // A solid chip, not a quiet button. The quiet tone is a transparent surface meant to sit on
        // the page chassis; floating over terminal output it let the text underneath read through
        // the label, so the control looked like a rendering glitch.
        Row(
            Modifier
                .shadow(8.dp, CircleShape)
                .clip(CircleShape)
                .background(TerminalBackground)
                .border(1.dp, Color.White.copy(alpha = 0.16f), CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .defaultMinSize(minHeight = 48.dp)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DsSpace.xs),
        ) {
            NeoIcon(NeoGlyph.ArrowDownToLine, modifier = Modifier.size(14.dp), tint = TerminalText, contentDescription = null)
            Text(
                text = if (unseen) stringResource(R.string.agent_back_to_bottom_new) else stringResource(R.string.agent_back_to_bottom),
                style = DsTypography.caption,
                color = TerminalText,
                maxLines = 1,
            )
        }
    }
}

/**
 * Chat layout: the same snapshot, shaped as turns.
 *
 * Chat is a reading surface, so it uses the page material and bubbles; the terminal keeps the dark
 * grid. Mixing the two into one skin was what made the two modes look like the same page with a
 * different filter.
 */
@Composable
private fun TranscriptView(
    modifier: Modifier = Modifier,
    onLongPress: () -> Unit = {},
    blocks: List<TranscriptBlock>,
    sent: List<SentMessage>,
    scroll: androidx.compose.foundation.ScrollState,
    loadingOlder: Boolean = false,
    historyEnded: Boolean = false,
    askPending: Boolean = false,
    working: Boolean = false,
    changedAt: Long = 0L,
    aliveAt: Long = 0L,
) {
    val colors = ds()
    val haptics = LocalHapticFeedback.current
    Column(
        modifier.fillMaxWidth().fillMaxHeight().verticalScroll(scroll)
            // Selection inside a scrolling column of bubbles never reliably started on device (the
            // same failure the terminal grid had), so a long press opens the transcript as text.
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPress()
                })
            }
            .padding(DsSpace.m),
        verticalArrangement = Arrangement.spacedBy(DsSpace.m),
    ) {
        // Reading further back is silent: a request goes out when the reader hits the top and this
        // line is the only trace. Without it a slow host looked like a page that simply refused to
        // scroll, which is exactly what "can't get the history" felt like.
        if (loadingOlder) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeoSpinner(Modifier.size(14.dp), color = colors.textFaint, strokeWidth = 1.6.dp)
                Spacer(Modifier.width(DsSpace.xs))
                Text(
                    stringResource(R.string.agent_reading_older),
                    style = DsTypography.caption,
                    color = colors.textFaint,
                )
            }
        }
        // The bridge has no cursor, so history can only be a bigger tail. When even the biggest
        // tail the host will serve fails, the reader deserves one plain sentence instead of an
        // error strip that looks like the machine dropped.
        if (historyEnded) {
            Text(
                stringResource(R.string.agent_history_end),
                style = DsTypography.caption,
                color = colors.textFaint,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        blocks.forEachIndexed { index, block ->
            // Only the newest turn animates: it is the one that changed. Animating a list of
            // forty cards on every poll is exactly the "everything jitters" effect users call
            // cheap.
            if (index == blocks.lastIndex && index > 0) {
                NewestTurn(block.text) { TranscriptBlockView(block, askPending) }
            } else {
                TranscriptBlockView(block, askPending)
            }
        }
        // Our own sends appear immediately, even before the agent echoes them on the screen.
        // They disappear from here once the snapshot carries them, so nothing is shown twice.
        sent.forEach { message ->
            TranscriptBlockView(TranscriptBlock(TranscriptRole.USER, message.text, message.text, false), false)
        }
        if (working) WorkingHint(changedAt, aliveAt)
        // No "看原文" button here: the 对话/终端 switch above is that action, and a second door to
        // the same room was pure noise at the bottom of every conversation.
    }
}

/**
 * The one live line in a conversation.
 *
 * Silence while an agent works reads as "nothing is happening"; a spinner reads as "the app is
 * busy", which is a different claim. This says how long the screen has been still, and stops
 * pretending after a minute — an agent that has printed nothing for a minute may simply be stuck.
 */
@Composable
private fun WorkingHint(changedAt: Long, aliveAt: Long) {
    val colors = ds()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(changedAt, aliveAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val quietFor = if (changedAt <= 0L) 0L else (now - changedAt).coerceAtLeast(0L)
    // A spinner keeps repainting, so "no new text" alone is not evidence of a stall. Only when
    // nothing at all has changed on the far end is this worth calling out, and even then it is a
    // fact about the screen, not a diagnosis.
    val repainting = aliveAt > 0L && (now - aliveAt).coerceAtLeast(0L) < 15_000L
    val label = when {
        quietFor < 8_000L -> stringResource(R.string.agent_working_fresh, relativeSeconds(quietFor))
        quietFor < 60_000L -> stringResource(R.string.agent_working_quiet, relativeSeconds(quietFor))
        repainting -> stringResource(R.string.agent_working_repainting, relativeSeconds(quietFor))
        else -> stringResource(R.string.agent_working_stalled, relativeSeconds(quietFor))
    }
    val alarming = quietFor >= 60_000L && !repainting
    Row(
        Modifier.fillMaxWidth().padding(top = DsSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.xs),
    ) {
        NeoLamp(color = if (alarming) colors.warn else colors.accent, size = 6.dp)
        Text(
            label,
            style = DsTypography.caption,
            color = if (alarming) colors.warn else colors.textSoft,
        )
    }
}

/** Coarse, human durations: "3 秒", "2 分钟". Nobody wants 137 秒. */
internal fun relativeSeconds(millis: Long): String = when {
    millis < 60_000L -> (millis / 1_000L).toString()
    else -> (millis / 60_000L).toString()
}

/** A short, small rise for the turn that just changed. Off when the user asked for less motion. */
@Composable
private fun NewestTurn(key: String, content: @Composable () -> Unit) {
    val reduced = LocalReducedMotion.current
    val duration = motionMillis(DsMotion.base)
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key, reduced, duration) {
        if (reduced) {
            progress.snapTo(1f)
        } else {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(duration))
        }
    }
    val rise = with(LocalDensity.current) { 10.dp.toPx() }
    Box(
        Modifier.graphicsLayer {
            alpha = 0.35f + 0.65f * progress.value
            translationY = (1f - progress.value) * rise
        },
    ) {
        content()
    }
}

/** One turn. My words sit right in an accent wash; the agent's sit left on a raised card. */
@Composable
private fun TranscriptBlockView(block: TranscriptBlock, askPending: Boolean = false) {
    val colors = ds()
    val roleLabel = stringResource(
        when (block.role) {
            TranscriptRole.USER -> R.string.agent_role_user
            TranscriptRole.ASSISTANT -> R.string.agent_role_assistant
            TranscriptRole.THINKING -> R.string.agent_role_thinking
            TranscriptRole.TOOL -> R.string.agent_role_tool
            TranscriptRole.APPROVAL -> R.string.agent_role_approval
            TranscriptRole.SYSTEM -> R.string.agent_role_system
        },
    )
    val prose = DsTypography.body.copy(lineHeight = 22.sp)
    when (block.role) {
        TranscriptRole.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                    .background(colors.accentWash)
                    .padding(horizontal = DsSpace.m, vertical = DsSpace.s),
            ) {
                Text(block.text, style = prose, color = colors.text)
            }
        }

        TranscriptRole.THINKING -> ThinkingBlock(block, roleLabel)

        TranscriptRole.TOOL -> ToolBlock(block, roleLabel)

        TranscriptRole.SYSTEM -> Text(
            block.text,
            style = DsTypography.caption,
            color = colors.textFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        TranscriptRole.APPROVAL -> Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DsRadius.control))
                .background(colors.accentWash)
                .padding(DsSpace.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
                NeoIcon(NeoGlyph.AlertTriangle, modifier = Modifier.size(14.dp), tint = colors.warn)
                Text(roleLabel, style = DsTypography.caption, color = colors.warn, maxLines = 1)
            }
            Spacer(Modifier.height(DsSpace.xxs))
            // While the decision panel is open the options belong to the panel: the same three
            // choices used to appear twice within one thumb's reach, and the copy in the flow was
            // the one that looked tappable but was not.
            Text(
                if (askPending) approvalQuestionOnly(block.text) else block.text,
                style = prose,
                color = colors.text,
            )
            // No "choose below" hint: the options are the next thing on screen, and a sentence
            // pointing at them is one more line between the question and the answer.
        }

        else -> Column(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
                NeoLamp(color = colors.accent, size = 6.dp)
                Text(roleLabel, style = DsTypography.caption, color = colors.textSoft, maxLines = 1)
                if (block.truncatedHead) {
                    Text(stringResource(R.string.agent_block_cut), style = DsTypography.caption, color = colors.textFaint, maxLines = 1)
                }
            }
            Spacer(Modifier.height(DsSpace.xxs))
            // Selectable: an agent's answer is the thing people copy out of this page — a command,
            // a path, a paragraph for a report. Plain Text could only be read, never taken.
            SelectionContainer {
            NeoCard(Modifier.fillMaxWidth(), depth = DsElev.soft) {
                // A turn is not always one paragraph. Screen-scraped output arrives as a block of
                // dozens of lines, and painting it as a single run made every long answer look like
                // one uninterrupted wall — the reader lost the sentence they came for.
                val paragraphs = remember(block.text) { paragraphsOf(block.text) }
                // Budget in rendered columns, not characters: 700 Chinese characters is thirty
                // lines on a phone, which is the same wall the fold was supposed to remove.
                val total = remember(block.text) { visualWidth(block.text) }
                val long = total > COLLAPSED_COLUMNS
                var expanded by remember(block.text) { mutableStateOf(!long) }
                Column(
                    Modifier.fillMaxWidth().padding(DsSpace.m),
                    verticalArrangement = Arrangement.spacedBy(DsSpace.s),
                ) {
                    // Collapsed, keep whole paragraphs up to the budget instead of cutting the first
                    // one mid-sentence: a half sentence reads as data loss.
                    val (shown, cut) = if (expanded) {
                        paragraphs to false
                    } else {
                        foldToBudget(paragraphs, COLLAPSED_COLUMNS)
                    }
                    // Truthful count: what the reader cannot see, in lines, never a guess.
                    val hiddenLines = (
                        (total - shown.sumOf { visualWidth(it) }) / COLUMNS_PER_LINE
                        ).coerceAtLeast(if (cut) 1 else 0)
                    shown.forEach { paragraph ->
                        Text(paragraph, style = prose, color = colors.text)
                    }
                    if (long) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable(role = Role.Button) { expanded = !expanded }
                                .padding(top = DsSpace.s),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(DsSpace.xs),
                        ) {
                            NeoIcon(
                                if (expanded) NeoGlyph.ChevronUp else NeoGlyph.ChevronDown,
                                modifier = Modifier.size(14.dp),
                                tint = colors.accent,
                            )
                            Text(
                                if (expanded) {
                                    stringResource(R.string.agent_collapse_block)
                                } else {
                                    // "Show all" without a number is a leap of faith; the count is
                                    // what makes folding acceptable rather than lossy.
                                    pluralStringResource(R.plurals.agent_expand_block_lines, hiddenLines, hiddenLines)
                                },
                                style = DsTypography.caption,
                                color = colors.accent,
                            )
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * The approval question without its numbered options.
 *
 * The transcript block and the decision panel read the same screen text, so the panel's three
 * choices used to appear a second time just above it — with the copy in the flow looking tappable
 * while only the panel actually worked. While the panel is open it owns the options; the flow keeps
 * the question so the conversation still reads in order.
 */
internal fun approvalQuestionOnly(text: String): String {
    val kept = text.lines().filterNot { OPTION_LINE.matches(it.trim()) }.joinToString("\n").trim()
    return kept.ifBlank { text.trim() }
}

private val OPTION_LINE = Regex("^[^\\p{L}\\p{N}]*\\d+[.)]\\s+\\S.*$")

/** Above this a single turn is folded: one agent dump should not bury the next exchange. */

/**
 * Fold budget measured the way the reader sees it: a column is one Latin glyph or half a CJK one,
 * and a phone line holds about 30 of them.
 */
private const val COLUMNS_PER_LINE = 30
private const val COLLAPSED_COLUMNS = COLUMNS_PER_LINE * 12

/**
 * Keeps whole paragraphs while they fit, and only cuts inside a paragraph when a single one is
 * longer than the whole budget — at a sentence boundary, never mid-clause.
 */
internal fun foldToBudget(paragraphs: List<String>, budget: Int): Pair<List<String>, Boolean> {
    val kept = mutableListOf<String>()
    var used = 0
    for (paragraph in paragraphs) {
        val width = visualWidth(paragraph)
        if (kept.isEmpty()) {
            if (width <= budget) {
                kept += paragraph
                used += width
                continue
            }
            // One paragraph longer than the whole budget: cut where a reader would stop.
            var acc = 0
            var cutAt = -1
            for ((index, ch) in paragraph.withIndex()) {
                acc += visualWidth(ch.toString())
                val boundary = ch == ' ' || ch.code == 10 || ch in "。！？!?；;."
                if (boundary && acc >= budget * 6 / 10) {
                    cutAt = index + 1
                    break
                }
                if (acc > budget) break
            }
            if (cutAt <= 0) cutAt = paragraph.length.coerceAtMost(budget)
            kept += paragraph.take(cutAt) + "…"
            return kept to true
        }
        if (used + width > budget) return kept to true
        kept += paragraph
        used += width
    }
    return kept to false
}

internal fun visualWidth(text: String): Int {
    var width = 0
    for (ch in text) {
        width += when {
            ch == '\n' -> COLUMNS_PER_LINE
            ch.code in 0x1100..0x115F || ch.code in 0x2E80..0xA4CF ||
                ch.code in 0xAC00..0xD7A3 || ch.code in 0xF900..0xFAFF ||
                ch.code in 0xFE30..0xFE4F || ch.code in 0xFF00..0xFF60 ||
                ch.code in 0xFFE0..0xFFE6 -> 2
            else -> 1
        }
    }
    return width
}

/**
 * Split a turn into readable paragraphs.
 *
 * Blank lines separate paragraphs; when a dump has no blank lines but many short lines (command
 * output, lists), each line is its own paragraph so the rhythm is visible again.
 */
internal fun paragraphsOf(text: String): List<String> {
    val trimmed = text.trim()
    if (trimmed.isEmpty()) return emptyList()
    val parts = trimmed.split(Regex("\n\\s*\n")).map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size > 1) return collapseRepeats(parts)
    val lines = trimmed.lines().map { it.trimEnd() }
    return if (lines.size >= 4 && lines.count { it.isBlank() } == 0) {
        collapseRepeats(lines.filter { it.isNotBlank() })
    } else {
        listOf(trimmed)
    }
}

/**
 * A TUI repaints its own message, so a snapshot can hold the same paragraph three or four times in
 * a row. Printing it verbatim buries the answer; dropping it silently lies about the screen. Keep
 * one copy and say how many there were.
 */
internal fun collapseRepeats(parts: List<String>): List<String> {
    if (parts.size < 2) return parts
    val out = ArrayList<String>(parts.size)
    var index = 0
    while (index < parts.size) {
        val current = parts[index]
        var repeat = 1
        while (index + repeat < parts.size && parts[index + repeat] == current) repeat++
        if (repeat >= 2 && current.length >= 20) {
            out.add(collapseRepeatedBlock(current) + "  " + '×' + repeat)
        } else {
            var n = 0
            while (n < repeat) { out.add(current); n++ }
        }
        index += repeat
    }
    return out
}

/**
 * The same repaint can repeat a block of prose inside a single paragraph ("...escape.The literal
 * newline ... broke it. Use an escape.The literal ..."), which no paragraph split can see. If the
 * text is a block repeated two or more times, keep one copy and say how many there were.
 */
internal fun collapseRepeatedBlock(text: String): String {
    if (text.length < 40) return text
    val limit = text.length / 2
    var size = 1
    while (size <= limit) {
        if (text.length >= size * 2 && text.regionMatches(0, text, size, size)) {
            var copies = 1
            while ((copies + 1) * size <= text.length &&
                text.regionMatches(0, text, copies * size, size)
            ) {
                copies++
            }
            if (copies >= 2) {
                val block = text.substring(0, size)
                val rest = text.substring(copies * size).trim()
                val marker = " " + '×' + copies
                return if (rest.isEmpty()) block + marker else block + marker + " " + rest
            }
        }
        size++
    }
    return text
}

/**
 * Reasoning, folded by default.
 *
 * It is context, not the answer: a footnote above the reply that opens on demand. Rendering it as
 * body text doubled the length of every turn and buried the sentence the user came to read.
 */
@Composable
private fun ThinkingBlock(block: TranscriptBlock, roleLabel: String) {
    val colors = ds()
    var expanded by remember(block.text) { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            // Same 48dp floor as every other tappable row; a 30dp disclosure is not a thumb target.
            .defaultMinSize(minHeight = 48.dp)
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(vertical = DsSpace.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
            NeoIcon(NeoGlyph.Sparkle, modifier = Modifier.size(13.dp), tint = colors.textFaint)
            Text(
                pluralStringResource(
                    R.plurals.agent_thinking_lines,
                    block.text.lineSequence().count(),
                    roleLabel,
                    block.text.lineSequence().count(),
                ),
                style = DsTypography.caption,
                color = colors.textFaint,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            NeoIcon(
                if (expanded) NeoGlyph.ChevronUp else NeoGlyph.ChevronDown,
                modifier = Modifier.size(14.dp),
                tint = colors.textFaint,
            )
        }
        ExpandableSection(expanded) {
            Text(
                block.text,
                style = DsTypography.caption.copy(lineHeight = 20.sp),
                color = colors.textSoft,
                modifier = Modifier.padding(start = DsSpace.l, top = DsSpace.xxs),
            )
        }
    }
}

/** Tool output is evidence, not conversation: it collapses to one quiet line until asked for. */
@Composable
private fun ToolBlock(block: TranscriptBlock, roleLabel: String) {
    val colors = ds()
    var expanded by remember(block.text) { mutableStateOf(false) }
    NeoWell(Modifier.fillMaxWidth(), corner = DsRadius.control) {
        Column(
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp)
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = DsSpace.m, vertical = DsSpace.s),
        ) {
            val preview = remember(block.text) { toolPreview(block.text) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
                NeoIcon(NeoGlyph.Command, modifier = Modifier.size(14.dp), tint = colors.textSoft)
                // A run of identical "tools · 3 lines" pills says nothing; the first line of what it
                // ran does. The count stays, because it is the only clue about how much is hidden.
                Text(
                    pluralStringResource(
                        R.plurals.agent_tool_lines,
                        block.text.lineSequence().count(),
                        roleLabel,
                        block.text.lineSequence().count(),
                    ),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (preview != null && !expanded) {
                    Text(
                        preview,
                        style = DsTypography.micro.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = colors.textFaint,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(2f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                NeoIcon(
                    if (expanded) NeoGlyph.ChevronUp else NeoGlyph.ChevronDown,
                    modifier = Modifier.size(16.dp),
                    tint = colors.textFaint,
                )
            }
            ExpandableSection(expanded) {
                Spacer(Modifier.height(DsSpace.xs))
                // Fences stay in the parsed text so a re-parse still sees a closed block, but they
                // are markdown punctuation and have no business in the reader.
                Text(stripFence(block.text), style = MonoStyle, color = colors.text)
            }
        }
    }
}


/**
 * The slash commands this agent actually offers.
 *
 * Two sections, because they carry different weight: what the pane is printing right now is
 * authoritative for the version that is running, while the fallback list is a remembered few. When
 * the agent is unrecognised and the screen shows nothing, the sheet says so instead of inventing a
 * list — a wrong command typed into a live agent costs more than an empty panel.
 */
@Composable
private fun CommandSheet(
    cli: AgentCli,
    discovered: List<AgentCommand>,
    curated: List<AgentCommand>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = ds()
    NeoSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = DsSpace.xl, end = DsSpace.xl, bottom = DsSpace.xxl),
        ) {
            Text(stringResource(R.string.agent_commands_title), style = DsTypography.title, color = colors.text)
            Text(
                stringResource(R.string.agent_commands_body),
                style = DsTypography.body,
                color = colors.textSoft,
            )
            if (discovered.isNotEmpty()) {
                CommandGroup(
                    title = stringResource(R.string.agent_commands_on_screen),
                    commands = discovered,
                    onPick = onPick,
                )
            }
            if (curated.isNotEmpty()) {
                CommandGroup(
                    title = stringResource(R.string.agent_commands_common),
                    commands = curated.filter { candidate -> discovered.none { it.text == candidate.text } },
                    onPick = onPick,
                )
            }
            if (discovered.isEmpty() && curated.isEmpty()) {
                Spacer(Modifier.height(DsSpace.m))
                Text(
                    stringResource(R.string.agent_commands_none),
                    style = DsTypography.body,
                    color = colors.textSoft,
                )
            }
            Spacer(Modifier.height(DsSpace.m))
            Text(
                stringResource(R.string.agent_commands_footer),
                style = DsTypography.micro,
                color = colors.textFaint,
            )
        }
    }
}

@Composable
private fun CommandGroup(
    title: String,
    commands: List<AgentCommand>,
    onPick: (String) -> Unit,
) {
    val colors = ds()
    if (commands.isEmpty()) return
    Spacer(Modifier.height(DsSpace.l))
    Text(title, style = DsTypography.caption, color = colors.textSoft)
    Spacer(Modifier.height(DsSpace.xs))
    NeoCard(Modifier.fillMaxWidth(), depth = DsElev.flat, corner = DsRadius.control) {
        Column(Modifier.fillMaxWidth()) {
            commands.forEachIndexed { index, command ->
                if (index > 0) NeoDivider(inset = DsSpace.m)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { onPick(command.text) }
                        // A command row is a tap target first and a label second.
                        .heightIn(min = 56.dp)
                        .padding(horizontal = DsSpace.m, vertical = DsSpace.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        command.text,
                        style = MonoStyle,
                        color = colors.text,
                        modifier = Modifier.widthIn(min = 96.dp),
                    )
                    Text(
                        command.purpose.orEmpty(),
                        style = DsTypography.caption,
                        color = colors.textSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = DsSpace.s),
                    )
                }
            }
        }
    }
}


/**
 * The first meaningful line of a tool block, or null if there is nothing worth showing.
 *
 * Tool blocks are what make a conversation long; a column of identical "tools · 3 lines" pills makes
 * it unreadable instead of shorter. The first line is the command in the CLI conventions this app
 * sees (`$ rg …`, `⌘ read file`, plain argv), so it is a reliable one-line summary.
 */
internal fun stripFence(text: String): String {
    val lines = text.lines()
    if (!lines.first().trimStart().startsWith("```")) return text
    val body = if (lines.size > 1 && lines.last().trimStart().startsWith("```")) {
        lines.subList(1, lines.size - 1)
    } else {
        lines.subList(1, lines.size)
    }
    return body.joinToString("\n")
}

internal fun toolPreview(text: String): String? =
    text.lineSequence()
        .map { it.trim() }
        .filterNot { it.startsWith("```") }
        .firstOrNull { it.isNotEmpty() && it.any { ch -> ch.isLetterOrDigit() } }
        ?.take(120)
