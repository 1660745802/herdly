package dev.herdwatch.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdTab
import dev.herdwatch.model.HerdWorkspace
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.ExpandableSection
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsMotion
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoCard
import dev.herdwatch.ui.design.NeoChip
import dev.herdwatch.ui.design.NeoDialog
import dev.herdwatch.ui.design.NeoDivider
import dev.herdwatch.ui.design.NeoField
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoLamp
import dev.herdwatch.ui.design.NeoPageHeader
import dev.herdwatch.ui.design.NeoSection
import dev.herdwatch.ui.design.NeoShadowBleed
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.design.spacing
import dev.herdwatch.ui.motionMillis
import dev.herdwatch.ui.theme.agentStatusColor

/**
 * Session management, one page.
 *
 * Creating, renaming and closing used to live three levels deep inside the structure tree's
 * overflow menus, so the common act of "give me another pane" cost four taps and a hunt. This page
 * is the flat answer: create at the top, then every workspace, tab and pane as a row with its two
 * actions visible. The structure page keeps its job — browsing and jumping into a pane.
 *
 * Destructive actions name the target and say what ends; nothing here closes a pane silently.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManageScreen(
    runtime: HostRuntime,
    agents: List<Agent>,
    onBack: () -> Unit,
    onCreateWorkspace: (String?) -> Unit,
    onCreateTab: (String, String?) -> Unit,
    onSplit: (String, String) -> Unit,
    onRenameWorkspace: (String, String) -> Unit,
    onRenameTab: (String, String) -> Unit,
    onRenamePane: (String, String) -> Unit,
    onCloseWorkspace: (String) -> Unit,
    onCloseTab: (String) -> Unit,
    onClosePane: (String) -> Unit,
    onOpenPane: (String) -> Unit,
    onBrowse: () -> Unit,
) {
    val colors = ds()
    val structure = runtime.structure
    val connected = runtime.conn is ConnState.Connected
    var rename by remember { mutableStateOf<RenameRequest?>(null) }
    var close by remember { mutableStateOf<CloseRequest?>(null) }
    var create by remember { mutableStateOf<CreateRequest?>(null) }
    // A machine with six workspaces is a long page; groups you are not working on collapse and stay
    // collapsed across visits.
    // Pane ids repeat across machines; scoping once here keeps a close dialog from naming another
    // host's agent.
    val hostAgents = remember(agents, runtime.host.id) { agents.filter { it.hostId == runtime.host.id } }
    var collapsed by rememberSaveable(runtime.host.id) { mutableStateOf(setOf<String>()) }
    // All confirmation copy is resolved during composition: click lambdas are not composable.
    val closePaneShellDetail = stringResource(R.string.manage_close_pane_shell)
    val closePaneAgentTemplate = stringResource(R.string.manage_close_pane_agent)

    Column(Modifier.fillMaxSize()) {
        NeoPageHeader(
            title = stringResource(R.string.manage_title, runtime.host.label),
            subtitle = stringResource(R.string.manage_subtitle),
            onBack = onBack,
            actions = {
                NeoIconButton(
                    glyph = NeoGlyph.Tray,
                    onClick = onBrowse,
                    contentDescription = stringResource(R.string.manage_browse),
                )
            },
        )
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = spacing().gutter,
                end = spacing().gutter,
                top = DsSpace.m,
                bottom = DsSpace.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(spacing().block),
        ) {
            item(key = "create") {
                NeoCard(Modifier.fillMaxWidth(), depth = DsElev.rest) {
                    Column(Modifier.padding(DsSpace.l)) {
                        Text(
                            stringResource(R.string.manage_create_title),
                            style = DsTypography.bodyStrong,
                            color = colors.text,
                        )
                        Spacer(Modifier.height(DsSpace.s))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(NeoShadowBleed + DsSpace.xs),
                        ) {
                            NeoButton(
                                label = stringResource(R.string.manage_new_workspace),
                                onClick = { create = CreateRequest(newWorkspace = true, workspaceId = null) },
                                tone = NeoButtonTone.Primary,
                                icon = NeoGlyph.Workspace,
                                enabled = connected,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                            NeoButton(
                                label = stringResource(R.string.manage_new_tab),
                                onClick = {
                                    val target = structure.workspaces.firstOrNull { it.focused }
                                        ?: structure.workspaces.firstOrNull()
                                    // "New tab" with no workspace means "give me a workspace first" —
                                    // creating somewhere is still what the tap asked for.
                                    create = CreateRequest(newWorkspace = target == null, workspaceId = target?.id)
                                },
                                tone = NeoButtonTone.Quiet,
                                icon = NeoGlyph.Tab,
                                enabled = connected,
                                compact = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (!connected) {
                            Text(
                                stringResource(R.string.manage_connect_first),
                                style = DsTypography.caption,
                                color = colors.textSoft,
                                modifier = Modifier.padding(top = DsSpace.s),
                            )
                        }
                    }
                }
            }

            structure.workspaces.forEach { workspace ->
                item(key = "ws:" + workspace.id) {
                    val workspaceDetail = pluralStringResource(
                        R.plurals.manage_close_workspace_detail,
                        workspace.paneCount,
                        workspace.paneCount,
                    )
                    WorkspaceHeader(
                        workspace = workspace,
                        open = workspace.id !in collapsed,
                        onToggle = {
                            collapsed = if (workspace.id in collapsed) collapsed - workspace.id else collapsed + workspace.id
                        },
                        trailing = {
                            NeoIconButton(
                                glyph = NeoGlyph.Edit,
                                onClick = { rename = RenameRequest("workspace", workspace.id, workspace.label) },
                                contentDescription = stringResource(R.string.manage_rename),
                            )
                            NeoIconButton(
                                glyph = NeoGlyph.Close,
                                onClick = {
                                    close = CloseRequest(
                                        type = "workspace",
                                        id = workspace.id,
                                        name = workspace.label.ifBlank { workspace.id },
                                        detail = workspaceDetail,
                                    )
                                },
                                contentDescription = stringResource(R.string.manage_close),
                            )
                        },
                    )
                }
                val tabs = structure.tabs.filter { it.workspaceId == workspace.id }
                if (workspace.id !in collapsed) items(tabs, key = { "tab:" + it.id }) { tab ->
                    val tabDetail = pluralStringResource(
                        R.plurals.manage_close_tab_detail,
                        tab.paneCount,
                        tab.paneCount,
                    )
                    val paneDetail: (HerdPane) -> String = { pane ->
                        hostAgents.firstOrNull { it.paneId == pane.id }
                            ?.let { closePaneAgentTemplate.replace("%AGENT%", it.name) }
                            ?: closePaneShellDetail
                    }
                    TabManageCard(
                        closeTabDetail = tabDetail,
                        tab = tab,
                        panes = structure.panes.filter { it.tabId == tab.id },
                        agents = hostAgents,
                        connected = connected,
                        onSplit = onSplit,
                        onOpenPane = onOpenPane,
                        onRenameTab = { rename = RenameRequest("tab", tab.id, tab.label) },
                        onRenamePane = { pane -> rename = RenameRequest("pane", pane.id, pane.title) },
                        onCloseTab = {
                            close = CloseRequest(
                                type = "tab",
                                id = tab.id,
                                name = tab.label.ifBlank { tab.id },
                                detail = tabDetail,
                            )
                        },
                        initiallyOpen = tab.id !in collapsed,
                        onToggle = { isOpen -> collapsed = if (isOpen) collapsed - tab.id else collapsed + tab.id },
                        onClosePane = { pane ->
                            close = CloseRequest(
                                type = "pane",
                                id = pane.id,
                                name = pane.title.ifBlank { pane.id },
                                detail = paneDetail(pane),
                            )
                        },
                    )
                }
            }

            if (structure.workspaces.isEmpty()) {
                item(key = "empty") {
                    NeoWell(Modifier.fillMaxWidth(), corner = DsRadius.control) {
                        Text(
                            stringResource(
                                if (connected) R.string.manage_empty else R.string.manage_empty_offline,
                            ),
                            style = DsTypography.body,
                            color = colors.textSoft,
                            modifier = Modifier.padding(DsSpace.l),
                        )
                    }
                }
            }
        }
    }

    create?.let { request ->
        // Preselect, so "create" is never a button that silently does nothing. The focused
        // workspace is the one the reader was last working in.
        var workspaceId by remember(request) {
            mutableStateOf(
                request.workspaceId
                    ?: structure.workspaces.firstOrNull { it.focused }?.id
                    ?: structure.workspaces.firstOrNull()?.id,
            )
        }
        var cwd by remember(request) { mutableStateOf("") }
        // There is no directory-listing API on this protocol, so the honest affordance is a path
        // field plus the directories this host is already using. Anything more would be a file
        // browser we cannot actually draw.
        val recent = remember(structure.panes) {
            structure.panes.mapNotNull { it.cwd?.trim()?.takeIf(String::isNotBlank) }.distinct().take(4)
        }
        NeoDialog(
            onDismissRequest = { create = null },
            title = stringResource(
                if (request.newWorkspace) R.string.manage_create_workspace_title else R.string.manage_create_tab_title,
            ),
            subtitle = stringResource(R.string.manage_create_folder_hint),
            confirmLabel = stringResource(R.string.common_create),
            confirmEnabled = request.newWorkspace || workspaceId != null,
            onConfirm = {
                val path = cwd.trim().ifBlank { null }
                if (request.newWorkspace) {
                    onCreateWorkspace(path)
                } else {
                    workspaceId?.let { onCreateTab(it, path) }
                }
                create = null
            },
            dismissLabel = stringResource(R.string.common_cancel),
        ) {
            if (!request.newWorkspace) {
                Text(
                    stringResource(R.string.manage_pick_workspace),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    structure.workspaces.forEach { workspace ->
                        NeoChip(
                            label = workspace.label.ifBlank { workspace.id },
                            selected = workspace.id == workspaceId,
                            onClick = { workspaceId = workspace.id },
                        )
                    }
                }
                Spacer(Modifier.height(DsSpace.m))
            }
            NeoField(
                value = cwd,
                onValueChange = { cwd = it },
                label = stringResource(R.string.manage_create_folder),
                hint = stringResource(R.string.manage_create_folder_placeholder),
                singleLine = true,
            )
            if (recent.isNotEmpty()) {
                Spacer(Modifier.height(DsSpace.s))
                Text(
                    stringResource(R.string.manage_create_folder_recent),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    recent.forEach { path ->
                        NeoChip(
                            label = path.replace(Regex("^/home/[^/]+"), "~"),
                            selected = path == cwd,
                            onClick = { cwd = path },
                        )
                    }
                }
            }
        }
    }

    rename?.let { request ->
        var value by remember(request.id) { mutableStateOf(request.label) }
        NeoDialog(
            onDismissRequest = { rename = null },
            title = stringResource(R.string.manage_rename_title),
            confirmLabel = stringResource(R.string.common_save),
            onConfirm = {
                val name = value.trim()
                if (name.isNotEmpty()) {
                    when (request.type) {
                        "workspace" -> onRenameWorkspace(request.id, name)
                        "tab" -> onRenameTab(request.id, name)
                        else -> onRenamePane(request.id, name)
                    }
                }
                rename = null
            },
            dismissLabel = stringResource(R.string.common_cancel),
        ) {
            NeoField(
                value = value,
                onValueChange = { value = it },
                label = stringResource(R.string.manage_rename_label),
                singleLine = true,
            )
        }
    }

    close?.let { request ->
        NeoDialog(
            onDismissRequest = { close = null },
            title = stringResource(R.string.manage_close_title, request.name),
            subtitle = request.detail,
            confirmLabel = stringResource(R.string.manage_close_confirm),
            onConfirm = {
                when (request.type) {
                    "workspace" -> onCloseWorkspace(request.id)
                    "tab" -> onCloseTab(request.id)
                    else -> onClosePane(request.id)
                }
                close = null
            },
            dismissLabel = stringResource(R.string.common_cancel),
            destructive = true,
        )
    }
}

@Composable
private fun WorkspaceHeader(
    workspace: HerdWorkspace,
    open: Boolean,
    onToggle: () -> Unit,
    trailing: @Composable RowScope.() -> Unit,
) {
    val colors = ds()
    Row(
        Modifier.fillMaxWidth().padding(start = DsSpace.xs, top = DsSpace.s, bottom = DsSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ToggleSurface(
            onClick = onToggle,
            modifier = Modifier.weight(1f).padding(horizontal = DsSpace.xs),
        ) {
            NeoIcon(
                if (open) NeoGlyph.ChevronDown else NeoGlyph.ChevronRight,
                modifier = Modifier.size(15.dp),
                tint = colors.textSoft,
            )
            Spacer(Modifier.width(DsSpace.xs))
            NeoIcon(NeoGlyph.Workspace, modifier = Modifier.size(15.dp), tint = colors.textSoft)
            Spacer(Modifier.width(DsSpace.xs))
            Text(
                workspace.label.ifBlank { workspace.id },
                style = DsTypography.bodyStrong,
                color = colors.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(DsSpace.s))
            Text(
                pluralStringResource(R.plurals.manage_pane_count, workspace.paneCount, workspace.paneCount),
                style = DsTypography.caption,
                color = colors.textFaint,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = trailing)
    }
}

private data class RenameRequest(val type: String, val id: String, val label: String)
private data class CloseRequest(val type: String, val id: String, val name: String, val detail: String)

/** A pending create, held until the dialog has a directory and (for a tab) a workspace. */
private data class CreateRequest(val newWorkspace: Boolean, val workspaceId: String?)

