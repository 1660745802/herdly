package dev.herdwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HerdTab
import dev.herdwatch.model.HerdWorkspace
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.theme.MonoStyle
import dev.herdwatch.ui.theme.statusPalette

@Composable
fun StructureScreen(
    runtime: HostRuntime,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onCreateWorkspace: () -> Unit,
    onCreateTab: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    onClosePane: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onCloseWorkspace: (String) -> Unit,
    onFocusPane: (String) -> Unit,
    onFocusTab: (String) -> Unit,
    onFocusWorkspace: (String) -> Unit,
    onStartAgent: (String?) -> Unit,
    onRestore: (Agent) -> Unit,
    onRenameWorkspace: (String, String) -> Unit,
    onRenameTab: (String, String) -> Unit,
    onRenamePane: (String, String) -> Unit,
    currentAgents: List<Agent>? = null,
) {
    val structure = runtime.structure
    var pendingClose by remember { mutableStateOf<CloseTarget?>(null) }
    var renaming by remember { mutableStateOf<RenameTarget?>(null) }
    var showShells by rememberSaveable(runtime.host.id) { mutableStateOf(false) }
    val availablePaneIds = structure.panes.filter { it.isAvailableShell(runtime, currentAgents) }.map { it.id }.toSet()
    fun targetPath(paneId: String? = null, tabId: String? = null, workspaceId: String? = null): String {
        val pane = structure.panes.firstOrNull { it.id == paneId }
        val tab = structure.tabs.firstOrNull { it.id == (tabId ?: pane?.tabId) }
        val workspace = structure.workspaces.firstOrNull { it.id == (workspaceId ?: tab?.workspaceId ?: pane?.workspaceId) }
        return listOfNotNull(runtime.host.label, workspace?.label, tab?.label, pane?.let { "${it.title.ifBlank { it.id }} (${it.id})" }, pane?.cwd ?: workspace?.worktreePath).joinToString("\n")
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Column(Modifier.weight(1f)) {
                Text(runtime.host.label, style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "工作区 / 标签页 / 分屏",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = !runtime.conn.isConnecting()) {
                Icon(Icons.Default.Refresh, contentDescription = "刷新")
            }
        }

        if (runtime.conn is ConnState.Failed) {
            Text(
                runtime.conn.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                SummaryCard(runtime, showShells, availablePaneIds.size, currentAgents != null, onCreateWorkspace, onStartAgent) { showShells = !showShells }
            }
            if (runtime.restorable.isNotEmpty()) {
                item { RestoreCard(runtime.restorable, onRestore) }
            }
            if (structure.workspaces.isEmpty()) {
                item {
                    EmptyBlock(
                        title = "还没有 workspace",
                        body = "Herdr 里每个项目通常是一个 workspace，里面按 tab 分组、再拆 pane。手机端可以直接建。",
                    ) {
                        Button(onClick = onCreateWorkspace, enabled = runtime.conn is ConnState.Connected, shape = RoundedCornerShape(10.dp)) { Text("新建 workspace") }
                    }
                }
            }
            items(structure.workspaces, key = { it.id }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    structure = structure,
                    onFocusWorkspace = { onFocusWorkspace(workspace.id) },
                    onCreateTab = { onCreateTab(workspace.id) },
                    onCloseWorkspace = { pendingClose = CloseTarget("workspace", workspace.id, targetPath(workspaceId = workspace.id)) },
                    onFocusTab = onFocusTab,
                    onCloseTab = { id -> pendingClose = CloseTarget("tab", id, targetPath(tabId = id)) },
                    onFocusPane = onFocusPane,
                    onSplit = onSplit,
                    onClosePane = { id -> pendingClose = CloseTarget("pane", id, targetPath(paneId = id)) },
                    onStartAgent = onStartAgent,
                    availablePaneIds = availablePaneIds,
                    showShells = showShells,
                    onShowShells = { showShells = true },
                    onRenameWorkspace = { renaming = RenameTarget("workspace", workspace.id, workspace.label) },
                    onRenameTab = { tab -> renaming = RenameTarget("tab", tab.id, tab.label) },
                    onRenamePane = { pane -> renaming = RenameTarget("pane", pane.id, pane.title) },
                )
            }
            item {
                Text(
                    "结构直接读自 Herdr；在这里建 tab、拆 pane、启动 agent，主机上的 TUI 会同步变化。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }

    renaming?.let { target ->
        var value by remember(target.id) { mutableStateOf(target.label) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名 ${typeName(target.type)}") },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it.take(40) },
                    singleLine = true,
                    label = { Text("新名字") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = value.isNotBlank(),
                    onClick = {
                        val name = value.trim()
                        when (target.type) {
                            "workspace" -> onRenameWorkspace(target.id, name)
                            "tab" -> onRenameTab(target.id, name)
                            else -> onRenamePane(target.id, name)
                        }
                        renaming = null
                    },
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    pendingClose?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingClose = null },
            title = { Text("关闭${typeName(target.type)}？") },
            text = {
                Text(
                    target.label + "\n\n" + when (target.type) {
                        "workspace" -> "会关闭整个工作区，里面所有标签页、分屏和进程都会结束。"
                        "tab" -> "会关闭这个标签页，里面的分屏和进程都会结束。"
                        else -> "会关闭这个分屏，其中的 agent 和其他进程都会被终止。"
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    when (target.type) {
                        "workspace" -> onCloseWorkspace(target.id)
                        "tab" -> onCloseTab(target.id)
                        else -> onClosePane(target.id)
                    }
                    pendingClose = null
                }) { Text("关闭", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingClose = null }) { Text("取消") } },
        )
    }
}

private data class CloseTarget(val type: String, val id: String, val label: String)

private data class RenameTarget(val type: String, val id: String, val label: String)

private fun typeName(type: String) = when (type) {
    "workspace" -> "工作区"
    "tab" -> "标签页"
    else -> "分屏"
}

private fun ConnState.isConnecting() = this == ConnState.Connecting

// UNKNOWN is not evidence of an empty shell. Missing current-agent data is also unsafe.
private fun HerdPane.isAvailableShell(host: HostRuntime, currentAgents: List<Agent>?): Boolean =
    host.conn is ConnState.Connected && host.lastSyncAt != null &&
        System.currentTimeMillis() - host.lastSyncAt <= 15_000 &&
        currentAgents != null && currentAgents.none { it.hostId == host.host.id && it.paneId == id } &&
        agentKind == null && status == AgentStatus.IDLE && sessionKind == null && sessionValue == null && restoreError == null

@Composable
private fun SummaryCard(
    runtime: HostRuntime,
    showShells: Boolean,
    availableShellCount: Int,
    occupancyAvailable: Boolean,
    onCreateWorkspace: () -> Unit,
    onStartAgent: (String?) -> Unit,
    onToggleShells: () -> Unit,
) {
    val p = statusPalette()
    val s = runtime.structure
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (runtime.conn is ConnState.Connected) p.review else p.working, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(
                "${s.workspaces.size} 工作区 · ${s.tabs.size} 标签页 · ${s.panes.size} 分屏",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            val shells = s.panes.count { it.agentKind == null }
            if (shells > 0) {
                TextButton(onClick = onToggleShells) {
                    Text(if (showShells) "收起非 agent 分屏" else "其他分屏 $shells", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onCreateWorkspace, enabled = runtime.conn is ConnState.Connected, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("工作区")
            }
            OutlinedButton(onClick = { onStartAgent(null) }, enabled = availableShellCount > 0, shape = RoundedCornerShape(10.dp)) {
                Text("启动 agent")
            }
        }
        if (availableShellCount == 0) {
            Text(
                if (!occupancyAvailable) "暂无法核实分屏占用情况，已有分屏的启动入口不可用。可从收件箱创建新任务。"
                else "没有已核实可用的空 shell 分屏。可新建标签页，刷新后再检查；已有 agent 或状态未识别的分屏不会用于启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Text(
            "最后成功同步：${formatAgo(runtime.lastSyncAt)}${if (runtime.conn !is ConnState.Connected) " · 当前未连接" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

@Composable
private fun RestoreCard(agents: List<Agent>, onRestore: (Agent) -> Unit) {
    val p = statusPalette()
    Column(
        Modifier.fillMaxWidth().background(p.waitingWash, RoundedCornerShape(14.dp)).padding(14.dp),
    ) {
        Text("上次的 agent 不在了", style = MaterialTheme.typography.titleSmall, color = p.waiting)
        Text(
            "Herdr 重启或会话退出后，可以按原 pane、原名字接着跑。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        agents.forEach { agent ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(agent.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(agent.hostLabel, agent.workspaceLabel, agent.tabLabel, agent.paneId, agent.cwd).joinToString(" / "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(agent.kind, style = MonoStyle.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { onRestore(agent) }, shape = RoundedCornerShape(8.dp)) {
                    Text("恢复", color = p.waiting)
                }
            }
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: HerdWorkspace,
    structure: HerdStructure,
    onFocusWorkspace: () -> Unit,
    onCreateTab: () -> Unit,
    onCloseWorkspace: () -> Unit,
    onFocusTab: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onFocusPane: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    onClosePane: (String) -> Unit,
    onStartAgent: (String?) -> Unit,
    showShells: Boolean,
    onShowShells: () -> Unit,
    onRenameWorkspace: () -> Unit,
    onRenameTab: (HerdTab) -> Unit,
    onRenamePane: (HerdPane) -> Unit,
    availablePaneIds: Set<String>,
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(workspace.id) { mutableStateOf(true) }
    val tabs = structure.tabsOf(workspace.id)
    Column(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "W${workspace.number}",
                style = MonoStyle.copy(fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                workspace.label,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (workspace.linkedWorktree) FocusPill("worktree")
            if (workspace.focused) FocusPill("当前")
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "${workspace.label} 工作区操作")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("聚焦") }, onClick = { menu = false; onFocusWorkspace() })
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRenameWorkspace() })
                    DropdownMenuItem(text = { Text("新建标签页") }, onClick = { menu = false; onCreateTab() })
                    DropdownMenuItem(text = { Text("关闭工作区") }, onClick = { menu = false; onCloseWorkspace() })
                }
            }
        }
        workspace.repoName?.let { repo ->
            Text(
                buildString {
                    append(repo)
                    workspace.worktreePath?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                },
                style = MonoStyle.copy(fontSize = 10.5.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        ) { Text(if (expanded) "收起 ${tabs.size} 个标签页" else "展开 ${tabs.size} 个标签页") }
        if (expanded) tabs.forEach { tab ->
            TabBlock(
                tab = tab,
                panes = structure.panesOf(tab.id),
                onFocusTab = { onFocusTab(tab.id) },
                onCloseTab = { onCloseTab(tab.id) },
                onFocusPane = onFocusPane,
                onSplit = onSplit,
                onClosePane = onClosePane,
                onStartAgent = onStartAgent,
                showShells = showShells,
                onShowShells = onShowShells,
                onRenameTab = { onRenameTab(tab) },
                onRenamePane = onRenamePane,
                availablePaneIds = availablePaneIds,
            )
        }
        if (expanded && tabs.isEmpty()) {
            TextButtonRow("这个工作区还没有标签页") { onCreateTab() }
        } else if (expanded) {
            TextButtonRow("＋ 新建标签页") { onCreateTab() }
        }
    }
}

@Composable
private fun TabBlock(
    tab: HerdTab,
    panes: List<HerdPane>,
    onFocusTab: () -> Unit,
    onCloseTab: () -> Unit,
    onFocusPane: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    onClosePane: (String) -> Unit,
    onStartAgent: (String?) -> Unit,
    showShells: Boolean,
    onShowShells: () -> Unit,
    onRenameTab: () -> Unit,
    onRenamePane: (HerdPane) -> Unit,
    availablePaneIds: Set<String>,
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(tab.id) { mutableStateOf(true) }
    val p = statusPalette()
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).background(statusColor(tab.status, p), CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(
                "T${tab.number} ${tab.label}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text("${panes.size} 分屏 · ${tab.status.label}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.MoreVert, contentDescription = "${tab.label} 标签页操作", modifier = Modifier.size(18.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("聚焦 tab") }, onClick = { menu = false; onFocusTab() })
                    DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRenameTab() })
                    DropdownMenuItem(text = { Text("关闭 tab") }, onClick = { menu = false; onCloseTab() })
                }
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 2.dp, bottom = 1.dp)
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        )
        TextButton(
            onClick = { expanded = !expanded },
            modifier = Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" },
        ) { Text(if (expanded) "收起分屏" else "展开 ${panes.size} 个分屏") }
        val visible = if (showShells) panes else panes.filter { it.agentKind != null || it.status == AgentStatus.UNKNOWN || it.sessionKind != null || it.sessionValue != null }
        val hiddenShells = panes.size - visible.size
        if (expanded) visible.forEach { pane ->
            PaneRow(pane, onFocusPane, onSplit, onClosePane, onStartAgent, onRenamePane, pane.id in availablePaneIds)
        }
        if (expanded && !showShells && hiddenShells > 0) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$hiddenShells 个其他分屏",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onShowShells) { Text("显示", style = MaterialTheme.typography.labelMedium) }
            }
        }
    }
}

