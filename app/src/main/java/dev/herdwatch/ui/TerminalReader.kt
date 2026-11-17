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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import dev.herdwatch.ui.theme.MonoStyle

private val ReaderBackground = Color(0xFF13151A)
private val ReaderForeground = Color(0xFFE4E7EE)
private val ReaderMuted = Color(0xFFA8B0C0)
private val ReaderAccent = Color(0xFFB6C3FF)

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
                    Text("屏幕原文", color = ReaderForeground, style = MaterialTheme.typography.titleMedium)
                    Text(identity, color = ReaderMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                TextButton(onClick = { clipboard.setText(AnnotatedString(text)); copied = true }, enabled = text.isNotBlank()) {
                    Text(if (copied) "已复制" else "复制全部", color = ReaderAccent)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "关闭全屏阅读", tint = ReaderForeground) }
            }
            Text("已冻结 · ${formatClock(loadedAt)} 的快照${if (truncated) " · 内容不完整" else ""}",
                color = ReaderMuted, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).background(Color.White.copy(alpha = 0.07f), androidx.compose.foundation.shape.RoundedCornerShape(10.dp)),
                verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 12.dp)
                        .semantics { contentDescription = "搜索屏幕原文" },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = ReaderForeground),
                    cursorBrush = SolidColor(ReaderAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    decorationBox = { inner ->
                        if (query.isEmpty()) Text("在这份快照中查找", color = ReaderMuted, style = MaterialTheme.typography.bodyMedium)
                        inner()
                    },
                )
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "清空查找", tint = ReaderMuted, modifier = Modifier.size(18.dp)) }
            }
            androidx.compose.animation.AnimatedVisibility(visible = query.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (matchingLines.isEmpty()) "没有匹配行" else "${matchIndex + 1} / ${matchingLines.size} 个匹配行",
                        color = ReaderMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { matchIndex = (matchIndex - 1 + matchingLines.size) % matchingLines.size }, enabled = matchingLines.isNotEmpty()) {
                        Icon(Icons.Default.KeyboardArrowUp, "上一个匹配行", tint = if (matchingLines.isEmpty()) ReaderMuted else ReaderAccent)
                    }
                    IconButton(onClick = { matchIndex = (matchIndex + 1) % matchingLines.size }, enabled = matchingLines.isNotEmpty()) {
                        Icon(Icons.Default.KeyboardArrowDown, "下一个匹配行", tint = if (matchingLines.isEmpty()) ReaderMuted else ReaderAccent)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onFontSize((fontSize - 1).coerceAtLeast(11)) }, enabled = fontSize > 11,
                    modifier = Modifier.semantics { contentDescription = "缩小原文字号" }) { Text("A−", color = ReaderMuted) }
                Text("$fontSize", color = ReaderMuted, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { onFontSize((fontSize + 1).coerceAtMost(20)) }, enabled = fontSize < 20,
                    modifier = Modifier.semantics { contentDescription = "放大原文字号" }) { Text("A+", color = ReaderAccent) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { wrap = !wrap }, modifier = Modifier.semantics { stateDescription = if (wrap) "自动换行已开启" else "自动换行已关闭" }) {
                    Text(if (wrap) "自动换行" else "横向查看", color = ReaderAccent)
                }
            }
            HorizontalDivider(color = Color.White.copy(alpha = 0.10f))
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
                                            addStyle(SpanStyle(background = Color(0xFF4D431F), color = Color(0xFFFFE7A0)), start, start + query.length)
                                            start = line.indexOf(query, start + query.length, ignoreCase = true)
                                        }
                                    }
                                }
                            }
                            Text(highlighted, color = ReaderForeground, softWrap = wrap,
                                style = MonoStyle.copy(fontSize = fontSize.sp, lineHeight = (fontSize * 1.55f).sp),
                                modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
        }
    }
}
