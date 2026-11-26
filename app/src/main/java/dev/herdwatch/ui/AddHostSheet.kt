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
import androidx.compose.ui.text.input.VisualTransformation
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
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoSheet
import dev.herdwatch.ui.design.NeoSkeleton
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds

@Composable
fun AddHostSheet(
    adding: Boolean,
    initial: HostConfig? = null,
    storeError: String? = null,
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
        fallbackHost: String,
        fallbackPort: String,
    ) -> Unit,
) {
    var label by remember(initial) { mutableStateOf(initial?.label ?: "") }
    var host by remember(initial) { mutableStateOf(initial?.host ?: "") }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 22).toString()) }
    // No prefill: System.getProperty("user.name") on Android is the process uid (e.g. u0_a123),
    // never a valid SSH login. An empty field with a hint beats a wrong default the user keeps.
    var user by remember(initial) { mutableStateOf(initial?.user ?: "") }
    var authType by remember(initial) { mutableStateOf(initial?.authType ?: AuthType.PASSWORD) }
    // Keep credentials in memory only; switching authentication must not reuse the other draft.
    var password by remember(initial) { mutableStateOf(initial?.secret?.takeIf { initial.authType == AuthType.PASSWORD } ?: "") }
    var privateKey by remember(initial) { mutableStateOf(initial?.secret?.takeIf { initial.authType == AuthType.KEY } ?: "") }
    val secret = if (authType == AuthType.PASSWORD) password else privateKey
    var passphrase by remember(initial) { mutableStateOf(initial?.passphrase ?: "") }
    // Reveal is transient UI state, never persisted; secrets default to hidden.
    var passwordVisible by remember(initial) { mutableStateOf(false) }
    var passphraseVisible by remember(initial) { mutableStateOf(false) }
    // Second address for the same machine: LAN at home, public elsewhere. Credentials, pin and
    // everything else stay shared, so this is one host with two routes rather than two hosts.
    var fallbackHost by remember(initial) { mutableStateOf(initial?.fallbackHost ?: "") }
    var fallbackPort by remember(initial) { mutableStateOf(initial?.fallbackPort?.takeIf { it > 0 }?.toString() ?: "") }
    var session by remember(initial) { mutableStateOf(initial?.session ?: "") }
    var herdrCommand by remember(initial) { mutableStateOf(initial?.herdrCommand ?: "herdr") }
    var advanced by rememberSaveable(initial?.id) {
        mutableStateOf(
            initial?.session?.isNotBlank() == true ||
                (initial != null && initial.herdrCommand != "herdr") ||
                initial?.hasFallback == true,
        )
    }
    // The error carries the field it belongs to, so it renders under that field instead of one
    // unattributed caption above the save button.
    var validation by remember { mutableStateOf<Pair<HostField, String>?>(null) }
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
            // Without a readable credential store every save will fail; say it before the user
            // fills the form, not only as a snackbar that the sheet covers.
            if (!storeError.isNullOrBlank()) {
                Spacer(Modifier.height(DsSpace.m))
                Text(storeError, style = DsTypography.body, color = colors.danger)
            }
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
                    onValueChange = {
                        host = it
                        if (validation?.first == HostField.HOST || validation?.first == HostField.FALLBACK_SAME) validation = null
                    },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    label = stringResource(R.string.addhost_host),
                    hint = stringResource(R.string.addhost_host_placeholder),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                NeoField(
                    value = port,
                    onValueChange = {
                        port = it.filter { c -> c.isDigit() }.take(5)
                        if (validation?.first == HostField.PORT) validation = null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                    label = stringResource(R.string.addhost_port),
                    singleLine = true,
                    modifier = Modifier.width(92.dp),
                )
            }
            FieldError(validation, HostField.HOST, HostField.PORT)
            Spacer(Modifier.height(10.dp))
            NeoField(
                value = user,
                onValueChange = {
                    user = it
                    if (validation?.first == HostField.USER) validation = null
                },
                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Next),
                label = stringResource(R.string.addhost_user),
                hint = stringResource(R.string.addhost_user_placeholder),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FieldError(validation, HostField.USER)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                NeoChip(
                    label = stringResource(R.string.addhost_password),
                    selected = authType == AuthType.PASSWORD,
                    onClick = {
                        authType = AuthType.PASSWORD
                        if (validation?.first == HostField.SECRET) validation = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                NeoChip(
                    label = stringResource(R.string.addhost_key),
                    selected = authType == AuthType.KEY,
                    onClick = {
                        authType = AuthType.KEY
                        if (validation?.first == HostField.SECRET) validation = null
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
            Spacer(Modifier.height(DsSpace.s))
            if (authType == AuthType.PASSWORD) {
                NeoField(
                    value = password,
                    onValueChange = {
                        password = it
                        if (validation?.first == HostField.SECRET) validation = null
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    label = stringResource(R.string.addhost_password),
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        NeoIconButton(
                            glyph = if (passwordVisible) NeoGlyph.EyeOff else NeoGlyph.Eye,
                            onClick = { passwordVisible = !passwordVisible },
                            contentDescription = stringResource(
                                if (passwordVisible) R.string.addhost_cd_hide_password else R.string.addhost_cd_show_password,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                FieldError(validation, HostField.SECRET)
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
                            onValueChange = {
                                privateKey = it
                                if (validation?.first == HostField.SECRET) validation = null
                            },
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
                    FieldError(validation, HostField.SECRET)
                }
                Spacer(Modifier.height(DsSpace.s))
                NeoField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    label = stringResource(R.string.addhost_passphrase_optional),
                    singleLine = true,
                    visualTransformation = if (passphraseVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailing = {
                        NeoIconButton(
                            glyph = if (passphraseVisible) NeoGlyph.EyeOff else NeoGlyph.Eye,
                            onClick = { passphraseVisible = !passphraseVisible },
                            contentDescription = stringResource(
                                if (passphraseVisible) R.string.addhost_cd_hide_passphrase else R.string.addhost_cd_show_passphrase,
                            ),
                        )
                    },
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
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NeoField(
                        value = fallbackHost,
                        onValueChange = {
                            fallbackHost = it
                            if (validation?.first == HostField.FALLBACK_SAME) validation = null
                        },
                        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        label = stringResource(R.string.addhost_fallback_host_optional),
                        hint = stringResource(R.string.addhost_fallback_host_placeholder),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    NeoField(
                        value = fallbackPort,
                        onValueChange = {
                            fallbackPort = it.filter { c -> c.isDigit() }.take(5)
                            if (validation?.first == HostField.FALLBACK_PORT || validation?.first == HostField.FALLBACK_SAME) validation = null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Next),
                        label = stringResource(R.string.addhost_port),
                        hint = port,
                        singleLine = true,
                        modifier = Modifier.width(92.dp),
                    )
                }
                FieldError(validation, HostField.FALLBACK_PORT, HostField.FALLBACK_SAME)
                Spacer(Modifier.height(DsSpace.xs))
                Text(
                    stringResource(R.string.addhost_fallback_hint),
                    style = DsTypography.caption,
                    color = colors.textSoft,
                )
                Spacer(Modifier.height(10.dp))
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
                    onValueChange = {
                        herdrCommand = it
                        if (validation?.first == HostField.HERDR_COMMAND) validation = null
                    },
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
                FieldError(validation, HostField.HERDR_COMMAND)
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
                                validation = validateHost(host, user, secret, authType, port, herdrCommand, fallbackHost, fallbackPort)
                                if (validation == null) {
                                    onSave(initial?.id, label.trim(), host.trim(), port, user.trim(), authType, secret, if (authType == AuthType.KEY) passphrase else "", session.trim(), herdrCommand.trim(), fallbackHost.trim(), fallbackPort)
                                } else if (validation?.first?.let { it in advancedFields } == true) {
                                    // The offending field lives in the collapsed section; open it
                                    // so the error is actually on screen.
                                    advanced = true
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
                                validation = validateHost(host, user, secret, authType, port, herdrCommand, fallbackHost, fallbackPort)
                                if (validation == null) {
                                    onSave(initial?.id, label.trim(), host.trim(), port, user.trim(), authType, secret, if (authType == AuthType.KEY) passphrase else "", session.trim(), herdrCommand.trim(), fallbackHost.trim(), fallbackPort)
                                } else if (validation?.first?.let { it in advancedFields } == true) {
                                    advanced = true
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

/** Which field a validation error belongs to, so the message renders under that field. */
private enum class HostField { HOST, PORT, USER, SECRET, HERDR_COMMAND, FALLBACK_PORT, FALLBACK_SAME }

private val advancedFields = setOf(HostField.HERDR_COMMAND, HostField.FALLBACK_PORT, HostField.FALLBACK_SAME)

/** The error caption under the offending field; renders nothing when the error is elsewhere. */
@Composable
private fun FieldError(validation: Pair<HostField, String>?, vararg fields: HostField) {
    if (validation != null && validation.first in fields) {
        Spacer(Modifier.height(DsSpace.xs))
        Text(validation.second, style = DsTypography.caption, color = ds().danger)
    }
}

private fun validateHost(
    host: String,
    user: String,
    secret: String,
    authType: AuthType,
    port: String,
    herdrCommand: String,
    fallbackHost: String,
    fallbackPort: String,
): Pair<HostField, String>? = when {
    host.isBlank() -> HostField.HOST to Str.get(R.string.addhost_err_host_required)
    user.isBlank() -> HostField.USER to Str.get(R.string.addhost_err_user_required)
    secret.isBlank() -> HostField.SECRET to
        (if (authType == AuthType.PASSWORD) Str.get(R.string.addhost_err_password_required) else Str.get(R.string.addhost_err_key_required))
    (port.toIntOrNull() ?: 0) !in 1..65535 -> HostField.PORT to Str.get(R.string.addhost_err_port_range)
    herdrCommand.isBlank() -> HostField.HERDR_COMMAND to Str.get(R.string.addhost_err_herdr_required)
    // Blank port means "same as the primary", so only a non-empty value has to be a port.
    fallbackPort.isNotBlank() && (fallbackPort.toIntOrNull() ?: 0) !in 1..65535 -> HostField.FALLBACK_PORT to Str.get(R.string.addhost_err_port_range)
    fallbackHost.isNotBlank() &&
        fallbackHost.trim() == host.trim() &&
        (fallbackPort.toIntOrNull() ?: port.toIntOrNull()) == port.toIntOrNull() ->
        HostField.FALLBACK_SAME to Str.get(R.string.addhost_err_fallback_same)
    else -> null
}
