package dev.herdwatch.ui

import android.content.Context
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import dev.herdwatch.domain.Transcript
import dev.herdwatch.domain.TranscriptBlock
import dev.herdwatch.domain.TranscriptRole
import dev.herdwatch.domain.requiresStandingConfirmation
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.ui.SentMessage
import dev.herdwatch.model.Ask
import dev.herdwatch.model.AskConfidence
import dev.herdwatch.model.AskOption
import dev.herdwatch.ui.design.DsElev
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
import dev.herdwatch.ui.design.NeoSkeleton
import dev.herdwatch.ui.design.NeoSpinner
import dev.herdwatch.ui.design.NeoShadowBleed
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.LocalTerminalTokens
import dev.herdwatch.ui.theme.MonoStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import dev.herdwatch.ui.theme.agentStatusColor
import dev.herdwatch.ui.motionMillis
import dev.herdwatch.ui.design.NeoSegmented
import dev.herdwatch.ui.design.NeoSnackbarBody
import dev.herdwatch.ui.design.spacing

private val TerminalBackground: Color @Composable get() = LocalTerminalTokens.current.background
private val TerminalText: Color @Composable get() = LocalTerminalTokens.current.text
private val TerminalMuted: Color @Composable get() = LocalTerminalTokens.current.muted
private val TerminalAccent: Color @Composable get() = LocalTerminalTokens.current.accent
private val TerminalRisk: Color @Composable get() = LocalTerminalTokens.current.risk
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
    var wrapOutput by rememberSaveable { mutableStateOf(false) }
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
                NeoMenuItem(
                    label = stringResource(
                        if (wrapOutput) R.string.agent_menu_wrap_off else R.string.agent_menu_wrap_on,
                    ),
                    onClick = { menu = false; wrapOutput = !wrapOutput },
                    modifier = Modifier.semantics { stateDescription = wrapStateLabel },
                )
                if (!paneOnly && !showChat) {
                    NeoMenuItem(
                        label = stringResource(R.string.agent_layout_chat),
                        onClick = { menu = false; chatLayout = true },
                    )
                }
                NeoMenuItem(
                    label = stringResource(R.string.agent_menu_status_source),
                    onClick = { menu = false; onExplain() },
                )
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
                        clipboard.setText(AnnotatedString(detail.text))
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
                NeoMenuDivider()
                NeoMenuItem(
                    label = stringResource(R.string.agent_menu_stop),
                    onClick = { menu = false; confirmStop = true },
                    enabled = !detail.sending,
                    danger = true,
                )
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
            Modifier.fillMaxWidth().padding(start = DsSpace.xxs, end = DsSpace.xs, top = DsSpace.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoIconButton(
                glyph = NeoGlyph.Back,
                onClick = onBack,
                contentDescription = stringResource(R.string.agent_cd_back),
            )
            Column(Modifier.weight(1f)) {
                Text(agent.name, style = if (compact) DsTypography.headline else DsTypography.title, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    NeoModeTag(stringResource(if (paneOnly) R.string.mode_shell_tag else R.string.mode_agent_tag))
                    NeoLamp(
                        color = if (paneOnly) (if (detail.hostConnected) colors.ok else colors.warn) else agentStatusColor(agent.status),
                        size = 7.dp,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Text(
                        if (paneOnly) stringResource(R.string.inbox_pane_shell) else stringResource(agent.status.labelRes),
                        style = DsTypography.caption,
                        color = colors.textSoft,
                        maxLines = 1,
                    )
                    if (!compact && !paneOnly && agent.status != AgentStatus.IDLE) {
                        Text(formatElapsed(agent.sinceMillis), style = DsTypography.mono, color = colors.textFaint, maxLines = 1)
                    }
                }
                // Where this pane lives. A pane is only meaningful inside host / workspace / tab /
                // directory, and without this line two panes with the same agent name were the same
                // page as far as the reader could tell.
                Text(
                    listOfNotNull(
                        agent.hostLabel.takeIf { it.isNotBlank() },
                        agent.workspaceLabel ?: agent.repo,
                        agent.tabLabel,
                        agent.paneId.substringAfterLast(':').takeIf { it.isNotBlank() },
                    ).distinct().joinToString("  ·  "),
                    style = DsTypography.micro,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
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
        NeoWell(
            // Terminal mode goes edge to edge. A rounded card inset from the page edges is a
            // document; the SSH page already paints its shell across the whole screen, and the two
            // pages should feel like the same instrument.
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = if (showChat) DsSpace.m else 0.dp),
            corner = if (showChat) DsRadius.well else 0.dp,
            fill = if (showChat) colors.base else TerminalBackground,
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
                                blocks = chatBlocks,
                                sent = detail.sent.filterNot { it.echoed },
                                scroll = paneScroll,
                                loadingOlder = detail.loading && detail.text.isNotBlank(),
                                askPending = detail.ask != null,
                            )
                        } else {
                            // A pane is a terminal, so it is drawn as one: fixed cells, no re-flow,
                            // the same ink as the SSH page. The old wrapped Text inside a padded
                            // column re-flowed column-aligned output and read as a document.
                            TerminalDump(
                                text = visibleText,
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
                            listOf(listOf("Esc" to "esc", "Tab" to "tab", "^C" to "ctrl-c", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right"))
                        } else {
                            listOf(
                            listOf("Esc" to "esc", "Tab" to "tab", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right"),
                            if (paneOnly) {
                                listOf("Ctrl" to "__ctrl", "^C" to "ctrl-c", "^D" to "ctrl-d", "Enter" to "enter")
                            } else {
                                // 中断 rides in the same row: on its own full-width line it cost a
                                // whole extra row of terminal output for one button.
                                listOf(
                                    "Ctrl" to "__ctrl",
                                    "y" to "y",
                                    "n" to "n",
                                    "Enter" to "enter",
                                    stringResource(R.string.agent_stop) to "__stop",
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
                                    if (showChat) R.string.agent_reply_placeholder else R.string.agent_input_placeholder,
                                ),
                                style = DsTypography.body,
                                color = colors.textFaint,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            inner()
                        },
                    )
                    NeoButton(
                        label = stringResource(if (showChat) R.string.agent_send_reply else R.string.agent_run),
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
    if (selecting) SelectionSheet(detail.text, onCopy = {
        clipboard.setText(AnnotatedString(it))
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
    }, onDismiss = { selecting = false })
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
            ApprovalOption(
                label = if (sending && option == ask.allow) stringResource(R.string.agent_submitting) else option.label,
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
        Box(
            Modifier.fillMaxWidth().defaultMinSize(minHeight = 48.dp).padding(horizontal = DsSpace.l, vertical = DsSpace.m),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                label,
                style = DsTypography.bodyStrong,
                color = when {
                    !enabled -> colors.textFaint
                    danger -> colors.danger
                    else -> colors.text
                },
            )
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

@Composable
private fun SelectionSheet(text: String, onCopy: (String) -> Unit, onDismiss: () -> Unit) {
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
                Spacer(Modifier.height(DsSpace.s))
                Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(DsSpace.s)) {
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
    blocks: List<TranscriptBlock>,
    sent: List<SentMessage>,
    scroll: androidx.compose.foundation.ScrollState,
    loadingOlder: Boolean = false,
    askPending: Boolean = false,
) {
    val colors = ds()
    Column(
        Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(scroll).padding(DsSpace.m),
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
        blocks.forEach { block -> TranscriptBlockView(block, askPending) }
        // Our own sends appear immediately, even before the agent echoes them on the screen.
        // They disappear from here once the snapshot carries them, so nothing is shown twice.
        sent.forEach { message ->
            TranscriptBlockView(TranscriptBlock(TranscriptRole.USER, message.text, message.text, false), false)
        }
        // No "看原文" button here: the 对话/终端 switch above is that action, and a second door to
        // the same room was pure noise at the bottom of every conversation.
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
            if (askPending) {
                Spacer(Modifier.height(DsSpace.xxs))
                Text(
                    stringResource(R.string.agent_approval_below),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
            }
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
            NeoCard(Modifier.fillMaxWidth(), depth = DsElev.soft) {
                // A turn is not always one paragraph. Screen-scraped output arrives as a block of
                // dozens of lines, and painting it as a single run made every long answer look like
                // one uninterrupted wall — the reader lost the sentence they came for.
                val paragraphs = remember(block.text) { paragraphsOf(block.text) }
                val long = block.text.length > LONG_BLOCK_CHARS
                var expanded by remember(block.text) { mutableStateOf(!long) }
                Column(
                    Modifier.fillMaxWidth().padding(DsSpace.m),
                    verticalArrangement = Arrangement.spacedBy(DsSpace.s),
                ) {
                    // Collapsed, keep whole paragraphs up to the budget instead of cutting the first
                    // one mid-sentence: a half sentence reads as data loss.
                    val shown = if (expanded) {
                        paragraphs
                    } else {
                        val kept = mutableListOf<String>()
                        var used = 0
                        for (paragraph in paragraphs) {
                            if (kept.isNotEmpty() && used + paragraph.length > LONG_BLOCK_CHARS) break
                            kept += paragraph
                            used += paragraph.length
                        }
                        kept
                    }
                    shown.forEach { paragraph ->
                        Text(paragraph, style = prose, color = colors.text)
                    }
                    if (long) {
                        Text(
                            stringResource(if (expanded) R.string.agent_collapse_block else R.string.agent_expand_block),
                            style = DsTypography.caption,
                            color = colors.accent,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 48.dp)
                                .clickable(role = Role.Button) { expanded = !expanded }
                                .padding(top = DsSpace.s),
                        )
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
private const val LONG_BLOCK_CHARS = 700

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
    if (parts.size > 1) return parts
    val lines = trimmed.lines().map { it.trimEnd() }
    return if (lines.size >= 4 && lines.count { it.isBlank() } == 0) {
        lines.filter { it.isNotBlank() }
    } else {
        listOf(trimmed)
    }
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
            .clickable(role = Role.Button) { expanded = !expanded }
            .padding(vertical = DsSpace.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
            NeoIcon(NeoGlyph.Sparkle, modifier = Modifier.size(13.dp), tint = colors.textFaint)
            Text(
                stringResource(R.string.agent_thinking_lines, roleLabel, block.text.lineSequence().count()),
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
                .clickable(role = Role.Button) { expanded = !expanded }
                .padding(horizontal = DsSpace.m, vertical = DsSpace.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
                NeoIcon(NeoGlyph.Command, modifier = Modifier.size(14.dp), tint = colors.textSoft)
                Text(
                    stringResource(
                        R.string.agent_tool_lines,
                        roleLabel,
                        block.text.lineSequence().count(),
                    ),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                NeoIcon(
                    if (expanded) NeoGlyph.ChevronUp else NeoGlyph.ChevronDown,
                    modifier = Modifier.size(16.dp),
                    tint = colors.textFaint,
                )
            }
            ExpandableSection(expanded) {
                Spacer(Modifier.height(DsSpace.xs))
                Text(block.text, style = MonoStyle, color = colors.text)
            }
        }
    }
}

