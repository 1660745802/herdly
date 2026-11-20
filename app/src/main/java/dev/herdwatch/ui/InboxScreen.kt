package dev.herdwatch.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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

@Composable
fun InboxScreen(
    ui: UiState,
    seen: Map<String, Long> = emptyMap(),
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
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    // One list: every pane the fleet has. A pane running an agent shows that agent's name, lamp
    // and clock in the same row, so a second "agents" list was the same data twice.
    // The saved selection can outlive the host it pointed at (deleted, or removed by a refresh).
    // Keeping it would filter the fleet down to nothing and the page would look empty while other
    // machines are fine, so an unknown id silently falls back to "all hosts".
    val hostScope = selectedHostId?.takeIf { id ->
        ui.repo.hosts.size > 1 && ui.repo.hosts.any { it.host.id == id }
    }
    val scopedHosts = hostScope?.let { id -> ui.repo.hosts.filter { it.host.id == id } } ?: ui.repo.hosts
    // A machine we cannot reach has nothing to report right now. Its cached agents and panes were
    // still listed, so the home page showed rows that could not be opened and statuses that were
    // minutes old. Offline machines keep a one-line block with a retry; their content comes back
    // when the connection does.
    val liveHosts = scopedHosts.filter { it.conn is ConnState.Connected }
    val offlineHosts = scopedHosts.filter { it.conn !is ConnState.Connected }
    val scopedAgents = liveHosts.flatMap { runtime -> agents.filter { it.hostId == runtime.host.id } }
        // A finished agent the person has already read is idle, not news. Applied once here so the
        // queue, the counts, the lamps and the search all describe the same status.
        .markSeen(seen)
    // Agents get their own section whenever the host reports any: "who is working" is a different
    // question from "what is running where", and a section that vanishes when everyone is idle makes
    // the page look like it lost half its content.
    // Same queue definition as the detail traversal: urgency first, then the search query. Sorting
    // here separately meant searching filtered the panes but left every agent on screen.
    val agentGroup = inboxQueue(scopedAgents, ui.query)
    val panes = liveHosts.flatMap { runtime ->
        runtime.structure.panes.map { pane -> runtime to pane }
    }.filter { (runtime, pane) ->
        ui.query.isBlank() || listOf(
            pane.id, pane.title, pane.agentKind.orEmpty(), pane.cwd.orEmpty(),
            runtime.host.label,
            runtime.structure.workspaces.firstOrNull { it.id == pane.workspaceId }?.label.orEmpty(),
            runtime.structure.tabs.firstOrNull { it.id == pane.tabId }?.label.orEmpty(),
        ).any { it.contains(ui.query, ignoreCase = true) }
    }
    val keyboard = LocalSoftwareKeyboardController.current
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
                    pluralStringResource(
                        R.plurals.inbox_connected_hosts,
                        scopedHosts.count { it.conn is ConnState.Connected },
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
                // The control keeps its shape while it works: replacing the icon with a grey
                // skeleton pill made the header look broken, and a second spinner appeared under
                // it from the pull gesture. One affordance, one piece of feedback.
                RefreshAction(refreshing = ui.repo.refreshing, onRefresh = onRefresh)
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

        // One list on a phone, two columns on a wide screen. Both branches emit the same items
        // through the same builders, so the queue cannot drift between the two layouts.
        val wide = configuration.screenWidthDp >= 600 ||
            configuration.screenWidthDp > configuration.screenHeightDp
        val listPadding = PaddingValues(
            start = spacing().gutter,
            end = spacing().gutter,
            top = DsSpace.m,
            bottom = 100.dp,
        )
        val connectingHosts = ui.repo.hosts.filter { it.conn == ConnState.Connecting }
        val showConnecting = connectingHosts.isNotEmpty() && agents.isEmpty()
        val noMatchVisible = panes.isEmpty() && agentGroup.isEmpty() &&
            (scopedAgents.isNotEmpty() || ui.query.isNotBlank())
        // Mutually exclusive: a search with no hit used to show "nothing matched" and "no machines"
        // at the same time, and a quiet fleet showed both the agent-empty and the pane-empty card.
        val panesEmptyVisible = !noMatchVisible && panes.isEmpty() && liveHosts.isNotEmpty()
        val agentsEmptyVisible = !noMatchVisible && ui.repo.hosts.isNotEmpty() &&
            scopedAgents.isEmpty() &&
            (hostScope != null || ui.repo.hosts.any { it.conn is ConnState.Connected })
        // On a wide screen the big "no agents" card only earns its space when there is nothing at
        // all to show; otherwise the queue column states it in one line and the panes stay visible.
        val wideAgentsEmptyCard = agentsEmptyVisible && panes.isEmpty()

        // No pull-to-refresh: the header action is the refresh, and it reports progress in place.
        // Two controls for one action meant two spinners, and the Material indicator sat on top of
        // the first machine block instead of inside the page's own material language.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (wide) {
                Column(Modifier.fillMaxSize()) {
                    if (ui.repo.hosts.isEmpty()) {
                        // Nothing to split yet: the first-run card is the whole screen.
                        LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                            item(key = "setup") { SetupCard(onAddHost) }
                        }
                    } else {
                        // Screen-wide notices stay outside the columns: they describe the fleet,
                        // not one side of the queue.
                        Column(Modifier.fillMaxWidth().padding(horizontal = spacing().gutter)) {
                            if (showConnecting) ConnectingNotice(connectingHosts)
                            if (wideAgentsEmptyCard) {
                                AgentsEmptyCard(
                                    hostScope = hostScope,
                                    hosts = ui.repo.hosts,
                                    onNewTask = onNewTask,
                                    onOpenHost = onOpenHost,
                                    onShowAllHosts = { selectedHostId = null },
                                    onRefresh = onRefresh,
                                )
                            }
                            if (noMatchVisible) NoMatchCard(ui.query, hostScope) { onQuery("") }
                            if (panesEmptyVisible) {
                                PanesEmptyCard(liveHosts, ui.query, onOpenHost, onRefresh)
                            }
                        }
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            LazyColumn(
                                Modifier.weight(1f).fillMaxHeight(),
                                contentPadding = listPadding,
                            ) {
                                if (agentGroup.isEmpty() && offlineHosts.isEmpty()) {
                                    item(key = "agents-empty-line") { QueueEmptyLine() }
                                } else {
                                    offlineBlocks(offlineHosts, onReconnect, onOpenHost)
                                    hostQueueBlocks(
                                        hosts = liveHosts,
                                        agentGroup = agentGroup,
                                        panes = panes,
                                        now = now,
                                        hostScope = hostScope,
                                        onOpenAgent = onOpenAgent,
                                        onOpenPane = onOpenPane,
                                        emitPanes = false,
                                    )
                                }
                            }
                            LazyColumn(
                                Modifier.weight(1f).fillMaxHeight(),
                                contentPadding = listPadding,
                            ) {
                                if (panes.isNotEmpty()) {
                                    hostQueueBlocks(
                                        hosts = liveHosts,
                                        agentGroup = agentGroup,
                                        panes = panes,
                                        now = now,
                                        hostScope = hostScope,
                                        onOpenAgent = onOpenAgent,
                                        onOpenPane = onOpenPane,
                                        emitAgents = false,
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
                    if (showConnecting) {
                        item(key = "connecting") { ConnectingNotice(connectingHosts) }
                    }
                    if (agentsEmptyVisible) {
                        item(key = "agents-empty") {
                            AgentsEmptyCard(
                                hostScope = hostScope,
                                hosts = ui.repo.hosts,
                                onNewTask = onNewTask,
                                onOpenHost = onOpenHost,
                                onShowAllHosts = { selectedHostId = null },
                                onRefresh = onRefresh,
                            )
                        }
                    }
                    if (ui.repo.hosts.isEmpty()) {
                        item(key = "setup") { SetupCard(onAddHost) }
                        return@LazyColumn
                    }
                    // One block per machine: its agents first, then its workspaces, then a weak
                    // hairline before the next machine. Grouping by state first and machine second
                    // made a six-device fleet read as one long list where the same machine appeared
                    // in two unrelated places.
                    hostQueueBlocks(
                        hosts = liveHosts,
                        agentGroup = agentGroup,
                        panes = panes,
                        now = now,
                        hostScope = hostScope,
                        onOpenAgent = onOpenAgent,
                        onOpenPane = onOpenPane,
                    )
                    // Offline machines come last: they are a fact about the environment, not work
                    // to triage, and each one carries its own retry instead of a fleet-wide row.
                    offlineBlocks(offlineHosts, onReconnect, onOpenHost)
                    if (noMatchVisible) {
                        item(key = "no-match") { NoMatchCard(ui.query, hostScope) { onQuery("") } }
                    }
                    if (panesEmptyVisible) {
                        item(key = "panes-empty") {
                            PanesEmptyCard(liveHosts, ui.query, onOpenHost, onRefresh)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The phone queue: one block per machine.
 *
 * Order inside a block answers the two questions a reader actually has, in order: who needs me here
 * (its agents), then what is running here (workspaces → tabs → panes). Between machines there is a
 * weak hairline instead of a gap, so a long fleet stays scannable without turning into a wall of
 * cards: a divider is a glance, a card is an object.
 */
private fun LazyListScope.hostQueueBlocks(
    hosts: List<HostRuntime>,
    agentGroup: List<Agent>,
    panes: List<Pair<HostRuntime, HerdPane>>,
    now: Long,
    hostScope: String?,
    onOpenAgent: (Agent, String?) -> Unit,
    onOpenPane: ((String, String) -> Unit)?,
    // On a wide screen the two columns are the same machine blocks, split: the left column is the
    // agents of each machine, the right column the sessions of the same machine. Splitting the page
    // into "all agents" and "all panes" instead lost the machine grouping on exactly the layout with
    // room to show it.
    emitAgents: Boolean = true,
    emitPanes: Boolean = true,
) {
    val agentsByHost = agentGroup.groupBy { it.hostId }
    val panesByHost = panes.groupBy { it.first.host.id }
    // Machines with attention come first, then whichever has more happening, then alphabetical so
    // the order is stable across refreshes instead of shuffling under the reader's thumb.
    val ordered = hosts.sortedWith(
        compareByDescending<HostRuntime> { runtime ->
            agentsByHost[runtime.host.id].orEmpty().count { isAttention(it) }
        }
            .thenByDescending { runtime -> agentsByHost[runtime.host.id].orEmpty().size }
            .thenByDescending { runtime -> panesByHost[runtime.host.id].orEmpty().size }
            .thenBy { it.host.label },
    )
    var first = true
    ordered.forEach { runtime ->
        val hostAgents = agentsByHost[runtime.host.id].orEmpty()
        // An agent is a pane. Listing its pane again under the workspaces showed the same process
        // twice in one machine block, once with a status and once with a path, and made the reader
        // wonder which row to tap. The agent row is the richer one, so the pane list keeps only the
        // panes that have no agent above them.
        val agentPanes = hostAgents.map { it.paneId }.toSet()
        val hostPanes = panesByHost[runtime.host.id].orEmpty()
            .filterNot { it.second.id in agentPanes }
        val shownAgents = if (emitAgents) hostAgents else emptyList()
        val shownPanes = if (emitPanes) hostPanes else emptyList()
        if (shownAgents.isEmpty() && shownPanes.isEmpty()) return@forEach
        if (!first) {
            item(key = "host-rule:" + runtime.host.id) { WeakRule() }
        }
        first = false
        item(key = "host-block:" + runtime.host.id) {
            HostBlockHeader(runtime, shownAgents, shownPanes.map { it.second })
        }
        if (shownAgents.isNotEmpty()) {
            item(key = "host-agents-label:" + runtime.host.id) {
                BlockLabel(stringResource(R.string.inbox_block_agents))
            }
        }
        if (shownAgents.isNotEmpty()) {
            agentQueueItems(
                agentGroup = shownAgents,
                hosts = listOf(runtime),
                hostScope = hostScope,
                now = now,
                showHeader = false,
                showHostHeadings = false,
                onOpenAgent = onOpenAgent,
            )
        }
        if (shownPanes.isNotEmpty()) {
            item(key = "host-panes-label:" + runtime.host.id) {
                BlockLabel(stringResource(R.string.inbox_block_panes))
            }
        }
        if (shownPanes.isNotEmpty()) {
            paneItems(
                scopedHosts = listOf(runtime),
                panes = shownPanes,
                scopedAgents = shownAgents,
                hostScope = hostScope,
                showHeader = false,
                showHostHeadings = false,
                onOpenPane = onOpenPane,
                onOpenAgent = onOpenAgent,
            )
        }
    }
}

/**
 * The refresh control. While a refresh runs the same glyph turns, so the header keeps its shape
 * and its hit target; the state is announced for screen readers rather than drawn as a new object.
 */
@Composable
private fun RefreshAction(refreshing: Boolean, onRefresh: () -> Unit) {
    val colors = ds()
    val syncingLabel = stringResource(R.string.inbox_state_syncing)
    val rotation = if (refreshing) {
        val spin = rememberInfiniteTransition(label = "refresh")
        val angle by spin.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing)),
            label = "refresh-angle",
        )
        angle
    } else {
        0f
    }
    NeoIconButton(
        glyph = NeoGlyph.Refresh,
        onClick = onRefresh,
        enabled = !refreshing,
        tint = if (refreshing) colors.accent else colors.textSoft,
        contentDescription = stringResource(R.string.inbox_cd_refresh),
        modifier = Modifier
            .rotate(rotation)
            .semantics { if (refreshing) stateDescription = syncingLabel },
    )
}

/**
 * Machines that are not reachable: one line each, with the reason and a retry.
 *
 * Their cached agents and panes are deliberately not listed. A row you cannot open, with a status
 * from the last successful poll, is worse than an honest "offline" — and on a fleet with one dead
 * machine it pushed the live work below the fold.
 */
private fun LazyListScope.offlineBlocks(
    hosts: List<HostRuntime>,
    onReconnect: (String) -> Unit,
    onOpenHost: ((String) -> Unit)?,
) {
    hosts.forEach { runtime ->
        item(key = "offline:" + runtime.host.id) {
            OfflineHostRow(runtime, onReconnect, onOpenHost)
        }
    }
}

@Composable
private fun OfflineHostRow(
    runtime: HostRuntime,
    onReconnect: (String) -> Unit,
    onOpenHost: ((String) -> Unit)?,
) {
    val colors = ds()
    val state = runtime.conn
    Row(
        Modifier.fillMaxWidth().padding(vertical = DsSpace.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoLamp(color = connStatusColor(state), size = 8.dp)
        Spacer(Modifier.width(DsSpace.m))
        Column(Modifier.weight(1f)) {
            Text(
                runtime.host.label,
                style = DsTypography.bodyStrong,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (state) {
                    is ConnState.Failed -> state.message
                    ConnState.Connecting -> stringResource(R.string.inbox_host_connecting)
                    else -> stringResource(R.string.inbox_host_offline)
                },
                style = DsTypography.caption,
                color = if (state is ConnState.Failed) colors.warn else colors.textSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DsSpace.s))
        if (onOpenHost != null) {
            NeoIconButton(
                glyph = NeoGlyph.Server,
                onClick = { onOpenHost(runtime.host.id) },
                contentDescription = stringResource(R.string.inbox_cd_open_host, runtime.host.label),
            )
        }
        NeoButton(
            label = stringResource(R.string.inbox_env_retry),
            onClick = { onReconnect(runtime.host.id) },
            tone = NeoButtonTone.Ghost,
            compact = true,
        )
    }
}

/** A structural caption inside a machine block: which half of the block follows. */
@Composable
private fun BlockLabel(text: String) {
    val colors = ds()
    Text(
        text,
        style = DsTypography.micro,
        color = colors.textFaint,
        modifier = Modifier.padding(start = DsSpace.s, top = DsSpace.s, bottom = DsSpace.xxs),
    )
}

/**
 * A hairline between two machine blocks.
 *
 * Weaker than [NeoDivider] on purpose: it separates groups without becoming a row of its own, and it
 * does not use the panel shadow, so the page still has exactly one chassis.
 */
@Composable
private fun WeakRule() {
    val colors = ds()
    Spacer(Modifier.height(DsSpace.l))
    HorizontalDivider(thickness = 1.dp, color = colors.textFaint.copy(alpha = 0.16f))
    Spacer(Modifier.height(DsSpace.l))
}



/**
 * The first line of a machine block: which machine, whether it is reachable, and how much is on it.
 * Everything after this line belongs to this machine, which is the point of the whole restructure.
 */
@Composable
private fun HostBlockHeader(runtime: HostRuntime, agents: List<Agent>, panes: List<HerdPane>) {
    val colors = ds()
    val attention = agents.count { isAttention(it) }
    val up = runtime.conn is ConnState.Connected
    Row(
        Modifier.fillMaxWidth().padding(bottom = DsSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (up) colors.ok else colors.danger),
        )
        Spacer(Modifier.width(DsSpace.s))
        Text(
            runtime.host.label,
            style = DsTypography.bodyStrong,
            color = colors.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(DsSpace.s))
        Text(
            buildList {
                if (agents.isNotEmpty()) {
                    add(pluralStringResource(R.plurals.inbox_group_agents, agents.size, agents.size))
                }
                if (panes.isNotEmpty()) {
                    add(pluralStringResource(R.plurals.inbox_group_panes, panes.size, panes.size))
                }
            }.joinToString(stringResource(R.string.inbox_list_separator)),
            style = DsTypography.micro,
            color = colors.textFaint,
            maxLines = 1,
        )
        if (attention > 0) {
            Spacer(Modifier.width(DsSpace.s))
            Text(
                pluralStringResource(R.plurals.inbox_group_waiting, attention, attention),
                style = DsTypography.micro,
                color = colors.warn,
                maxLines = 1,
            )
        }
    }
}

/**
 * The agent queue, emitted into whichever list is hosting it. Machines are the outer block:
 * heading, its cards, then a weak hairline before the next machine.
 */
private fun LazyListScope.agentQueueItems(
    agentGroup: List<Agent>,
    hosts: List<HostRuntime>,
    hostScope: String?,
    now: Long,
    showHeader: Boolean,
    showHostHeadings: Boolean,
    onOpenAgent: (Agent, String?) -> Unit,
) {
    if (agentGroup.isEmpty()) return
    if (showHeader) {
        val waitingTotal = agentGroup.count { isAttention(it) }
        item(key = "agents-header") {
            val colors = ds()
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
    }
    val byHost = agentGroup.groupBy { it.hostId }
    byHost.entries.forEachIndexed { hostIndex, (hostId, hostAgents) ->
        val hostRuntime = hosts.firstOrNull { it.host.id == hostId }
        if (showHostHeadings) {
            val waitingForHost = hostAgents.count { isAttention(it) }
            item(key = "agents-host:$hostId") {
                val colors = ds()
                NeoSection(
                    title = hostAgents.first().hostLabel,
                    trailing = if (waitingForHost > 0) ({
                        Text(
                            pluralStringResource(R.plurals.inbox_group_waiting, waitingForHost, waitingForHost),
                            style = DsTypography.micro,
                            color = colors.warn,
                        )
                    }) else null,
                )
            }
        }
        val byWorkspace = hostAgents.groupBy { it.workspaceLabel ?: it.repo ?: "" }
        byWorkspace.forEach { (workspaceLabel, workspaceAgents) ->
            if (workspaceLabel.isNotBlank()) {
                item(key = "agents-ws:$hostId:$workspaceLabel") {
                    val colors = ds()
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
        if (hostIndex != byHost.size - 1) {
            item(key = "agents-host-divider:$hostId") {
                NeoDivider(Modifier.padding(vertical = DsSpace.s))
            }
        }
    }
}

/** The pane tree. Machines are the outer block here too: heading, its panes, a weak hairline. */
private fun LazyListScope.paneItems(
    scopedHosts: List<HostRuntime>,
    panes: List<Pair<HostRuntime, HerdPane>>,
    scopedAgents: List<Agent>,
    hostScope: String?,
    showHeader: Boolean,
    showHostHeadings: Boolean = true,
    onOpenPane: ((String, String) -> Unit)?,
    onOpenAgent: (Agent, String?) -> Unit,
) {
    if (showHeader) {
        item(key = "panes-header") {
            NeoSection(
                title = pluralStringResource(R.plurals.inbox_group_panes, panes.size, panes.size),
            )
        }
    }
    val perHost = scopedHosts.mapNotNull { runtime ->
        val hostPanes = panes.filter { it.first.host.id == runtime.host.id }.map { it.second }
        if (hostPanes.isEmpty()) null else runtime to hostPanes
    }
    perHost.forEachIndexed { hostIndex, (runtime, hostPanes) ->
        val tabs = runtime.structure.tabs
        if (showHostHeadings) {
            item(key = "host:" + runtime.host.id) {
                val colors = ds()
                NeoSection(
                    title = runtime.host.label + "  ·  " +
                        pluralStringResource(R.plurals.inbox_group_panes, hostPanes.size, hostPanes.size),
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
                    val colors = ds()
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
                // The tab is a sub-level of the workspace heading above: a small glyph and a
                // caption, indented one step so the tree reads without a rail.
                val tabLabel = tab?.label ?: tabId.substringAfterLast(':')
                item(key = "tab:" + runtime.host.id + ":" + tabId) {
                    val colors = ds()
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
        if (hostIndex != perHost.lastIndex) {
            item(key = "host-divider:" + runtime.host.id) {
                NeoDivider(Modifier.padding(vertical = DsSpace.s))
            }
        }
    }
}

/** Wide-screen queue column with nothing in it: one line, not a full empty-state card. */
@Composable
private fun QueueEmptyLine() {
    val colors = ds()
    Text(
        stringResource(R.string.inbox_queue_empty),
        style = DsTypography.body,
        color = colors.textSoft,
        modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpace.s, vertical = DsSpace.l),
    )
}

@Composable
private fun ConnectingNotice(hosts: List<HostRuntime>) {
    val colors = ds()
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
                    hosts.joinToString(stringResource(R.string.inbox_list_separator)) { it.host.label },
                ),
                style = DsTypography.body,
                color = colors.textSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun AgentsEmptyCard(
    hostScope: String?,
    hosts: List<HostRuntime>,
    onNewTask: (() -> Unit)?,
    onOpenHost: ((String) -> Unit)?,
    onShowAllHosts: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = ds()
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
                    onClick = onShowAllHosts,
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
                hosts.filter { it.conn is ConnState.Connected }.forEach { runtime ->
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

@Composable
private fun SetupCard(onAddHost: () -> Unit) {
    val colors = ds()
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

@Composable
private fun NoMatchCard(query: String, hostScope: String?, onClearQuery: () -> Unit) {
    NeoEmptyState(
        icon = NeoGlyph.Search,
        title = stringResource(R.string.inbox_no_match_title),
        body = stringResource(
            when {
                query.isNotBlank() -> R.string.inbox_no_match_search_body
                hostScope != null -> R.string.inbox_no_match_host_body
                else -> R.string.inbox_no_match_none_body
            },
        ),
    ) {
        if (query.isNotBlank()) {
            NeoButton(
                label = stringResource(R.string.inbox_clear_search),
                onClick = onClearQuery,
                tone = NeoButtonTone.Ghost,
                compact = true,
            )
        }
    }
}

@Composable
private fun PanesEmptyCard(
    hosts: List<HostRuntime>,
    query: String,
    onOpenHost: ((String) -> Unit)?,
    onRefresh: () -> Unit,
) {
    NeoEmptyState(
        icon = NeoGlyph.Pane,
        title = stringResource(R.string.inbox_panes_empty_title),
        body = stringResource(
            if (query.isNotBlank()) R.string.inbox_no_match_search_body
            else R.string.inbox_panes_empty_body,
        ),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
            if (onOpenHost != null) {
                NeoButton(
                    label = stringResource(R.string.inbox_check_host_sessions, hosts.first().host.label),
                    onClick = { onOpenHost(hosts.first().host.id) },
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
