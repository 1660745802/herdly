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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdwatch.model.Agent
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.theme.statusPalette

@OptIn(ExperimentalMaterial3Api::class)
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
            Text("新任务", style = MaterialTheme.typography.headlineSmall)
            Text(
                "在已连接的机器上创建新标签页并启动 agent。提交后请查看会话确认结果。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(14.dp))

            Text("机器", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(hosts, key = { it.host.id }) { h ->
                    val ok = h.conn is ConnState.Connected
                    FilterChip(
                        selected = h.host.id == hostId,
                        enabled = ok,
                        onClick = { hostId = h.host.id },
                        modifier = Modifier.heightIn(min = 48.dp),
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier
                                        .size(6.dp)
                                        .background(if (ok) p.review else p.idle, CircleShape),
                                )
                                Spacer(Modifier.width(5.dp))
                                Text("${h.host.label} · ${if (ok) "已连接" else "未连接"}")
                            }
                        },
                    )
                }
            }
            if (connected.isEmpty()) {
                Text("没有已连接的机器。返回「机器」添加配置或重试连接后，再创建任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text("项目", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                item {
                    FilterChip(
                        selected = workspaceId == null,
                        enabled = host != null,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { workspaceId = null },
                        label = { Text("＋ 新工作区") },
                    )
                }
                items(host?.structure?.workspaces.orEmpty(), key = { it.id }) { w ->
                    FilterChip(
                        selected = workspaceId == w.id,
                        modifier = Modifier.heightIn(min = 48.dp),
                        onClick = { workspaceId = w.id },
                        label = { Text(w.label) },
                    )
                }
            }
            if (workspaceId != null) {
                Text(
                    "目标：${host?.host?.label ?: "机器未连接"} / ${workspace?.label ?: "工作区已不可用，请重新选择"}\n目录：${workspace?.worktreePath ?: "由主机工作区决定"}\n将在此工作区新建标签页。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    "目标：${host?.host?.label ?: "请选择已连接的机器"} / 新工作区 / 新标签页\n工作目录由主机默认设置决定。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))

            Text("Agent", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                items(dev.herdwatch.domain.AgentKinds.common) { k ->
                    FilterChip(selected = kind == k, onClick = { applyKind(k) }, label = { Text(k) }, modifier = Modifier.heightIn(min = 48.dp))
                }
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Agent 名称") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                isError = duplicateName || name.isBlank(),
                supportingText = { Text(if (duplicateName) "此主机上已有同名 agent 或分屏，请换一个名字" else "使用主机内唯一的名称") },
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
                    val target = hosts.firstOrNull { it.host.id == hostId && it.conn is ConnState.Connected } ?: return@Button
                    if (name.isBlank() || duplicateName || (workspaceId != null && target.structure.workspaces.none { it.id == workspaceId })) return@Button
                    onStart(target.host.id, workspaceId, kind, name.trim(), if (resume) argsText.lines().filter { it.isNotBlank() } else emptyList())
                },
                enabled = validTarget && name.isNotBlank() && !duplicateName,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            ) { Text("创建并启动 ${name.trim().ifBlank { kind }}") }
        }
    }
}
