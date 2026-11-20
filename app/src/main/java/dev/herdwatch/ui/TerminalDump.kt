package dev.herdwatch.ui

import dev.herdwatch.ui.design.DsMono

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdwatch.ui.theme.LocalTerminalTokens

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
    // Absolute line ordinals for stable LazyColumn keys. Index-based keys shift when older lines
    // are prepended, which re-anchors firstVisibleItemIndex to different content and jumps the view.
    val identityState = remember { mutableStateOf(emptyLineIndex<String>()) }
    val lineIndex = remember(lines) {
        advanceLineIndex(identityState.value, lines).also { identityState.value = it }
    }
    val listState = rememberLazyListState()
    val gridScroll = rememberScrollState()

    val lineHeightSp = fontSize * 1.3f
    val textStyle = TextStyle(
        fontFamily = DsMono,
        fontSize = fontSize.sp,
        lineHeight = lineHeightSp.sp,
        color = term.text,
    )

    // Measured once per font size from a run of glyphs: a single-glyph measurement rounds to whole
    // pixels, and that error would leave the right-most column of a 200-column build log unreachable.
    val probeStyle = TextStyle(fontFamily = DsMono, fontSize = fontSize.sp)
    val measurer = rememberTextMeasurer()
    val charWidthPx = remember(fontSize, probeStyle, measurer, density) {
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
        // Reaching the first row is the request for more history. Older lines are prepended by the
        // host into a bigger tail, so the row the reader is on does NOT keep its index — the stable
        // ordinal keys above are what let LazyColumn re-anchor the first visible row by identity
        // and keep the view on the same content.
        //
        // The trigger fires once per visit to the top of actually-scrollable content: short initial
        // content has nothing to scroll, and parking at the top must not re-request history on every
        // layout pass. It re-arms only after the reader scrolls away from the top.
        LaunchedEffect(listState) {
            val trigger = TopReachTrigger()
            snapshotFlow {
                val atTop = listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset <= 4
                val scrollable = listState.canScrollBackward || listState.canScrollForward
                atTop to scrollable
            }.collect { (atTop, scrollable) -> if (trigger.update(atTop, scrollable)) onReachedTop() }
        }

        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().then(if (wrap) Modifier else Modifier.horizontalScroll(gridScroll)),
            ) {
                itemsIndexed(lines, key = { index, _ -> lineIndex.keys[index] }) { _, line ->
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
internal fun ansiCells(line: String): Int = terminalCells(stripAnsi(line))

/**
 * Terminal cells in plain text, counting code points (not UTF-16 units) with East-Asian width:
 * CJK/fullwidth/emoji occupy 2 cells, combining marks and zero-width characters 0, everything
 * else 1. Counting UTF-16 units mis-sized every CJK or emoji row and misaligned the grid.
 *
 * Limitation (deliberate): rows are still rendered as one Text each, not as a positioned cell
 * grid, so column alignment ultimately depends on the monospace font painting wide glyphs at
 * exactly twice the ASCII advance. This function makes the measured grid width correct; a full
 * cell-grid renderer is out of scope here.
 */
internal fun terminalCells(text: String): Int {
    var cells = 0
    var index = 0
    while (index < text.length) {
        val cp = text.codePointAt(index)
        cells += terminalCellWidth(cp)
        index += Character.charCount(cp)
    }
    return cells
}

/**
 * Cell width of one code point. Mirrors the live renderer's heuristic in
 * `dev.herdwatch.term.VtScreen.charWidth` (private there); keep the two in sync.
 */
internal fun terminalCellWidth(cp: Int): Int {
    if (cp == 0 || cp < 0x20 || cp in 0x7F..0x9F) return 0
    if (cp in 0x200B..0x200F || cp == 0x2060 || cp == 0xFEFF) return 0
    if (cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF) return 0
    when (Character.getType(cp)) {
        Character.NON_SPACING_MARK.toInt(),
        Character.ENCLOSING_MARK.toInt(),
        Character.COMBINING_SPACING_MARK.toInt(),
        -> return 0
    }
    return when (cp) {
        in 0x1100..0x115F -> 2
        in 0x2E80..0x303E -> 2
        in 0x3041..0x33FF -> 2
        in 0x3400..0x4DBF -> 2
        in 0x4E00..0x9FFF -> 2
        in 0xA000..0xA4CF -> 2
        in 0xA960..0xA97F -> 2
        in 0xAC00..0xD7A3 -> 2
        in 0xF900..0xFAFF -> 2
        in 0xFE10..0xFE19 -> 2
        in 0xFE30..0xFE6F -> 2
        in 0xFF00..0xFF60 -> 2
        in 0xFFE0..0xFFE6 -> 2
        in 0x1F300..0x1FAFF -> 2
        in 0x20000..0x3FFFD -> 2
        else -> 1
    }
}

/**
 * Stable identities for a window of terminal lines.
 *
 * [keys] holds one absolute ordinal per line in [lines]; ordinals come from a monotonic counter
 * ([nextOrdinal]) and are never reused, so a line keeps its identity when older lines are
 * prepended or newer ones appended — which is what lets LazyColumn anchor scrolling by content
 * instead of by shifting index.
 */
internal data class LineIndex<T>(
    val lines: List<T>,
    val keys: List<Long>,
    val nextOrdinal: Long,
)

internal fun <T> emptyLineIndex(): LineIndex<T> = LineIndex(emptyList(), emptyList(), 0L)

/**
 * Re-keys [lines] against the previous window, preserving ordinals wherever content overlaps.
 *
 * Two overlap shapes cover every terminal update: appending/scrolling forward (the old window's
 * tail is the new window's head) and prepending history (the old window's head is the new
 * window's tail). The larger overlap wins; unrelated content gets fresh ordinals. Duplicate
 * lines are handled because the match is positional over the whole region, not per-line content.
 */
internal fun <T> advanceLineIndex(previous: LineIndex<T>, lines: List<T>): LineIndex<T> {
    if (lines == previous.lines) return previous
    val max = minOf(previous.lines.size, lines.size)
    // Old tail == new head: append, or the visible window sliding forward over scrollback.
    var head = max
    while (head > 0 && !regionEquals(previous.lines, previous.lines.size - head, lines, 0, head)) head--
    // Old head == new tail: older history prepended above the rows already on screen.
    var tail = max
    while (tail > 0 && !regionEquals(previous.lines, 0, lines, lines.size - tail, tail)) tail--
    var next = previous.nextOrdinal
    val keys = when {
        head >= tail && head > 0 ->
            previous.keys.takeLast(head) + List(lines.size - head) { next++ }
        tail > 0 ->
            List(lines.size - tail) { next++ } + previous.keys.take(tail)
        else -> List(lines.size) { next++ }
    }
    return LineIndex(lines, keys, next)
}

private fun <T> regionEquals(a: List<T>, aFrom: Int, b: List<T>, bFrom: Int, count: Int): Boolean {
    for (i in 0 until count) {
        if (a[aFrom + i] != b[bFrom + i]) return false
    }
    return true
}

/**
 * Edge trigger for "the reader reached the top": fires once per visit, and only when the content
 * actually scrolls. A level trigger re-requested history on every layout pass while parked at the
 * top, and fired on initial content too short to scroll at all.
 */
internal class TopReachTrigger {
    private var fired = false

    fun update(atTop: Boolean, scrollable: Boolean): Boolean {
        if (!atTop || !scrollable) {
            fired = false
            return false
        }
        if (fired) return false
        fired = true
        return true
    }
}

private const val PROBE_COLUMNS = 40
