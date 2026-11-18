package dev.herdwatch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.model.Agent
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoChip
import dev.herdwatch.ui.design.NeoField
import dev.herdwatch.ui.design.NeoSheet
import dev.herdwatch.ui.design.NeoToggle
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.statusPalette

@Composable
fun NewTaskSheet(
    hosts: List<HostRuntime>,
    initialHostId: String?,
    onDismiss: () -> Unit,
    onStart: (hostId: String, workspaceId: String?, kind: String, name: String, args: List<String>) -> Unit,
    currentAgents: List<Agent> = emptyList(),
) {
    val connected = hosts.filter { it.conn is ConnState.Connected }
    var hostId by rememberSaveable(initialHostId) {
        mutableStateOf(initialHostId?.takeIf { id -> connected.any { it.host.id == id } } ?: connected.firstOrNull()?.host?.id)
    }
    val host = connected.firstOrNull { it.host.id == hostId }
    var workspaceId by rememberSaveable(hostId) { mutableStateOf<String?>(null) }
    val workspace = host?.structure?.workspaces?.firstOrNull { it.id == workspaceId }
    val validTarget = host != null && (workspaceId == null || workspace != null)
    val usedNames = currentAgents.filter { it.hostId == hostId }.map { it.name }.toSet() + host?.structure?.panes.orEmpty().map { it.title }
    fun suggestedName(agentKind: String): String {
        if (agentKind !in usedNames) return agentKind
        var suffix = 2
        while ("$agentKind-$suffix" in usedNames) suffix++
        return "$agentKind-$suffix"
    }
    var kind by rememberSaveable { mutableStateOf("claude") }
    var name by rememberSaveable(hostId) { mutableStateOf(suggestedName(kind)) }
    val duplicateName = name.trim() in usedNames
    var resume by rememberSaveable { mutableStateOf(false) }
    var argsText by rememberSaveable { mutableStateOf("") }
    val p = statusPalette()
    val resumeLabel = stringResource(R.string.newtask_resume_label)
    val colors = ds()

    fun resumeArgs(k: String) = AppViewModel.resumeArgs(k)
    fun applyKind(next: String) {
        kind = next
        name = suggestedName(next)
        if (resume) argsText = resumeArgs(next).joinToString("\n")
    }
    fun applyResume(on: Boolean) {
        resume = on
        argsText = if (on) resumeArgs(kind).joinToString("\n") else ""
    }

    // NeoSheet 用 ds().base 作底，和页面共用一块底盘（Material surface 不是这个颜色）。
    NeoSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(stringResource(R.string.newtask_title), style = DsTypography.title, color = colors.text)
            Text(
                stringResource(R.string.newtask_body),
                style = DsTypography.caption,
                color = colors.textSoft,
            )
            Spacer(Modifier.height(14.dp))

            Text(stringResource(R.string.newtask_host_label), style = DsTypography.micro, color = colors.textSoft)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(hosts, key = { it.host.id }) { h ->
                    val ok = h.conn is ConnState.Connected
                    TaskChip(
                        label = stringResource(
                            R.string.newtask_host_chip,
                            h.host.label,
                            if (ok) stringResource(R.string.newtask_connected) else stringResource(R.string.newtask_disconnected),
                        ),
                        selected = h.host.id == hostId,
                        enabled = ok,
                        dot = if (ok) p.review else p.idle,
                        onClick = { hostId = h.host.id },
                    )
                }
            }
            if (connected.isEmpty()) {
                Text(stringResource(R.string.newtask_no_connected), style = DsTypography.caption, color = colors.textSoft)
            }

            Text(stringResource(R.string.newtask_project_label), style = DsTypography.micro, color = colors.textSoft)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                item {
                    TaskChip(
                        label = stringResource(R.string.newtask_new_workspace),
                        selected = workspaceId == null,
                        enabled = host != null,
                        onClick = { workspaceId = null },
                    )
                }
                items(host?.structure?.workspaces.orEmpty(), key = { it.id }) { w ->
                    NeoChip(
                        label = w.label,
                        selected = workspaceId == w.id,
                        onClick = { workspaceId = w.id },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            if (workspaceId != null) {
                Text(
                    stringResource(
                        R.string.newtask_target_existing,
                        host?.host?.label ?: stringResource(R.string.newtask_host_unconnected),
                        workspace?.label ?: stringResource(R.string.newtask_workspace_unavailable),
                        workspace?.worktreePath ?: stringResource(R.string.newtask_cwd_by_workspace),
                    ),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
            } else {
                Text(
                    stringResource(
                        R.string.newtask_target_new,
                        host?.host?.label ?: stringResource(R.string.newtask_select_host),
                    ),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
            }
            Spacer(Modifier.height(12.dp))

            Text("Agent", style = DsTypography.micro, color = colors.textSoft)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(dev.herdwatch.domain.AgentKinds.common) { k ->
                    NeoChip(
                        label = k,
                        selected = kind == k,
                        onClick = { applyKind(k) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            // NeoField 没有 isError/supportingText：支持文案放到字段下方，
            // 错误态用 danger 色 + liveRegion 播报，等价于 Material 的 error 语义。
            val nameError = duplicateName || name.isBlank()
            val nameSupport = if (duplicateName) stringResource(R.string.newtask_duplicate_name)
            else stringResource(R.string.newtask_name_hint)
            NeoField(
                value = name,
                onValueChange = { name = it },
                label = stringResource(R.string.newtask_name_label),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(DsSpace.xs))
            Text(
                nameSupport,
                style = DsTypography.caption,
                color = if (nameError) colors.danger else colors.textSoft,
                modifier = if (nameError) Modifier.semantics { liveRegion = LiveRegionMode.Polite } else Modifier,
            )
            Spacer(Modifier.height(DsSpace.s))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.newtask_resume_label), style = DsTypography.body, color = colors.text)
                    Text(
                        if (resume) stringResource(R.string.newtask_resume_on_hint) else stringResource(R.string.newtask_resume_off_hint),
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
                    label = stringResource(R.string.newtask_args_label),
                    singleLine = false,
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
                Spacer(Modifier.height(DsSpace.xs))
                Text(
                    stringResource(R.string.newtask_args_hint),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
            }
            Spacer(Modifier.height(14.dp))
            NeoButton(
                label = stringResource(R.string.newtask_start_action, name.trim().ifBlank { kind }),
                onClick = {
                    val target = hosts.firstOrNull { it.host.id == hostId && it.conn is ConnState.Connected } ?: return@NeoButton
                    if (name.isBlank() || duplicateName || (workspaceId != null && target.structure.workspaces.none { it.id == workspaceId })) return@NeoButton
                    onStart(target.host.id, workspaceId, kind, name.trim(), if (resume) argsText.lines().filter { it.isNotBlank() } else emptyList())
                },
                tone = NeoButtonTone.Primary,
                enabled = validTarget && name.isNotBlank() && !duplicateName,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * NeoChip 没有 `enabled` 参数：禁用态用降低的不透明度 + `disabled()` 语义 + onClick 门控表达，
 * 保持原来 FilterChip(enabled = ...) 的观感与行为。
 */
@Composable
private fun TaskChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
    dot: Color? = null,
) {
    NeoChip(
        label = label,
        selected = selected,
        dot = dot,
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .heightIn(min = 48.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .semantics { if (!enabled) disabled() },
    )
}
