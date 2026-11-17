package dev.herdwatch.ui

import android.content.Context
import android.widget.Toast
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
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
import dev.herdwatch.domain.requiresStandingConfirmation
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.Ask
import dev.herdwatch.model.AskConfidence
import dev.herdwatch.model.AskOption
import dev.herdwatch.ui.theme.MonoStyle
import dev.herdwatch.ui.theme.statusPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val TerminalBackground = Color(0xFF13151A)
private val TerminalText = Color(0xFFE4E7EE)
private val TerminalMuted = Color(0xFFA8B0C0)
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
    onStop: () -> Unit,
    onExplain: () -> Unit,
    onDismissExplain: () -> Unit,
    onRename: (String) -> Unit,
) {
    val agent = detail.agent ?: return
    val palette = statusPalette()
    val agentColor = when (agent.status) {
        AgentStatus.WAITING -> palette.waiting
        AgentStatus.UNKNOWN -> palette.unknown
        AgentStatus.WORKING -> palette.working
        AgentStatus.REVIEW -> palette.review
        AgentStatus.IDLE -> palette.idle
    }
    var confirmStop by remember(agent.key) { mutableStateOf(false) }
    var renaming by remember(agent.key) { mutableStateOf(false) }
    var menu by remember(agent.key) { mutableStateOf(false) }
    var confirmVerification by remember(agent.key) { mutableStateOf(false) }
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
    LaunchedEffect(agent.key, pauseUpdates, selecting, reader) { onReadingPaused(pauseUpdates || selecting || reader != null) }
    val latestRefresh by rememberUpdatedState(onAutoRefresh)
    val currentSending by rememberUpdatedState(detail.sending)
    val currentLoading by rememberUpdatedState(detail.loading)
    val currentPaused by rememberUpdatedState(pauseUpdates || selecting || reader != null)
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

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding().imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回收件箱") }
            Column(Modifier.weight(1f)) {
                Text(agent.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() })
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StatusGlyph(agent.status, size = 10)
                    Text(agent.status.label, style = MaterialTheme.typography.labelMedium, color = agentColor)
                    if (agent.status != AgentStatus.IDLE) {
                        Text(formatElapsed(agent.sinceMillis), style = MonoStyle.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            IconButton(onClick = onRefresh, enabled = !detail.loading && !detail.sending) {
                Icon(Icons.Default.Refresh, contentDescription = "重新读取屏幕")
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, contentDescription = "更多操作") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("全屏阅读原文") }, onClick = {
                        menu = false; reader = ReaderSnapshot(detail.text, detail.loadedAt, detail.truncated)
                    }, enabled = detail.text.isNotBlank())
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; renaming = true }, enabled = !detail.sending)
                    DropdownMenuItem(text = { Text("状态来源") }, onClick = { menu = false; onExplain() })
                    DropdownMenuItem(text = { Text("复制完整屏幕") }, onClick = {
                        menu = false
                        clipboard.setText(AnnotatedString(detail.text))
                        Toast.makeText(context, "已复制完整屏幕", Toast.LENGTH_SHORT).show()
                    }, enabled = detail.text.isNotBlank())
                    DropdownMenuItem(text = { Text("中断 Agent", color = palette.danger) }, onClick = { menu = false; confirmStop = true }, enabled = !detail.sending)
                }
            }
        }
        Text(
            listOfNotNull(agent.hostLabel, agent.workspaceLabel ?: agent.repo, agent.tabLabel, agent.paneId)
                .filter { it.isNotBlank() }.distinct().joinToString(" / "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
        )
        if (canOpenPrevious || canOpenNext) {
            AgentTraversal(agent.key, canOpenPrevious, canOpenNext, detail.queuePosition, detail.queueSize, onSwipeAgent)
        }

        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val terminalHeight = (maxHeight * 0.70f).coerceIn(240.dp, 520.dp)
            Column(
                Modifier.fillMaxSize().verticalScroll(pageScroll).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(if (detail.stale) palette.unknown else palette.review, androidx.compose.foundation.shape.CircleShape))
                    Text(
                        if (detail.loading) "正在读取…" else if (detail.stale) "快照待同步" else "读取于 ${formatClock(detail.loadedAt)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 7.dp),
                    )
                    TextButton(
                        onClick = {
                            if (pauseUpdates) { userPaused = false; accessibilityPaused = false }
                            else userPaused = true
                        },
                        modifier = Modifier.semantics { stateDescription = if (detail.readingPaused) "自动更新已暂停" else "自动更新已开启" },
                        contentPadding = PaddingValues(horizontal = 8.dp),
                    ) { Text(if (pauseUpdates) "恢复更新" else "暂停更新", style = MaterialTheme.typography.labelMedium) }
                }
                if (!detail.hostConnected) {
                    DetailNotice("${agent.hostLabel} 尚未连接，保留上次屏幕和草稿。", action = {
                        TextButton(onClick = onReconnect, enabled = !detail.hostConnecting) {
                            Text(if (detail.hostConnecting) "正在重连…" else "重连并读取")
                        }
                    })
                } else if (detail.stale) DetailNotice("此页证据尚未同步，请重新读取后再判断。")
                detail.error?.let { error ->
                    DetailNotice(error, isError = true, action = {
                        if (detail.hostConnected) TextButton(onClick = onRefresh, enabled = !detail.loading) { Text("重新读取") }
                    })
                }
                detail.operationNotice?.let { notice ->
                    DetailNotice(notice, isError = detail.operationUncertain, action = {
                        if (detail.canVerifyOperation) {
                            TextButton(onClick = { confirmVerification = true }) { Text("我已核对远端状态…") }
                        }
                    })
                }
                val ask = detail.ask
                if (ask != null) {
                    ApprovalCard(
                        ask = ask,
                        sending = detail.sending,
                        enabled = detail.canChoose,
                        blockedReason = when {
                            detail.truncated -> "快照不完整，请先读取更多并核对原文。"
                            detail.operationUncertain -> "上次操作结果待核实，请刷新屏幕后再判断。"
                            !detail.canChoose && !detail.sending -> "当前选项暂不可用，请重新读取并核对屏幕。"
                            else -> null
                        },
                        onChoose = onChoose,
                        onReadEvidence = { reader = ReaderSnapshot(detail.text, detail.loadedAt, detail.truncated) },
                    )
                } else if (agent.status == AgentStatus.WAITING || agent.status == AgentStatus.UNKNOWN) {
                    Surface(color = palette.waitingWash, shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(if (agent.status == AgentStatus.UNKNOWN) "状态待核实" else "需要查看原文", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "尚未识别出可安全选择的选项。请核对完整屏幕，再使用回复或终端按键。",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(
                    Modifier.fillMaxWidth().height(terminalHeight).background(TerminalBackground, RoundedCornerShape(16.dp)),
                ) {
                    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text("屏幕证据", color = TerminalText, style = MaterialTheme.typography.labelLarge)
                            Text("读取于 ${formatClock(detail.loadedAt)}${if (detail.truncated) " · 不完整" else ""}", color = TerminalMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { reader = ReaderSnapshot(detail.text, detail.loadedAt, detail.truncated) }, enabled = detail.text.isNotBlank()) { Text("全屏", color = TerminalText) }
                        TextButton(onClick = { focusedOutput = !focusedOutput }, modifier = Modifier.semantics {
                            contentDescription = "折叠装饰线与多余空行"
                            stateDescription = if (focusedOutput) "已开启" else "已关闭"
                        }) { Text(if (focusedOutput) "看原文" else "聚焦", color = Color(0xFFB6C3FF)) }
                    }
                    HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if (detail.text.isBlank() && detail.loading) {
                            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = TerminalMuted)
                                Text("正在读取屏幕…", color = TerminalMuted)
                            }
                        } else {
                            SelectionContainer {
                                Column(Modifier.fillMaxSize().verticalScroll(paneScroll).padding(14.dp)) {
                                    if (focusedOutput) {
                                        Text("已折叠装饰线和多余空行；审批仍核对完整原文。", style = MaterialTheme.typography.bodySmall, color = TerminalMuted)
                                        Spacer(Modifier.height(10.dp))
                                    }
                                    val textModifier = if (wrapOutput) Modifier.fillMaxWidth() else Modifier.horizontalScroll(rememberScrollState())
                                    Text(
                                        visibleText.ifBlank { "当前屏幕为空。" },
                                        modifier = textModifier,
                                        style = MonoStyle.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
                                        color = TerminalText, softWrap = wrapOutput,
                                    )
                                    if (detail.truncated) {
                                        Spacer(Modifier.height(12.dp))
                                        Text("更早内容已截断。当前显示的是快照，不是完整滚动历史。", color = TerminalMuted, style = MaterialTheme.typography.bodySmall)
                                        TextButton(onClick = onLoadMore, enabled = !detail.loading) { Text("读取更多", color = Color(0xFFB6C3FF)) }
                                    }
                                }
                            }
                        }
                        if (detail.loading && detail.text.isNotBlank()) {
                            CircularProgressIndicator(Modifier.align(Alignment.TopEnd).padding(12.dp).size(16.dp), color = TerminalMuted, strokeWidth = 2.dp)
                        }
                        if (!stickToBottom) {
                            FilledTonalButton(
                                onClick = {
                                    stickToBottom = true
                                    unseenOutput = false
                                    scope.launch { paneScroll.animateScrollTo(paneScroll.maxValue) }
                                },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp),
                            ) { Text(if (unseenOutput) "有新内容 · 回到底部" else "回到底部") }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { wrapOutput = !wrapOutput }, modifier = Modifier.semantics {
                            contentDescription = "终端自动换行"
                            stateDescription = if (wrapOutput) "已开启" else "已关闭"
                        }) { Text(if (wrapOutput) "自动换行" else "横向查看", color = TerminalMuted, style = MaterialTheme.typography.labelMedium) }
                        TextButton(onClick = { selecting = true }, enabled = detail.text.isNotBlank()) { Text("选择复制", color = TerminalMuted, style = MaterialTheme.typography.labelMedium) }
                        Spacer(Modifier.weight(1f))
                        Text(if (stickToBottom) "跟随中" else "历史中", color = TerminalMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
                    }
                }
                val signals = agent.stateLabels.values.take(3) + agent.tokens.entries.take(3).map { "${it.key} ${it.value}" }
                if (signals.isNotEmpty()) {
                    Text(signals.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { keyboardExpanded = !keyboardExpanded }, modifier = Modifier.semantics {
                        stateDescription = if (keyboardExpanded) "已展开" else "已收起"
                    }) { Text(if (keyboardExpanded) "收起按键" else "终端按键") }
                    Spacer(Modifier.weight(1f))
                    if (detail.sending) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("正在提交…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text("${agent.hostLabel} / ${agent.name}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    }
                }
                AnimatedVisibility(keyboardExpanded) {
                    Column {
                        Text("直接向当前 pane 发送按键，发送前请核对屏幕。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 8.dp))
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("Esc" to "esc", "Tab" to "tab", "↑" to "up", "↓" to "down", "←" to "left", "→" to "right", "Ctrl" to "__ctrl", "r" to "r", "o" to "o", "d" to "d", "y" to "y", "n" to "n", "Enter" to "enter").forEach { (label, key) ->
                                OutlinedButton(
                                    onClick = {
                                        when {
                                            key == "__ctrl" -> ctrlLatched = !ctrlLatched
                                            ctrlLatched && key.length == 1 -> { onSendKeys(listOf("ctrl-$key")); ctrlLatched = false }
                                            else -> { onSendKeys(listOf(key)); ctrlLatched = false }
                                        }
                                    },
                                    enabled = detail.canSend,
                                    modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics {
                                        if (key == "__ctrl") stateDescription = if (ctrlLatched) "已按下，下个字母将发送组合键" else "未按下"
                                        when (key) {
                                            "up" -> contentDescription = "方向键上"
                                            "down" -> contentDescription = "方向键下"
                                            "left" -> contentDescription = "方向键左"
                                            "right" -> contentDescription = "方向键右"
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(containerColor = if (key == "__ctrl" && ctrlLatched) MaterialTheme.colorScheme.primaryContainer else Color.Transparent),
                                ) { Text(label, style = MonoStyle) }
                            }
                            TextButton(onClick = { confirmStop = true }, enabled = detail.canSend, modifier = Modifier.heightIn(min = 48.dp)) { Text("中断…", color = palette.danger) }
                        }
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp)).padding(start = 14.dp, end = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BasicTextField(
                        value = detail.draft,
                        onValueChange = onDraftChange,
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(vertical = 12.dp).semantics { contentDescription = "回复 ${agent.name}" },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        maxLines = 4,
                        decorationBox = { inner ->
                            if (detail.draft.isEmpty()) Text("回复 Agent…", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            inner()
                        },
                    )
                    IconButton(onClick = { onSendPrompt(detail.draft) }, enabled = detail.draft.isNotBlank() && detail.canSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送到 ${agent.name}")
                    }
                }
            }
        }
    }
    reader?.let { snapshot ->
        TerminalReader(snapshot.text, "${agent.hostLabel} / ${agent.name}", snapshot.loadedAt, snapshot.truncated,
            readerFontSize, onFontSize = { readerFontSize = it }, onDismiss = { reader = null })
    }
    if (selecting) SelectionSheet(detail.text, onCopy = {
        clipboard.setText(AnnotatedString(it))
        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
    }, onDismiss = { selecting = false })
    detail.explain?.let { text ->
        AlertDialog(onDismissRequest = onDismissExplain, title = { Text("Herdr 的状态判断") }, text = {
            SelectionContainer { Text(text, style = MonoStyle, modifier = Modifier.verticalScroll(rememberScrollState())) }
        }, confirmButton = { TextButton(onClick = onDismissExplain) { Text("关闭") } })
    }
    if (confirmVerification) {
        AlertDialog(onDismissRequest = { confirmVerification = false }, title = { Text("已核对远端状态？") }, text = {
            Text("请先刷新并阅读屏幕，确认上次操作是否已执行。解除限制后可以再次操作；重复提交可能再次执行命令。")
        }, confirmButton = {
            TextButton(onClick = { confirmVerification = false; onVerifyOperation() }, enabled = detail.canVerifyOperation) { Text("已核对，继续操作") }
        }, dismissButton = { TextButton(onClick = { confirmVerification = false }) { Text("返回核对") } })
    }
    if (renaming) {
        var value by remember(agent.key) { mutableStateOf(agent.name) }
        AlertDialog(onDismissRequest = { renaming = false }, title = { Text("重命名 Agent") }, text = {
            OutlinedTextField(value, onValueChange = { value = it.take(40) }, singleLine = true, label = { Text("新名字") }, modifier = Modifier.fillMaxWidth())
        }, confirmButton = { TextButton(enabled = value.isNotBlank() && value.trim() != agent.name, onClick = { onRename(value.trim()); renaming = false }) { Text("保存") } }, dismissButton = { TextButton(onClick = { renaming = false }) { Text("取消") } })
    }
    if (confirmStop) {
        AlertDialog(onDismissRequest = { confirmStop = false }, title = { Text("中断 ${agent.name}？") }, text = { Text("将向 ${agent.hostLabel} / ${agent.paneId} 发送 Ctrl-C。当前步骤可能终止，pane 会保留。") }, confirmButton = {
            TextButton(onClick = { confirmStop = false; onStop() }, enabled = detail.canSend) { Text("中断", color = palette.danger) }
        }, dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("取消") } })
    }
}