@Composable
private fun PaneRow(
    pane: HerdPane,
    onFocusPane: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    onClosePane: (String) -> Unit,
    onStartAgent: (String?) -> Unit,
    onRenamePane: (HerdPane) -> Unit,
    availableShell: Boolean,
) {
    var menu by remember { mutableStateOf(false) }
    val p = statusPalette()
    Row(
        Modifier.fillMaxWidth().clickable(onClickLabel = "聚焦 ${pane.title.ifBlank { pane.id }}") { onFocusPane(pane.id) }
            .semantics { stateDescription = pane.status.label }.heightIn(min = 48.dp).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(statusColor(pane.status, p), CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${pane.title.ifBlank { pane.id }} · ${pane.agentKind ?: if (availableShell) "空闲 shell" else "未核实分屏"}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    pane.id,
                    pane.status.label,
                    pane.cwd,
                    pane.restoreError?.let { "恢复失败" },
                ).joinToString(" · "),
                style = MonoStyle.copy(fontSize = 10.5.sp),
                color = if (pane.restoreError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (availableShell) {
            TextButton(
                onClick = { onStartAgent(pane.id) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("启动", style = MaterialTheme.typography.labelMedium) }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.MoreVert, contentDescription = "${pane.title.ifBlank { pane.id }} 分屏操作", modifier = Modifier.size(18.dp))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("聚焦 pane") }, onClick = { menu = false; onFocusPane(pane.id) })
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRenamePane(pane) })
                DropdownMenuItem(text = { Text("向右拆分") }, onClick = { menu = false; onSplit(pane.id, "right") })
                DropdownMenuItem(text = { Text("向下拆分") }, onClick = { menu = false; onSplit(pane.id, "down") })
                DropdownMenuItem(text = { Text("关闭 pane") }, onClick = { menu = false; onClosePane(pane.id) })
            }
        }
    }
}

