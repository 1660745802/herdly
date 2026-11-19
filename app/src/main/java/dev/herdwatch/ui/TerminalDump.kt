package dev.herdwatch.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdwatch.ui.theme.LocalTerminalTokens
import dev.herdwatch.ui.theme.MonoStyle

/**
 * A pane's screen, drawn as a terminal instead of as a document.
 *
 * The pane page used to render its snapshot as one wrapped [Text] inside a padded, scrollable
 * column. That is a code block: lines re-flow at the viewport width, column-aligned output
 * (`ls -l`, `docker ps`, `git log --graph`, `htop`) stops lining up, and the page reads like a chat
 * message with a terminal screenshot in it. A terminal is a fixed cell grid that pans sideways, and
 * that is what this draws: monospace cells, one row per line, no wrapping, horizontal pan, and the
 * same ink as the SSH page.
 *
 * It is a *dump*, not a live VT: `pane.read` hands back rendered rows, so there is no cursor and no
 * alternate screen to drive. Scrolling is local; the caller only learns whether the reader is
 * following the tail, so new output can avoid yanking the view, and when the reader reached the top,
 * so older lines can be requested.
 */
@Composable
internal fun TerminalDump(
    text: String,
    modifier: Modifier = Modifier,
    fontSize: Float = 13f,
    wrap: Boolean = false,
    followTick: Long = 0,
    onStickToBottom: (Boolean) -> Unit = {},
    onReachedTop: () -> Unit = {},
) {
    val term = LocalTerminalTokens.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val lines = remember(text) { text.split('\n') }
    val listState = rememberLazyListState()
    val gridScroll = rememberScrollState()

    val lineHeightSp = fontSize * 1.3f
    val textStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = fontSize.sp,
        lineHeight = lineHeightSp.sp,
        color = term.text,
    )

    // Measured once per font size from a run of glyphs: a single-glyph measurement rounds to whole
    // pixels, and that error would leave the right-most column of a 200-column build log unreachable.
    val probeStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = fontSize.sp)
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSize) {
        (measurer.measure("M".repeat(PROBE_COLUMNS), probeStyle).size.width.toFloat() / PROBE_COLUMNS)
            .coerceAtLeast(1f)
    }
    val widest = remember(lines) { lines.maxOfOrNull { ansiCells(it) } ?: 0 }
    val charWidth = with(density) { charWidthPx.toDp() }

    BoxWithConstraints(modifier.fillMaxSize().padding(horizontal = 6.dp)) {
        // The grid is never narrower than the viewport: a short dump should still fill the well, and
        // a narrow one should not leave a hairline of chassis ink at the right edge.
        val cols = maxOf(widest, (maxWidth / charWidth).toInt() + 1)
        val gridWidth = with(density) { (charWidthPx * cols).toDp() }

        val stickToBottom = remember { androidx.compose.runtime.derivedStateOf { !listState.canScrollForward } }

        LaunchedEffect(listState) {
            snapshotFlow { !listState.canScrollForward }.collect(onStickToBottom)
        }

        // Follow the tail only while the reader is already there. Scrolling up to read means the
        // screen must not move under the thumb every time the pane emits a line.
        LaunchedEffect(text, stickToBottom.value) {
            val last = lines.lastIndex
            if (last >= 0 && stickToBottom.value) listState.scrollToItem(last)
        }
        LaunchedEffect(followTick) {
            val last = lines.lastIndex
            if (last >= 0) listState.scrollToItem(last)
        }
        // Reaching the first row is the request for more history. Unlike the old pixel trigger this
        // needs no anchor bookkeeping: older lines are prepended by the host into a bigger tail, so
        // the row the reader is on keeps its index and the view stays put.
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> if (index == 0 && offset <= 4) onReachedTop() }
        }

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(gridScroll)),
            ) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = ansiAnnotated(line, term.text, term.background),
                        style = textStyle,
                        softWrap = wrap,
                        maxLines = if (wrap) Int.MAX_VALUE else 1,
                        modifier = if (wrap) Modifier else Modifier.width(gridWidth),
                    )
                }
            }
        }
    }
}

/**
 * Visible cells in one row, ignoring colour escapes.
 *
 * Uses the same width heuristic as the renderer: a wide rune painted with a single advance would
 * otherwise make the grid too narrow and clip the last columns it occupies.
 */
private fun ansiCells(line: String): Int = stripAnsi(line).length

private const val PROBE_COLUMNS = 40