@Composable
private fun AgentTraversal(agentKey: String, canPrevious: Boolean, canNext: Boolean, position: Int, total: Int, onMove: (Int) -> Unit) {
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
        TextButton(onClick = { onMove(-1) }, enabled = canPrevious) { Text("上一条") }
        Text("$position / $total", style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).graphicsLayer { translationX = offset }, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { onMove(1) }, enabled = canNext) { Text("下一条") }
    }
}

@Composable
private fun DetailNotice(text: String, isError: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Surface(color = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(12.dp)) {
        Column(Modifier.fillMaxWidth().padding(14.dp).semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium, color = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
            action?.invoke()
        }
    }
}

@Composable
private fun ApprovalCard(ask: Ask, sending: Boolean, enabled: Boolean, blockedReason: String?, onChoose: (AskOption) -> Unit, onReadEvidence: () -> Unit) {
    var pendingStanding by remember(ask) { mutableStateOf<AskOption?>(null) }
    val request: (AskOption) -> Unit = { option ->
        if (requiresStandingConfirmation(option)) pendingStanding = option else onChoose(option)
    }
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前需要你的决定", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            TextButton(onClick = onReadEvidence, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("核对屏幕原文", style = MaterialTheme.typography.labelMedium)
            }
            if (ask.confidence != AskConfidence.HIGH) {
                Text("选项来自屏幕解析，请核对下面的原文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SelectionContainer { Text(ask.question, style = MaterialTheme.typography.bodyLarge) }
            ask.risk?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
            // Keep every option visible, including multiple ALLOW choices with different scopes.
            ask.options.forEach { option ->
                val modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                if (option == ask.allow && !requiresStandingConfirmation(option)) {
                    Button(onClick = { request(option) }, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(10.dp)) { Text(if (sending) "正在提交…" else option.label) }
                } else {
                    OutlinedButton(onClick = { request(option) }, enabled = enabled, modifier = modifier, shape = RoundedCornerShape(10.dp)) {
                        Text(option.label, color = if (requiresStandingConfirmation(option)) MaterialTheme.colorScheme.error else Color.Unspecified)
                    }
                }
            }
            Text(blockedReason ?: "发送前会复核问题、选项和上下文。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    pendingStanding?.let { option ->
        AlertDialog(onDismissRequest = { pendingStanding = null }, title = { Text("确认长期授权") }, text = { Text("${option.label}\n\n这项选择可能让后续同类请求不再询问，请核对授权范围。") }, confirmButton = {
            TextButton(onClick = { pendingStanding = null; onChoose(option) }, enabled = enabled) { Text("确认长期授权", color = MaterialTheme.colorScheme.error) }
        }, dismissButton = { TextButton(onClick = { pendingStanding = null }) { Text("取消") } })
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
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.88f).background(TerminalBackground, RoundedCornerShape(18.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("选择复制", style = MaterialTheme.typography.titleMedium, color = TerminalText, modifier = Modifier.weight(1f))
                TextButton(onClick = { value = value.copy(selection = TextRange(0, text.length)) }) { Text("全选", color = TerminalMuted) }
                TextButton(onClick = { onCopy(selected) }, enabled = selected.isNotEmpty()) { Text("复制", color = if (selected.isNotEmpty()) Color(0xFFB6C3FF) else TerminalMuted) }
                TextButton(onClick = onDismiss) { Text("关闭", color = TerminalMuted) }
            }
            Text("长按选择，拖动两端调整范围。", style = MaterialTheme.typography.bodySmall, color = TerminalMuted)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp)) {
                BasicTextField(value = value, onValueChange = { value = it }, readOnly = true, modifier = Modifier.fillMaxWidth(), textStyle = MonoStyle.copy(color = TerminalText), cursorBrush = SolidColor(Color.Transparent))
            }
        }
    }
}
