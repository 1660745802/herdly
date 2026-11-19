package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HerdTab
import dev.herdwatch.model.HerdWorkspace
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoCard
import dev.herdwatch.ui.design.NeoEmptyState
import dev.herdwatch.ui.design.NeoChip
import dev.herdwatch.ui.design.NeoDialog
import dev.herdwatch.ui.design.NeoDivider
import dev.herdwatch.ui.design.NeoField
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoMenu
import dev.herdwatch.ui.design.NeoMenuItem
import dev.herdwatch.ui.design.NeoPageHeader
import dev.herdwatch.ui.design.NeoSheet
import dev.herdwatch.ui.design.NeoToggle
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.statusPalette
import dev.herdwatch.ui.theme.statusColor
import dev.herdwatch.ui.design.spacing
import androidx.compose.ui.semantics.Role

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
    onRenameWorkspace: (String, String) -> Unit,
    onRenameTab: (String, String) -> Unit,
    onRenamePane: (String, String) -> Unit,
    currentAgents: List<Agent>? = null,
) {
    val structure = runtime.structure
    val colors = ds()
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
    Column(Modifier.fillMaxSize()) {
        NeoPageHeader(
            title = runtime.host.label,
            subtitle = stringResource(R.string.structure_header_breadcrumb),
            onBack = onBack,
            actions = {
                NeoIconButton(
                    glyph = NeoGlyph.Refresh,
                    onClick = onRefresh,
                    contentDescription = stringResource(R.string.common_refresh),
                    enabled = !runtime.conn.isConnecting(),
                )
            },
        )

        if (runtime.conn is ConnState.Failed) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = DsSpace.l, vertical = DsSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    runtime.conn.message,
                    style = DsTypography.caption,
                    color = colors.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                NeoButton(
                    label = stringResource(R.string.common_reconnect),
                    onClick = onRefresh,
                    tone = NeoButtonTone.Quiet,
                    compact = true,
                )
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            // The first card's raised top rim needs room; otherwise the header/tab above clips it.
            contentPadding = PaddingValues(
                start = spacing().gutter, top = DsSpace.m, end = spacing().gutter, bottom = DsSpace.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing().block),
        ) {
            item {
                SummaryCard(runtime, showShells, availablePaneIds.size, currentAgents != null, onCreateWorkspace, onStartAgent) { showShells = !showShells }
            }
            if (structure.workspaces.isEmpty()) {
                item {
                    // 空状态也用同一套组件：图标、标题、说明、主行动按钮。
                    NeoEmptyState(
                        icon = NeoGlyph.Tray,
                        title = stringResource(R.string.structure_empty_title),
                        body = stringResource(R.string.structure_empty_body),
                    ) {
                        NeoButton(
                            label = stringResource(R.string.structure_new_workspace),
                            onClick = onCreateWorkspace,
                            tone = NeoButtonTone.Primary,
                            icon = NeoGlyph.Plus,
                            enabled = runtime.conn is ConnState.Connected,
                        )
                    }
                }
            }
            items(structure.workspaces, key = { it.id }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    modifier = animateItemMotion(),
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
                    stringResource(R.string.structure_footer_hint),
                    style = DsTypography.caption,
                    color = colors.textFaint,
                    modifier = Modifier.padding(top = DsSpace.xs),
                )
            }
        }
    }

    renaming?.let { target ->
        var value by remember(target.id) { mutableStateOf(target.label) }
        NeoDialog(
            onDismissRequest = { renaming = null },
            title = stringResource(R.string.structure_rename_title, typeName(target.type)),
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = {
                val name = value.trim()
                when (target.type) {
                    "workspace" -> onRenameWorkspace(target.id, name)
                    "tab" -> onRenameTab(target.id, name)
                    else -> onRenamePane(target.id, name)
                }
                renaming = null
            },
            dismissLabel = stringResource(R.string.common_cancel),
            confirmEnabled = value.isNotBlank(),
        ) {
            NeoField(
                value = value,
                onValueChange = { value = it.take(40) },
                label = stringResource(R.string.structure_new_name),
            )
        }
    }

    pendingClose?.let { target ->
        val closeBody = when (target.type) {
            "workspace" -> stringResource(R.string.structure_close_workspace_body)
            "tab" -> stringResource(R.string.structure_close_tab_body)
            else -> stringResource(R.string.structure_close_pane_body)
        }
        NeoDialog(
            onDismissRequest = { pendingClose = null },
            title = stringResource(R.string.structure_close_title, typeName(target.type)),
            subtitle = stringResource(R.string.structure_close_message, target.label, closeBody),
            confirmLabel = stringResource(R.string.common_close),
            onConfirm = {
                when (target.type) {
                    "workspace" -> onCloseWorkspace(target.id)
                    "tab" -> onCloseTab(target.id)
                    else -> onClosePane(target.id)
                }
                pendingClose = null
            },
            dismissLabel = stringResource(R.string.common_cancel),
            destructive = true,
        )
    }
}

