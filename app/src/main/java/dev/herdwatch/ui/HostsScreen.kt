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
import dev.herdwatch.ui.design.NeoEmptyState
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
import dev.herdwatch.ui.design.NeoShadowBleed
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.connStatusColor
import dev.herdwatch.ui.design.spacing

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HostsScreen(
    hosts: List<HostRuntime>,
    onAdd: () -> Unit,
    onReconnect: (String) -> Unit,
    onEdit: (HostConfig) -> Unit,
    onDelete: (String) -> Unit,
    onTerminal: (String) -> Unit,
    onManage: (String) -> Unit,
    onOpenHelp: () -> Unit,
    // hostId to displayed fingerprint: the dialog's decision is only valid for the key it showed.
    onTrustNewKey: (String, String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<HostConfig?>(null) }
    var pendingTrust by remember { mutableStateOf<HostRuntime?>(null) }
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
            contentPadding = PaddingValues(start = spacing().gutter, end = spacing().gutter, top = DsSpace.m, bottom = 108.dp),
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
                    onTerminal = { onTerminal(runtime.host.id) },
                    onManage = { onManage(runtime.host.id) },
                    onReconnect = { onReconnect(runtime.host.id) },
                    onEdit = { onEdit(runtime.host) },
                    onDelete = { pendingDelete = runtime.host },
                    onTrustNewKey = { pendingTrust = runtime },
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

    pendingTrust?.let { runtime ->
        val mismatch = runtime.keyMismatch
        val pending = runtime.pendingKey
        if (mismatch != null || pending != null) {
            HostKeyDialog(
                label = runtime.host.label,
                mismatch = mismatch,
                pending = pending,
                onDismiss = { pendingTrust = null },
                onTrust = {
                    val shown = mismatch?.actual ?: pending?.fingerprint
                    if (shown != null) onTrustNewKey(runtime.host.id, shown)
                    pendingTrust = null
                },
            )
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
    NeoEmptyState(
        icon = NeoGlyph.Terminal,
        title = stringResource(R.string.hosts_empty_title),
        body = stringResource(R.string.hosts_empty_body),
    ) {
        NeoButton(
            label = stringResource(R.string.hosts_empty_action),
            onClick = onAdd,
            tone = NeoButtonTone.Primary,
            icon = NeoGlyph.Plus,
        )
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

/**
 * The decision a key change actually needs: both fingerprints side by side, and a warning that
 * accepting means the old machine is gone or someone is in the middle. The card only offers this
 * when the repository reported a real mismatch, so it cannot be used to pin an unverified key.
 */
@Composable
private fun HostKeyDialog(
    label: String,
    mismatch: dev.herdwatch.model.KeyMismatch?,
    pending: dev.herdwatch.model.PendingHostKey?,
    onDismiss: () -> Unit,
    onTrust: () -> Unit,
) {
    val colors = ds()
    val changed = mismatch != null
    NeoDialog(
        onDismissRequest = onDismiss,
        title = stringResource(
            if (changed) R.string.hosts_key_changed_title else R.string.hosts_key_first_title,
            label,
        ),
    ) {
        Text(
            stringResource(if (changed) R.string.hosts_key_changed_body else R.string.hosts_key_first_body),
            style = DsTypography.body,
            color = colors.textSoft,
        )
        if (mismatch != null) {
            KeyFingerprint(stringResource(R.string.hosts_key_expected), mismatch.expected)
            KeyFingerprint(stringResource(R.string.hosts_key_actual), mismatch.actual)
        }
        pending?.let {
            val type = dev.herdwatch.ssh.hostKeyLabel(it.keyType)
            KeyFingerprint(stringResource(R.string.hosts_key_first_label, type), it.fingerprint)
        }
        Text(
            stringResource(if (changed) R.string.hosts_key_changed_warning else R.string.hosts_key_first_warning),
            style = DsTypography.caption,
            color = if (changed) colors.danger else colors.textSoft,
        )
        // Stacked, decision first: side by side the destructive label was cut to "Machine
        // changed, tr…", which is exactly the text a person must read before tapping.
        Column(
            Modifier.fillMaxWidth().padding(top = DsSpace.xs, bottom = NeoShadowBleed),
            verticalArrangement = Arrangement.spacedBy(NeoShadowBleed + DsSpace.xs),
        ) {
            NeoButton(
                label = stringResource(if (changed) R.string.hosts_key_trust else R.string.hosts_key_first_trust),
                onClick = onTrust,
                tone = if (changed) NeoButtonTone.Danger else NeoButtonTone.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            NeoButton(
                label = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                tone = NeoButtonTone.Ghost,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun KeyFingerprint(caption: String, value: String) {
    val colors = ds()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(caption, style = DsTypography.micro, color = colors.textSoft)
        NeoWell(fill = colors.well, depth = DsElev.flat, corner = DsRadius.control) {
            SelectionContainer {
                Text(
                    value,
                    style = DsTypography.mono,
                    color = colors.text,
                    modifier = Modifier.padding(horizontal = DsSpace.m, vertical = DsSpace.s),
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostCard(
    runtime: HostRuntime,
    modifier: Modifier = Modifier,
    onTerminal: () -> Unit,
    onManage: () -> Unit,
    onReconnect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTrustNewKey: () -> Unit,
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
    val lampColor = connStatusColor(conn)
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
                        contentDescription = stringResource(R.string.hosts_manage_a11y, host.label),
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
            // With a fallback address the primary one above is only half the story: say which
            // route the live session took, so "home goes over Wi-Fi" is verifiable, not assumed.
            val route = runtime.activeAddress
            if (host.hasFallback && route != null) {
                // Compare the rendered form: `HostEndpoint` drops `:22`, so "host" and
                // "host:22" are the same route and must not be reported as the fallback.
                val primary = host.endpoints.firstOrNull()?.toString()
                Text(
                    stringResource(
                        if (route == primary) R.string.hosts_route_primary
                        else R.string.hosts_route_fallback,
                        route,
                    ),
                    style = DsTypography.caption,
                    color = colors.textFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
                        // "see Details" hid the one fact the user needs: which failure it was.
                        // The message is already humanised by the repository, so show it here.
                        // A first connection is not a failure: no "connection failed" prefix, and
                        // the hint below already says what to do, so the line is the step itself.
                        is ConnState.Failed -> if (runtime.pendingKey != null) {
                            stringResource(R.string.hosts_key_first_hint)
                        } else {
                            stringResource(R.string.hosts_failed_reason, state.message)
                        }
                        ConnState.Idle -> stringResource(R.string.hosts_state_idle)
                    },
                    style = DsTypography.body,
                    // A first connection is a step, not an incident: amber, like the hint below it.
                    color = when {
                        runtime.pendingKey != null -> colors.warn
                        state is ConnState.Failed -> colors.danger
                        else -> colors.textSoft
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(DsSpace.xs))
            Text(
                stringResource(R.string.hosts_last_sync, formatAgo(runtime.lastSyncAt)),
                style = DsTypography.caption,
                color = colors.textFaint,
            )
            if (conn is ConnState.Failed && runtime.keyMismatch == null && runtime.pendingKey == null) {
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
            if (runtime.keyMismatch != null) {
                Text(
                    stringResource(R.string.hosts_key_changed_hint),
                    style = DsTypography.caption,
                    color = colors.danger,
                )
            }
            Spacer(Modifier.height(DsSpace.m))
            // Two actions, not five: the terminal is the thing you came for, and everything about
            // the machine's sessions — browsing, creating, renaming, closing — lives on one page.
            // "Check sessions" and "Manage" used to be two buttons leading to two views of the same
            // tree; the structure page is now reached from inside Manage.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(NeoShadowBleed),
            ) {
                if (runtime.keyMismatch != null) {
                    NeoButton(
                        label = stringResource(R.string.hosts_key_review),
                        onClick = onTrustNewKey,
                        tone = NeoButtonTone.Danger,
                        icon = NeoGlyph.Key,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                } else if (runtime.pendingKey != null) {
                    NeoButton(
                        label = stringResource(R.string.hosts_key_confirm),
                        onClick = onTrustNewKey,
                        tone = NeoButtonTone.Primary,
                        icon = NeoGlyph.Fingerprint,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    NeoButton(
                        label = stringResource(R.string.hosts_open_terminal),
                        onClick = onTerminal,
                        tone = NeoButtonTone.Primary,
                        icon = NeoGlyph.Terminal,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    // Ghost, not Quiet: Quiet paints a plate the same size as the primary button,
                    // so the row read as two equal choices with a stray chevron beside them. The
                    // sessions page is the secondary door and should look like one.
                    NeoButton(
                        label = stringResource(R.string.hosts_sessions),
                        onClick = onManage,
                        tone = NeoButtonTone.Ghost,
                        icon = NeoGlyph.Workspace,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                NeoIconButton(
                    glyph = if (details) NeoGlyph.ChevronDown else NeoGlyph.ChevronRight,
                    onClick = { details = !details },
                    contentDescription = stringResource(
                        if (details) R.string.common_collapse else R.string.hosts_details,
                    ),
                )
            }
            ExpandableSection(details) {
                Column(verticalArrangement = Arrangement.spacedBy(DsSpace.s), modifier = Modifier.padding(top = DsSpace.m)) {
                    NeoDivider()
                    // The raw transport words live here, not on the card: this is the panel a
                    // person opens when the one-line summary is not enough to act on.
                    (conn as? ConnState.Failed)?.technical?.takeIf { it.isNotBlank() && it != conn.message }?.let { raw ->
                        SelectionContainer {
                            Text(
                                raw,
                                style = DsTypography.mono,
                                color = colors.danger,
                            )
                        }
                    }
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
                                            if (conn is ConnState.Failed) {
                                                appendLine(conn.message)
                                                conn.technical?.takeIf { it != conn.message }?.let(::append)
                                            }
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
