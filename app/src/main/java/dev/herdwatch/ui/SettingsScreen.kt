package dev.herdwatch.ui

import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import dev.herdwatch.ui.design.NeoShadowBleed
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.data.AppPreferences
import dev.herdwatch.data.LanguageMode
import dev.herdwatch.data.ThemeMode
import dev.herdwatch.data.UpdateChecker
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoDialog
import dev.herdwatch.ui.design.NeoPageHeader
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoCard
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoSection
import dev.herdwatch.ui.design.NeoSegmented
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import kotlin.math.roundToInt
import dev.herdwatch.ui.design.spacing

/**
 * Settings is a control panel, not a manual: four groups of real switches, one danger action, and
 * a folded help shelf at the bottom. Documentation that used to occupy its own cards is now either
 * a one-line fact next to the thing it explains, or folded into "about".
 *
 * Every control here is bound to a [dev.herdwatch.data.PreferencesStore] value, so what the page
 * shows is exactly what the app will do on the next launch.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SettingsScreen(
    hosts: List<HostRuntime>,
    preferences: AppPreferences = AppPreferences(),
    storeError: String? = null,
    onLanguage: (LanguageMode) -> Unit = {},
    onTheme: (ThemeMode) -> Unit = {},
    onFontSize: (Float) -> Unit = {},
    onResetFingerprints: () -> Unit = {},
    version: String = "",
    update: UpdateUi = UpdateUi(),
    onCheckUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
    onInstallUpdate: () -> Unit = {},
    onOpenInstallPermission: () -> Unit = {},
) {
    var confirmReset by remember { mutableStateOf(false) }
    val colors = ds()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // Settings is a root tab, so it shows no back chevron: the tab bar is the way out, and a
        // chevron here was the reason this page's header was 12dp shorter than the other two roots.
        NeoPageHeader(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
        )

        // The credential store can fail independently of any host (a lost Keystore entry). Say so
        // where the user comes to fix configuration, instead of showing an empty fleet as if the
        // machines had never been added.
        if (!storeError.isNullOrBlank()) {
            NeoWell(
                Modifier
                    .fillMaxWidth()
                    .padding(start = spacing().gutter, end = spacing().gutter, top = spacing().block),
                corner = DsRadius.control,
            ) {
                Row(
                    Modifier.padding(horizontal = DsSpace.l, vertical = DsSpace.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NeoIcon(NeoGlyph.Warning, modifier = Modifier.size(18.dp), tint = colors.warn)
                    Spacer(Modifier.width(DsSpace.m))
                    Text(storeError, style = DsTypography.body, color = colors.warn)
                }
            }
        }

        SettingsGroup(stringResource(R.string.settings_group_appearance)) {
            LanguageRow(preferences.language, onLanguage)
            OptionRow(
                title = stringResource(R.string.settings_theme_title),
                subtitle = stringResource(R.string.settings_theme_summary),
                options = listOf(
                    stringResource(R.string.settings_theme_system),
                    stringResource(R.string.settings_theme_light),
                    stringResource(R.string.settings_theme_dark),
                ),
                modes = listOf(ThemeMode.SYSTEM, ThemeMode.LIGHT, ThemeMode.DARK),
                selected = preferences.theme,
                onSelect = onTheme,
            )
        }

        // One habit: how big the text is. Everything else that used to live here (输入模式, 按键栏
        // 行数, 长按连发) was a switch for a decision the app should have made itself.
        SettingsGroup(stringResource(R.string.settings_group_terminal)) {
            FontSizeRow(preferences.terminalFontSize, onFontSize)
        }

        // Only interactive rows here. The three static statements that used to live in this group
        // (foreground-only, credentials stay local, no telemetry) were unclickable text taking a
        // row each; they are now one caption at the very bottom of the page.
        SettingsGroup(stringResource(R.string.settings_group_security)) {
            DangerRow(
                title = stringResource(R.string.settings_reset_fp_title),
                subtitle = stringResource(R.string.settings_reset_fp_summary),
                action = stringResource(R.string.settings_reset_fp_action),
                onClick = { confirmReset = true },
            )
        }

        // Updating lives here rather than behind a website link: the release page is a download
        // button, and a phone that opens a browser to fetch an APK has already lost the user.
        SettingsGroup(stringResource(R.string.settings_group_about)) {
            UpdateRow(
                version = version,
                update = update,
                onCheck = onCheckUpdate,
                onInstall = onInstallUpdate,
                onPermission = onOpenInstallPermission,
                onDismiss = onDismissUpdate,
            )
        }

        Text(
            stringResource(R.string.settings_version_footnote, version),
            style = DsTypography.micro,
            color = colors.textFaint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpace.xl, vertical = DsSpace.l),
        )
        Spacer(Modifier.height(DsSpace.xxl))
    }

    if (confirmReset) {
        ResetFingerprintDialog(
            onDismiss = { confirmReset = false },
            onConfirm = {
                confirmReset = false
                onResetFingerprints()
            },
        )
    }
}

// ---------------------------------------------------------------- groups and rows

/** Section label plus one raised panel: the only two levels this page needs. */
@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = spacing().gutter, end = spacing().gutter, top = spacing().block)) {
        NeoSection(title)
        NeoCard(Modifier.fillMaxWidth(), depth = DsElev.rest) {
            Column(
                Modifier.padding(horizontal = DsSpace.l, vertical = DsSpace.s),
                content = content,
            )
        }
    }
}

