package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import dev.herdwatch.ui.design.NeoField
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoLamp
import dev.herdwatch.ui.design.NeoMenu
import dev.herdwatch.ui.design.NeoMenuDivider
import dev.herdwatch.ui.design.NeoMenuItem
import dev.herdwatch.ui.design.NeoPageHeader
import dev.herdwatch.ui.design.NeoSheet
import dev.herdwatch.ui.design.NeoToggle
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.statusPalette
import dev.herdwatch.ui.theme.statusColor
import dev.herdwatch.ui.design.spacing
import androidx.compose.ui.semantics.Role

/**
 * Workspace > tab > pane tree.
 *
 * One card per workspace; tabs and panes nest behind a hairline rail instead of repeating the
 * words "tab"/"pane" on every row. Workspaces default to collapsed except the focused one, so a
 * 3-workspace / 7-tab / 11-pane host fits on one phone screen. Each level has exactly one
 * overflow menu; destructive entries go through a NeoDialog that names the target and counts
 * what will end.
 *
 * [onOpenAgent] is optional: App.kt does not wire it yet, so pane rows fall back to focusing the
 * pane and the "Open agent" menu entry renders disabled with a reason. When a caller supplies it,
 * agent panes get a trailing chevron and tapping opens the agent instead.
 */
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
    onOpenAgent: ((Agent) -> Unit)? = null,
) {
    val structure = runtime.structure
    val colors = ds()
    var pendingClose by remember { mutableStateOf<CloseTarget?>(null) }
    var renaming by remember { mutableStateOf<RenameTarget?>(null) }
    val availablePaneIds = structure.panes.filter { it.isAvailableShell(runtime, currentAgents) }.map { it.id }.toSet()
    val occupancyAvailable = currentAgents != null
    fun targetPath(paneId: String? = null, tabId: String? = null, workspaceId: String? = null): String {
        val pane = structure.panes.firstOrNull { it.id == paneId }
        val tab = structure.tabs.firstOrNull { it.id == (tabId ?: pane?.tabId) }
        val workspace = structure.workspaces.firstOrNull { it.id == (workspaceId ?: tab?.workspaceId ?: pane?.workspaceId) }
        return listOfNotNull(runtime.host.label, workspace?.label, tab?.label, pane?.let { "${it.title.ifBlank { it.id }} (${it.id})" }, pane?.cwd ?: workspace?.worktreePath).joinToString("\n")
    }
    // Cards resolve the consequence sentence (counts, agent name) during composition where
    // string resources are available, then hand it up with the target id.
    val requestClose: (String, String, String) -> Unit = { type, id, detail ->
        pendingClose = CloseTarget(
            type = type,
            id = id,
            path = targetPath(
                paneId = id.takeIf { type == "pane" },
                tabId = id.takeIf { type == "tab" },
                workspaceId = id.takeIf { type == "workspace" },
            ),
            detail = detail,
        )
    }
    val requestRename: (String, String, String) -> Unit = { type, id, label ->
        renaming = RenameTarget(type, id, label)
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
                SummaryCard(runtime, availablePaneIds.size, occupancyAvailable, onCreateWorkspace, onStartAgent)
            }
            if (structure.workspaces.isEmpty()) {
                item {
                    if (runtime.conn is ConnState.Connecting || runtime.conn is ConnState.Idle) {
                        // Loading reads as a state with a next step, not a spinner in a list.
                        NeoEmptyState(
                            icon = NeoGlyph.Server,
                            title = stringResource(R.string.structure_loading_title),
                            body = stringResource(R.string.structure_loading_body),
                        )
                    } else {
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
            }
            items(structure.workspaces, key = { it.id }) { workspace ->
                WorkspaceCard(
                    workspace = workspace,
                    modifier = animateItemMotion(),
                    structure = structure,
                    hostId = runtime.host.id,
                    currentAgents = currentAgents,
                    occupancyAvailable = occupancyAvailable,
                    availablePaneIds = availablePaneIds,
                    // Focused workspace opens by default; if Herdr reports no focus at all,
                    // open the first one so the page never loads as a wall of collapsed cards.
                    defaultExpanded = workspace.focused ||
                        (structure.workspaces.none { it.focused } && workspace == structure.workspaces.first()),
                    onFocusWorkspace = { onFocusWorkspace(workspace.id) },
                    onCreateTab = { onCreateTab(workspace.id) },
                    onFocusTab = onFocusTab,
                    onFocusPane = onFocusPane,
                    onSplit = onSplit,
                    onStartAgent = onStartAgent,
                    onOpenAgent = onOpenAgent,
                    onRequestClose = requestClose,
                    onRequestRename = requestRename,
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
        NeoDialog(
            onDismissRequest = { pendingClose = null },
            title = stringResource(R.string.structure_close_title, typeName(target.type)),
            subtitle = stringResource(R.string.structure_close_message, target.path, target.detail),
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

private data class CloseTarget(val type: String, val id: String, val path: String, val detail: String)

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

/** Agent count prefers live occupancy data; falls back to the pane's reported kind. */
private fun countAgents(panes: List<HerdPane>, hostId: String, currentAgents: List<Agent>?): Int =
    if (currentAgents != null) {
        panes.count { pane -> currentAgents.any { it.hostId == hostId && it.paneId == pane.id } }
    } else {
        panes.count { it.agentKind != null }
    }

/** Caption-sized mono: ids and paths must never drop below the caption token. */
private val captionMono
    @Composable get() = DsTypography.mono.copy(
        fontSize = DsTypography.caption.fontSize,
        lineHeight = DsTypography.caption.lineHeight,
    )

/**
 * The hairline rail that carries the tree: one 1dp line in the divider ink, indented per level.
 * Must live inside a Row with IntrinsicSize.Min height so fillMaxHeight measures against content.
 */
@Composable
private fun TreeRail() {
    val colors = ds()
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .padding(vertical = DsSpace.xs)
            .background(colors.shadowDark.copy(alpha = if (colors.isDark) 0.35f else 0.18f)),
    )
}

@Composable
private fun SummaryCard(
    runtime: HostRuntime,
    availableShellCount: Int,
    occupancyAvailable: Boolean,
    onCreateWorkspace: () -> Unit,
    onStartAgent: (String?) -> Unit,
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
                    // Body size, not headline: at 20sp these counts wrapped and collided with the
                    // actions in English and at 1.3x font scale.
                    style = DsTypography.body,
                    color = colors.text,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(DsSpace.m))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            if (runtime.conn !is ConnState.Connected) {
                Text(
                    stringResource(R.string.structure_connect_first),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    modifier = Modifier.padding(top = DsSpace.s),
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
    hostId: String,
    currentAgents: List<Agent>?,
    occupancyAvailable: Boolean,
    availablePaneIds: Set<String>,
    defaultExpanded: Boolean,
    modifier: Modifier = Modifier,
    onFocusWorkspace: () -> Unit,
    onCreateTab: () -> Unit,
    onFocusTab: (String) -> Unit,
    onFocusPane: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    onStartAgent: (String?) -> Unit,
    onOpenAgent: ((Agent) -> Unit)?,
    onRequestClose: (String, String, String) -> Unit,
    onRequestRename: (String, String, String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(workspace.id) { mutableStateOf(defaultExpanded) }
    val expandedState = stringResource(R.string.structure_state_expanded)
    val collapsedState = stringResource(R.string.structure_state_collapsed)
    val tabs = structure.tabsOf(workspace.id)
    val panes = structure.panes.filter { it.workspaceId == workspace.id }
    val agentCount = countAgents(panes, hostId, currentAgents)
    val colors = ds()
    val closeDetail = stringResource(
        R.string.structure_close_workspace_detail,
        pluralStringResource(R.plurals.structure_tab_count, tabs.size, tabs.size),
        pluralStringResource(R.plurals.structure_pane_count, panes.size, panes.size),
        pluralStringResource(R.plurals.structure_agent_count, agentCount, agentCount),
    )
    NeoCard(modifier = modifier.fillMaxWidth(), depth = DsElev.soft) {
        Column(Modifier.padding(DsSpace.l)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClickLabel = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                    ) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) expandedState else collapsedState },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RotatingChevron(expanded = expanded, contentDescription = null)
                Spacer(Modifier.width(DsSpace.s))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            workspace.label,
                            style = DsTypography.headline,
                            color = colors.text,
                            modifier = Modifier.weight(1f, fill = false),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        AnimatedVisibility(
                            visible = workspace.focused,
                            enter = fadeIn(tween(motionMillis(160))) + expandHorizontally(tween(motionMillis(200))),
                            exit = fadeOut(tween(motionMillis(120))) + shrinkHorizontally(tween(motionMillis(160))),
                        ) {
                            Row {
                                Spacer(Modifier.width(DsSpace.s))
                                FocusPill(stringResource(R.string.structure_pill_current))
                            }
                        }
                    }
                    Text(
                        stringResource(
                            R.string.structure_summary_line,
                            stringResource(R.string.structure_ws_number, workspace.number),
                            pluralStringResource(R.plurals.structure_tab_count, tabs.size, tabs.size),
                            pluralStringResource(R.plurals.structure_pane_count, panes.size, panes.size),
                        ),
                        style = DsTypography.caption,
                        color = colors.textSoft,
                        maxLines = 2,
                    )
                }
                Box {
                    NeoIconButton(
                        glyph = NeoGlyph.More,
                        onClick = { menu = true },
                        contentDescription = stringResource(R.string.structure_workspace_actions_desc, workspace.label),
                    )
                    NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        NeoMenuItem(label = stringResource(R.string.structure_focus), onClick = { menu = false; onFocusWorkspace() })
                        NeoMenuItem(label = stringResource(R.string.structure_rename), icon = NeoGlyph.Edit, onClick = { menu = false; onRequestRename("workspace", workspace.id, workspace.label) })
                        NeoMenuItem(label = stringResource(R.string.structure_new_tab), icon = NeoGlyph.Plus, onClick = { menu = false; onCreateTab() })
                        NeoMenuDivider()
                        NeoMenuItem(label = stringResource(R.string.structure_close_workspace), icon = NeoGlyph.Close, danger = true, onClick = { menu = false; onRequestClose("workspace", workspace.id, closeDetail) })
                    }
                }
            }
            ExpandableSection(expanded) {
                if (workspace.linkedWorktree || workspace.repoName != null) {
                    Row(
                        Modifier.padding(top = DsSpace.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (workspace.linkedWorktree) {
                            FocusPill(stringResource(R.string.structure_pill_worktree), active = false)
                            Spacer(Modifier.width(DsSpace.s))
                        }
                        workspace.repoName?.let { repo ->
                            Text(
                                buildString {
                                    append(repo)
                                    workspace.worktreePath?.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                                },
                                style = captionMono,
                                color = colors.textFaint,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                tabs.forEachIndexed { index, tab ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = DsSpace.s)
                            .height(IntrinsicSize.Min),
                    ) {
                        TreeRail()
                        Spacer(Modifier.width(DsSpace.m))
                        Column(Modifier.weight(1f)) {
                            TabBlock(
                                tab = tab,
                                panes = structure.panesOf(tab.id),
                                hostId = hostId,
                                currentAgents = currentAgents,
                                occupancyAvailable = occupancyAvailable,
                                availablePaneIds = availablePaneIds,
                                defaultExpanded = tab.focused || (tabs.none { it.focused } && index == 0),
                                onFocusTab = { onFocusTab(tab.id) },
                                onFocusPane = onFocusPane,
                                onSplit = onSplit,
                                onStartAgent = onStartAgent,
                                onOpenAgent = onOpenAgent,
                                onRequestClose = onRequestClose,
                                onRequestRename = onRequestRename,
                            )
                        }
                    }
                }
                NeoButton(
                    label = if (tabs.isEmpty()) stringResource(R.string.structure_no_tabs) else stringResource(R.string.structure_new_tab_plus),
                    onClick = onCreateTab,
                    tone = NeoButtonTone.Ghost,
                    icon = NeoGlyph.Plus,
                    compact = true,
                    modifier = Modifier.padding(top = DsSpace.xs),
                )
            }
        }
    }
}

@Composable
private fun TabBlock(
    tab: HerdTab,
    panes: List<HerdPane>,
    hostId: String,
    currentAgents: List<Agent>?,
    occupancyAvailable: Boolean,
    availablePaneIds: Set<String>,
    defaultExpanded: Boolean,
    onFocusTab: () -> Unit,
    onFocusPane: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    onStartAgent: (String?) -> Unit,
    onOpenAgent: ((Agent) -> Unit)?,
    onRequestClose: (String, String, String) -> Unit,
    onRequestRename: (String, String, String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var expanded by rememberSaveable(tab.id) { mutableStateOf(defaultExpanded) }
    val expandedState = stringResource(R.string.structure_state_expanded)
    val collapsedState = stringResource(R.string.structure_state_collapsed)
    val colors = ds()
    val agentCount = countAgents(panes, hostId, currentAgents)
    val tabShell = panes.firstOrNull { it.id in availablePaneIds }
    val closeDetail = stringResource(
        R.string.structure_close_tab_detail,
        pluralStringResource(R.plurals.structure_pane_count, panes.size, panes.size),
        pluralStringResource(R.plurals.structure_agent_count, agentCount, agentCount),
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(
                    role = Role.Button,
                    onClickLabel = if (expanded) stringResource(R.string.common_collapse) else stringResource(R.string.common_expand),
                ) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) expandedState else collapsedState },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NeoLamp(
                color = statusColor(tab.status, statusPalette()),
                pulse = tab.status == AgentStatus.WORKING,
                size = 7.dp,
            )
            Spacer(Modifier.width(DsSpace.s))
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(
                        R.string.structure_tab_title,
                        stringResource(R.string.structure_tab_number, tab.number),
                        tab.label,
                    ),
                    style = DsTypography.headline,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(
                        R.string.structure_summary_line,
                        pluralStringResource(R.plurals.structure_agent_count, agentCount, agentCount),
                        pluralStringResource(R.plurals.structure_pane_count, panes.size, panes.size),
                        stringResource(tab.status.labelRes),
                    ),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    maxLines = 2,
                )
            }
            RotatingChevron(expanded = expanded, contentDescription = null)
            Box {
                NeoIconButton(
                    glyph = NeoGlyph.More,
                    onClick = { menu = true },
                    contentDescription = stringResource(R.string.structure_tab_actions_desc, tab.label),
                )
                NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    NeoMenuItem(label = stringResource(R.string.structure_focus_tab), onClick = { menu = false; onFocusTab() })
                    if (tabShell != null) {
                        NeoMenuItem(label = stringResource(R.string.structure_start_agent), icon = NeoGlyph.Play, onClick = { menu = false; onStartAgent(tabShell.id) })
                    } else {
                        // Disabled with the reason in the label: a greyed "Start agent" alone
                        // would read as a bug instead of a safety gate.
                        NeoMenuItem(
                            label = if (!occupancyAvailable) stringResource(R.string.structure_start_agent_unverified)
                            else stringResource(R.string.structure_start_agent_no_shell),
                            icon = NeoGlyph.Play,
                            enabled = false,
                            onClick = {},
                        )
                    }
                    NeoMenuItem(label = stringResource(R.string.structure_rename), icon = NeoGlyph.Edit, onClick = { menu = false; onRequestRename("tab", tab.id, tab.label) })
                    NeoMenuDivider()
                    NeoMenuItem(label = stringResource(R.string.structure_close_tab), icon = NeoGlyph.Close, danger = true, onClick = { menu = false; onRequestClose("tab", tab.id, closeDetail) })
                }
            }
        }
        ExpandableSection(expanded) {
            if (panes.isEmpty()) {
                Text(
                    stringResource(R.string.structure_no_panes),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    modifier = Modifier.padding(start = DsSpace.l, top = DsSpace.xs, bottom = DsSpace.xs),
                )
            } else {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = DsSpace.s)
                        .height(IntrinsicSize.Min),
                ) {
                    TreeRail()
                    Spacer(Modifier.width(DsSpace.m))
                    Column(Modifier.weight(1f)) {
                        panes.forEach { pane ->
                            PaneRow(
                                pane = pane,
                                agent = currentAgents?.firstOrNull { it.hostId == hostId && it.paneId == pane.id },
                                availableShell = pane.id in availablePaneIds,
                                occupancyAvailable = occupancyAvailable,
                                onFocusPane = onFocusPane,
                                onSplit = onSplit,
                                onStartAgent = onStartAgent,
                                onOpenAgent = onOpenAgent,
                                onRequestClose = onRequestClose,
                                onRequestRename = onRequestRename,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PaneRow(
    pane: HerdPane,
    agent: Agent?,
    availableShell: Boolean,
    occupancyAvailable: Boolean,
    onFocusPane: (String) -> Unit,
    onSplit: (String, String) -> Unit,
    onStartAgent: (String?) -> Unit,
    onOpenAgent: ((Agent) -> Unit)?,
    onRequestClose: (String, String, String) -> Unit,
    onRequestRename: (String, String, String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    // A shell's terminal title is the whole "user@host" string, which truncated to the same
    // useless prefix on every row; the directory is the name a person recognises.
    val shellDirectory = pane.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val paneLabel = if (agent == null && pane.agentKind.isNullOrBlank()) {
        shellDirectory ?: pane.title.ifBlank { pane.id }
    } else {
        pane.title.ifBlank { pane.id }
    }
    val displayName = agent?.name ?: paneLabel
    val kindLabel = agent?.kind ?: pane.agentKind ?: if (availableShell) {
        stringResource(R.string.structure_pane_idle_shell)
    } else {
        stringResource(R.string.structure_pane_unverified)
    }
    val restoreFailedLabel = stringResource(R.string.structure_restore_failed)
    val paneStatusLabel = stringResource(pane.status.labelRes)
    val cwdBase = pane.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
    val colors = ds()
    // Tapping opens the agent only when the caller wired onOpenAgent; otherwise it focuses the
    // pane, and the chevron stays hidden so the row never promises a navigation it cannot do.
    val opensAgent = agent != null && onOpenAgent != null
    val closeDetail = if (agent != null) {
        stringResource(R.string.structure_close_pane_agent_body, agent.name)
    } else {
        stringResource(R.string.structure_close_pane_body)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                role = Role.Button,
                onClickLabel = if (opensAgent) stringResource(R.string.structure_open_agent_label, displayName)
                else stringResource(R.string.structure_focus_pane_label, paneLabel),
            ) {
                if (opensAgent) onOpenAgent!!(agent!!) else onFocusPane(pane.id)
            }
            .semantics { stateDescription = paneStatusLabel }
            .padding(vertical = DsSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoLamp(
            color = statusColor(pane.status, statusPalette()),
            pulse = pane.status == AgentStatus.WORKING,
            size = 7.dp,
        )
        Spacer(Modifier.width(DsSpace.s))
        Column(Modifier.weight(1f)) {
            Text(
                // The row leads with what differs from its siblings: the agent (or shell),
                // never the repeated word "pane".
                stringResource(R.string.structure_pane_meta, displayName, kindLabel),
                style = DsTypography.bodyStrong,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    pane.id,
                    paneStatusLabel,
                    // The directory is already the row's name when it is a shell, so repeating it
                    // here only added noise to every second line.
                    cwdBase.takeIf { it != paneLabel },
                    pane.restoreError?.let { restoreFailedLabel },
                ).joinToString(" · "),
                style = captionMono,
                color = if (pane.restoreError != null) colors.danger else colors.textSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (opensAgent) {
            NeoIcon(
                NeoGlyph.ChevronRight,
                modifier = Modifier.size(18.dp),
                tint = colors.textSoft,
                contentDescription = null,
            )
        }
        Box {
            NeoIconButton(
                glyph = NeoGlyph.More,
                onClick = { menu = true },
                contentDescription = stringResource(R.string.structure_pane_actions_desc, displayName),
            )
            NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (agent != null) {
                    if (onOpenAgent != null) {
                        NeoMenuItem(label = stringResource(R.string.structure_open_agent), icon = NeoGlyph.ChevronRight, onClick = { menu = false; onOpenAgent(agent) })
                    } else {
                        NeoMenuItem(
                            label = stringResource(R.string.structure_open_agent_unavailable),
                            icon = NeoGlyph.ChevronRight,
                            enabled = false,
                            onClick = {},
                        )
                    }
                } else if (availableShell) {
                    NeoMenuItem(label = stringResource(R.string.structure_start_agent), icon = NeoGlyph.Play, onClick = { menu = false; onStartAgent(pane.id) })
                } else {
                    NeoMenuItem(
                        label = if (!occupancyAvailable) stringResource(R.string.structure_start_agent_unverified)
                        else stringResource(R.string.structure_start_agent_no_shell),
                        icon = NeoGlyph.Play,
                        enabled = false,
                        onClick = {},
                    )
                }
                NeoMenuItem(label = stringResource(R.string.structure_focus_pane), onClick = { menu = false; onFocusPane(pane.id) })
                NeoMenuItem(label = stringResource(R.string.structure_rename), icon = NeoGlyph.Edit, onClick = { menu = false; onRequestRename("pane", pane.id, pane.title) })
                NeoMenuDivider()
                NeoMenuItem(label = stringResource(R.string.structure_split_right), onClick = { menu = false; onSplit(pane.id, "right") })
                NeoMenuItem(label = stringResource(R.string.structure_split_down), onClick = { menu = false; onSplit(pane.id, "down") })
                NeoMenuDivider()
                NeoMenuItem(label = stringResource(R.string.structure_close_pane), icon = NeoGlyph.Close, danger = true, onClick = { menu = false; onRequestClose("pane", pane.id, closeDetail) })
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
        style = DsTypography.caption,
        color = tint,
        modifier = Modifier
            .background(wash, RoundedCornerShape(DsRadius.key))
            .padding(horizontal = DsSpace.s, vertical = DsSpace.xxs),
    )
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
