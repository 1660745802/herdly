package dev.herdwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.herdwatch.model.AuthType
import dev.herdwatch.model.HostConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddHostSheet(
    adding: Boolean,
    initial: HostConfig? = null,
    onDismiss: () -> Unit,
    onSave: (
        id: String?,
        label: String,
        host: String,
        port: String,
        user: String,
        authType: AuthType,
        secret: String,
        passphrase: String,
        session: String,
        herdrCommand: String,
    ) -> Unit,
) {
    var label by remember(initial) { mutableStateOf(initial?.label ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by remember(initial) { mutableStateOf(initial?.user ?: (System.getProperty("user.name") ?: "")) }
    var authType by remember(initial) { mutableStateOf(initial?.authType ?: AuthType.PASSWORD) }
    // Keep credentials in memory only; switching authentication must not reuse the other draft.
    var password by remember(initial) { mutableStateOf(initial?.secret?.takeIf { initial.authType == AuthType.PASSWORD } ?: "") }
    var privateKey by remember(initial) { mutableStateOf(initial?.secret?.takeIf { initial.authType == AuthType.KEY } ?: "") }
    val secret = if (authType == AuthType.PASSWORD) password else privateKey
    var passphrase by remember(initial) { mutableStateOf(initial?.passphrase ?: "") }
    var session by remember(initial) { mutableStateOf(initial?.session ?: "") }
    var herdrCommand by remember(initial) { mutableStateOf(initial?.herdrCommand ?: "herdr") }
    var advanced by rememberSaveable(initial?.id) { mutableStateOf(initial?.session?.isNotBlank() == true || (initial != null && initial.herdrCommand != "herdr")) }
    var validation by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!adding) onDismiss() },
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(if (initial == null) "添加机器" else "编辑机器", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            Text(
                "SSH 直连，凭据在本机加密保存。保存配置后尝试连接，连接结果请在机器列表中查看。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                label = { Text("名字（可选）") },
                placeholder = { Text("build-01") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    label = { Text("主机") },
                    placeholder = { Text("192.168.1.10 或 build.example.com") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    label = { Text("端口") },
                    singleLine = true,
                    modifier = Modifier.width(92.dp),
                    shape = RoundedCornerShape(10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = user,
                onValueChange = { user = it },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                label = { Text("用户名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = authType == AuthType.PASSWORD,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { authType = AuthType.PASSWORD },
                    label = { Text("密码") },
                    shape = RoundedCornerShape(8.dp),
                )
                FilterChip(
                    selected = authType == AuthType.KEY,
                    modifier = Modifier.heightIn(min = 48.dp),
                    onClick = { authType = AuthType.KEY },
                    label = { Text("私钥") },
                    shape = RoundedCornerShape(8.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            if (authType == AuthType.PASSWORD) {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    label = { Text("密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
            } else {
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    label = { Text("私钥（OpenSSH / PEM 全文）") },
                    placeholder = { Text("-----BEGIN OPENSSH PRIVATE KEY-----") },
                    minLines = 3,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    label = { Text("私钥口令（可空）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            TextButton(
                onClick = { advanced = !advanced },
                modifier = Modifier.semantics { stateDescription = if (advanced) "已展开" else "已收起" },
            ) { Text(if (advanced) "收起高级设置" else "高级：session 与命令路径") }
            if (advanced) {
                OutlinedTextField(
                    value = session,
                    onValueChange = { session = it },
                    label = { Text("Herdr session（可空）") },
                    placeholder = { Text("默认 session") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = herdrCommand,
                    onValueChange = { herdrCommand = it },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    label = { Text("herdr 命令") },
                    supportingText = { Text("非交互 SSH 的 PATH 可能很短；可填绝对路径") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp),
                )
            }

            validation?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stackedActions = maxWidth < 420.dp
                if (stackedActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            "首次连接自动记录指纹；已保存的指纹变化时停止连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        SaveHostButton(
                            adding = adding,
                            modifier = Modifier.fillMaxWidth(),
                            onClick = {
                                validation = validateHost(host, user, secret, authType, port, herdrCommand)
                                if (validation == null) {
                                    onSave(initial?.id, label.trim(), host.trim(), port, user.trim(), authType, secret, if (authType == AuthType.KEY) passphrase else "", session.trim(), herdrCommand.trim())
                                }
                            },
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "首次连接自动记录指纹；已保存的指纹变化时停止连接。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(16.dp))
                        SaveHostButton(
                            adding = adding,
                            modifier = Modifier.widthIn(min = 112.dp),
                            onClick = {
                                validation = validateHost(host, user, secret, authType, port, herdrCommand)
                                if (validation == null) {
                                    onSave(initial?.id, label.trim(), host.trim(), port, user.trim(), authType, secret, if (authType == AuthType.KEY) passphrase else "", session.trim(), herdrCommand.trim())
                                }
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "提示：主机上先运行 herdr remote-api-bridge --check，确认输出 herdr-api-bridge-v1。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SaveHostButton(adding: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = !adding,
        shape = RoundedCornerShape(10.dp),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (adding) {
            CircularProgressIndicator(Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("保存中…")
        } else {
            Text("保存配置")
        }
    }
}

private fun validateHost(
    host: String,
    user: String,
    secret: String,
    authType: AuthType,
    port: String,
    herdrCommand: String,
): String? = when {
    host.isBlank() -> "主机不能为空"
    user.isBlank() -> "用户名不能为空"
    secret.isBlank() -> if (authType == AuthType.PASSWORD) "密码不能为空" else "私钥不能为空"
    (port.toIntOrNull() ?: 0) !in 1..65535 -> "端口须为 1–65535"
    herdrCommand.isBlank() -> "请填写 herdr 命令或绝对路径"
    else -> null
}
