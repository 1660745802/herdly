package dev.herdwatch.ui.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import dev.herdwatch.R
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.ui.theme.DarkStatusPalette
import dev.herdwatch.ui.theme.LightStatusPalette
import dev.herdwatch.ui.theme.LocalStatusPalette
import dev.herdwatch.ui.theme.statusColor

/**
 * Living style sheet. Every control in the app is drawn from these parts, so this screen is the
 * contract: if it is not here, it does not ship. Reachable from Settings → 设计系统.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StyleGalleryScreen(onBack: () -> Unit) {
    var dark by rememberSaveable { mutableStateOf(false) }
    val colors = if (dark) DsDark else DsLight
    // The gallery must show the colours the app really paints with, otherwise preview and product
    // drift apart: the status row used to demo `colors.warn`/`colors.ok` while the queue, the
    // machine tree and the agent header all use the status palette.
    val palette = if (dark) DarkStatusPalette else LightStatusPalette
    CompositionLocalProvider(
        LocalDs provides colors,
        LocalStatusPalette provides palette,
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .background(colors.base)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing().gutter),
        ) {
            Spacer(Modifier.height(DsSpace.l))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NeoIconButton(NeoGlyph.Back, onClick = onBack, contentDescription = stringResource(R.string.common_back), tint = colors.text)
                Column(Modifier.weight(1f).padding(start = DsSpace.s)) {
                    Text(stringResource(R.string.gallery_title), style = DsTypography.title, color = colors.text)
                    Text(stringResource(R.string.gallery_subtitle), style = DsTypography.caption, color = colors.textSoft)
                }
                NeoToggle(checked = dark, onCheckedChange = { dark = it })
            }
            Spacer(Modifier.height(DsSpace.l))

            Section(stringResource(R.string.gallery_section_material)) {
                NeoCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(DsSpace.l), verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                        Text(stringResource(R.string.gallery_raised_title), style = DsTypography.bodyStrong, color = colors.text)
                        Text(
                            stringResource(R.string.gallery_raised_body),
                            style = DsTypography.body,
                            color = colors.textSoft,
                        )
                    }
                }
                Spacer(Modifier.height(DsSpace.m))
                NeoWell(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(DsSpace.l), verticalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                        Text(stringResource(R.string.gallery_well_title), style = DsTypography.bodyStrong, color = colors.text)
                        Text(
                            stringResource(R.string.gallery_well_body),
                            style = DsTypography.body,
                            color = colors.textSoft,
                        )
                    }
                }
                Spacer(Modifier.height(DsSpace.m))
                NeoWell(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    corner = DsRadius.card,
                    fill = Color(0xFF101318),
                    depth = DsElev.insetDeep,
                ) {
                    Column(Modifier.padding(DsSpace.m), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("\$ herdr agents", style = DsTypography.mono.copy(color = Color(0xFF8BD5CA)))
                        Text("w1:p1  WAITING   needs approval", style = DsTypography.mono.copy(color = Color(0xFFE4E7EE)))
                        Text("w1:p2  WORKING   tests running", style = DsTypography.mono.copy(color = Color(0xFFA8B0C0)))
                    }
                }
            }

            Section(stringResource(R.string.gallery_section_actions)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.m), verticalArrangement = Arrangement.spacedBy(DsSpace.m)) {
                    NeoButton(stringResource(R.string.gallery_btn_primary), onClick = {}, tone = NeoButtonTone.Primary, icon = NeoGlyph.Check)
                    NeoButton(stringResource(R.string.gallery_btn_secondary), onClick = {}, icon = NeoGlyph.Refresh)
                    NeoButton(stringResource(R.string.gallery_btn_danger), onClick = {}, tone = NeoButtonTone.Danger, icon = NeoGlyph.Warning)
                    NeoButton(stringResource(R.string.gallery_btn_disabled), onClick = {}, enabled = false)
                    NeoButton(stringResource(R.string.gallery_btn_ghost), onClick = {}, tone = NeoButtonTone.Ghost)
                }
                Spacer(Modifier.height(DsSpace.m))
                Text(
                    stringResource(R.string.gallery_press_note),
                    style = DsTypography.caption,
                    color = colors.textFaint,
                )
            }

            Section(stringResource(R.string.gallery_section_selection)) {
                var segment by rememberSaveable { mutableStateOf(0) }
                NeoSegmented(
                    listOf(stringResource(R.string.gallery_seg_all_agents), stringResource(R.string.gallery_seg_needs_attention)),
                    segment,
                    { segment = it },
                )
                Spacer(Modifier.height(DsSpace.m))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    NeoChip(stringResource(R.string.gallery_chip_local), selected = true, onClick = {}, dot = colors.ok)
                    NeoChip(stringResource(R.string.gallery_chip_build), selected = false, onClick = {}, dot = colors.textFaint)
                    NeoChip(stringResource(R.string.gallery_chip_all), selected = false, onClick = {})
                }
                Spacer(Modifier.height(DsSpace.m))
                var toggle by rememberSaveable { mutableStateOf(true) }
                NeoToggle(checked = toggle, onCheckedChange = { toggle = it }, label = stringResource(R.string.gallery_toggle_follow_system))
                NeoToggle(checked = false, onCheckedChange = {}, label = stringResource(R.string.gallery_toggle_disabled_example), enabled = false)
            }

            Section(stringResource(R.string.gallery_section_input)) {
                var value by rememberSaveable { mutableStateOf("git rebase -i HEAD~3") }
                NeoField(
                    value = value,
                    onValueChange = { value = it },
                    label = stringResource(R.string.gallery_field_label),
                    hint = stringResource(R.string.gallery_field_hint),
                    trailing = {
                        NeoIconButton(NeoGlyph.Copy, onClick = {}, contentDescription = stringResource(R.string.common_copy), tint = colors.textSoft)
                    },
                )
            }

            Section(stringResource(R.string.gallery_section_keys)) {
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    NeoKey("Esc", onPress = {})
                    NeoKey("Tab", onPress = {})
                    NeoKey("^C", onPress = {}, active = true)
                    NeoKey("↑", onPress = {})
                    NeoKey("↓", onPress = {})
                    NeoKey("Ctrl", onPress = {}, wide = true)
                }
            }

            Section(stringResource(R.string.gallery_section_status)) {
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.l), verticalAlignment = Alignment.CenterVertically) {
                    StatusSample(stringResource(R.string.gallery_status_waiting_you), statusColor(AgentStatus.WAITING, palette), pulse = false)
                    StatusSample(stringResource(R.string.gallery_status_working), statusColor(AgentStatus.WORKING, palette), pulse = true)
                    StatusSample(stringResource(R.string.gallery_status_idle), statusColor(AgentStatus.IDLE, palette), pulse = false)
                    StatusSample(stringResource(R.string.gallery_status_unknown), statusColor(AgentStatus.UNKNOWN, palette), pulse = false)
                }
                Spacer(Modifier.height(DsSpace.m))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    NeoSpinner(Modifier.size(16.dp))
                    Text(stringResource(R.string.gallery_loading), style = DsTypography.caption, color = colors.textSoft)
                }
                Spacer(Modifier.height(DsSpace.s))
                NeoSkeleton(Modifier.fillMaxWidth(0.7f))
                Spacer(Modifier.height(DsSpace.s))
                NeoSkeleton(Modifier.fillMaxWidth(0.45f))
            }

            Section(stringResource(R.string.gallery_section_icons)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(DsSpace.m), verticalArrangement = Arrangement.spacedBy(DsSpace.m)) {
                    NeoGlyph.entries.forEach { glyph ->
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            NeoIcon(glyph, tint = colors.textSoft, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }

            Section(stringResource(R.string.gallery_section_type)) {
                Text(stringResource(R.string.gallery_type_display), style = DsTypography.display, color = colors.text)
                Text(stringResource(R.string.gallery_type_title), style = DsTypography.title, color = colors.text)
                Text(stringResource(R.string.gallery_type_headline), style = DsTypography.headline, color = colors.text)
                Text(stringResource(R.string.gallery_type_body), style = DsTypography.body, color = colors.textSoft)
                Text(stringResource(R.string.gallery_type_body_strong), style = DsTypography.bodyStrong, color = colors.text)
                Text(stringResource(R.string.gallery_type_caption), style = DsTypography.caption, color = colors.textFaint)
                Text("w1:p1 \$ npm test", style = DsTypography.mono, color = colors.text)
            }

            Section(stringResource(R.string.gallery_section_density)) {
                NeoCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(DsSpace.l)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            NeoLamp(statusColor(AgentStatus.WAITING, palette), pulse = true)
                            Column(Modifier.weight(1f).padding(start = DsSpace.s)) {
                                Text("auth-service", style = DsTypography.bodyStrong, color = colors.text)
                                Text(
                                    pluralStringResource(R.plurals.gallery_meta_waiting_minutes, 2, 2),
                                    style = DsTypography.caption,
                                    color = colors.textSoft,
                                )
                            }
                            Text(stringResource(R.string.gallery_action_view), style = DsTypography.caption, color = colors.accent)
                        }
                        Spacer(Modifier.height(DsSpace.s))
                        Text(
                            stringResource(R.string.gallery_meta_host_panel),
                            style = DsTypography.micro,
                            color = colors.textFaint,
                        )
                    }
                }
            }

            Section(stringResource(R.string.gallery_section_context)) {
                // The two surfaces that must never be confused with each other: the agent
                // decision view and the raw SSH session.
                Row(horizontalArrangement = Arrangement.spacedBy(DsSpace.s)) {
                    NeoModeTag(stringResource(R.string.mode_agent_tag))
                    NeoModeTag(stringResource(R.string.mode_terminal_tag))
                }
                Spacer(Modifier.height(DsSpace.s))
                NeoEmptyState(
                    icon = NeoGlyph.Tray,
                    title = stringResource(R.string.gallery_empty_title),
                    body = stringResource(R.string.gallery_empty_body),
                ) {
                    NeoButton(
                        label = stringResource(R.string.gallery_action_view),
                        onClick = {},
                        tone = NeoButtonTone.Ghost,
                        compact = true,
                    )
                }
            }

            Spacer(Modifier.height(48.dp))
        }
    }
}

@Composable
private fun StatusSample(label: String, color: Color, pulse: Boolean) {
    val colors = ds()
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DsSpace.xs)) {
        NeoLamp(color, pulse = pulse)
        Text(label, style = DsTypography.caption, color = colors.textSoft)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = DsSpace.xl)) {
        NeoSection(title)
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DsSpace.xs)) { content() }
    }
}
