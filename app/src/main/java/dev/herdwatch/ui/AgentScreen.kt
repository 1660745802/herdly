package dev.herdwatch.ui

import android.content.Context
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.herdwatch.R
import dev.herdwatch.domain.requiresStandingConfirmation
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
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
import dev.herdwatch.ui.design.NeoMenu
import dev.herdwatch.ui.design.NeoMenuDivider
import dev.herdwatch.ui.design.NeoMenuItem
import dev.herdwatch.ui.design.NeoSkeleton
import dev.herdwatch.ui.design.NeoSheet
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.LocalTerminalTokens
import dev.herdwatch.ui.theme.MonoStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TerminalBackground: Color @Composable get() = LocalTerminalTokens.current.background
private val TerminalText: Color @Composable get() = LocalTerminalTokens.current.text
private val TerminalMuted: Color @Composable get() = LocalTerminalTokens.current.muted
private data class ReaderSnapshot(val text: String, val loadedAt: Long?, val truncated: Boolean)

@Composable
fun AgentScreen(
    detail: DetailState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onAutoRefresh: () -> Unit,
    onReadingPaused: (Boolean) -> Unit,
    onReadingPosition: (String, ReadingPosition) -> Unit,
    onChoose: (AskOption) -> Unit,
    onVerifyOperation: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSendPrompt: (String) -> Unit,
    onSendKeys: (List<String>) -> Unit,
    onSwipeAgent: (Int) -> Unit,
    canOpenPrevious: Boolean,
    canOpenNext: Boolean,
    onLoadMore: () -> Unit,
    onSetSnapshotLines: (Int) -> Unit,
    onStop: () -> Unit,
    onExplain: () -> Unit,
    onDismissExplain: () -> Unit,
    onRename: (String) -> Unit,
) {
    val agent = detail.agent ?: return
    val colors = ds()
    var confirmStop by remember(agent.key) { mutableStateOf(false) }
    var renaming by remember(agent.key) { mutableStateOf(false) }
    var menu by remember(agent.key) { mutableStateOf(false) }
    var confirmVerification by remember(agent.key) { mutableStateOf(false) }
    var choosing by remember(agent.key) { mutableStateOf(false) }
    var selecting by remember(agent.key) { mutableStateOf(false) }
    var reader by remember(agent.key) { mutableStateOf<ReaderSnapshot?>(null) }
    var readerFontSize by rememberSaveable { mutableIntStateOf(13) }
    var keyboardExpanded by rememberSaveable(agent.key) { mutableStateOf(false) }
    var ctrlLatched by remember(agent.key) { mutableStateOf(false) }
    var wrapOutput by rememberSaveable { mutableStateOf(true) }
    var focusedOutput by rememberSaveable { mutableStateOf(false) }
    val initialPosition = remember(agent.key) { detail.readingPosition }
    val paneScroll = androidx.compose.runtime.key(agent.key) { rememberScrollState(initialPosition.paneOffset) }
    val pageScroll = androidx.compose.runtime.key(agent.key) { rememberScrollState(initialPosition.pageOffset) }
    var restoredPosition by rememberSaveable(agent.key) { mutableStateOf(false) }
    var stickToBottom by rememberSaveable(agent.key) { mutableStateOf(initialPosition.following) }
    var unseenOutput by remember(agent.key) { mutableStateOf(false) }
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
    val pauseUpdates = userPaused || accessibilityPaused
    val autoUpdateStateLabel = stringResource(
        when {
            accessibilityPaused -> R.string.agent_state_talkback_paused
            userPaused -> R.string.agent_state_autoupdate_paused
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
    LaunchedEffect(agent.key, pauseUpdates, selecting, reader, choosing) { onReadingPaused(pauseUpdates || selecting || reader != null || choosing) }
    LaunchedEffect(detail.sending) {
        if (detail.sending && choosing) choosing = false
    }
    val latestRefresh by rememberUpdatedState(onAutoRefresh)
    val currentSending by rememberUpdatedState(detail.sending)
    val currentLoading by rememberUpdatedState(detail.loading)
    val currentPaused by rememberUpdatedState(pauseUpdates || selecting || reader != null || choosing)
    val visibleText = remember(detail.text, focusedOutput) {
        if (focusedOutput) focusTerminalText(detail.text) else detail.text
    }
    LaunchedEffect(agent.key, paneScroll) {
        snapshotFlow { paneScroll.isScrollInProgress to paneScroll.canScrollForward }
            .collect { (scrolling, canForward) ->
                if (scrolling) {
                    stickToBottom = !canForward
                    if (!canForward) unseenOutput = false
                }
            }
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

    // The IME inset already includes the nav bar height, so union them instead of stacking
    // padding: the composer must clear the gesture bar when the keyboard is closed.
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .windowInsetsPadding(
                WindowInsets.statusBars.union(WindowInsets.ime.union(WindowInsets.navigationBars)),
            ),
    ) {
    val compact = maxHeight < 640.dp || LocalDensity.current.fontScale >= 1.3f
    val typing = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val minimal = maxHeight < 360.dp
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().weight(1f)) {
        if (!minimal) Row(
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
                    NeoLamp(
                        color = when (agent.status) {
                            AgentStatus.WAITING -> colors.danger
                            AgentStatus.WORKING -> colors.accent
                            AgentStatus.REVIEW -> colors.ok
                            AgentStatus.IDLE, AgentStatus.UNKNOWN -> colors.textFaint
                        },
                        size = 7.dp,
                        modifier = Modifier.clearAndSetSemantics { },
                    )
                    Text(stringResource(agent.status.labelRes), style = DsTypography.caption, color = colors.textSoft, maxLines = 1)
                    if (!compact && agent.status != AgentStatus.IDLE) {
                        Text(formatElapsed(agent.sinceMillis), style = DsTypography.mono, color = colors.textFaint, maxLines = 1)
                    }
                }
            }
            NeoIconButton(
                glyph = NeoGlyph.Refresh,
                onClick = onRefresh,
                contentDescription = stringResource(R.string.agent_cd_reread_screen),
                enabled = !detail.loading && !detail.sending,
            )
            Box {
                NeoIconButton(
                    glyph = NeoGlyph.More,
                    onClick = { menu = true },
                    contentDescription = stringResource(R.string.agent_cd_more_actions),
                )
                NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    NeoMenuItem(
                        label = stringResource(R.string.agent_menu_read_fullscreen),
                        onClick = {
                            menu = false; reader = ReaderSnapshot(detail.text, detail.loadedAt, detail.truncated)
                        },
                        enabled = detail.text.isNotBlank(),
                    )
                    NeoMenuItem(
                        label = stringResource(R.string.agent_rename),
                        onClick = { menu = false; renaming = true },
                        enabled = !detail.sending,
                    )
                    NeoMenuItem(
                        label = stringResource(
                            if (detail.snapshotLines < 1200) R.string.agent_menu_expand_snapshot_1200
                            else R.string.agent_menu_reread_snapshot_1200,
                        ),
                        onClick = { menu = false; onLoadMore() },
                        enabled = !detail.loading && detail.hostConnected,
                    )
                    if (detail.snapshotLines > 400) NeoMenuItem(
                        label = stringResource(R.string.agent_menu_restore_snapshot_400),
                        onClick = { menu = false; onSetSnapshotLines(400) },
                        enabled = !detail.loading && detail.hostConnected,
                    )
                    NeoMenuItem(
                        label = stringResource(R.string.agent_menu_status_source),
                        onClick = { menu = false; onExplain() },
                    )
                    NeoMenuDivider()
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
                        label = stringResource(
                            if (wrapOutput) R.string.agent_menu_wrap_off else R.string.agent_menu_wrap_on,
                        ),
                        onClick = {
                            menu = false
                            wrapOutput = !wrapOutput
                        },
                        modifier = Modifier.semantics { stateDescription = wrapStateLabel },
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
        if (!compact) Text(
            listOfNotNull(agent.hostLabel, agent.workspaceLabel ?: agent.repo, agent.tabLabel, agent.paneId)
                .filter { it.isNotBlank() }.distinct().joinToString(" / "),
            style = DsTypography.mono,
            color = colors.textSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().padding(start = DsSpace.xxl, end = DsSpace.xl, top = DsSpace.xxs, bottom = DsSpace.xs),
        )
        if (!compact && (canOpenPrevious || canOpenNext)) {
            AgentTraversal(agent.key, canOpenPrevious, canOpenNext, detail.queuePosition, detail.queueSize, onSwipeAgent)
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
            accessibilityPaused = accessibilityPaused,
            autoUpdateStateLabel = autoUpdateStateLabel,
            onTogglePause = { userPaused = !userPaused },
            onRefresh = onRefresh,
            onReconnect = onReconnect,
            onVerify = { confirmVerification = true },
        )

        NeoWell(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = DsSpace.m),
            corner = DsRadius.well,
            fill = TerminalBackground,
        ) {
            // fillMaxHeight, not fillMaxSize: the well is already width-constrained by its parent,
            // and asking for max size here re-measured it at the column's full width, which pushed
            // the whole page past the viewport.
            Column(Modifier.fillMaxWidth().fillMaxHeight()) {
                // Terminal chrome is drawn with the same type scale and hit sizes as the rest of
                // the app; Material TextButtons here were the last mismatched control on the page.
                if (!minimal) Row(
                    Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(start = DsSpace.m, end = DsSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(
                            if (detail.truncated) R.string.agent_snapshot_lines_truncated
                            else R.string.agent_snapshot_lines,
                            detail.snapshotLines,
                        ),
                        color = TerminalMuted,
                        style = DsTypography.caption,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TerminalChromeAction(
                        label = stringResource(R.string.agent_fullscreen),
                        enabled = detail.text.isNotBlank(),
                        onClick = { reader = ReaderSnapshot(detail.text, detail.loadedAt, detail.truncated) },
                    )
                    TerminalChromeAction(
                        label = stringResource(if (focusedOutput) R.string.agent_view_source else R.string.agent_focus),
                        enabled = true,
                        accent = true,
                        onClick = { focusedOutput = !focusedOutput },
                        modifier = Modifier.semantics {
                            contentDescription = foldDescription
                            stateDescription = foldStateLabel
                        },
                    )
                }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (detail.text.isBlank() && detail.loading) {
                            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TerminalMuted)
                                Text(stringResource(R.string.agent_reading_screen), color = TerminalMuted)
                            }
                        } else {
                            SelectionContainer {
                                Column(Modifier.fillMaxWidth().fillMaxHeight().verticalScroll(paneScroll).padding(14.dp)) {
                                    if (focusedOutput) {
                                        Text(
                                            stringResource(R.string.agent_folded_hint),
                                            style = DsTypography.caption,
                                            color = TerminalMuted,
                                        )
                                        Spacer(Modifier.height(10.dp))
                                    }
                                    val textModifier = if (wrapOutput) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())
                                    Text(
                                        visibleText.ifBlank { stringResource(R.string.agent_screen_empty) },
                                        modifier = textModifier,
                                        style = MonoStyle.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                                        color = TerminalText, softWrap = wrapOutput,
                                    )
                                    if (detail.truncated) {
                                        Spacer(Modifier.height(12.dp))
                                        Text(
                                            stringResource(
                                                if (detail.snapshotLines < 1200) R.string.agent_truncated_hint
                                                else R.string.agent_truncated_hint_max,
                                            ),
                                            color = TerminalMuted,
                                            style = DsTypography.caption,
                                        )
                                        if (detail.snapshotLines < 1200) {
                                            TerminalChromeAction(
                                                label = stringResource(R.string.agent_expand_to_1200),
                                                enabled = !detail.loading,
                                                onClick = onLoadMore,
                                                accent = true,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        if (detail.loading && detail.text.isNotBlank()) {
                            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(16.dp), color = TerminalMuted, strokeWidth = 2.dp)
                        }
                        Box(Modifier.align(Alignment.BottomCenter).padding(DsSpace.s)) {
                            BackToBottomChip(
                                visible = !stickToBottom,
                                unseen = unseenOutput,
                                onClick = {
                                    stickToBottom = true
                                    unseenOutput = false
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
            Modifier.fillMaxWidth().background(colors.base).padding(horizontal = DsSpace.m),
        ) {
            if (!typing) AnimatedVisibility(
                visible = detail.ask != null,
                enter = fadeIn(tween(160)) + expandVertically(tween(200)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(160)),
            ) {
                val ask = detail.ask ?: return@AnimatedVisibility
                Row(
                    Modifier.fillMaxWidth().padding(top = DsSpace.s),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(
                                if (ask.confidence == AskConfidence.HIGH) R.string.agent_need_decision
                                else R.string.agent_recognition_unverified,
                            ),
                            style = DsTypography.caption,
                            color = colors.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            ask.question,
                            style = DsTypography.body,
                            color = colors.text,
                            maxLines = if (compact) 1 else 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NeoButton(
                        label = pluralStringResource(R.plurals.agent_view_options, ask.options.size, ask.options.size),
                        onClick = { choosing = true },
                        tone = NeoButtonTone.Quiet,
                        compact = true,
                    )
                }
            }
            if (!typing) Row(verticalAlignment = Alignment.CenterVertically) {
                NeoButton(
                    label = stringResource(if (keyboardExpanded) R.string.agent_keys_collapse else R.string.agent_keys),
                    onClick = { keyboardExpanded = !keyboardExpanded },
                    tone = NeoButtonTone.Quiet,
                    compact = true,
                    modifier = Modifier.semantics {
                        stateDescription = keyboardStateLabel
                    },
                )
                Spacer(Modifier.weight(1f))
                if (detail.sending) {
                    NeoSkeleton(Modifier.width(14.dp), height = 14.dp, corner = 7.dp)
                    Spacer(Modifier.width(DsSpace.s))
                    Text(stringResource(R.string.agent_submitting), style = DsTypography.caption, color = colors.textSoft)
                } else {
                    Text(stringResource(R.string.agent_identity, agent.hostLabel, agent.name), maxLines = 1, overflow = TextOverflow.Ellipsis, style = DsTypography.caption, color = colors.textSoft, modifier = Modifier.weight(1f))
                }
            }
            AnimatedVisibility(keyboardExpanded && !typing) {
                Column {
                    if (!compact) Text(stringResource(R.string.agent_keys_hint), style = DsTypography.caption, color = colors.textSoft, modifier = Modifier.padding(horizontal = 8.dp))
                    // Same key material as the terminal key bar: NeoKey, not an outlined Material
                    // button, so a thumb learns one key shape across both pages.
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(DsSpace.xs),
                    ) {
                        val keys = listOf(
                            "Esc" to "esc",
                            "Tab" to "tab",
                            "↑" to "up",
                            "↓" to "down",
                            "←" to "left",
                            "→" to "right",
                            "Ctrl" to "__ctrl",
                            "y" to "y",
                            "n" to "n",
                            "Enter" to "enter",
                        )
                        keys.forEach { (label, key) ->
                            Box(
                                Modifier.semantics {
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
                                    wide = key == "Enter",
                                    active = key == "__ctrl" && ctrlLatched,
                                )
                            }
                        }
                        NeoKey(label = stringResource(R.string.agent_stop_ellipsis), onPress = { confirmStop = true }, wide = true)
                    }
                }
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
                            if (detail.draft.isEmpty()) Text(stringResource(R.string.agent_reply_placeholder), style = DsTypography.body, color = colors.textFaint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        },
                    )
                    NeoButton(
                        label = stringResource(R.string.agent_send_reply),
                        onClick = { onSendPrompt(detail.draft) },
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
    if (choosing) {
        NeoSheet(onDismissRequest = { choosing = false }) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = DsSpace.xl).padding(bottom = DsSpace.xl),
                verticalArrangement = Arrangement.spacedBy(DsSpace.m),
            ) {
                Text(
                    stringResource(R.string.agent_context_line, agent.hostLabel, agent.name, agent.paneId),
                    color = colors.textSoft,
                    style = DsTypography.mono,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val ask = detail.ask
                if (ask == null) {
                    Text(stringResource(R.string.agent_no_options_now), color = colors.text, style = DsTypography.body)
                } else {
                    DecisionPrompt(ask)
                    NeoButton(
                        label = stringResource(R.string.agent_verify_screen_source),
                        onClick = {
                            choosing = false
                            reader = ReaderSnapshot(detail.text, detail.loadedAt, detail.truncated)
                        },
                        tone = NeoButtonTone.Quiet,
                        compact = true,
                    )
                    ApprovalActions(
                        ask, detail.sending, detail.canChoose,
                        detail.error ?: detail.operationNotice ?: when {
                            detail.truncated -> stringResource(R.string.agent_blocked_truncated)
                            detail.operationUncertain -> stringResource(R.string.agent_blocked_unverified)
                            !detail.canChoose && !detail.sending -> stringResource(R.string.agent_blocked_cannot_approve)
                            else -> null
                        },
                        onChoose = onChoose,
                    )
                }
                NeoButton(
                    label = stringResource(R.string.agent_back_to_terminal),
                    onClick = { choosing = false },
                    tone = NeoButtonTone.Ghost,
                )
            }
        }
    }
    reader?.let { snapshot ->
        TerminalReader(snapshot.text, stringResource(R.string.agent_identity, agent.hostLabel, agent.name), snapshot.loadedAt, snapshot.truncated,
            readerFontSize, onFontSize = { readerFontSize = it }, onDismiss = { reader = null })
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

/** Text-only action on the terminal chrome: 44dp tall, no ripple, design-system type. */
@Composable
private fun TerminalChromeAction(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Boolean = false,
) {
    val colors = ds()
    val tint = when {
        !enabled -> TerminalMuted.copy(alpha = 0.55f)
        accent -> LocalTerminalTokens.current.accent
        else -> TerminalText
    }
    Box(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(DsRadius.key))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = DsSpace.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = DsTypography.body, color = tint, maxLines = 1)
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
        }

        accessibilityPaused -> {
            lamp = colors.textFaint
            message = stringResource(R.string.agent_pause_talkback)
        }

        else -> {
            lamp = if (userPaused) colors.textFaint else colors.ok
            message = when {
                userPaused -> stringResource(R.string.agent_updates_paused)
                detail.loading -> stringResource(R.string.agent_reading)
                detail.loadedAt != null -> stringResource(R.string.agent_read_at, formatClock(detail.loadedAt))
                else -> stringResource(R.string.agent_reading)
            }
            action = {
                NeoButton(
                    label = stringResource(
                        if (userPaused) R.string.agent_resume_updates else R.string.agent_pause_updates,
                    ),
                    onClick = onTogglePause,
                    tone = NeoButtonTone.Ghost,
                    enabled = !accessibilityPaused,
                    compact = true,
                    modifier = Modifier.semantics { stateDescription = autoUpdateStateLabel },
                )
            }
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(start = DsSpace.m, end = DsSpace.m, top = DsSpace.xs, bottom = DsSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
    ) {
        NeoLamp(color = lamp, size = 6.dp, modifier = Modifier.clearAndSetSemantics { })
        Text(
            message,
            style = DsTypography.caption,
            color = if (error != null) colors.danger else colors.textSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
private fun AgentTraversal(agentKey: String, canPrevious: Boolean, canNext: Boolean, position: Int, total: Int, onMove: (Int) -> Unit) {
    val colors = ds()
    var drag by remember(agentKey) { mutableStateOf(0f) }
    val threshold = with(LocalDensity.current) { 72.dp.toPx() }
    val latestMove by rememberUpdatedState(onMove)
    val offset by animateFloatAsState(drag.coerceIn(-threshold, threshold) * 0.2f, label = "agent-drag")
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp)
            .pointerInput(agentKey, threshold, canPrevious, canNext) {
                detectHorizontalDragGestures(
                    onDragStart = { drag = 0f },
                    onHorizontalDrag = { change, amount -> change.consume(); drag += amount },
                    onDragEnd = {
                        if (drag > threshold && canPrevious) latestMove(-1)
                        if (drag < -threshold && canNext) latestMove(1)
                        drag = 0f
                    },
                    onDragCancel = { drag = 0f },
                )
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIconButton(
            glyph = NeoGlyph.ChevronRight,
            onClick = { onMove(-1) },
            contentDescription = stringResource(R.string.agent_prev),
            enabled = canPrevious,
            modifier = Modifier.rotate(180f),
        )
        Text("$position / $total", style = DsTypography.mono, color = colors.textSoft, modifier = Modifier.weight(1f).graphicsLayer { translationX = offset }, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        NeoIconButton(
            glyph = NeoGlyph.ChevronRight,
            onClick = { onMove(1) },
            contentDescription = stringResource(R.string.agent_next),
            enabled = canNext,
        )
    }
}

@Composable
private fun DetailNotice(text: String, isError: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val colors = ds()
    NeoWell(Modifier.fillMaxWidth(), corner = DsRadius.control) {
        Column(
            Modifier.fillMaxWidth().padding(DsSpace.l).semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(DsSpace.xs),
        ) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                NeoLamp(
                    color = if (isError) colors.danger else colors.warn,
                    size = 5.dp,
                    modifier = Modifier.padding(top = DsSpace.xxs).clearAndSetSemantics { },
                )
                Text(
                    text,
                    style = DsTypography.body,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
            }
            action?.invoke()
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
    return result.joinToString("\n").trim('\n')
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
        enter = fadeIn(tween(140)) + scaleIn(tween(160), initialScale = 0.92f),
        exit = fadeOut(tween(110)) + scaleOut(tween(120), targetScale = 0.92f),
    ) {
        NeoButton(
            label = if (unseen) stringResource(R.string.agent_back_to_bottom_new) else stringResource(R.string.agent_back_to_bottom),
            onClick = onClick,
            tone = NeoButtonTone.Quiet,
            compact = true,
        )
    }
}