/**
 * Title/subtitle on the left, a control on the right. Used for toggles and steppers where the
 * control has a fixed width; segmented controls get their own full-width layout below.
 */
@Composable
private fun SettingRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ds()
    val clickable = if (onClick == null) {
        Modifier
    } else {
        Modifier.clickable(role = Role.Button, onClick = onClick)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(clickable).padding(vertical = DsSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = DsTypography.bodyStrong,
                color = colors.text,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = DsTypography.caption,
                    color = colors.textSoft,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(DsSpace.m))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DsSpace.xs),
                content = trailing,
            )
        }
    }
}

/** Read-only fact: no control, so it reads as a statement rather than an affordance. */
@Composable
private fun InfoRow(title: String, subtitle: String) {
    SettingRow(title = title, subtitle = subtitle)
}

/**
 * Segmented choice. The track takes the full row width under the label instead of sitting on the
 * right: three English options plus a label do not fit side by side at 360dp.
 */
@Composable
private fun <T> OptionRow(
    title: String,
    subtitle: String,
    options: List<String>,
    modes: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    val colors = ds()
    val index = modes.indexOf(selected).coerceAtLeast(0)
    // Label above, control below — always. The earlier side-by-side variant looked tidier on paper
    // but squeezed English titles into "Snapshot li…" and subtitles into three clipped lines, and
    // it changed shape again at 1.3x font. One shape at every size beats a conditional layout.
    Column(Modifier.fillMaxWidth().padding(vertical = DsSpace.s)) {
        Text(title, style = DsTypography.bodyStrong, color = colors.text)
        Text(
            subtitle,
            style = DsTypography.caption,
            color = colors.textSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DsSpace.s))
        NeoSegmented(
            options = options,
            selectedIndex = index,
            onSelect = { i -> modes.getOrNull(i)?.let(onSelect) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Font size stepper: two 48dp keys around a milled readout, the terminal's own visual grammar. */
@Composable
private fun FontSizeRow(size: Float, onFontSize: (Float) -> Unit) {
    val current = size.roundToInt().coerceIn(AppPreferences.MIN_FONT.toInt(), AppPreferences.MAX_FONT.toInt())
    val colors = ds()
    // Stacked like every other setting row: three 48dp controls beside an English title left room
    // for neither, and the title truncated. One shape at every language and font scale.
    Column(Modifier.fillMaxWidth().padding(vertical = DsSpace.s)) {
        Text(stringResource(R.string.settings_font_title), style = DsTypography.bodyStrong, color = colors.text)
        Text(
            stringResource(R.string.settings_font_summary),
            style = DsTypography.caption,
            color = colors.textSoft,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DsSpace.s))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
            StepKey(
                label = "−",
                contentDescription = stringResource(R.string.settings_font_decrease),
                enabled = current > AppPreferences.MIN_FONT.toInt(),
                onClick = { onFontSize((current - 1).toFloat()) },
            )
            NeoWell(Modifier.weight(1f).height(48.dp), corner = DsRadius.control) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("$current", style = DsTypography.monoStrong, color = colors.text, maxLines = 1)
                }
            }
            StepKey(
                label = "+",
                contentDescription = stringResource(R.string.settings_font_increase),
                enabled = current < AppPreferences.MAX_FONT.toInt(),
                onClick = { onFontSize((current + 1).toFloat()) },
            )
        }
    }
}

