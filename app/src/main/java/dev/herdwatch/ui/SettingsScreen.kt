package dev.herdwatch.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdwatch.R
import dev.herdwatch.data.AppPreferences
import dev.herdwatch.data.DensityMode
import dev.herdwatch.data.InputMode
import dev.herdwatch.data.LanguageMode
import dev.herdwatch.data.MotionMode
import dev.herdwatch.data.ThemeMode
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsMotion
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.data.UpdateChecker
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoDialog
import dev.herdwatch.ui.design.NeoPageHeader
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoCard
import dev.herdwatch.ui.design.NeoDivider
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIcon
import dev.herdwatch.ui.design.NeoSection
import dev.herdwatch.ui.design.NeoSegmented
import dev.herdwatch.ui.design.NeoToggle
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.design.ds
import dev.herdwatch.ui.theme.MonoStyle
import kotlin.math.roundToInt

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
    onBack: () -> Unit,
    onOpenStyleGuide: (() -> Unit)? = null,
    onLanguage: (LanguageMode) -> Unit = {},
    onTheme: (ThemeMode) -> Unit = {},
    onMotion: (MotionMode) -> Unit = {},
    onDensity: (DensityMode) -> Unit = {},
    onFontSize: (Float) -> Unit = {},
    onSnapshotLines: (Int) -> Unit = {},
    onKeyBarRows: (Int) -> Unit = {},
    onLongPressRepeat: (Boolean) -> Unit = {},
    onInputMode: (InputMode) -> Unit = {},
    onResetFingerprints: () -> Unit = {},
    version: String = "",
    update: UpdateUi = UpdateUi(),
    onCheckUpdate: () -> Unit = {},
    onDismissUpdate: () -> Unit = {},
) {
    var confirmReset by remember { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    val colors = ds()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
    ) {
        // Settings is a root tab, so it shows no back chevron: the tab bar is the way out, and a
        // chevron here was the reason this page's header was 12dp shorter than the other two roots.
        NeoPageHeader(
            title = stringResource(R.string.settings_title),
            subtitle = stringResource(R.string.settings_subtitle),
            titleModifier = if (onOpenStyleGuide == null) {
                Modifier
            } else {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = onOpenStyleGuide,
                    onLongClickLabel = stringResource(R.string.settings_style_title),
                )
            },
        )

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
            OptionRow(
                title = stringResource(R.string.settings_motion_title),
                subtitle = stringResource(R.string.settings_motion_summary),
                options = listOf(
                    stringResource(R.string.settings_motion_full),
                    stringResource(R.string.settings_motion_reduced),
                ),
                modes = listOf(MotionMode.FULL, MotionMode.REDUCED),
                selected = preferences.motion,
                onSelect = onMotion,
            )
            OptionRow(
                title = stringResource(R.string.settings_density_title),
                subtitle = stringResource(R.string.settings_density_summary),
                options = listOf(
                    stringResource(R.string.settings_density_comfortable),
                    stringResource(R.string.settings_density_compact),
                ),
                modes = listOf(DensityMode.COMFORTABLE, DensityMode.COMPACT),
                selected = preferences.density,
                onSelect = onDensity,
            )
        }

        SettingsGroup(stringResource(R.string.settings_group_terminal)) {
            FontSizeRow(preferences.terminalFontSize, onFontSize)
            OptionRow(
                title = stringResource(R.string.settings_snapshot_title),
                subtitle = stringResource(R.string.settings_snapshot_summary),
                options = listOf(
                    stringResource(R.string.settings_snapshot_400),
                    stringResource(R.string.settings_snapshot_1200),
                ),
                modes = AppPreferences.SNAPSHOT_CHOICES,
                selected = preferences.snapshotLines,
                onSelect = onSnapshotLines,
            )
            OptionRow(
                title = stringResource(R.string.settings_keyrows_title),
                subtitle = stringResource(R.string.settings_keyrows_summary),
                options = listOf(
                    stringResource(R.string.settings_keyrows_one),
                    stringResource(R.string.settings_keyrows_two),
                ),
                modes = listOf(1, 2),
                selected = preferences.keyBarRows.coerceIn(1, 2),
                onSelect = onKeyBarRows,
            )
            ToggleRow(
                title = stringResource(R.string.settings_longpress_title),
                subtitle = stringResource(R.string.settings_longpress_summary),
                checked = preferences.longPressRepeat,
                onCheckedChange = onLongPressRepeat,
            )
            OptionRow(
                title = stringResource(R.string.settings_input_title),
                subtitle = stringResource(R.string.settings_input_summary),
                options = listOf(
                    stringResource(R.string.settings_input_line),
                    stringResource(R.string.settings_input_direct),
                ),
                modes = listOf(InputMode.LINE, InputMode.DIRECT),
                selected = preferences.inputMode,
                onSelect = onInputMode,
            )
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

        SettingsGroup(stringResource(R.string.settings_group_about)) {
            VersionRow(
                version = version,
                update = update,
                onCheck = onCheckUpdate,
                onOpenRelease = { url -> openUrl(uriHandler, url) },
            )
        }

        Text(
            listOf(
                stringResource(R.string.settings_boundaries_foreground),
                stringResource(R.string.settings_boundaries_credentials),
                stringResource(R.string.settings_rows_privacy_short),
            ).joinToString("  ·  "),
            style = DsTypography.micro,
            color = colors.textFaint,
            modifier = Modifier.fillMaxWidth().padding(horizontal = DsSpace.xl, vertical = DsSpace.m),
        )
        Spacer(Modifier.height(DsSpace.xxl))
    }

    (update.result as? UpdateChecker.Result.Newer)?.let { newer ->
        UpdateAvailableDialog(
            release = newer.release,
            onDismiss = onDismissUpdate,
            onOpen = { url ->
                onDismissUpdate()
                openUrl(uriHandler, url)
            },
        )
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
    Column(Modifier.fillMaxWidth().padding(start = DsSpace.xl, end = DsSpace.xl, top = DsSpace.m)) {
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

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    SettingRow(
        title = title,
        subtitle = subtitle,
        onClick = { onCheckedChange(!checked) },
    ) {
        NeoToggle(checked = checked, onCheckedChange = onCheckedChange)
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
        NeoChevron(expanded = false)
    }
}

/** A navigate-away row: raised surface, whole row clickable, chevron on the right. */
@Composable
private fun EntryRow(title: String, subtitle: String, onClick: () -> Unit) {
    val colors = ds()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = DsSpace.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = DsTypography.bodyStrong, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                subtitle,
                style = DsTypography.caption,
                color = colors.textSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(DsSpace.m))
        NeoIcon(NeoGlyph.ChevronRight, modifier = Modifier.size(18.dp), tint = colors.textFaint, contentDescription = null)
    }
}