private data class CloseTarget(val type: String, val id: String, val label: String)

private data class RenameTarget(val type: String, val id: String, val label: String)

@Composable
private fun typeName(type: String): String = when (type) {
    "workspace" -> stringResource(R.string.structure_type_workspace)
    "tab" -> stringResource(R.string.structure_type_tab)
    else -> stringResource(R.string.structure_type_pane)
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
    val s = runtime.structure
    val colors = ds()
    NeoCard(modifier = Modifier.fillMaxWidth(), depth = DsElev.rest) {
        Column(Modifier.padding(DsSpace.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AnimatedConnDot(runtime.conn)
                Spacer(Modifier.width(DsSpace.s))
                Text(
                    stringResource(
                        R.string.structure_summary_line,
                        pluralStringResource(R.plurals.structure_workspace_count, s.workspaces.size, s.workspaces.size),
                        pluralStringResource(R.plurals.structure_tab_count, s.tabs.size, s.tabs.size),
                        pluralStringResource(R.plurals.structure_pane_count, s.panes.size, s.panes.size),
                    ),
                    style = DsTypography.headline,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                )
                val shells = s.panes.count { it.agentKind == null }
                if (shells > 0) {
                    NeoButton(
                        label = if (showShells) {
                            stringResource(R.string.structure_collapse_shells)
                        } else {
                            pluralStringResource(R.plurals.structure_other_panes, shells, shells)
                        },
                        onClick = onToggleShells,
                        tone = NeoButtonTone.Ghost,
                        compact = true,
                    )
                }
            }
            Spacer(Modifier.height(DsSpace.m))
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                NeoButton(
                    label = stringResource(R.string.structure_add_workspace),
                    onClick = onCreateWorkspace,
                    tone = NeoButtonTone.Primary,
                    icon = NeoGlyph.Plus,
                    enabled = runtime.conn is ConnState.Connected,
                    compact = true,
                )
                NeoButton(
                    label = stringResource(R.string.structure_start_agent),
                    onClick = { onStartAgent(null) },
                    tone = NeoButtonTone.Quiet,
                    enabled = availableShellCount > 0,
                    compact = true,
                )
            }
            if (availableShellCount == 0) {
                Text(
                    if (!occupancyAvailable) stringResource(R.string.structure_occupancy_unverified)
                    else stringResource(R.string.structure_no_available_shells),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    modifier = Modifier.padding(top = DsSpace.s),
                )
            }
            val lastSync = formatAgo(runtime.lastSyncAt)
            Text(
                if (runtime.conn !is ConnState.Connected) {
                    stringResource(R.string.structure_last_sync_offline, lastSync)
                } else {
                    stringResource(R.string.structure_last_sync, lastSync)
                },
                style = DsTypography.caption,
                color = colors.textSoft,
                modifier = Modifier.padding(top = DsSpace.s),
            )
        }
    }
}

