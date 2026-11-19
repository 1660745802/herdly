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
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import kotlinx.coroutines.delay
import dev.herdwatch.ui.theme.agentStatusColor
import dev.herdwatch.ui.theme.connStatusColor
import dev.herdwatch.ui.design.spacing

/**
 * Height cap of the queue panel. Beyond this the panel scrolls on its own, so a long queue does
 * not push the rest of the page off the screen and only the visible rows are composed.
 */
private val QueuePanelMaxHeight = 420.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(
    ui: UiState,
    onRefresh: () -> Unit,
    onOpenAgent: (Agent, String?) -> Unit,
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

    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    val hostScope = selectedHostId.takeIf { ui.repo.hosts.size > 1 }
    val scopedAgents = hostScope?.let { hostId -> agents.filter { it.hostId == hostId } } ?: agents
    val filtered = inboxQueue(scopedAgents, ui.filter, ui.query)
    val keyboard = LocalSoftwareKeyboardController.current
    val syncingLabel = stringResource(R.string.inbox_state_syncing)
    val refreshLabel = stringResource(R.string.inbox_cd_refresh)
    val colors = ds()

    Column(Modifier.fillMaxSize()) {
        // Header describes the whole queue, not the current search/filter result; otherwise
        // typing a query would make the task summary appear to change.
        val attentionForHeader = scopedAgents.count { isAttention(it) }
        NeoPageHeader(
            title = "Herdly",
            // Connection state stays on the host strip and duty summary; repeating it here
            // made the home header look like a status dashboard instead of a task list.
            subtitle = when {
                ui.repo.hosts.isEmpty() -> stringResource(R.string.inbox_subtitle_pocket)
                scopedAgents.isEmpty() -> stringResource(R.string.inbox_empty_none_title)
                else -> stringResource(
                    R.string.inbox_header_summary,
                    pluralStringResource(
                        R.plurals.inbox_attention_count, attentionForHeader, attentionForHeader,
                    ),
                    pluralStringResource(
                        R.plurals.inbox_count_agents, scopedAgents.size, scopedAgents.size,
                    ),
                )
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
                item {
                    DutySummary(
                        agents = scopedAgents,
                        hosts = ui.repo.hosts,
                        now = now,
                        onOpen = { agent -> onOpenAgent(agent, hostScope) },
                        onReconnect = onReconnect,
                        selectedHostId = hostScope,
                    )
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

            item {
                // The count drops to its own line instead of squeezing the filter track: at 1.3x
                // font "Needs attention" would ellipsize inside a half-width segment.
                Column(Modifier.fillMaxWidth().padding(top = DsSpace.s, bottom = DsSpace.xs)) {
                    NeoSegmented(
                        // 「全部」在前，「需要处理」在后：先给全量，再给聚焦视图。
                        options = listOf(
                            stringResource(R.string.inbox_filter_all),
                            stringResource(R.string.inbox_filter_attention),
                        ),
                        selectedIndex = if (ui.filter == InboxFilter.ATTENTION) 1 else 0,
                        onSelect = { index -> onFilter(if (index == 0) InboxFilter.ALL else InboxFilter.ATTENTION) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (ui.query.isNotBlank())
                            pluralStringResource(R.plurals.inbox_count_items_matched, filtered.size, filtered.size)
                        else pluralStringResource(R.plurals.inbox_count_items, filtered.size, filtered.size),
                        style = DsTypography.caption,
                        color = colors.textSoft,
                        maxLines = 1,
                        modifier = Modifier.padding(start = DsSpace.xs, top = DsSpace.xxs),
                    )
                }
            }

            // One panel holding the queue. Extruding every row made a five-item list look like
            // five separate objects glued to the screen; rows are now lines on a single surface.
            //
            // The rows are composed lazily inside the panel: a busy fleet used to build one row per
            // agent on every frame, whether or not the row was on screen. The inner list is capped
            // in height so it never asks for an infinite box, and hands scrolling back to the page
            // once the visible part of the queue is reached.
            item(key = "queue") {
                NeoCard(modifier = Modifier.fillMaxWidth().padding(top = DsSpace.s), depth = DsElev.soft) {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = QueuePanelMaxHeight),
                    ) {
                        items(
                            count = filtered.size,
                            key = { index -> filtered[index].key },
                        ) { index ->
                            val agent = filtered[index]
                            AgentRow(
                                agent,
                                now,
                                ui.repo.hosts.firstOrNull { it.host.id == agent.hostId },
                                hostScoped = hostScope != null,
                            ) { onOpenAgent(agent, hostScope) }
                            if (index != filtered.lastIndex) NeoDivider(inset = DsSpace.l)
                        }
                    }
                }
            }

            if (filtered.isEmpty() && scopedAgents.isNotEmpty()) {
                item {
                    NeoEmptyState(
                        icon = NeoGlyph.Search,
                        title = stringResource(R.string.inbox_no_match_title),
                        body = stringResource(
                            when {
                                ui.query.isNotBlank() -> R.string.inbox_no_match_search_body
                                ui.filter == InboxFilter.ATTENTION -> R.string.inbox_no_match_attention_body
                                hostScope != null -> R.string.inbox_no_match_host_body
                                else -> R.string.inbox_no_match_none_body
                            },
                        ),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                            if (ui.filter == InboxFilter.ATTENTION) {
                                NeoButton(
                                    label = stringResource(R.string.inbox_show_all_agents),
                                    onClick = { onFilter(InboxFilter.ALL) },
                                    tone = NeoButtonTone.Ghost,
                                    compact = true,
                                )
                            }
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
private fun DutySummary(
    agents: List<Agent>,
    hosts: List<HostRuntime>,
    now: Long,
    onOpen: (Agent) -> Unit,
    onReconnect: (String) -> Unit,
    selectedHostId: String?,
) {
    val colors = ds()
    val attention = agents.filter { isAttention(it) }
    val waiting = attention.filter { it.status == AgentStatus.WAITING }
    val unknownCount = agents.count { it.status == AgentStatus.UNKNOWN }
    val workingCount = agents.count { it.status == AgentStatus.WORKING }
    val reviewCount = agents.count { it.status == AgentStatus.REVIEW }
    val idleCount = agents.count { it.status == AgentStatus.IDLE }
    val longest = waiting.minByOrNull { it.sinceMillis }
    val failures = hosts.filter { (selectedHostId == null || it.host.id == selectedHostId) && it.conn is ConnState.Failed }
    val offline = hosts.filter { selectedHostId == null || it.host.id == selectedHostId }.any { it.conn !is ConnState.Connected }
    var showFailures by remember { mutableStateOf(false) }
    val failuresStateLabel = stringResource(
        if (showFailures) R.string.inbox_state_expanded else R.string.inbox_state_collapsed,
    )
    val failuresToggleLabel = pluralStringResource(
        if (showFailures) R.plurals.inbox_failures_hide else R.plurals.inbox_failures_show,
        failures.size,
        failures.size,
    )
    val failuresSummary = pluralStringResource(R.plurals.inbox_failures_short, failures.size, failures.size)
    NeoCard(
        modifier = Modifier.fillMaxWidth().padding(top = DsSpace.xs, bottom = DsSpace.xs),
        depth = DsElev.soft,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = DsSpace.l, vertical = DsSpace.m),
            verticalArrangement = Arrangement.spacedBy(DsSpace.s),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (attention.isNotEmpty()) {
                    NeoLamp(color = colors.danger, size = 7.dp)
                    Spacer(Modifier.width(DsSpace.s))
                    // Count and label live in one plural so locales can place the space themselves
                    // ("2 need attention" vs "2需要处理"); concatenating them leaked "2Needs attention".
                    Text(
                        pluralStringResource(
                            R.plurals.inbox_attention_count, attention.size, attention.size,
                        ),
                        style = DsTypography.bodyStrong,
                        color = colors.text,
                        modifier = Modifier.weight(1f).semantics { heading() },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    longest?.let { oldest ->
                        NeoButton(
                            label = stringResource(R.string.inbox_view_oldest, oldest.name),
                            onClick = { onOpen(oldest) },
                            tone = NeoButtonTone.Ghost,
                            compact = true,
                        )
                    }
                    // The wait duration keeps its own line: inline it used to ellipsize the whole row.
                } else {
                    NeoLamp(color = if (failures.isNotEmpty()) colors.warn else colors.ok, size = 7.dp)
                    Spacer(Modifier.width(DsSpace.s))
                    Text(
                        stringResource(if (failures.isNotEmpty()) R.string.inbox_some_hosts_offline else R.string.inbox_nothing_pending),
                        style = DsTypography.bodyStrong,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val other = listOfNotNull(
                        unknownCount.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.inbox_count_unverified, it, it)
                        },
                        reviewCount.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.inbox_count_review, it, it)
                        },
                        workingCount.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.inbox_count_working, it, it)
                        },
                        idleCount.takeIf { it > 0 }?.let {
                            pluralStringResource(R.plurals.inbox_count_idle, it, it)
                        },
                    ).joinToString(" · ")
                    if (other.isNotBlank()) {
                        Text(
                            other,
                            style = DsTypography.caption,
                            color = colors.textSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (longest != null) {
                // Second line of the same block, flush left with the count above it. Inline it used
                // to ellipsize the whole row, and a leading "·" here read as a stray bullet.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (offline) {
                            stringResource(
                                R.string.inbox_longest_waiting_snapshot,
                                formatElapsed(longest!!.sinceMillis, now),
                            )
                        } else {
                            stringResource(R.string.inbox_longest_waiting, formatElapsed(longest!!.sinceMillis, now))
                        },
                        style = DsTypography.caption,
                        color = colors.textSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unknownCount > 0) {
                        Text(
                            "  ·  " + pluralStringResource(R.plurals.inbox_count_unverified, unknownCount, unknownCount),
                            style = DsTypography.caption,
                            color = colors.textSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (failures.isNotEmpty()) {
                NeoDivider()
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    NeoLamp(color = colors.danger, size = 5.dp)
                    Text(
                        failuresSummary,
                        style = DsTypography.caption,
                        color = colors.text,
                        modifier = Modifier.weight(1f).padding(start = DsSpace.s).clearAndSetSemantics { },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    NeoIconButton(
                        glyph = if (showFailures) NeoGlyph.ChevronDown else NeoGlyph.ChevronRight,
                        onClick = { showFailures = !showFailures },
                        contentDescription = failuresToggleLabel,
                        modifier = Modifier.semantics { stateDescription = failuresStateLabel },
                    )
                }
                ExpandableSection(expanded = showFailures) {
                    Column(
                        verticalArrangement = Arrangement.spacedBy(DsSpace.m),
                        modifier = Modifier.padding(top = DsSpace.s),
                    ) {
                        failures.forEach { runtime ->
                            Column(verticalArrangement = Arrangement.spacedBy(DsSpace.xxs)) {
                                Text(
                                    stringResource(
                                        R.string.inbox_host_error_line,
                                        runtime.host.label,
                                        (runtime.conn as ConnState.Failed).message,
                                    ),
                                    style = DsTypography.body,
                                    color = colors.text,
                                )
                                Text(
                                    stringResource(R.string.inbox_last_synced, formatAgo(runtime.lastSyncAt, now)),
                                    style = DsTypography.caption,
                                    color = colors.textSoft,
                                )
                                NeoButton(
                                    label = stringResource(R.string.inbox_reconnect_host, runtime.host.label),
                                    onClick = { onReconnect(runtime.host.id) },
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
private fun AgentRow(
    agent: Agent,
    now: Long,
    runtime: HostRuntime?,
    hostScoped: Boolean,
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
            .padding(horizontal = DsSpace.l, vertical = spacing().row),
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
            Spacer(Modifier.width(DsSpace.s))
            Text(
                if (agent.status == AgentStatus.WAITING) formatElapsed(agent.sinceMillis, now) else statusDescription,
                style = DsTypography.caption,
                color = colors.textSoft,
                maxLines = 1,
            )
        }
        // Pane id first: it is the shortest, most stable identifier and must survive a long
        // English repo name. The tab label is redundant with the pane id, so it is dropped here.
        val location = listOfNotNull(
            agent.paneId.takeIf { it.isNotBlank() },
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