/**
 * Row that opens or closes a section.
 *
 * The press mark has to cover the thing that is actually tappable. A bare `clickable` on an inner
 * Row left a ripple the size of the label while the row read as one control, and the highlight
 * looked like a rectangle pasted over the card. One rounded, full-row surface reads as a button.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ToggleSurface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val colors = ds()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val wash by animateColorAsState(
        targetValue = if (pressed) colors.well else Color.Transparent,
        animationSpec = tween(motionMillis(DsMotion.press)),
        label = "toggleWash",
    )
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(DsRadius.control))
            .background(wash)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .padding(vertical = DsSpace.xxs),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun TabManageCard(
    tab: HerdTab,
    closeTabDetail: String,
    panes: List<HerdPane>,
    agents: List<Agent>,
    connected: Boolean,
    onSplit: (String, String) -> Unit,
    onOpenPane: (String) -> Unit,
    onRenameTab: () -> Unit,
    onRenamePane: (HerdPane) -> Unit,
    onCloseTab: () -> Unit,
    onClosePane: (HerdPane) -> Unit,
    initiallyOpen: Boolean = true,
    onToggle: (Boolean) -> Unit = {},
) {
    val colors = ds()
    var open by rememberSaveable(tab.id) { mutableStateOf(initiallyOpen) }
    NeoCard(Modifier.fillMaxWidth(), depth = DsElev.soft) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(start = DsSpace.l, end = DsSpace.xs, top = DsSpace.s, bottom = DsSpace.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ToggleSurface(
                    onClick = {
                        open = !open
                        onToggle(open)
                    },
                    modifier = Modifier.weight(1f).padding(start = DsSpace.xs),
                ) {
                    NeoIcon(
                        if (open) NeoGlyph.ChevronDown else NeoGlyph.ChevronRight,
                        modifier = Modifier.size(14.dp),
                        tint = colors.textFaint,
                    )
                    Spacer(Modifier.width(DsSpace.xs))
                    NeoIcon(NeoGlyph.Tab, modifier = Modifier.size(14.dp), tint = colors.textFaint)
                    Spacer(Modifier.width(DsSpace.xs))
                    Text(
                        tab.label.ifBlank { tab.id },
                        style = DsTypography.bodyStrong,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (!open) {
                        Text(
                            pluralStringResource(R.plurals.manage_pane_count, panes.size, panes.size),
                            style = DsTypography.caption,
                            color = colors.textFaint,
                        )
                        Spacer(Modifier.width(DsSpace.xs))
                    }
                }
                NeoIconButton(
                    glyph = NeoGlyph.Plus,
                    onClick = { panes.firstOrNull()?.let { onSplit(it.id, "right") } },
                    contentDescription = stringResource(R.string.manage_split),
                    enabled = connected && panes.isNotEmpty(),
                )
                NeoIconButton(
                    glyph = NeoGlyph.Edit,
                    onClick = onRenameTab,
                    contentDescription = stringResource(R.string.manage_rename),
                )
                NeoIconButton(
                    glyph = NeoGlyph.Close,
                    onClick = onCloseTab,
                    contentDescription = stringResource(R.string.manage_close),
                )
            }
            ExpandableSection(open) {
            Column(Modifier.fillMaxWidth()) {
            NeoDivider(inset = DsSpace.l)
            panes.forEachIndexed { index, pane ->
                val agent = agents.firstOrNull { it.paneId == pane.id }
                Row(
                    Modifier.fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenPane(pane.id) }
                        .padding(start = DsSpace.l, end = DsSpace.xs, top = DsSpace.xs, bottom = DsSpace.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeoLamp(
                        color = if (agent != null) agentStatusColor(agent.status) else colors.ok,
                        size = 7.dp,
                    )
                    Spacer(Modifier.width(DsSpace.s))
                    Column(
                        Modifier.weight(1f).padding(vertical = DsSpace.xxs),
                    ) {
                        // A shell's terminal title is the whole "user@host" string, which truncated
                        // to nothing useful; the directory is the name a person recognises.
                        val directory = pane.cwd?.trimEnd('/')?.substringAfterLast('/')
                        Text(
                            directory?.takeIf { pane.agentKind.isNullOrBlank() && it.isNotBlank() }
                                ?: pane.title.ifBlank { pane.id.substringAfterLast(':') },
                            style = DsTypography.body,
                            color = colors.text,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            listOfNotNull(
                                pane.id.substringAfterLast(':'),
                                pane.cwd?.replace(Regex("^/home/[^/]+"), "~"),
                            ).joinToString("  ·  "),
                            style = DsTypography.micro,
                            color = colors.textFaint,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    NeoIconButton(
                        glyph = NeoGlyph.Edit,
                        onClick = { onRenamePane(pane) },
                        contentDescription = stringResource(R.string.manage_rename),
                    )
                    NeoIconButton(
                        glyph = NeoGlyph.Close,
                        onClick = { onClosePane(pane) },
                        contentDescription = stringResource(R.string.manage_close),
                    )
                }
                if (index != panes.lastIndex) NeoDivider(inset = DsSpace.l)
            }
            }
            }
        }
    }
}
