package dev.herdwatch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.herdwatch.R
import dev.herdwatch.ui.design.DsElev
import dev.herdwatch.ui.design.DsRadius
import dev.herdwatch.ui.design.DsSpace
import dev.herdwatch.ui.design.DsTypography
import dev.herdwatch.ui.design.NeoButton
import dev.herdwatch.ui.design.NeoButtonTone
import dev.herdwatch.ui.design.NeoGlyph
import dev.herdwatch.ui.design.NeoIconButton
import dev.herdwatch.ui.design.NeoWell
import dev.herdwatch.ui.theme.LocalTerminalTokens

/**
 * Search-hit colours, kept as literals on purpose.
 *
 * The pair is tuned for contrast against the *frozen terminal* background, not against the app
 * chassis: amber-on-olive has to stay readable inside a block of coloured VT output, where an
 * accent wash (semi-transparent, chassis-tinted) would either vanish or fight the ANSI colours
 * already on the line. Do not tokenize these without re-checking that contrast.
 */
private val SearchHighlightBackground = Color(0xFF4D431F)
private val SearchHighlightForeground = Color(0xFFFFE7A0)

private val ReaderBackground: Color @Composable get() = LocalTerminalTokens.current.background
private val ReaderForeground: Color @Composable get() = LocalTerminalTokens.current.text
private val ReaderMuted: Color @Composable get() = LocalTerminalTokens.current.muted
private val ReaderAccent: Color @Composable get() = LocalTerminalTokens.current.accent