@Composable
private fun StepKey(label: String, contentDescription: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = ds()
    NeoCard(
        modifier = Modifier
            .size(48.dp)
            .semantics { this.contentDescription = contentDescription },
        corner = DsRadius.control,
        depth = DsElev.soft,
        onClick = if (enabled) onClick else null,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = DsTypography.monoStrong,
                color = if (enabled) colors.text else colors.textFaint,
                maxLines = 1,
            )
        }
    }
}

/**
 * Destructive *entry*, not a destructive button: the row opens a confirmation dialog, so it uses
 * the same grammar as other rows (danger-tinted label + warning glyph) instead of a red slab that
 * would out-shout every real action on the page.
 */
@Composable
private fun DangerRow(title: String, subtitle: String, action: String, onClick: () -> Unit) {
    val colors = ds()
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(DsRadius.control))
            .clickable(role = Role.Button, onClickLabel = action, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(vertical = DsSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        NeoIcon(
            NeoGlyph.Warning,
            modifier = Modifier.size(18.dp),
            tint = colors.danger,
            contentDescription = null,
        )
        Spacer(Modifier.width(DsSpace.s))
        Column(Modifier.weight(1f)) {
            Text(title, style = DsTypography.bodyStrong, color = colors.danger, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = DsTypography.caption,
                color = colors.textSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DsSpace.s))
        NeoChevron()
    }
}

@Composable
private fun NeoChevron() {
    NeoIcon(
        NeoGlyph.ChevronDown,
        modifier = Modifier.size(18.dp),
        tint = ds().textSoft,
        contentDescription = null,
    )
}

/**
 * The whole update flow in one row.
 *
 * States are ordered by what the user can do next, not by what the code is doing: nothing known yet
 * offers 「检查」, a newer release offers the download, a download offers its percentage, and a
 * finished download offers the installer (or the one-time permission it needs).
 */
