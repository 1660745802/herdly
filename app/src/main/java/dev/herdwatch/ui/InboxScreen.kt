package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.model.Agent
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
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
import dev.herdwatch.ui.design.NeoDivider
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoSegmented
import dev.herdwatch.ui.design.NeoLamp
import dev.herdwatch.ui.design.NeoPageHeader
import dev.herdwatch.ui.design.NeoSkeleton
import dev.herdwatch.ui.design.NeoSection
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import kotlinx.coroutines.delay
import dev.herdwatch.ui.theme.agentStatusColor
import dev.herdwatch.ui.theme.connStatusColor
import dev.herdwatch.ui.design.spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    ui: UiState,
    onRefresh: () -> Unit,
    onOpenAgent: (Agent, String?) -> Unit,
    onQuery: (String) -> Unit,
    onToggleSearch: (Boolean) -> Unit,
    onAddHost: () -> Unit,
    onReconnect: (String) -> Unit,
    onNewTask: (() -> Unit)? = null,
    onOpenHost: ((String) -> Unit)? = null,
    onOpenPane: ((String, String) -> Unit)? = null,
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

    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    // One list: every pane the fleet has. A pane running an agent shows that agent's name, lamp
    // and clock in the same row, so a second "agents" list was the same data twice.
    val hostScope = selectedHostId.takeIf { ui.repo.hosts.size > 1 }
    val scopedHosts = hostScope?.let { id -> ui.repo.hosts.filter { it.host.id == id } } ?: ui.repo.hosts
    val scopedAgents = scopedHosts.flatMap { runtime -> agents.filter { it.hostId == runtime.host.id } }
    // Agents get their own section whenever the host reports any: "who is working" is a different
    // question from "what is running where", and a section that vanishes when everyone is idle makes
    // the page look like it lost half its content.
    // Same queue definition as the detail traversal: urgency first, then the search query. Sorting
    // here separately meant searching filtered the panes but left every agent on screen.
    val agentGroup = inboxQueue(scopedAgents, ui.query)
    val panes = scopedHosts.flatMap { runtime ->
        runtime.structure.panes.map { pane -> runtime to pane }
    }.filter { (runtime, pane) ->
        ui.query.isBlank() || listOf(
            pane.id, pane.title, pane.agentKind.orEmpty(), pane.cwd.orEmpty(),
            runtime.host.label,
            runtime.structure.workspaces.firstOrNull { it.id == pane.workspaceId }?.label.orEmpty(),
            runtime.structure.tabs.firstOrNull { it.id == pane.tabId }?.label.orEmpty(),
        ).any { it.contains(ui.query, ignoreCase = true) }
    }
    // Only environment facts live above the list; nothing here describes urgency for the user.
    val failedHosts = scopedHosts.filter { it.conn is ConnState.Failed }
    val keyboard = LocalSoftwareKeyboardController.current
    val syncingLabel = stringResource(R.string.inbox_state_syncing)
    val refreshLabel = stringResource(R.string.inbox_cd_refresh)
    val colors = ds()

    Column(Modifier.fillMaxSize()) {
        // Header describes the whole queue, not the current search/filter result; otherwise
        // typing a query would make the task summary appear to change.
        NeoPageHeader(
            title = "Herdly",
            // Connection state stays on the host strip; the header describes the fleet, never a
            // "mode" the user has to manage.
            subtitle = when {
                ui.repo.hosts.isEmpty() -> stringResource(R.string.inbox_subtitle_pocket)
                // The host section below already names the machine and its state. Repeating it in
                // the header was the same fact twice, one line apart.
                scopedHosts.size == 1 -> stringResource(R.string.inbox_subtitle_single)
                else -> listOf(
                    pluralStringResource(R.plurals.inbox_count_hosts, scopedHosts.size, scopedHosts.size),
                    stringResource(
                        R.string.inbox_connected_hosts,
                        scopedHosts.count { it.conn is ConnState.Connected },
                    ),
                ).joinToString(stringResource(R.string.inbox_list_separator))
            },
            compact = compact,
            leading = if (compact) null else ({ BrandMark() }),
            actions = {
                NeoIconButton(
                    glyph = if (ui.searching) NeoGlyph.Close else NeoGlyph.Search,
                    onClick = { keyboard?.hide(); onToggleSearch(!ui.searching) },
                    contentDescription = stringResource(
                        if (ui.searching) R.string.inbox_cd_search_collapse else R.string.inbox_cd_search,
                    ),
                )
                if (ui.repo.refreshing) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .semantics {
                                contentDescription = refreshLabel
                                stateDescription = syncingLabel
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        NeoSkeleton(Modifier.width(20.dp), height = 20.dp, corner = 10.dp)
                    }
                } else {
                    NeoIconButton(
                        glyph = NeoGlyph.Refresh,
                        onClick = onRefresh,
                        contentDescription = stringResource(R.string.inbox_cd_refresh),
                    )
                }
            },
        )

        if (ui.repo.hosts.size > 1) {
            HostStrip(
                hosts = ui.repo.hosts,
                agents = agents,
                selectedHostId = selectedHostId,
                onSelectHost = { selectedHostId = it },
            )
        }

        // 搜索框放在列表外：若作为第一个 item，列表已有滚动位置时它会插到视口上方，
        // 视觉上“点了搜索没反应”。
        AnimatedVisibility(
            visible = ui.searching,
            enter = fadeIn(tween(motionMillis(160))),
            exit = fadeOut(tween(motionMillis(120))),
        ) {
            val searchFocus = remember { FocusRequester() }
            LaunchedEffect(Unit) { searchFocus.requestFocus() }
            InboxSearchField(
                value = ui.query,
                onValueChange = onQuery,
                onClear = { onQuery("") },
                placeholder = stringResource(R.string.inbox_search_placeholder),
                label = stringResource(R.string.inbox_search_label),
                clearDescription = stringResource(R.string.inbox_cd_clear_search),
                focusRequester = searchFocus,
                modifier = Modifier.fillMaxWidth().padding(start = spacing().gutter, end = spacing().gutter, top = DsSpace.s),
                onSearch = { keyboard?.hide() },
            )
        }

        if (!ui.searching && ui.query.isNotBlank()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = spacing().gutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                NeoButton(
                    label = stringResource(R.string.inbox_search_active, ui.query),
                    onClick = { onToggleSearch(true) },
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                NeoButton(
                    label = stringResource(R.string.inbox_clear),
                    onClick = { onQuery("") },
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                )
            }
        }

        PullToRefreshBox(
            isRefreshing = ui.repo.refreshing,
            onRefresh = onRefresh,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = spacing().gutter, end = spacing().gutter, top = DsSpace.m, bottom = 100.dp),
        ) {
            if (ui.repo.hosts.isNotEmpty()) {
                val connecting = ui.repo.hosts.filter { it.conn == ConnState.Connecting }
                if (connecting.isNotEmpty() && agents.isEmpty()) {
                    item {
                        NeoWell(
                            modifier = Modifier.fillMaxWidth().padding(top = DsSpace.m),
                            corner = DsRadius.control,
                            depth = DsElev.inset,
                        ) {
                            Row(
                                Modifier.padding(horizontal = DsSpace.l, vertical = DsSpace.m),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NeoSkeleton(Modifier.width(18.dp), height = 18.dp, corner = 9.dp)
                                Spacer(Modifier.width(DsSpace.m))
                                Text(
                                    stringResource(
                                        R.string.inbox_connecting_hosts,
                                        connecting.joinToString(stringResource(R.string.inbox_list_separator)) { it.host.label },
                                    ),
                                    style = DsTypography.body,
                                    color = colors.textSoft,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
                if (failedHosts.isNotEmpty()) {
                    item { EnvironmentRow(failedHosts, onReconnect) }
                }
                if (scopedAgents.isEmpty() && (hostScope != null || ui.repo.hosts.any { it.conn is ConnState.Connected })) {
                    item {
                        NeoEmptyState(
                            icon = NeoGlyph.Tray,
                            title = stringResource(
                                if (hostScope == null) R.string.inbox_empty_none_title else R.string.inbox_empty_host_title,
                            ),
                            body = stringResource(
                                if (hostScope == null) R.string.inbox_empty_none_body else R.string.inbox_empty_host_body,
                            ),
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                                if (hostScope != null) {
                                    NeoButton(
                                        label = stringResource(R.string.inbox_show_all_hosts),
                                        onClick = { selectedHostId = null },
                                        tone = NeoButtonTone.Ghost,
                                        compact = true,
                                    )
                                }
                                if (onNewTask != null && hostScope == null) {
                                    NeoButton(
                                        label = stringResource(R.string.inbox_start_new_task),
                                        onClick = onNewTask,
                                        tone = NeoButtonTone.Primary,
                                        icon = NeoGlyph.Plus,
                                    )
                                } else {
                                    Text(
                                        stringResource(R.string.inbox_new_task_hint),
                                        style = DsTypography.body,
                                        color = colors.textSoft,
                                    )
                                }
                                if (onOpenHost != null && hostScope == null) {
                                    ui.repo.hosts.filter { it.conn is ConnState.Connected }.forEach { runtime ->
                                        NeoButton(
                                            label = stringResource(R.string.inbox_check_host_sessions, runtime.host.label),
                                            onClick = { onOpenHost(runtime.host.id) },
                                            tone = NeoButtonTone.Ghost,
                                            icon = NeoGlyph.Server,
                                            compact = true,
                                        )
                                    }
                                } else {
                                    NeoButton(
                                        label = stringResource(R.string.inbox_recheck_agents),
                                        onClick = onRefresh,
                                        tone = NeoButtonTone.Ghost,
                                        icon = NeoGlyph.Refresh,
                                        compact = true,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (ui.repo.hosts.isEmpty()) {
                item {
                    val clipboard = LocalClipboardManager.current
                    NeoEmptyState(
                        icon = NeoGlyph.Server,
                        title = stringResource(R.string.inbox_setup_title),
                        body = stringResource(R.string.inbox_setup_body),
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NeoButton(
                                    label = stringResource(R.string.inbox_add_host),
                                    onClick = onAddHost,
                                    tone = NeoButtonTone.Primary,
                                    icon = NeoGlyph.Plus,
                                    compact = true,
                                )
                                NeoButton(
                                    label = stringResource(R.string.inbox_copy_check_command),
                                    onClick = { clipboard.setText(AnnotatedString("herdr remote-api-bridge --check")) },
                                    tone = NeoButtonTone.Quiet,
                                    icon = NeoGlyph.Copy,
                                    compact = true,
                                )
                            }
                            Text(
                                stringResource(R.string.inbox_check_command_hint),
                                style = DsTypography.caption,
                                color = colors.textSoft,
                            )
                        }
                    }
                }
                return@LazyColumn
            }

            // Hierarchy, in this order: machine → tab → pane. Agents are their own group above the
            // panes, because "who needs me" is a different question from "what is running where".
            // A flat list of eleven rows told you neither, and a panel with a fixed height left half
            // the screen empty while the rest of the list hid behind an inner scroll.
            // Agents are grouped the same way the panes below them are: workspace, then tab. Five
            // rows called "π - hyper" with a bare p1/pF told you nothing about which is which; the
            // group heading carries the machine and project so the row only has to name the agent.
            if (agentGroup.isNotEmpty()) {
                val waitingTotal = agentGroup.count { isAttention(it) }
                item(key = "agents-header") {
                    NeoSection(
                        title = pluralStringResource(R.plurals.inbox_group_agents, agentGroup.size, agentGroup.size),
                        trailing = if (waitingTotal > 0) ({
                            Text(
                                pluralStringResource(R.plurals.inbox_group_waiting, waitingTotal, waitingTotal),
                                style = DsTypography.micro,
                                color = colors.warn,
                            )
                        }) else null,
                    )
                }
                val byHost = agentGroup.groupBy { it.hostId }
                byHost.forEach { (hostId, hostAgents) ->
                    val hostRuntime = ui.repo.hosts.firstOrNull { it.host.id == hostId }
                    if (hostScope == null && byHost.size > 1) {
                        item(key = "agents-host:$hostId") {
                            Text(
                                hostAgents.first().hostLabel,
                                style = DsTypography.bodyStrong,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = DsSpace.s, top = DsSpace.s, bottom = DsSpace.xxs),
                            )
                        }
                    }
                    val byWorkspace = hostAgents.groupBy { it.workspaceLabel ?: it.repo ?: "" }
                    byWorkspace.forEach { (workspaceLabel, workspaceAgents) ->
                        if (workspaceLabel.isNotBlank()) {
                            item(key = "agents-ws:$hostId:$workspaceLabel") {
                                Row(
                                    Modifier.fillMaxWidth().padding(start = DsSpace.s, top = DsSpace.s, bottom = DsSpace.xxs),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    NeoIcon(NeoGlyph.Workspace, modifier = Modifier.size(14.dp), tint = colors.textFaint)
                                    Spacer(Modifier.width(DsSpace.xs))
                                    Text(
                                        workspaceLabel,
                                        style = DsTypography.caption,
                                        color = colors.textSoft,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        item(key = "agents-card:$hostId:$workspaceLabel") {
                            NeoCard(modifier = Modifier.fillMaxWidth(), depth = DsElev.soft) {
                                Column(Modifier.fillMaxWidth()) {
                                    workspaceAgents.forEachIndexed { index, agent ->
                                        AgentRow(
                                            agent,
                                            now,
                                            hostRuntime,
                                            hostScoped = true,
                                            compact = true,
                                        ) { onOpenAgent(agent, hostScope) }
                                        if (index != workspaceAgents.lastIndex) NeoDivider(inset = DsSpace.l)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            scopedHosts.forEach { runtime ->
                val hostPanes = panes.filter { it.first.host.id == runtime.host.id }.map { it.second }
                if (hostPanes.isEmpty()) return@forEach
                val tabs = runtime.structure.tabs
                val paneCount = hostPanes.size
                item(key = "host:" + runtime.host.id) {
                    NeoSection(
                        title = runtime.host.label + "  ·  " +
                            pluralStringResource(R.plurals.inbox_group_panes, paneCount, paneCount),
                        trailing = {
                            Text(
                                stringResource(
                                    if (runtime.conn is ConnState.Connected) R.string.inbox_host_up
                                    else R.string.inbox_host_down,
                                ),
                                style = DsTypography.micro,
                                color = if (runtime.conn is ConnState.Connected) colors.ok else colors.danger,
                            )
                        },
                    )
                }
                // Group by tab so the path and the tab a pane belongs to are structural facts, not
                // three dots of text squeezed into a caption.
                // Workspace first, then its tabs: repeating "hyper" above every tab made the same
                // level look like three different machines.
                val byWorkspace = hostPanes.groupBy { it.workspaceId }
                byWorkspace.forEach { (workspaceId, workspacePanes) ->
                    val workspace = runtime.structure.workspaces.firstOrNull { it.id == workspaceId }
                    if (workspace?.label != null) {
                        item(key = "ws:" + runtime.host.id + ":" + workspaceId) {
                            Text(
                                workspace.label,
                                style = DsTypography.bodyStrong,
                                color = colors.text,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(start = DsSpace.s, top = DsSpace.m, bottom = DsSpace.xxs),
                            )
                        }
                    }
                val groups = workspacePanes.groupBy { it.tabId }
                groups.forEach { (tabId, group) ->
                    val tab = tabs.firstOrNull { it.id == tabId }
                    val workspace = runtime.structure.workspaces.firstOrNull { it.id == group.first().workspaceId }
                    // The tab is a sub-level of the workspace heading above: a small glyph and a
                    // caption, indented one step so the tree reads without a rail.
                    val tabLabel = tab?.label ?: tabId.substringAfterLast(':')
                    item(key = "tab:" + runtime.host.id + ":" + tabId) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = DsSpace.m, top = DsSpace.s, bottom = DsSpace.xxs),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            NeoIcon(NeoGlyph.Tab, modifier = Modifier.size(13.dp), tint = colors.textFaint)
                            Spacer(Modifier.width(DsSpace.xs))
                            Text(
                                tabLabel,
                                style = DsTypography.caption,
                                color = colors.textSoft,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    item(key = "panes:" + runtime.host.id + ":" + tabId) {
                        NeoCard(modifier = Modifier.fillMaxWidth(), depth = DsElev.soft) {
                            Column(Modifier.fillMaxWidth()) {
                                group.forEachIndexed { index, pane ->
                                    PaneRow(
                                        pane = pane,
                                        runtime = runtime,
                                        agent = scopedAgents.firstOrNull { it.hostId == runtime.host.id && it.paneId == pane.id },
                                        hostScoped = true,
                                    ) {
                                        if (onOpenPane != null) onOpenPane(runtime.host.id, pane.id)
                                        else runtimeHostAgent(scopedAgents, pane, runtime)
                                            ?.let { onOpenAgent(it, hostScope) }
                                    }
                                    if (index != group.lastIndex) NeoDivider(inset = DsSpace.l)
                                }
                            }
                        }
                    }
                }
                }
            }

            if (panes.isEmpty() && agentGroup.isEmpty() && (scopedAgents.isNotEmpty() || ui.query.isNotBlank())) {
                item {
                    NeoEmptyState(
                        icon = NeoGlyph.Search,
                        title = stringResource(R.string.inbox_no_match_title),
                        body = stringResource(
                            when {
                                ui.query.isNotBlank() -> R.string.inbox_no_match_search_body
                                hostScope != null -> R.string.inbox_no_match_host_body
                                else -> R.string.inbox_no_match_none_body
                            },
                        ),
                    ) {
                        if (ui.query.isNotBlank()) {
                            NeoButton(
                                label = stringResource(R.string.inbox_clear_search),
                                onClick = { onQuery("") },
                                tone = NeoButtonTone.Ghost,
                                compact = true,
                            )
                        }
                    }
                }
            }

            if (panes.isEmpty() && scopedHosts.isNotEmpty()) {
                item {
                    NeoEmptyState(
                        icon = NeoGlyph.Pane,
                        title = stringResource(R.string.inbox_panes_empty_title),
                        body = stringResource(
                            if (ui.query.isNotBlank()) R.string.inbox_no_match_search_body
                            else R.string.inbox_panes_empty_body,
                        ),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                            if (onOpenHost != null) {
                                NeoButton(
                                    label = stringResource(R.string.inbox_check_host_sessions, scopedHosts.first().host.label),
                                    onClick = { onOpenHost(scopedHosts.first().host.id) },
                                    tone = NeoButtonTone.Ghost,
                                    icon = NeoGlyph.Server,
                                    compact = true,
                                )
                            }
                            NeoButton(
                                label = stringResource(R.string.inbox_recheck_agents),
                                onClick = onRefresh,
                                tone = NeoButtonTone.Ghost,
                                icon = NeoGlyph.Refresh,
                                compact = true,
                            )
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
    val colors = ds()
    Canvas(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(DsRadius.key))
            .background(colors.well),
    ) {
        val u = size.width / 108f
        val ink = Brush.verticalGradient(
            listOf(colors.text, colors.textSoft),
            startY = 39f * u, endY = 75f * u,
        )
        drawRoundRect(ink, Offset(31f * u, 39f * u), Size(46f * u, 36f * u), CornerRadius(14f * u))
        drawRoundRect(colors.baseRaised, Offset(50f * u, 31f * u), Size(8f * u, 9f * u), CornerRadius(2f * u))
        drawCircle(colors.warn, radius = 4.7f * u, center = Offset(54f * u, 26f * u))
        drawRoundRect(colors.well, Offset(37f * u, 51f * u), Size(34f * u, 13f * u), CornerRadius(6.5f * u))
        drawCircle(colors.baseRaised, radius = 3.5f * u, center = Offset(44f * u, 57.5f * u))
        drawCircle(colors.accent, radius = 3.5f * u, center = Offset(61f * u, 57.5f * u))
    }
}

/** Search field: milled well, no border — the same material as the design-system text field. */
@Composable
private fun InboxSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    onClear: () -> Unit,
    placeholder: String,
    label: String,
    clearDescription: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    onSearch: () -> Unit,
) {
    val colors = ds()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
        Text(label, style = DsTypography.caption, color = colors.textSoft)
        NeoWell(Modifier.fillMaxWidth(), corner = DsRadius.control) {
            Row(
                Modifier.fillMaxWidth().padding(start = DsSpace.l, end = DsSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = DsTypography.body,
                            color = colors.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier
                            .fillMaxWidth()
                            .defaultMinSize(minHeight = 48.dp)
                            .padding(vertical = DsSpace.m)
                            .focusRequester(focusRequester)
                            .semantics { contentDescription = label },
                        singleLine = true,
                        textStyle = DsTypography.body.copy(color = colors.text),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    )
                }
                if (value.isNotEmpty()) {
                    NeoIconButton(
                        glyph = NeoGlyph.Close,
                        onClick = onClear,
                        contentDescription = clearDescription,
                    )
                }
            }
        }
    }
}

@Composable
private fun HostStrip(
    hosts: List<HostRuntime>,
    agents: List<Agent>,
    selectedHostId: String?,
    onSelectHost: (String?) -> Unit,
) {
    val colors = ds()
    LazyRow(
        Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = spacing().gutter),
        horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
    ) {
        item(key = "all-hosts") {
            HostFilterChip(
                label = stringResource(R.string.inbox_host_all),
                selected = selectedHostId == null,
                count = agents.size,
                status = null,
                dot = colors.textFaint,
            ) { onSelectHost(null) }
        }
        items(hosts, key = { it.host.id }) { host ->
            val count = agents.count { it.hostId == host.host.id }
            val waiting = agents.count { it.hostId == host.host.id && it.status == AgentStatus.WAITING }
            val status = when {
                waiting > 0 -> pluralStringResource(R.plurals.inbox_host_waiting, waiting, waiting)
                host.conn is ConnState.Connected -> stringResource(R.string.inbox_host_online)
                host.conn is ConnState.Connecting -> stringResource(R.string.inbox_host_connecting)
                host.conn is ConnState.Failed -> stringResource(R.string.inbox_host_offline)
                else -> stringResource(R.string.inbox_host_disconnected)
            }
            val dot = if (waiting > 0) agentStatusColor(AgentStatus.WAITING) else connStatusColor(host.conn)
            HostFilterChip(
                label = host.host.label,
                selected = selectedHostId == host.host.id,
                count = count,
                status = status,
                dot = dot,
            ) { onSelectHost(if (selectedHostId == host.host.id) null else host.host.id) }
        }
    }
}

@Composable
private fun HostFilterChip(
    label: String,
    selected: Boolean,
    count: Int,
    status: String?,
    dot: Color,
    onClick: () -> Unit,
) {
    val text = listOfNotNull(label, count.toString(), status).joinToString(" · ")
    NeoChip(
        label = text,
        selected = selected,
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp),
        dot = dot,
    )
}

@Composable
private fun EnvironmentRow(failedHosts: List<HostRuntime>, onReconnect: (String) -> Unit) {
    val colors = ds()
    Row(
        Modifier.fillMaxWidth().padding(top = DsSpace.m, bottom = DsSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoLamp(color = colors.warn, size = 7.dp)
        Spacer(Modifier.width(DsSpace.m))
        Text(
            stringResource(
                R.string.inbox_env_offline,
                failedHosts.joinToString(stringResource(R.string.inbox_list_separator)) { it.host.label },
            ),
            style = DsTypography.caption,
            color = colors.textSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        NeoButton(
            label = stringResource(R.string.inbox_env_retry),
            onClick = { failedHosts.forEach { onReconnect(it.host.id) } },
            tone = NeoButtonTone.Ghost,
            icon = NeoGlyph.Refresh,
            compact = true,
        )
    }
}

/** One row per pane: the whole fleet, agent or not, in the same shape as an agent row. */
@Composable
private fun PaneRow(
    pane: HerdPane,
    runtime: HostRuntime,
    agent: Agent?,
    hostScoped: Boolean,
    onClick: () -> Unit,
) {
    val colors = ds()
    // A shell pane has no agent status to report; calling it "unknown" reads as a fault.
    val isShell = agent == null && pane.agentKind.isNullOrBlank()
    val statusLabel = if (isShell) {
        stringResource(R.string.inbox_pane_shell)
    } else {
        stringResource((agent?.status ?: pane.status).labelRes)
    }
    val lamp = when {
        agent != null -> agentStatusColor(agent.status)
        isShell -> connStatusColor(runtime.conn)
        else -> agentStatusColor(pane.status)
    }
    // Terminal titles for a shell are the whole "user@host" string; the directory is the useful name.
    val name = if (isShell) {
        pane.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: pane.title
    } else {
        pane.title
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = statusLabel }
            .padding(horizontal = DsSpace.l, vertical = spacing().row),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoLamp(color = lamp, pulse = agent?.status == AgentStatus.WORKING)
            Spacer(Modifier.width(DsSpace.m))
            Text(
                name.ifBlank { pane.id },
                style = DsTypography.bodyStrong,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                pane.id.substringAfterLast(':'),
                style = DsTypography.mono,
                color = colors.textFaint,
                maxLines = 1,
                modifier = Modifier.padding(start = DsSpace.s),
            )
            Spacer(Modifier.width(DsSpace.s))
            Text(statusLabel, style = DsTypography.caption, color = colors.textSoft, maxLines = 1)
            // The destination is part of the row: an agent pane opens a conversation, a shell pane
            // opens a terminal. Saying so beats making the user tap to find out.
            NeoIcon(
                if (agent != null || !pane.agentKind.isNullOrBlank()) NeoGlyph.Agent else NeoGlyph.Terminal,
                modifier = Modifier.size(15.dp).padding(start = DsSpace.xs),
                tint = colors.textFaint,
            )
        }
        // The workspace and tab are the group heading above this card, so the row itself carries
        // only what is unique inside that tab: the pane id (next to the name) and the directory.
        val cwd = agent?.cwd?.takeIf { it.isNotBlank() } ?: pane.cwd?.takeIf { it.isNotBlank() }
        if (cwd != null) {
            Text(
                cwd.replace(Regex("^/home/[^/]+"), "~"),
                style = DsTypography.micro,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = DsSpace.l + DsSpace.s, top = DsSpace.xxs),
            )
        } else if (!hostScoped) {
            Text(
                runtime.host.label,
                style = DsTypography.micro,
                color = colors.textFaint,
                maxLines = 1,
                modifier = Modifier.padding(start = DsSpace.l + DsSpace.s, top = DsSpace.xxs),
            )
        }
    }
}

private fun runtimeHostAgent(agents: List<Agent>, pane: HerdPane, runtime: HostRuntime): Agent? =
    agents.firstOrNull { it.paneId == pane.id && it.hostId == runtime.host.id }

@Composable
private fun AgentRow(
    agent: Agent,
    now: Long,
    runtime: HostRuntime?,
    hostScoped: Boolean,
    compact: Boolean = false,
    onClick: () -> Unit,
) {
    val colors = ds()
    val statusDescription = stringResource(agent.status.labelRes)
    val lamp = agentStatusColor(agent.status)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { stateDescription = statusDescription }
            .padding(horizontal = DsSpace.l, vertical = if (compact) DsSpace.s + DsSpace.xxs else spacing().row),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            NeoLamp(color = lamp, pulse = agent.status == AgentStatus.WORKING)
            Spacer(Modifier.width(DsSpace.m))
            Text(
                agent.name,
                style = DsTypography.bodyStrong,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (compact) {
                // Tab and pane, not the workspace: the group heading above already names the
                // project, and "main · p1" is what tells two same-named agents apart.
                Text(
                    listOfNotNull(
                        agent.tabLabel?.takeIf { it.isNotBlank() },
                        agent.paneId.substringAfterLast(':'),
                    ).joinToString(" · "),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    maxLines = 1,
                    modifier = Modifier.padding(start = DsSpace.s),
                )
            }
            Spacer(Modifier.width(DsSpace.s))
            Text(
                if (agent.status == AgentStatus.WAITING) formatElapsed(agent.sinceMillis, now) else statusDescription,
                style = DsTypography.caption,
                color = colors.textSoft,
                maxLines = 1,
            )
        }
        // Compact rows (the agent section) put the location on the name's own line: five agents
        // then fit above the fold instead of pushing the pane list off the screen.
        if (!compact) {
            val location = listOfNotNull(
                agent.paneId.substringAfterLast(':').takeIf { it.isNotBlank() },
                agent.workspaceLabel ?: agent.repo,
                if (hostScoped) null else agent.hostLabel,
            ).distinct().joinToString("  ·  ")
            Text(
                location,
                style = DsTypography.caption,
                color = colors.textSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = DsSpace.l + DsSpace.s, top = DsSpace.xxs),
            )
        }
        val lastSync = runtime?.lastSyncAt
        val stale = runtime?.conn !is ConnState.Connected || lastSync == null || now - lastSync > 15_000
        if (stale) {
            Text(
                if (runtime?.conn is ConnState.Connected)
                    stringResource(R.string.inbox_stale_snapshot, formatAgo(lastSync, now))
                else stringResource(R.string.inbox_offline_snapshot, formatAgo(lastSync, now)),
                style = DsTypography.micro,
                color = colors.textFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = DsSpace.l + DsSpace.s, top = DsSpace.xxs),
            )
        }
    }
}