/** A frozen snapshot: neither polling nor remote controls can change the text being read. */
@Composable
internal fun TerminalReader(
    text: String,
    identity: String,
    loadedAt: Long?,
    truncated: Boolean,
    fontSize: Int,
    onFontSize: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var wrap by rememberSaveable { mutableStateOf(true) }
    var matchIndex by remember { mutableIntStateOf(0) }
    val lines = remember(text) { text.split('\n') }
    val matchingLines = remember(lines, query) {
        if (query.isBlank()) emptyList() else lines.indices.filter { lines[it].contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()
    val horizontal = rememberScrollState()
    val clipboard = LocalClipboardManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    var copied by remember { mutableStateOf(false) }
    val wrapStateLabel = stringResource(if (wrap) R.string.reader_state_wrap_on else R.string.reader_state_wrap_off)
    val searchDescription = stringResource(R.string.reader_cd_search)
    val fontSmallerDescription = stringResource(R.string.reader_cd_font_smaller)
    val fontLargerDescription = stringResource(R.string.reader_cd_font_larger)
    LaunchedEffect(query) { matchIndex = 0 }
    LaunchedEffect(matchingLines, matchIndex) {
        matchingLines.getOrNull(matchIndex)?.let { listState.scrollToItem(it) }
    }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(2000)
            copied = false
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxWidth().height(configuration.screenHeightDp.dp).background(ReaderBackground).systemBarsPadding().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.reader_title), color = ReaderForeground, style = DsTypography.headline)
                    Text(identity, color = ReaderMuted, style = DsTypography.caption, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                NeoButton(
                    label = if (copied) stringResource(R.string.common_copied) else stringResource(R.string.reader_copy_all),
                    onClick = { clipboard.setText(AnnotatedString(text)); copied = true },
                    enabled = text.isNotBlank(),
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                )
                NeoIconButton(
                    glyph = NeoGlyph.Close,
                    onClick = onDismiss,
                    contentDescription = stringResource(R.string.reader_cd_close),
                    tint = ReaderForeground,
                )
            }
            Text(
                stringResource(
                    if (truncated) R.string.reader_frozen_truncated else R.string.reader_frozen,
                    formatClock(loadedAt),
                ),
                color = ReaderMuted, style = DsTypography.caption,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            NeoWell(
                Modifier.fillMaxWidth().padding(horizontal = DsSpace.m, vertical = DsSpace.xs),
                corner = DsRadius.control,
                fill = ReaderMuted.copy(alpha = 0.12f),
                depth = DsElev.flat,
            ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp)
                        .semantics { contentDescription = searchDescription },
                    singleLine = true,
                    textStyle = DsTypography.body.copy(color = ReaderForeground),
                    cursorBrush = SolidColor(ReaderAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text(stringResource(R.string.reader_search_placeholder), color = ReaderMuted, style = DsTypography.body)
                        inner()
                    },
                )
                if (query.isNotEmpty()) NeoIconButton(
                    glyph = NeoGlyph.Close,
                    onClick = { query = "" },
                    contentDescription = stringResource(R.string.reader_cd_clear_search),
                    tint = ReaderMuted,
                )
            }
            }
            androidx.compose.animation.AnimatedVisibility(visible = query.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (matchingLines.isEmpty()) stringResource(R.string.reader_no_matches)
                        else pluralStringResource(R.plurals.reader_match_position, matchingLines.size, matchIndex + 1, matchingLines.size),
                        color = ReaderMuted, style = DsTypography.caption, modifier = Modifier.weight(1f))
                    NeoIconButton(
                        glyph = NeoGlyph.ChevronRight,
                        onClick = { matchIndex = (matchIndex - 1 + matchingLines.size) % matchingLines.size },
                        contentDescription = stringResource(R.string.reader_cd_prev_match),
                        tint = if (matchingLines.isEmpty()) ReaderMuted else ReaderAccent,
                        enabled = matchingLines.isNotEmpty(),
                        modifier = Modifier.rotate(-90f),
                    )
                    NeoIconButton(
                        glyph = NeoGlyph.ChevronRight,
                        onClick = { matchIndex = (matchIndex + 1) % matchingLines.size },
                        contentDescription = stringResource(R.string.reader_cd_next_match),
                        tint = if (matchingLines.isEmpty()) ReaderMuted else ReaderAccent,
                        enabled = matchingLines.isNotEmpty(),
                        modifier = Modifier.rotate(90f),
                    )
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                NeoButton(
                    label = "A−",
                    onClick = { onFontSize((fontSize - 1).coerceAtLeast(11)) },
                    enabled = fontSize > 11,
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                    modifier = Modifier.semantics { contentDescription = fontSmallerDescription },
                )
                Text("$fontSize", color = ReaderMuted, style = DsTypography.mono)
                NeoButton(
                    label = "A+",
                    onClick = { onFontSize((fontSize + 1).coerceAtMost(20)) },
                    enabled = fontSize < 20,
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                    modifier = Modifier.semantics { contentDescription = fontLargerDescription },
                )
                Spacer(Modifier.weight(1f))
                NeoButton(
                    label = if (wrap) stringResource(R.string.reader_wrap) else stringResource(R.string.reader_unwrap),
                    onClick = { wrap = !wrap },
                    tone = NeoButtonTone.Ghost,
                    compact = true,
                    modifier = Modifier.semantics { stateDescription = wrapStateLabel },
                )
            }
            // NeoDivider paints colors.shadowDark (a near-black hairline) — invisible on the frozen
            // terminal panel. Reproduce the original faint light rule from the terminal token instead.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(LocalTerminalTokens.current.muted.copy(alpha = 0.12f)),
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val outputModifier = if (wrap) Modifier.fillMaxWidth() else Modifier.horizontalScroll(horizontal).widthIn(min = 280.dp)
                SelectionContainer {
                    LazyColumn(state = listState, modifier = outputModifier.fillMaxHeight(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                        items(lines.size) { index ->
                            val line = lines[index]
                            val highlighted = remember(line, query) {
                                buildAnnotatedString {
                                    append(line.ifEmpty { " " })
                                    if (query.isNotBlank()) {
                                        var start = line.indexOf(query, ignoreCase = true)
                                        while (start >= 0) {
                                            addStyle(SpanStyle(background = SearchHighlightBackground, color = SearchHighlightForeground), start, start + query.length)
                                            start = line.indexOf(query, start + query.length, ignoreCase = true)
                                        }
                                    }
                                }
                            }
                            Text(highlighted, color = ReaderForeground, softWrap = wrap,
                                style = DsTypography.mono.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.55f).sp),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