// ---------------------------------------------------------------- folded help

/**
 * A folded shelf: the header row is the same 48dp row as everywhere else, the body animates in
 * place. Height animation keeps its token-based timing, so "reduced motion" still zeroes it out.
 */
@Composable
private fun DetailsCard(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(false) }
    val colors = ds()
    val expandedLabel = stringResource(R.string.settings_expanded)
    val collapsedLabel = stringResource(R.string.settings_collapsed)
    NeoCard(Modifier.fillMaxWidth(), depth = DsElev.rest) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { expanded = !expanded }
                    .semantics { stateDescription = if (expanded) expandedLabel else collapsedLabel }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = DsSpace.l, vertical = DsSpace.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = DsTypography.bodyStrong,
                        color = colors.text,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        subtitle,
                        style = DsTypography.caption,
                        color = colors.textSoft,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(DsSpace.m))
                NeoChevron(expanded)
            }
            ExpandableSection(expanded) {
                Column(
                    Modifier.padding(start = DsSpace.l, end = DsSpace.l, bottom = DsSpace.l),
                    verticalArrangement = Arrangement.spacedBy(DsSpace.m),
                ) { content() }
            }
        }
    }
}

@Composable
private fun NeoChevron(expanded: Boolean) {
    val rotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(motionMillis(DsMotion.quick)),
        label = "settings-chevron",
    )
    NeoIcon(
        NeoGlyph.ChevronDown,
        modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = rotation },
        tint = ds().textSoft,
        contentDescription = null,
    )
}

@Composable
private fun HostPrepDetails() {
    val clipboard = LocalClipboardManager.current
    val command = "herdr remote-api-bridge --check"
    DetailsCard(
        title = stringResource(R.string.settings_hostprep_title),
        subtitle = stringResource(R.string.settings_hostprep_summary),
    ) {
        DetailBlock(stringResource(R.string.settings_row_hostprep), stringResource(R.string.settings_row_hostprep_body))
        NeoWell(Modifier.fillMaxWidth(), corner = DsRadius.control) {
            Column(Modifier.padding(DsSpace.m)) {
                SelectionContainer {
                    Text(stringResource(R.string.settings_check_block), style = MonoStyle, color = ds().text)
                }
            }
        }
        NeoButton(
            label = stringResource(R.string.settings_copy_check),
            onClick = { clipboard.setText(AnnotatedString(command)) },
            icon = NeoGlyph.Copy,
            compact = true,
        )
        DetailBlock(stringResource(R.string.settings_row_missing), stringResource(R.string.settings_row_missing_body))
        DetailBlock(stringResource(R.string.settings_row_connfail), stringResource(R.string.settings_row_connfail_body))
    }
}