@Composable
private fun WorkspaceCard(
    workspace: HerdWorkspace,
    structure: HerdStructure,
    modifier: Modifier = Modifier,
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
    val expandedState = stringResource(R.string.structure_state_expanded)
    val collapsedState = stringResource(R.string.structure_state_collapsed)
    val tabs = structure.tabsOf(workspace.id)
    val colors = ds()
    NeoCard(modifier = modifier.fillMaxWidth(), depth = DsElev.soft) {
        Column(Modifier.padding(DsSpace.l)) {
            Text(stringResource(R.string.structure_type_workspace), style = DsTypography.micro, color = colors.textFaint)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "W${workspace.number}",
                    style = DsTypography.mono.copy(fontSize = DsTypography.micro.fontSize),
                    color = colors.textFaint,
                )
                Spacer(Modifier.width(DsSpace.s))
                Text(
                    workspace.label,
                    style = DsTypography.headline,
                    color = colors.text,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                NeoIconButton(
                    glyph = NeoGlyph.Edit,
                    onClick = onRenameWorkspace,
                    contentDescription = stringResource(R.string.structure_rename_workspace_desc, workspace.label),
                )
                if (workspace.linkedWorktree) FocusPill("worktree")
                AnimatedVisibility(
                    visible = workspace.focused,
                    enter = fadeIn(tween(motionMillis(160))) + expandHorizontally(tween(motionMillis(200))),
                    exit = fadeOut(tween(motionMillis(120))) + shrinkHorizontally(tween(motionMillis(160))),
                ) { FocusPill(stringResource(R.string.structure_pill_current), active = workspace.focused) }
                Box {
                    NeoIconButton(
                        glyph = NeoGlyph.More,
                        onClick = { menu = true },
                        contentDescription = stringResource(R.string.structure_workspace_actions_desc, workspace.label),
                    )
                    NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        NeoMenuItem(label = stringResource(R.string.structure_focus), onClick = { menu = false; onFocusWorkspace() })
                        NeoMenuItem(label = stringResource(R.string.structure_rename), onClick = { menu = false; onRenameWorkspace() })
                        NeoMenuItem(label = stringResource(R.string.structure_new_tab), onClick = { menu = false; onCreateTab() })
                        NeoMenuItem(label = stringResource(R.string.structure_close_workspace), onClick = { menu = false; onCloseWorkspace() })
                    }
                }
            }
            workspace.repoName?.let { repo ->
                Text(
                    buildString {
                        append(repo)
                        workspace.worktreePath?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                    },
                    style = DsTypography.mono.copy(fontSize = DsTypography.micro.fontSize),
                    color = colors.textFaint,
                    modifier = Modifier.padding(top = DsSpace.xxs),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeoButton(
                    label = if (expanded) {
                        pluralStringResource(R.plurals.structure_collapse_tabs, tabs.size, tabs.size)
                    } else {
                        pluralStringResource(R.plurals.structure_expand_tabs, tabs.size, tabs.size)
                    },
                    onClick = { expanded = !expanded },
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                    modifier = Modifier.semantics { stateDescription = if (expanded) expandedState else collapsedState },
                )
                ExpandChevron(expanded = expanded)
            }
            ExpandableSection(expanded) {
                tabs.forEach { tab ->
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
                NeoButton(
                    label = if (tabs.isEmpty()) stringResource(R.string.structure_no_tabs) else stringResource(R.string.structure_new_tab_plus),
                    onClick = onCreateTab,
                    tone = NeoButtonTone.Ghost,
                    icon = NeoGlyph.Plus,
                    compact = true,
                )
            }
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
    val expandedState = stringResource(R.string.structure_state_expanded)
    val collapsedState = stringResource(R.string.structure_state_collapsed)
    val colors = ds()
    Column(
        Modifier.fillMaxWidth().padding(top = DsSpace.s, start = DsSpace.s),
    ) {
        Text(stringResource(R.string.structure_type_tab), style = DsTypography.micro, color = colors.textFaint)
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedStatusDot(tab.status, 6.dp)
            Spacer(Modifier.width(DsSpace.s))
            Text(
                "T${tab.number} ${tab.label}",
                style = DsTypography.headline,
                color = colors.text,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            NeoIconButton(
                    glyph = NeoGlyph.Edit,
                onClick = onRenameTab,
                contentDescription = stringResource(R.string.structure_rename_tab_desc, tab.label),
            )
            Text(
                stringResource(
                    R.string.structure_tab_meta,
                    pluralStringResource(R.plurals.structure_pane_count, panes.size, panes.size),
                    stringResource(tab.status.labelRes),
                ),
                style = DsTypography.micro,
                color = colors.textFaint,
            )
            Box {
                NeoIconButton(
                    glyph = NeoGlyph.More,
                    onClick = { menu = true },
                    contentDescription = stringResource(R.string.structure_tab_actions_desc, tab.label),
                )
                NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    NeoMenuItem(label = stringResource(R.string.structure_focus_tab), onClick = { menu = false; onFocusTab() })
                    NeoMenuItem(label = stringResource(R.string.structure_rename), onClick = { menu = false; onRenameTab() })
                    NeoMenuItem(label = stringResource(R.string.structure_close_tab), onClick = { menu = false; onCloseTab() })
                }
            }
        }
        NeoDivider(Modifier.padding(top = DsSpace.xxs, bottom = DsSpace.xxs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoButton(
                label = if (expanded) {
                    stringResource(R.string.structure_collapse_panes)
                } else {
                    pluralStringResource(R.plurals.structure_expand_panes, panes.size, panes.size)
                },
                onClick = { expanded = !expanded },
                tone = NeoButtonTone.Ghost,
                compact = true,
                modifier = Modifier.semantics { stateDescription = if (expanded) expandedState else collapsedState },
            )
            ExpandChevron(expanded = expanded)
        }
        val visible = if (showShells) panes else panes.filter { it.agentKind != null || it.status == AgentStatus.UNKNOWN || it.sessionKind != null || it.sessionValue != null }
        val hiddenShells = panes.size - visible.size
        ExpandableSection(expanded) {
            visible.forEach { pane ->
                PaneRow(pane, onFocusPane, onSplit, onClosePane, onStartAgent, onRenamePane, pane.id in availablePaneIds)
            }
            if (!showShells && hiddenShells > 0) {
                Row(Modifier.fillMaxWidth().padding(vertical = DsSpace.xs), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pluralStringResource(R.plurals.structure_hidden_panes, hiddenShells, hiddenShells),
                        style = DsTypography.caption,
                        color = colors.textSoft,
                        modifier = Modifier.weight(1f),
                    )
                    NeoButton(
                        label = stringResource(R.string.structure_show),
                        onClick = onShowShells,
                        tone = NeoButtonTone.Ghost,
                        compact = true,
                    )
                }
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
    val paneLabel = pane.title.ifBlank { pane.id }
    val paneKindLabel = pane.agentKind ?: if (availableShell) {
        stringResource(R.string.structure_pane_idle_shell)
    } else {
        stringResource(R.string.structure_pane_unverified)
    }
    val restoreFailedLabel = stringResource(R.string.structure_restore_failed)
    val paneStatusLabel = stringResource(pane.status.labelRes)
    val colors = ds()
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.structure_focus_pane_label, paneLabel)) { onFocusPane(pane.id) }
            .semantics { stateDescription = paneStatusLabel }.heightIn(min = 48.dp).padding(vertical = DsSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedStatusDot(pane.status, 7.dp)
        Spacer(Modifier.width(DsSpace.s))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    R.string.structure_pane_meta,
                    stringResource(R.string.structure_type_pane),
                    paneLabel,
                    paneKindLabel,
                ),
                style = DsTypography.body,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    pane.id,
                    paneStatusLabel,
                    pane.cwd,
                    pane.restoreError?.let { restoreFailedLabel },
                ).joinToString(" · "),
                style = DsTypography.mono.copy(fontSize = DsTypography.micro.fontSize),
                color = if (pane.restoreError != null) colors.danger else colors.textSoft,
            )
        }
        NeoIconButton(
                    glyph = NeoGlyph.Edit,
            onClick = { onRenamePane(pane) },
            contentDescription = stringResource(R.string.structure_rename_pane_desc, paneLabel),
        )
        if (availableShell) {
            NeoButton(
                label = stringResource(R.string.structure_start_pane),
                onClick = { onStartAgent(pane.id) },
                tone = NeoButtonTone.Quiet,
                compact = true,
            )
        }
        Box {
            NeoIconButton(
                glyph = NeoGlyph.More,
                onClick = { menu = true },
                contentDescription = stringResource(R.string.structure_pane_actions_desc, paneLabel),
            )
            NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                NeoMenuItem(label = stringResource(R.string.structure_focus_pane), onClick = { menu = false; onFocusPane(pane.id) })
                NeoMenuItem(label = stringResource(R.string.structure_rename), onClick = { menu = false; onRenamePane(pane) })
                NeoMenuItem(label = stringResource(R.string.structure_split_right), onClick = { menu = false; onSplit(pane.id, "right") })
                NeoMenuItem(label = stringResource(R.string.structure_split_down), onClick = { menu = false; onSplit(pane.id, "down") })
                NeoMenuItem(label = stringResource(R.string.structure_close_pane), onClick = { menu = false; onClosePane(pane.id) })
            }
        }
    }
}

