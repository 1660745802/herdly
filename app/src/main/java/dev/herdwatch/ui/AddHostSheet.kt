package dev.herdwatch.ui

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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.AuthType
import dev.herdwatch.model.HostConfig
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoChip
import dev.herdwatch.ui.design.NeoField
import dev.herdwatch.ui.design.NeoSheet
import dev.herdwatch.ui.design.NeoSkeleton
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds

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
    // Hoisted out of the semantics block: that lambda is not composable.
    val expandedLabel = stringResource(R.string.addhost_expanded)
    val collapsedLabel = stringResource(R.string.addhost_collapsed)
    val colors = ds()

    // NeoSheet 用 ds().base 作底（与页面同一块底盘），而不是 Material 的 colorScheme.background。
    NeoSheet(
        onDismissRequest = { if (!adding) onDismiss() },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
        ) {
            Text(
                if (initial == null) stringResource(R.string.addhost_title_add) else stringResource(R.string.addhost_title_edit),
                style = DsTypography.title,
                color = colors.text,
            )
            Spacer(Modifier.height(DsSpace.xs))
            Text(
                stringResource(R.string.addhost_subtitle),
                style = DsTypography.body,
                color = colors.textSoft,
            )
            Spacer(Modifier.height(DsSpace.l))

            NeoField(
                value = label,
                onValueChange = { label = it },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                label = stringResource(R.string.addhost_label_optional),
                hint = "build-01",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeoField(
                    value = host,
                    onValueChange = { host = it },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    label = stringResource(R.string.addhost_host),
                    hint = stringResource(R.string.addhost_host_placeholder),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                NeoField(
                    value = port,
                    onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    label = stringResource(R.string.addhost_port),
                    singleLine = true,
                    modifier = Modifier.width(92.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            NeoField(
                value = user,
                onValueChange = { user = it },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                label = stringResource(R.string.addhost_user),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                NeoChip(
                    label = stringResource(R.string.addhost_password),
                    selected = authType == AuthType.PASSWORD,
                    onClick = { authType = AuthType.PASSWORD },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                NeoChip(
                    label = stringResource(R.string.addhost_key),
                    selected = authType == AuthType.KEY,
                    onClick = { authType = AuthType.KEY },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            Spacer(Modifier.height(DsSpace.s))
            if (authType == AuthType.PASSWORD) {
                NeoField(
                    value = password,
                    onValueChange = { password = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    label = stringResource(R.string.addhost_password),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                // NeoField 没有 maxLines：私钥需要 minLines=3 / maxLines=6 的内部滚动，
                // 所以这里在调用点组合 NeoWell + 去边框的 OutlinedTextField（颜色/字体/圆角仍走 ds()）。
                Column(verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    Text(
                        stringResource(R.string.addhost_key_full),
                        style = DsTypography.caption,
                        color = colors.textSoft,
                    )
                    NeoWell(corner = DsRadius.control) {
                        OutlinedTextField(
                            value = privateKey,
                            onValueChange = { privateKey = it },
                            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                            placeholder = {
                                Text(
                                    "-----BEGIN OPENSSH PRIVATE KEY-----",
                                    style = DsTypography.body,
                                    color = colors.textFaint,
                                )
                            },
                            minLines = 3,
                            maxLines = 6,
                            textStyle = DsTypography.body.copy(color = colors.text),
                            shape = RoundedCornerShape(DsRadius.control),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent,
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent,
                                cursorColor = colors.accent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                Spacer(Modifier.height(DsSpace.s))
                NeoField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    label = stringResource(R.string.addhost_passphrase_optional),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(10.dp))
            NeoButton(
                label = if (advanced) stringResource(R.string.addhost_advanced_hide) else stringResource(R.string.addhost_advanced_show),
                onClick = { advanced = !advanced },
                tone = NeoButtonTone.Ghost,
                compact = true,
                modifier = Modifier.semantics { stateDescription = if (advanced) expandedLabel else collapsedLabel },
            )
            if (advanced) {
                NeoField(
                    value = session,
                    onValueChange = { session = it },
                    label = stringResource(R.string.addhost_session_optional),
                    hint = stringResource(R.string.addhost_session_placeholder),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                NeoField(
                    value = herdrCommand,
                    onValueChange = { herdrCommand = it },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    label = stringResource(R.string.addhost_herdr_command),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                // NeoField 没有 supportingText：说明文字紧贴字段下方，样式与字段标签同一 token。
                Spacer(Modifier.height(DsSpace.xs))
                Text(
                    stringResource(R.string.addhost_herdr_command_hint),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
            }

            validation?.let {
                Spacer(Modifier.height(DsSpace.s))
                Text(it, color = colors.danger, style = DsTypography.caption)
            }

            Spacer(Modifier.height(DsSpace.l))
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val stackedActions = maxWidth < 420.dp
                if (stackedActions) {
                    Column(verticalArrangement = Arrangement.spacedBy(DsSpace.m)) {
                        Text(
                            stringResource(R.string.addhost_fingerprint_note),
                            style = DsTypography.caption,
                            color = colors.textSoft,
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
                            stringResource(R.string.addhost_fingerprint_note),
                            style = DsTypography.caption,
                            color = colors.textSoft,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(DsSpace.l))
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
            Spacer(Modifier.height(DsSpace.s))
            Text(
                stringResource(R.string.addhost_check_hint),
                style = DsTypography.caption,
                color = colors.textSoft,
            )
        }
    }
}

@Composable
private fun SaveHostButton(adding: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    // NeoButton 没有内容插槽，忙碌指示只能放在按钮左侧；modifier 仍落在按钮上以保持原有宽度行为。
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DsSpace.s),
    ) {
        if (adding) {
            NeoSkeleton(Modifier.width(14.dp), height = 14.dp, corner = 7.dp)
        }
        NeoButton(
            label = stringResource(if (adding) R.string.addhost_saving else R.string.common_save),
            onClick = onClick,
            tone = NeoButtonTone.Primary,
            enabled = !adding,
            modifier = modifier.heightIn(min = 48.dp),
        )
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
    host.isBlank() -> Str.get(R.string.addhost_err_host_required)
    user.isBlank() -> Str.get(R.string.addhost_err_user_required)
    secret.isBlank() -> if (authType == AuthType.PASSWORD) Str.get(R.string.addhost_err_password_required) else Str.get(R.string.addhost_err_key_required)
    (port.toIntOrNull() ?: 0) !in 1..65535 -> Str.get(R.string.addhost_err_port_range)
    herdrCommand.isBlank() -> Str.get(R.string.addhost_err_herdr_required)
    else -> null
}
