package dev.herdwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.model.HostConfig
import dev.herdwatch.ui.theme.MonoStyle
import dev.herdwatch.ui.theme.statusPalette

@Composable
fun HostsScreen(
    hosts: List<HostRuntime>,
    onAdd: () -> Unit,
    onReconnect: (String) -> Unit,
    onEdit: (HostConfig) -> Unit,
    onDelete: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<HostConfig?>(null) }
    val configuration = LocalConfiguration.current
    val compact = configuration.screenWidthDp < 360 || configuration.fontScale >= 1.3f
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("机器", style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium)
                Text(
                    if (compact) "${hosts.count { it.conn is ConnState.Connected }}/${hosts.size} 台在线"
                    else "${hosts.size} 台机器 · ${hosts.count { it.conn is ConnState.Connected }} 台已连接",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onAdd, shape = RoundedCornerShape(10.dp)) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("添加")
            }
        }

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 100.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (hosts.isEmpty()) {
                item {
                    EmptyBlock(
                        title = "添加一台 Herdr 主机",
                        body = "只需要 SSH 可达，并且主机上能运行 herdr remote-api-bridge。不需要安装任何手机端守护进程。",
                    ) {
                        Button(onClick = onAdd, shape = RoundedCornerShape(10.dp)) { Text("添加机器") }
                    }
                }
                return@LazyColumn
            }
            items(hosts, key = { it.host.id }) { runtime ->
                HostCard(
                    runtime,
                    onOpen = { onOpen(runtime.host.id) },
                    onReconnect = { onReconnect(runtime.host.id) },
                    onEdit = { onEdit(runtime.host) },
                    onDelete = { pendingDelete = runtime.host },
                )
            }
            item {
                Text(
                    "没有通知、没有后台常驻：离开 App 就断开，回来先显示上次快照，再刷新。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                )
            }
        }
    }

    pendingDelete?.let { host ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("移除 ${host.label}？") },
            text = { Text("只会删除手机上的连接配置，不会影响主机或正在运行的 agent。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(host.id)
                    pendingDelete = null
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HostCard(runtime: HostRuntime, onOpen: () -> Unit, onReconnect: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val host = runtime.host
    val p = statusPalette()
    val conn = runtime.conn
    var details by rememberSaveable(host.id) { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val fingerprint = host.fingerprint ?: runtime.fingerprint
    val color = when (conn) {
        is ConnState.Connected -> p.review
        ConnState.Connecting -> p.working
        is ConnState.Failed -> MaterialTheme.colorScheme.error
        ConnState.Idle -> MaterialTheme.colorScheme.outline
    }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.width(9.dp))
            Text(host.label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "编辑 ${host.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "移除 ${host.label}", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("${host.user}@${host.host}:${host.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (conn) {
                    is ConnState.Connected -> "已连接 · ${runtime.agentCount} 个 agent"
                    ConnState.Connecting -> "连接中…"
                    is ConnState.Failed -> "连接失败：${conn.message}"
                    ConnState.Idle -> "未连接"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (conn is ConnState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "最后成功同步：${formatAgo(runtime.lastSyncAt)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (conn is ConnState.Failed) {
            Text(
                "检查网络与 SSH 凭据；若 SSH 正常，检查主机上的 Herdr 命令。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (conn is ConnState.Connected) {
                OutlinedButton(onClick = onOpen, shape = RoundedCornerShape(9.dp)) { Text("检查会话", softWrap = false) }
            } else {
                OutlinedButton(onClick = onReconnect, enabled = conn != ConnState.Connecting, shape = RoundedCornerShape(9.dp)) {
                    Text(if (conn == ConnState.Connecting) "连接中" else "连接机器", softWrap = false)
                }
            }
            TextButton(
                onClick = { details = !details },
                modifier = Modifier.semantics { stateDescription = if (details) "已展开" else "已收起" },
            ) { Text(if (details) "收起详情" else "连接详情", softWrap = false) }
        }
        if (details) {
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Text("${host.user}@${host.host}:${host.port}", style = MonoStyle)
                    Text("Session：${host.session.ifBlank { "默认" }}\nHerdr：${host.herdrCommand}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${if (host.fingerprint != null) "已保存的主机指纹" else "本次连接的主机指纹"}\n${fingerprint ?: "尚无指纹；连接后可查看"}",
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(buildString {
                    appendLine("${host.label}: ${host.user}@${host.host}:${host.port}")
                    appendLine("session=${host.session.ifBlank { "默认" }}; herdr=${host.herdrCommand}")
                    appendLine("fingerprint=${fingerprint ?: "尚无"}")
                    appendLine("lastSync=${runtime.lastSyncAt ?: "尚无"}")
                    if (conn is ConnState.Failed) append(conn.message)
                }))
            }) { Text("复制连接诊断") }
            if (conn is ConnState.Connected) TextButton(onClick = onReconnect) { Text("重新连接") }
        }
    }
}
