package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.theme.MonoStyle
import dev.herdwatch.ui.theme.statusPalette

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(
    ui: UiState,
    onRefresh: () -> Unit,
    onOpenAgent: (Agent) -> Unit,
    onFilter: (InboxFilter) -> Unit,
    onQuery: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onAddHost: () -> Unit,
    onReconnect: (String) -> Unit,
    onNewTask: (() -> Unit)? = null,
    onOpenHost: ((String) -> Unit)? = null,
) {
    val agents = ui.repo.agents
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 360 || configuration.fontScale >= 1.3f
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    val filtered = inboxQueue(agents, ui.filter, ui.query)
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                BrandMark()
                Spacer(Modifier.width(10.dp))
            }
            Column(Modifier.weight(1f)) {
                Text("Herdly", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
                val connected = ui.repo.hosts.count { it.conn is ConnState.Connected }
                val connecting = ui.repo.hosts.any { it.conn is ConnState.Connecting }
                Text(
                    when {
                        ui.repo.hosts.isEmpty() -> "手机上的 Herdr 收件箱"
                        connecting && connected == 0 -> "连接中 · ${agents.size} 个 agent"
                        compact -> "$connected/${ui.repo.hosts.size} 台在线"
                        else -> "$connected/${ui.repo.hosts.size} 台在线 · ${agents.size} 个 agent"
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { keyboard?.hide(); onToggleSearch(!ui.searching) }) {
                Icon(Icons.Default.Search, contentDescription = if (ui.searching) "收起搜索" else "搜索")
            }
            IconButton(onClick = onRefresh, enabled = !ui.repo.refreshing,
                modifier = Modifier.semantics { stateDescription = if (ui.repo.refreshing) "同步中" else "" },
            ) {
                if (ui.repo.refreshing) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新收件箱")
                }
            }
        }

        if (ui.repo.hosts.isNotEmpty()) {
            HostStrip(ui.repo.hosts, onOpenHost)
        }

        // 搜索框放在列表外：若作为第一个 item，列表已有滚动位置时它会插到视口上方，
        // 视觉上“点了搜索没反应”。
        AnimatedVisibility(
            visible = ui.searching,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { searchFocus.requestFocus() }
            OutlinedTextField(
                value = ui.query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 6.dp).focusRequester(searchFocus),
                placeholder = { Text("名称、机器、标签页或分屏 ID") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { keyboard?.hide() }),
                label = { Text("搜索收件箱") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    if (ui.query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
                            Icon(Icons.Default.Close, contentDescription = "清空")
                        }
                    }
                },
            )
        }

        if (!ui.searching && ui.query.isNotBlank()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onToggleSearch(true) }, modifier = Modifier.weight(1f)) {
                    Text("搜索：${ui.query}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                TextButton(onClick = { onQuery("") }) { Text("清除") }
            }
        }

        PullToRefreshBox(
            isRefreshing = ui.repo.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
        ) {
            if (ui.repo.hosts.isNotEmpty()) {
                val connecting = ui.repo.hosts.filter { it.conn == ConnState.Connecting }
                if (connecting.isNotEmpty() && agents.isEmpty()) {
                    item {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 12.dp)
                                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                "正在连接 ${connecting.joinToString("、") { it.host.label }}…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { DutyCard(filtered, ui.repo.hosts, now, onOpenAgent, onReconnect) }
                if (agents.isEmpty() && ui.repo.hosts.any { it.conn is ConnState.Connected }) {
                    item {
                        EmptyBlock(
                            title = "已连接，还没有 agent",
                            body = "可以创建新任务，或检查主机里的工作区和分屏。",
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (onNewTask != null) {
                                    Button(onClick = onNewTask) { Text("启动新任务") }
                                } else {
                                    Text("点右下角「新任务」创建并启动 agent。", style = MaterialTheme.typography.bodyMedium)
                                }
                                if (onOpenHost != null) {
                                    ui.repo.hosts.filter { it.conn is ConnState.Connected }.forEach { runtime ->
                                        TextButton(onClick = { onOpenHost(runtime.host.id) }) {
                                            Text("检查 ${runtime.host.label} 的会话")
                                        }
                                    }
                                } else {
                                    TextButton(onClick = onRefresh) { Text("重新检查 agent") }
                                }
                            }
                        }
                    }
                }
            }

            if (ui.repo.hosts.isEmpty()) {
                item {
                    val clipboard = LocalClipboardManager.current
                    EmptyBlock(
                        title = "把 Herdr 装进口袋",
                        body = "Herdly不替你跑 agent，只负责看和批。\n\n1. 主机装好 Herdr，手机能 SSH 登录\n2. 检查桥接是否可用\n3. 添加机器，收件箱会列出每个 agent",
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = onAddHost, shape = RoundedCornerShape(10.dp)) {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("添加机器")
                            }
                            Button(
                                onClick = { clipboard.setText(AnnotatedString("herdr remote-api-bridge --check")) },
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            ) { Text("复制检查命令") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "在主机上运行上面那条命令，应输出 herdr-api-bridge-v1。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                return@LazyColumn
            }

            item {
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp).selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterTab("全部", ui.filter == InboxFilter.ALL, Modifier.align(Alignment.CenterVertically)) { onFilter(InboxFilter.ALL) }
                    FilterTab("需要我", ui.filter == InboxFilter.ATTENTION, Modifier.align(Alignment.CenterVertically)) { onFilter(InboxFilter.ATTENTION) }
                    Text(
                        "${filtered.size} 项${if (ui.query.isNotBlank()) "匹配" else ""}",
                        modifier = Modifier.align(Alignment.CenterVertically),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            fun section(status: AgentStatus, rows: List<Agent>, title: String) {
                val visible = filtered.filter { it.status == status }
                if (visible.isEmpty()) return
                item(key = "h-$status") { SectionHeader(title, visible.size, status) }
                items(visible, key = { it.key }) { agent ->
                    AgentRow(agent, now, ui.repo.hosts.firstOrNull { it.host.id == agent.hostId }, Modifier.animateItem()) { onOpenAgent(agent) }
                }
            }

            section(AgentStatus.WAITING, agents.filter { it.status == AgentStatus.WAITING }, "等待你处理")
            section(AgentStatus.UNKNOWN, agents.filter { it.status == AgentStatus.UNKNOWN }, "状态待核实")
            section(AgentStatus.REVIEW, agents.filter { it.status == AgentStatus.REVIEW }, "待查看")
            section(AgentStatus.WORKING, agents.filter { it.status == AgentStatus.WORKING }, "进行中")
            section(AgentStatus.IDLE, agents.filter { it.status == AgentStatus.IDLE }, "空闲")

            if (filtered.isEmpty() && agents.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().padding(top = 36.dp, bottom = 24.dp)) {
                        Text("没有匹配的 agent", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (ui.filter == InboxFilter.ATTENTION) "当前筛选没有待处理项。" else "试试其他关键词，或清除搜索。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (ui.filter == InboxFilter.ATTENTION) {
                                TextButton(onClick = { onFilter(InboxFilter.ALL) }) { Text("查看全部") }
                            }
                            if (ui.query.isNotBlank()) {
                                TextButton(onClick = { onQuery("") }) { Text("清除搜索") }
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun BrandMark() {
    Canvas(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(Color(0xFF12151A)),
    ) {
        val u = size.width / 108f
        val paper = Color(0xFFF2EFE6)
        val ink = Brush.verticalGradient(
            listOf(Color(0xFFFBF7EC), Color(0xFFE2DBC9)),
            startY = 39f * u, endY = 75f * u,
        )
        drawRoundRect(ink, Offset(31f * u, 39f * u), Size(46f * u, 36f * u), CornerRadius(14f * u))
        drawRoundRect(paper, Offset(50f * u, 31f * u), Size(8f * u, 9f * u), CornerRadius(2f * u))
        drawCircle(Color(0xFFFF8A3D), radius = 4.7f * u, center = Offset(54f * u, 26f * u))
        drawRoundRect(Color(0xFF12151A), Offset(37f * u, 51f * u), Size(34f * u, 13f * u), CornerRadius(6.5f * u))
        drawCircle(Color(0xFFFAF6EC), radius = 3.5f * u, center = Offset(44f * u, 57.5f * u))
        drawCircle(Color(0xFFFF8A3D), radius = 3.5f * u, center = Offset(61f * u, 57.5f * u))
    }
}

@Composable
private fun HostStrip(hosts: List<HostRuntime>, onOpenHost: ((String) -> Unit)?) {
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(hosts, key = { it.host.id }) { host ->
            val failed = host.conn as? ConnState.Failed
            val connected = host.conn is ConnState.Connected
            val p = statusPalette()
            Row(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .heightIn(min = 48.dp)
                    .then(if (onOpenHost != null) Modifier.clickable(
                        role = androidx.compose.ui.semantics.Role.Button,
                        onClickLabel = "打开 ${host.host.label}",
                    ) { onOpenHost(host.host.id) } else Modifier)
                    .semantics {
                        if (onOpenHost != null) contentDescription = "${host.host.label}，${when {
                            failed != null -> "连接失败"
                            connected -> "已连接"
                            host.conn == ConnState.Connecting -> "连接中"
                            else -> "未连接"
                        }}"
                    }
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .background(
                            when {
                                failed != null -> MaterialTheme.colorScheme.error
                                connected -> p.review
                                host.conn == ConnState.Connecting -> p.working
                                else -> MaterialTheme.colorScheme.outline
                            },
                            CircleShape,
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    host.host.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (failed != null) {
                    Spacer(Modifier.width(6.dp))
                    Text("连接失败", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
                } else if (connected) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "已连接",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DutyCard(
    agents: List<Agent>,
    hosts: List<HostRuntime>,
    now: Long,
    onOpen: (Agent) -> Unit,
    onReconnect: (String) -> Unit,
) {
    val p = statusPalette()
    val configuration = LocalConfiguration.current
    val compact = configuration.screenHeightDp < 640 || configuration.fontScale >= 1.3f
    val waiting = agents.filter { it.status == AgentStatus.WAITING }
    val unknownCount = agents.count { it.status == AgentStatus.UNKNOWN }
    val workingCount = agents.count { it.status == AgentStatus.WORKING }
    val reviewCount = agents.count { it.status == AgentStatus.REVIEW }
    val idleCount = agents.count { it.status == AgentStatus.IDLE }
    val longest = waiting.minByOrNull { it.sinceMillis }
    val failures = hosts.filter { it.conn is ConnState.Failed }
    val offline = hosts.any { it.conn !is ConnState.Connected }
    var showFailures by remember { mutableStateOf(false) }
    val counts = buildList {
        if (unknownCount > 0) add("$unknownCount 待核实")
        if (workingCount > 0) add("$workingCount 进行中")
        if (reviewCount > 0) add("$reviewCount 待查看")
        if (idleCount > 0) add("$idleCount 空闲")
    }.joinToString("  ·  ")
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.58f))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        if (waiting.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${waiting.size} 个等待处理",
                        style = MaterialTheme.typography.titleSmall,
                        color = p.waiting,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (longest != null) {
                        Text(
                            "最久 ${formatElapsed(longest.sinceMillis, now)}${if (offline) " · 上次同步快照" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (longest != null) {
                    TextButton(
                        onClick = { onOpen(longest) },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    ) { Text("查看") }
                }
            }
        } else {
            Text(
                if (failures.isNotEmpty()) "部分机器未连接" else "没有等待处理的 agent",
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (counts.isNotBlank() && !compact) {
            Text(
                counts,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        if (failures.isNotEmpty()) {
            TextButton(
                onClick = { showFailures = !showFailures },
                modifier = Modifier.semantics { stateDescription = if (showFailures) "已展开" else "已收起" },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) { Text("${failures.size} 台连接失败 · ${if (showFailures) "收起" else "查看详情"}", color = MaterialTheme.colorScheme.error) }
            AnimatedVisibility(showFailures) {
            Column { failures.forEach { runtime ->
                Text(
                    "${runtime.host.label}：${(runtime.conn as ConnState.Failed).message}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    "最后成功同步：${formatAgo(runtime.lastSyncAt, now)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { onReconnect(runtime.host.id) }, enabled = runtime.conn != ConnState.Connecting) { Text("重连 ${runtime.host.label}") }
            } }
            }
        }
    }
}

@Composable
private fun FilterTab(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tint by androidx.compose.animation.animateColorAsState(
        targetValue = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = androidx.compose.animation.core.tween(160),
        label = "filter-tint",
    )
    val underline by androidx.compose.animation.core.animateDpAsState(
        targetValue = if (selected) 18.dp else 0.dp,
        animationSpec = androidx.compose.animation.core.tween(180),
        label = "filter-underline",
    )
    Column(
        modifier.selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, onClick = onClick)
            .heightIn(min = 48.dp).widthIn(min = 64.dp).padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = tint, softWrap = false)
        Spacer(Modifier.height(5.dp))
        Box(
            Modifier
                .width(underline)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(1.dp)),
        )
    }
}

@Composable
private fun AgentRow(agent: Agent, now: Long, runtime: HostRuntime?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = statusPalette()
    val color = when (agent.status) {
        AgentStatus.WAITING -> p.waiting
        AgentStatus.WORKING -> p.working
        AgentStatus.REVIEW -> p.review
        AgentStatus.IDLE -> p.idle
        AgentStatus.UNKNOWN -> p.unknown
    }
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "查看 ${agent.name} 的终端", onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = agent.status.label }
            .background(MaterialTheme.colorScheme.background)
            .padding(vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(22.dp)
                    .background(if (agent.status == AgentStatus.WAITING) color else Color.Transparent, RoundedCornerShape(2.dp)),
            )
            Spacer(Modifier.width(10.dp))
            StatusGlyph(agent.status, size = 15)
            Spacer(Modifier.width(10.dp))
            Text(
                agent.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            "${agent.hostLabel}  /  ${agent.workspaceLabel ?: agent.repo}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 23.dp, top = 3.dp),
        )
        Text(
            listOfNotNull(agent.tabLabel, agent.paneId, agent.kind).distinct().joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 23.dp, top = 1.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(start = 23.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (agent.status == AgentStatus.WAITING) "等待 ${formatElapsed(agent.sinceMillis, now)}" else agent.status.label,
                style = MaterialTheme.typography.labelMedium,
                color = color,
            )
        }
        val lastSync = runtime?.lastSyncAt
        val stale = runtime?.conn !is ConnState.Connected || lastSync == null || now - lastSync > 15_000
        if (stale) {
            Text(
                "${if (runtime?.conn is ConnState.Connected) "快照待更新" else "未连接，显示上次快照"} · ${formatAgo(lastSync, now)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 23.dp, top = 2.dp),
            )
        }
    }
    Box(Modifier.fillMaxWidth().height(0.7.dp).background(MaterialTheme.colorScheme.outlineVariant))
}
