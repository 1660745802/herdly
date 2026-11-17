package dev.herdwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.theme.MonoStyle

@Composable
fun SettingsScreen(hosts: List<HostRuntime>) {
    val clipboard = LocalClipboardManager.current
    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        Text("设置", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 12.dp))
        Text("连接、隐私与使用帮助", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Column(
            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp)).padding(16.dp),
        ) {
            Text("仅在前台连接", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "离开 App 即断开，不会在后台监控或发送通知。回来后先显示上次快照，再尝试同步。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Text("${hosts.count { it.conn is ConnState.Connected }} / ${hosts.size} 台机器已连接", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(14.dp))
        SettingsDetails("连接与安全", "SSH 直连 · 凭据仅在本机") {
            SettingsRow("连接", "前台定期尝试刷新，单次同步可能因网络或主机状态延迟。SSH 直连，一请求一通道。")
            SettingsRow("凭据", "凭据在本机加密存储，密钥由 Android Keystore 保护。")
            SettingsRow("主机指纹", "首次连接自动记录 SHA-256 指纹；已保存的指纹不匹配时停止连接。可在机器的连接详情中查看和复制指纹。")
            SettingsRow("隐私", "无遥测、无中继、无云端账号。连接诊断只在你主动复制时进入剪贴板，请按需分享。")
        }
        Spacer(Modifier.height(14.dp))
        SettingsDetails("主机准备与诊断", "检查 Herdr、SSH 路径与 session") {
            SettingsRow("主机准备", "安装 Herdr 0.9+，确保 SSH 登录后可以执行 herdr。")
            SelectionContainer {
                Text(
                    "herdr remote-api-bridge --check\n预期输出：herdr-api-bridge-v1",
                    style = MonoStyle,
                    modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp)).padding(12.dp),
                )
            }
            TextButton(onClick = { clipboard.setText(AnnotatedString("herdr remote-api-bridge --check")) }) { Text("复制检查命令") }
            SettingsRow("找不到命令或会话", "非交互 SSH 的 PATH 可能不同。在添加或编辑机器的高级设置中填写 herdr 绝对路径；使用非默认 session 时填写 session 名。")
            SettingsRow("连接失败", "先检查网络、地址与 SSH 凭据，再检查 Herdr。机器的连接详情可复制诊断，不包含保存的密码或私钥。")
        }
        Spacer(Modifier.height(14.dp))
        SettingsDetails("关于 Herdly", "版本与操作边界") {
            SettingsRow("版本", "Herdly 0.2.0 · Herdr protocol 22")
            SettingsRow("用途", "发现需要介入的 agent、阅读终端证据、发送操作。手机端不提供文件管理、端口转发或完整终端。")
            SettingsRow("操作反馈", "远端接受请求不代表 agent 已执行完成。以重新读取的终端和后续快照核实；结果不确定时先检查，避免重复发送。")
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SettingsDetails(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { expanded = !expanded }
                .semantics { stateDescription = if (expanded) "已展开" else "已收起" }
                .heightIn(min = 64.dp).padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        if (expanded) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) { content() }
    }
}

@Composable
private fun SettingsRow(title: String, body: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