@Composable
private fun FocusPill(text: String, active: Boolean = true) {
    val colors = ds()
    val tint by animateColorAsState(
        targetValue = if (active) colors.accent else colors.textSoft,
        animationSpec = tween(motionMillis(180)),
        label = "focus-pill-tint",
    )
    val wash by animateColorAsState(
        targetValue = if (active) colors.accentWash else colors.well,
        animationSpec = tween(motionMillis(180)),
        label = "focus-pill-wash",
    )
    Text(
        text,
        style = DsTypography.micro,
        color = tint,
        modifier = Modifier
            .background(wash, RoundedCornerShape(DsRadius.key))
            .padding(horizontal = DsSpace.s, vertical = DsSpace.xxs),
    )
}

/** 展开/折叠的旋转三角：同一字形与同一运动时长，但用设计系统的描边图标。 */
@Composable
private fun ExpandChevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(motionMillis(200)),
        label = "expand-chevron",
    )
    NeoIcon(
        NeoGlyph.ChevronDown,
        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
        tint = ds().textSoft,
        contentDescription = null,
    )
}

/** Tab/pane status dot: fades between status colors instead of snapping. */
@Composable
private fun AnimatedStatusDot(status: AgentStatus, size: Dp) {
    val target = statusColor(status, statusPalette())
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(motionMillis(220)),
        label = "status-dot",
    )
    Box(Modifier.size(size).background(color, CircleShape))
}


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
    val resumeLabel = stringResource(R.string.structure_resume_label)
    val colors = ds()
    val invalidName = duplicateName || name.isBlank()

    fun applyResume(on: Boolean) {
        resume = on
        argsText = if (on) AppViewModel.resumeArgs(kind).joinToString("\n") else ""
    }

    fun applyKind(next: String) {
        kind = next
        name = next
        if (resume) argsText = AppViewModel.resumeArgs(next).joinToString("\n")
    }

    NeoSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = DsSpace.xl, end = DsSpace.xl, bottom = DsSpace.xxl),
        ) {
            Text(stringResource(R.string.structure_start_agent), style = DsTypography.title, color = colors.text)
            Text(
                stringResource(R.string.structure_start_sheet_body),
                style = DsTypography.body,
                color = colors.textSoft,
            )
            Spacer(Modifier.height(DsSpace.l))
            Text(stringResource(R.string.structure_target_pane), style = DsTypography.caption, color = colors.textSoft)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.s), modifier = Modifier.padding(vertical = DsSpace.xs)) {
                items(candidates, key = { it.id }) { pane ->
                    NeoChip(
                        label = "${pane.title.ifBlank { pane.id }} · ${pane.id}",
                        selected = paneId == pane.id,
                        onClick = { paneId = pane.id },
                    )
                }
            }
            if (candidates.isEmpty()) {
                Text(
                    if (currentAgents == null) stringResource(R.string.structure_no_candidates_no_agents)
                    else stringResource(R.string.structure_no_candidates_no_shells),
                    style = DsTypography.body,
                    color = colors.textSoft,
                )
            }
            if (selectedPane != null) {
                Text(
                    stringResource(
                        R.string.structure_target_detail,
                        host.host.label,
                        workspace?.label ?: selectedPane.workspaceId,
                        tab?.label ?: selectedPane.tabId,
                        selectedPane.title.ifBlank { selectedPane.id },
                        selectedPane.id,
                        selectedPane.cwd ?: stringResource(R.string.structure_not_provided),
                    ),
                    style = DsTypography.body,
                    color = colors.textSoft,
                )
            }
            Spacer(Modifier.height(DsSpace.s))
            Text(stringResource(R.string.common_agent), style = DsTypography.caption, color = colors.textSoft)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.s), modifier = Modifier.padding(vertical = DsSpace.xs)) {
                items(dev.herdwatch.domain.AgentKinds.common) { k ->
                    NeoChip(label = k, selected = kind == k, onClick = { applyKind(k) })
                }
            }
            NeoField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.structure_name_label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            // NeoField 没有 isError 态：校验文案放在字段下方，错误时用 danger 色。
            Text(
                if (duplicateName) stringResource(R.string.structure_duplicate_name)
                else stringResource(R.string.structure_unique_name_hint),
                style = DsTypography.caption,
                color = if (invalidName) colors.danger else colors.textFaint,
                modifier = Modifier.padding(top = DsSpace.xs),
            )
            Spacer(Modifier.height(DsSpace.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.structure_resume_label), style = DsTypography.body, color = colors.text)
                    Text(
                        if (resume) stringResource(R.string.structure_resume_on_hint)
                        else stringResource(R.string.structure_resume_off_hint),
                        style = DsTypography.caption,
                        color = colors.textSoft,
                    )
                }
                NeoToggle(
                    checked = resume,
                    onCheckedChange = { applyResume(it) },
                    modifier = Modifier.semantics { contentDescription = resumeLabel },
                )
            }
            if (resume) {
                NeoField(
                    value = argsText,
                    onValueChange = { argsText = it },
                    label = stringResource(R.string.structure_args_label),
                    hint = stringResource(R.string.structure_args_hint),
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = DsSpace.s),
                )
            }
            Spacer(Modifier.height(DsSpace.l))
            NeoButton(
                label = stringResource(R.string.structure_start_named, name.ifBlank { kind }),
                onClick = {
                    val pane = host.structure.panes.firstOrNull { it.id == paneId && it.isAvailableShell(host, currentAgents) } ?: return@NeoButton
                    if (name.isBlank() || duplicateName) return@NeoButton
                    onStart(pane.id, kind, name.trim(), if (resume) argsText.lines().filter { it.isNotBlank() } else emptyList())
                },
                tone = NeoButtonTone.Primary,
                enabled = selectedPane != null && name.isNotBlank() && !duplicateName,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