@Composable
private fun FocusPill(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun TextButtonRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = 14.dp),
    )
}

private fun statusColor(status: AgentStatus, p: dev.herdwatch.ui.theme.StatusPalette) = when (status) {
    AgentStatus.WAITING -> p.waiting
    AgentStatus.WORKING -> p.working
    AgentStatus.REVIEW -> p.review
    AgentStatus.IDLE -> p.idle
    AgentStatus.UNKNOWN -> p.unknown
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun StartAgentSheet(
    host: HostRuntime,
    initialPaneId: String?,
    onDismiss: () -> Unit,
    onStart: (paneId: String, kind: String, name: String, args: List<String>) -> Unit,
    currentAgents: List<Agent>? = null,
) {
    val candidates = host.structure.panes.filter { it.isAvailableShell(host, currentAgents) }
    var paneId by rememberSaveable(host.host.id, initialPaneId) { mutableStateOf(initialPaneId?.takeIf { id -> candidates.any { it.id == id } } ?: candidates.firstOrNull()?.id) }
    val selectedPane = candidates.firstOrNull { it.id == paneId }
    val workspace = host.structure.workspaces.firstOrNull { it.id == selectedPane?.workspaceId }
    val tab = host.structure.tabs.firstOrNull { it.id == selectedPane?.tabId }
    var kind by rememberSaveable(host.host.id) { mutableStateOf("claude") }
    var name by rememberSaveable(host.host.id) { mutableStateOf("claude") }
    var resume by rememberSaveable(host.host.id) { mutableStateOf(false) }
    var argsText by rememberSaveable(host.host.id) { mutableStateOf("") }
    val duplicateName = currentAgents.orEmpty().any { it.hostId == host.host.id && it.name == name.trim() }

    fun applyResume(on: Boolean) {
        resume = on
        argsText = if (on) AppViewModel.resumeArgs(kind).joinToString("\n") else ""
    }

    fun applyKind(next: String) {
        kind = next
        name = next
        if (resume) argsText = AppViewModel.resumeArgs(next).joinToString("\n")
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text("启动 agent", style = MaterialTheme.typography.headlineSmall)
            Text(
                "仅列出已连接主机中可核实为空闲 shell 的分屏。请核对目标后启动。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))
            Text("目标分屏", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(candidates, key = { it.id }) { pane ->
                    FilterChip(
                        selected = paneId == pane.id,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { paneId = pane.id },
                        label = {
                            Text(
                                "${pane.title.ifBlank { pane.id }} · ${pane.id}",
                                style = MonoStyle.copy(fontSize = 11.sp),
                            )
                        },
                    )
                }
            }
            if (candidates.isEmpty()) {
                Text(
                    if (currentAgents == null) "暂无法核实分屏占用情况。请返回收件箱创建新任务，以使用新标签页。"
                    else "没有已核实可用的空 shell 分屏。返回会话结构，新建标签页并刷新；不会向已有 agent 或未识别分屏发送启动命令。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selectedPane != null) {
                Text("目标：${host.host.label} / ${workspace?.label ?: selectedPane.workspaceId} / ${tab?.label ?: selectedPane.tabId} / ${selectedPane.title.ifBlank { selectedPane.id }} (${selectedPane.id})\n目录：${selectedPane.cwd ?: "未提供"}", style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text("Agent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(dev.herdwatch.domain.AgentKinds.common) { k ->
                    FilterChip(selected = kind == k, onClick = { applyKind(k) }, label = { Text(k) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                isError = duplicateName || name.isBlank(),
                supportingText = { Text(if (duplicateName) "此主机上已有同名 agent，请换一个名字" else "使用主机内唯一的 agent 名称") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                label = { Text("名字（Herdr 里的唯一名）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("接着上次会话", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (resume) "使用下方续接参数；由 agent 查找历史会话" else "新开一个会话",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = resume, onCheckedChange = { applyResume(it) }, modifier = Modifier.semantics { contentDescription = "接着上次会话" })
            }
            if (resume) {
                OutlinedTextField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    label = { Text("启动参数（每行一个）") },
                    supportingText = { Text("含空格的路径也放在同一行，无需添加引号") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    val pane = host.structure.panes.firstOrNull { it.id == paneId && it.isAvailableShell(host, currentAgents) } ?: return@Button
                    if (name.isBlank() || duplicateName) return@Button
                    onStart(pane.id, kind, name.trim(), if (resume) argsText.lines().filter { it.isNotBlank() } else emptyList())
                },
                enabled = selectedPane != null && name.isNotBlank() && !duplicateName,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text("启动 ${name.ifBlank { kind }}") }
        }
    }
}