@Composable
private fun UpdateRow(
    version: String,
    update: UpdateUi,
    onCheck: () -> Unit,
    onInstall: () -> Unit,
    onPermission: () -> Unit,
    onDismiss: () -> Unit = {},
) {
    val colors = ds()
    val newer = update.result as? UpdateChecker.Result.Newer
    val current = update.result as? UpdateChecker.Result.Current
    val failed = update.result as? UpdateChecker.Result.Failed
    val downloading = update.downloading
    // One line for "what this is" and "what I can do about it": the title on the left, its actions
    // on the right. Stacking title, status and a button row made a five-line block out of a row
    // that is, most of the time, just "up to date — check again".
    Column(Modifier.fillMaxWidth().padding(vertical = DsSpace.m)) {
        val actions: @Composable RowScope.() -> Unit = {
            when {
                update.needsPermission -> NeoButton(
                    label = stringResource(R.string.settings_update_permission_action),
                    onClick = onPermission,
                    tone = NeoButtonTone.Primary,
                    compact = true,
                )

                newer != null || update.readyVersion != null -> NeoButton(
                    label = if (update.readyVersion != null) {
                        stringResource(R.string.settings_update_install)
                    } else {
                        stringResource(R.string.settings_update_download)
                    },
                    onClick = onInstall,
                    tone = NeoButtonTone.Primary,
                    enabled = !downloading && !update.installing && !update.checking,
                    compact = true,
                )

                else -> NeoButton(
                    label = stringResource(R.string.settings_update_check),
                    onClick = onCheck,
                    tone = NeoButtonTone.Quiet,
                    enabled = !update.checking,
                    compact = true,
                )
            }
            // Anything known can be put away. Without this the row kept the last answer for the
            // rest of the session, including a cancelled install.
            if (update.result != null || update.error != null || update.readyVersion != null) {
                NeoButton(
                    label = stringResource(R.string.settings_update_dismiss),
                    onClick = onDismiss,
                    tone = NeoButtonTone.Ghost,
                    enabled = !downloading && !update.installing && !update.checking,
                    compact = true,
                )
            }
        }
        Text(
            if (newer != null) {
                stringResource(R.string.settings_update_available, newer.release.version)
            } else {
                stringResource(R.string.settings_update_title)
            },
            style = DsTypography.bodyStrong,
            color = if (newer != null) colors.accent else colors.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(DsSpace.xs))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(NeoShadowBleed, Alignment.End),
            content = actions,
        )
        val detail = when {
            update.error != null -> update.error
            update.installing -> stringResource(R.string.settings_update_installing)
            downloading -> stringResource(R.string.settings_update_downloading, update.progress)
            update.needsPermission -> stringResource(R.string.settings_update_permission)
            update.readyVersion != null && newer != null ->
                stringResource(R.string.settings_update_ready, newer.release.version)
            newer != null -> newer.release.notes.lineSequence()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .take(3)
                .joinToString(" ")

            current != null -> stringResource(R.string.settings_update_current, current.version)
            failed != null -> failureText(failed.reason)
            update.checking -> stringResource(R.string.settings_update_checking)
            else -> stringResource(R.string.settings_update_summary)
        }
        Spacer(Modifier.height(DsSpace.xs))
        Text(
            detail.orEmpty(),
            style = DsTypography.caption,
            color = if (failed != null || update.error != null) colors.danger else colors.textSoft,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (downloading) {
            Spacer(Modifier.height(DsSpace.s))
            NeoWell(Modifier.fillMaxWidth().height(6.dp), corner = DsRadius.pill) {
                Box(
                    Modifier.fillMaxWidth(update.progress / 100f).fillMaxSize()
                        .background(colors.accent, RoundedCornerShape(DsRadius.pill)),
                )
            }
        }
    }
}

@Composable
private fun failureText(reason: String): String = when {
    reason.contains("rate limited", ignoreCase = true) ->
        stringResource(R.string.settings_update_failed_rate)

    reason.contains("no release", ignoreCase = true) ->
        stringResource(R.string.settings_update_failed_none)

    reason.startsWith("http", ignoreCase = true) ->
        stringResource(R.string.settings_update_failed_http, reason)

    else -> stringResource(R.string.settings_update_failed_network)
}

/** Language: a preference, but on Android 12 and below it is the system's, so say so. */
@Composable
private fun LanguageRow(language: LanguageMode, onLanguage: (LanguageMode) -> Unit) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        InfoRow(
            title = stringResource(R.string.settings_language_title),
            subtitle = stringResource(R.string.settings_language_legacy),
        )
        return
    }
    OptionRow(
        title = stringResource(R.string.settings_language_title),
        subtitle = stringResource(R.string.settings_language_summary),
        options = listOf(
            stringResource(R.string.settings_language_system),
            stringResource(R.string.settings_language_zh),
            stringResource(R.string.settings_language_en),
        ),
        modes = listOf(LanguageMode.SYSTEM, LanguageMode.ZH, LanguageMode.EN),
        selected = language,
        onSelect = onLanguage,
    )
}

@Composable
private fun ResetFingerprintDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    NeoDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_reset_fp_confirm_title),
        subtitle = stringResource(R.string.settings_reset_fp_confirm_body),
        confirmLabel = stringResource(R.string.settings_reset_fp_action),
        onConfirm = onConfirm,
        dismissLabel = stringResource(R.string.common_cancel),
        destructive = true,
    )
}
