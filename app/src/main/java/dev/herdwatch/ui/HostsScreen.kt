package dev.herdwatch.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoCard
import dev.herdwatch.ui.design.NeoDialog
import dev.herdwatch.ui.design.NeoDivider
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoPageHeader
import dev.herdwatch.ui.design.NeoLamp
import dev.herdwatch.ui.design.NeoMenu
import dev.herdwatch.ui.design.NeoMenuDivider
import dev.herdwatch.ui.design.NeoMenuItem
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostsScreen(
    hosts: List<HostRuntime>,
    onAdd: () -> Unit,
    onReconnect: (String) -> Unit,
    onEdit: (HostConfig) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
    onTerminal: (String) -> Unit,
    onOpenHelp: () -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<HostConfig?>(null) }
    val compact = LocalConfiguration.current.run { screenWidthDp < 360 || fontScale >= 1.3f }
    val connected = hosts.count { it.conn is ConnState.Connected }
    Column(Modifier.fillMaxSize()) {
        NeoPageHeader(
            title = stringResource(R.string.hosts_title),
            subtitle = if (compact) {
                stringResource(R.string.hosts_subtitle_short, connected, hosts.size)
            } else {
                pluralStringResource(R.plurals.hosts_subtitle_count, hosts.size, hosts.size, connected)
            },
            compact = compact,
            actions = {
                NeoIconButton(
                    glyph = NeoGlyph.Sliders,
                    onClick = onOpenHelp,
                    contentDescription = stringResource(R.string.hosts_usage_security),
                )
                NeoButton(
                    label = stringResource(R.string.common_add),
                    onClick = onAdd,
                    tone = NeoButtonTone.Primary,
                    icon = NeoGlyph.Plus,
                    compact = true,
                )
            },
        )

        LazyColumn(
            Modifier.weight(1f),
            // Top padding is not cosmetic: the card's highlight is drawn outside its bounds, and
            // a LazyColumn clips at the viewport edge, so without it the first card's top rim is
            // sliced off (the inbox already had this breathing room).
            contentPadding = PaddingValues(start = DsSpace.xl, end = DsSpace.xl, top = DsSpace.m, bottom = 108.dp),
            verticalArrangement = Arrangement.spacedBy(DsSpace.m),
        ) {
            if (hosts.isEmpty()) {
                item { EmptyMachines(onAdd) }
                return@LazyColumn
            }
            items(hosts, key = { it.host.id }) { runtime ->
                HostCard(
                    runtime,
                    modifier = animateItemMotion(),
                    onOpen = { onOpen(runtime.host.id) },
                    onTerminal = { onTerminal(runtime.host.id) },
                    onReconnect = { onReconnect(runtime.host.id) },
                    onEdit = { onEdit(runtime.host) },
                    onDelete = { pendingDelete = runtime.host },
                )
            }
            item {
                Text(
                    stringResource(R.string.hosts_footer),
                    style = DsTypography.caption,
                    color = ds().textFaint,
                    modifier = Modifier.padding(top = DsSpace.s, bottom = DsSpace.l),
                )
            }
        }
    }

    pendingDelete?.let { host ->
        NeoConfirmRemove(
            label = host.label,
            onDismiss = { pendingDelete = null },
            onConfirm = {
                onDelete(host.id)
                pendingDelete = null
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EmptyMachines(onAdd: () -> Unit) {
    NeoCard(
        modifier = Modifier.fillMaxWidth(),
        depth = DsElev.soft,
    ) {
        Column(Modifier.padding(DsSpace.xxl), verticalArrangement = Arrangement.spacedBy(DsSpace.m)) {
            NeoWell(Modifier.size(56.dp), corner = DsRadius.control) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    NeoIcon(NeoGlyph.Terminal, modifier = Modifier.size(26.dp), tint = ds().accent, contentDescription = null)
                }
            }
            Text(stringResource(R.string.hosts_empty_title), style = DsTypography.title, color = ds().text)
            Text(stringResource(R.string.hosts_empty_body), style = DsTypography.body, color = ds().textSoft)
            NeoButton(
                label = stringResource(R.string.hosts_empty_action),
                onClick = onAdd,
                tone = NeoButtonTone.Primary,
                icon = NeoGlyph.Plus,
            )
        }
    }
}

@Composable
private fun NeoConfirmRemove(label: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    NeoDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.hosts_delete_title, label),
        subtitle = stringResource(R.string.hosts_delete_body),
        confirmLabel = stringResource(R.string.common_remove),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.common_cancel),
        destructive = true,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostCard(
    runtime: HostRuntime,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onTerminal: () -> Unit,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val host = runtime.host
    val conn = runtime.conn
    var details by rememberSaveable(host.id) { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val colors = ds()
    val clipboard = LocalClipboardManager.current
    val fingerprint = host.fingerprint ?: runtime.fingerprint
    val stateLabel = when (conn) {
        is ConnState.Connected -> stringResource(R.string.hosts_state_connected)
        ConnState.Connecting -> stringResource(R.string.hosts_state_connecting)
        is ConnState.Failed -> stringResource(R.string.hosts_state_failed)
        ConnState.Idle -> stringResource(R.string.hosts_state_idle)
    }
    val lampColor = when (conn) {
        is ConnState.Connected -> colors.ok
        ConnState.Connecting -> colors.warn
        is ConnState.Failed -> colors.danger
        ConnState.Idle -> colors.textFaint
    }
    val connected = conn is ConnState.Connected
    NeoCard(
        modifier = modifier.fillMaxWidth().semantics { stateDescription = stateLabel },
        depth = DsElev.rest,
    ) {
        Column(Modifier.padding(DsSpace.l)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeoLamp(color = lampColor, pulse = conn == ConnState.Connecting)
                Spacer(Modifier.width(DsSpace.s))
                Text(
                    host.label,
                    style = DsTypography.headline,
                    color = colors.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Box {
                    NeoIconButton(
                        glyph = NeoGlyph.More,
                        onClick = { menu = true },
                        contentDescription = stringResource(R.string.hosts_manage, host.label),
                    )
                    NeoMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        NeoMenuItem(
                            label = stringResource(R.string.hosts_menu_edit),
                            onClick = { menu = false; onEdit() },
                        )
                        NeoMenuDivider()
                        NeoMenuItem(
                            label = stringResource(R.string.hosts_menu_delete),
                            onClick = { menu = false; onDelete() },
                            danger = true,
                        )
                    }
                }
            }
            Text(
                "${host.user}@${host.host}:${host.port}",
                style = DsTypography.mono,
                color = colors.textSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(DsSpace.s))
            val fadeInMs = motionMillis(180)
            val fadeOutMs = motionMillis(120)
            AnimatedContent(
                targetState = conn,
                contentKey = { it::class },
                transitionSpec = { fadeIn(tween(fadeInMs)) togetherWith fadeOut(tween(fadeOutMs)) },
                label = "host-conn-label",
            ) { state ->
                Text(
                    when (state) {
                        is ConnState.Connected ->
                            pluralStringResource(R.plurals.hosts_connected_agents, runtime.agentCount, runtime.agentCount)
                        ConnState.Connecting -> stringResource(R.string.hosts_connecting_ellipsis)
                        is ConnState.Failed -> stringResource(R.string.hosts_failed_short)
                        ConnState.Idle -> stringResource(R.string.hosts_state_idle)
                    },
                    style = DsTypography.body,
                    color = if (state is ConnState.Failed) colors.danger else colors.textSoft,
                )
            }
            Spacer(Modifier.height(DsSpace.xs))
            Text(
                stringResource(R.string.hosts_last_sync, formatAgo(runtime.lastSyncAt)),
                style = DsTypography.caption,
                color = colors.textFaint,
            )
            if (conn is ConnState.Failed) {
                // The card is a summary: two lines of red plus a paragraph of advice pushed the
                // actual actions below the fold. Raw transport text lives in Details.
                Text(
                    stringResource(R.string.hosts_failure_hint),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(DsSpace.m))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
                verticalArrangement = Arrangement.spacedBy(DsSpace.s),
            ) {
                NeoButton(
                    label = stringResource(R.string.hosts_open_terminal),
                    onClick = onTerminal,
                    tone = NeoButtonTone.Primary,
                    icon = NeoGlyph.Terminal,
                    compact = true,
                )
                NeoButton(
                    label = if (connected) {
                        stringResource(R.string.hosts_check_sessions)
                    } else {
                        stringResource(R.string.hosts_connect_bridge)
                    },
                    onClick = if (connected) onOpen else onReconnect,
                    tone = NeoButtonTone.Quiet,
                    enabled = conn != ConnState.Connecting,
                    icon = NeoGlyph.Tray,
                    compact = true,
                )
                NeoButton(
                    label = if (details) {
                        stringResource(R.string.common_collapse)
                    } else {
                        stringResource(R.string.hosts_details)
                    },
                    onClick = { details = !details },
                    tone = NeoButtonTone.Ghost,
                    icon = if (details) NeoGlyph.ChevronDown else NeoGlyph.ChevronRight,
                    compact = true,
                )
            }
            ExpandableSection(details) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpace.s), modifier = Modifier.padding(top = DsSpace.m)) {
                    NeoDivider()
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
                            Text(
                                "${host.user}@${host.host}:${host.port}",
                                style = DsTypography.mono,
                                color = colors.text,
                            )
                            Text(
                                stringResource(
                                    R.string.hosts_detail_session,
                                    host.session.ifBlank { stringResource(R.string.hosts_session_default) },
                                    host.herdrCommand,
                                ),
                                style = DsTypography.body,
                                color = colors.textSoft,
                            )
                            Text(
                                stringResource(
                                    R.string.hosts_detail_fingerprint_line,
                                    if (host.fingerprint != null) {
                                        stringResource(R.string.hosts_detail_fingerprint_saved)
                                    } else {
                                        stringResource(R.string.hosts_detail_fingerprint_observed)
                                    },
                                    fingerprint ?: stringResource(R.string.hosts_detail_fingerprint_none),
                                ),
                                style = DsTypography.mono,
                                color = colors.textSoft,
                            )
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                        NeoButton(
                            label = stringResource(R.string.hosts_detail_copy),
                            onClick = {
                                clipboard.setText(
                                    AnnotatedString(
                                        buildString {
                                            appendLine("${host.label}: ${host.user}@${host.host}:${host.port}")
                                            appendLine("session=${host.session.ifBlank { "-" }}; herdr=${host.herdrCommand}")
                                            appendLine("fingerprint=${fingerprint ?: "-"}")
                                            appendLine("lastSync=${runtime.lastSyncAt ?: "-"}")
                                            if (conn is ConnState.Failed) append(conn.message)
                                        },
                                    ),
                                )
                            },
                            tone = NeoButtonTone.Ghost,
                            icon = NeoGlyph.Copy,
                            compact = true,
                        )
                        NeoButton(
                            label = stringResource(R.string.common_reconnect),
                            onClick = onReconnect,
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