/**
 * Version + the only network feature in the app the user has to ask for.
 *
 * The result lives inline instead of behind another dialog: "already latest" is a five-word
 * answer, and pushing it into a sheet would be heavier than the information it carries.
 */
@Composable
private fun VersionRow(
    version: String,
    update: UpdateUi,
    onCheck: () -> Unit,
    onOpenRelease: (String) -> Unit,
) {
    val colors = ds()
    val result = update.result
    val status: String = when {
        update.checking -> stringResource(R.string.settings_update_checking)
        result is UpdateChecker.Result.Current -> stringResource(R.string.settings_update_current, result.version)
        result is UpdateChecker.Result.Newer ->
            stringResource(R.string.settings_update_available, result.release.version)

        result is UpdateChecker.Result.Failed -> stringResource(R.string.settings_update_failed, failureText(result.reason))
        else -> stringResource(R.string.settings_version_summary)
    }
    Column(Modifier.fillMaxWidth().padding(vertical = DsSpace.m), verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.settings_version_title, version),
                    style = DsTypography.bodyStrong,
                    color = colors.text,
                    maxLines = 2,
                )
                Text(
                    status,
                    style = DsTypography.caption,
                    color = if (result is UpdateChecker.Result.Failed) colors.danger else colors.textSoft,
                    maxLines = 3,
                )
            }
            NeoButton(
                label = if (update.checking) {
                    stringResource(R.string.settings_update_checking_short)
                } else {
                    stringResource(R.string.settings_update_action)
                },
                onClick = onCheck,
                tone = NeoButtonTone.Quiet,
                enabled = !update.checking,
                compact = true,
            )
        }
        val newer = result as? UpdateChecker.Result.Newer
        if (newer != null) {
            NeoButton(
                label = stringResource(R.string.settings_update_open),
                onClick = { onOpenRelease(newer.release.pageUrl) },
                tone = NeoButtonTone.Primary,
                compact = true,
                icon = NeoGlyph.Play,
            )
        }
    }
    NeoDivider(inset = 0.dp)
}

/** Turns transport failures into something a person can act on. */
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

@Composable
private fun UpdateAvailableDialog(
    release: UpdateChecker.Release,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val colors = ds()
    NeoDialog(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.settings_update_dialog_title, release.version),
        confirmLabel = stringResource(
            if (release.apkUrl != null) R.string.settings_update_download else R.string.settings_update_open,
        ),
        onConfirm = { onOpen(release.apkUrl ?: release.pageUrl) },
        dismissLabel = stringResource(R.string.settings_update_later),
    ) {
        if (release.publishedAt.isNotBlank()) {
            Text(release.publishedAt.take(10), style = DsTypography.caption, color = colors.textSoft)
        }
        Column(
            Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
        ) {
            SelectionContainer {
                Text(
                    release.notes.ifBlank { stringResource(R.string.settings_update_no_notes) },
                    style = DsTypography.body,
                    color = colors.text,
                )
            }
        }
    }
}

/** Opens a release page or APK in whatever the device uses; failures are silent by design. */
private fun openUrl(handler: UriHandler, url: String) {
    if (url.isBlank()) return
    runCatching { handler.openUri(url) }
}

@Composable
private fun AboutDetails() {
    DetailsCard(
        title = stringResource(R.string.settings_about),
        subtitle = stringResource(R.string.settings_about_summary),
    ) {
        DetailBlock(stringResource(R.string.settings_row_version), stringResource(R.string.settings_version_value))
        DetailBlock(stringResource(R.string.settings_row_purpose), stringResource(R.string.settings_row_purpose_body))
        DetailBlock(stringResource(R.string.settings_row_feedback), stringResource(R.string.settings_row_feedback_body))
        DetailBlock(stringResource(R.string.settings_row_connection), stringResource(R.string.settings_row_connection_body))
        DetailBlock(stringResource(R.string.settings_row_privacy), stringResource(R.string.settings_row_privacy_body))
    }
}

@Composable
private fun DetailBlock(title: String, body: String) {
    val colors = ds()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpace.xxs)) {
        Text(title, style = DsTypography.caption, color = colors.textSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(body, style = DsTypography.body, color = colors.text)
    }
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
