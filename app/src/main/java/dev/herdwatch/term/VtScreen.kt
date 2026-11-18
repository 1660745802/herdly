package dev.herdwatch.term

/**
 * A VT100/xterm-compatible-enough screen buffer for an interactive SSH shell.
 *
 * Pure JVM: no Android types, so it can be exercised by `testDebugUnitTest`. All mutating
 * entry points are `@Synchronized`; a UI thread may call [snapshot] while the IO thread is
 * inside [feed].
 *
 * Colours are xterm palette indices 0..255, or `null` for "use the theme default". A
 * truecolour value is stored as `0x1000000 or (r shl 16) or (g shl 8) or b` — the high bit
 * cannot collide with a palette index in 0..255. Consumers must check
 * `value >= 0x1000000` before treating a colour as a palette index.
 *
 * Deliberately unsupported: reflow on resize, sixel/kitty graphics, mouse reporting
 * (DECSET 1000..1006 are swallowed) and focus events (1004) are tracked by nothing — the UI
 * layer decides those policies itself. Bracketed paste (DECSET 2004) *is* tracked and exposed
 * on [VtSnapshot.bracketedPaste] so the UI can wrap clipboard pastes in ESC[200~ .. ESC[201~.
 */
class VtScreen(
    cols: Int = 80,
    rows: Int = 24,
    private val maxScrollback: Int = 2000,
) {
    private var width = cols.coerceAtLeast(1)
    private var height = rows.coerceAtLeast(1)

    /** Active grid. While the alternate screen is up, [primaryGrid] holds the other one. */
    private var grid: Array<Row> = newGrid(height, width)
    private var primaryGrid: Array<Row>? = null
    private val scrollback = ArrayDeque<VtLine>()

    private var alternate = false
    private var cursorRow = 0
    private var cursorCol = 0
    private var wrapPending = false
    private var cursorVisible = true
    private var autowrap = true
    private var appCursorKeys = false
    /** DECSET 2004: the remote wants clipboard pastes wrapped in ESC[200~ .. ESC[201~. */
    private var bracketedPaste = false
    private var scrollTop = 0
    private var scrollBottom = height - 1
    private val pen = Pen()
    private var savedCursor: SavedCursor? = null
    private var alternatesReturn: SavedCursor? = null

    private var revision = 0L
    private var dirty = false

    // Parser state.
    private var state = S_GROUND
    private var params = IntArray(16)
    private var paramCount = 0
    private var digit = -1
    private var prefix = 0
    private var intermediate = 0
    private var carry = ByteArray(4)
    private var carryLen = 0

    @Synchronized
    fun resize(cols: Int, rows: Int) {
        val newWidth = cols.coerceAtLeast(1)
        val newHeight = rows.coerceAtLeast(1)
        if (newWidth == width && newHeight == height) return
        val dropped = (grid.size - newHeight).coerceAtLeast(0)
        if (!alternate) {
            for (i in 0 until minOf(dropped, grid.size)) pushScrollback(renderRow(grid[i]))
        }
        grid = resizedGrid(grid, newWidth, newHeight)
        primaryGrid = primaryGrid?.let { resizedGrid(it, newWidth, newHeight) }
        if (dropped > 0) cursorRow = (cursorRow - dropped).coerceAtLeast(0)
        width = newWidth
        height = newHeight
        cursorRow = cursorRow.coerceIn(0, height - 1)
        cursorCol = cursorCol.coerceIn(0, width - 1)
        scrollTop = 0
        scrollBottom = height - 1
        wrapPending = false
        markDirty()
    }

    @Synchronized
    fun feed(bytes: ByteArray, length: Int = bytes.size) {
        val count = length.coerceIn(0, bytes.size)
        if (count == 0) return
        val data: ByteArray = if (carryLen > 0) {
            ByteArray(carryLen + count).also { merged ->
                System.arraycopy(carry, 0, merged, 0, carryLen)
                System.arraycopy(bytes, 0, merged, carryLen, count)
            }
        } else {
            bytes
        }
        var i = 0
        // `data` may be the caller's array (no carry), so the limit is the merged length we own.
        val end = if (data === bytes) count else data.size
        while (i < end) {
            val b0 = data[i].toInt() and 0xFF
            if (b0 < 0x80) {
                handleCodePoint(b0)
                i++
                continue
            }
            val size = when (b0) {
                in 0xC2..0xDF -> 2
                in 0xE0..0xEF -> 3
                in 0xF0..0xF4 -> 4
                else -> 0
            }
            if (size == 0) {
                handleCodePoint(REPLACEMENT)
                i++
                continue
            }
            if (i + size > end) break
            var cp = b0 and (0x7F shr size)
            var valid = true
            for (k in 1 until size) {
                val b = data[i + k].toInt() and 0xFF
                if (b and 0xC0 != 0x80) {
                    valid = false
                    break
                }
                cp = (cp shl 6) or (b and 0x3F)
            }
            if (!valid || cp > 0x10FFFF || cp in 0xD800..0xDFFF) {
                handleCodePoint(REPLACEMENT)
                i++
                continue
            }
            handleCodePoint(cp)
            i += size
        }
        carryLen = 0
        if (i < end) {
            carryLen = (end - i).coerceAtMost(carry.size)
            System.arraycopy(data, i, carry, 0, carryLen)
        }
        if (dirty) {
            dirty = false
            revision++
        }
    }

    @Synchronized
    fun snapshot(): VtSnapshot {
        val lines = ArrayList<VtLine>(scrollback.size + height)
        lines.addAll(scrollback)
        for (row in grid) lines.add(renderRow(row))
        return VtSnapshot(
            cols = width,
            rows = height,
            revision = revision,
            cursorRow = cursorRow,
            cursorCol = cursorCol,
            cursorVisible = cursorVisible,
            lines = lines,
            scrollbackCount = scrollback.size,
            alternateScreen = alternate,
            applicationCursorKeys = appCursorKeys,
            bracketedPaste = bracketedPaste,
        )
    }

    // ------------------------------------------------------------------ parser

    private fun handleCodePoint(cp: Int) {
        when (state) {
            S_GROUND -> ground(cp)
            S_ESCAPE -> escape(cp)
            S_ESCAPE_SINGLE -> state = S_GROUND
            S_CSI -> csi(cp)
            S_OSC -> osc(cp)
            S_STRING -> string(cp)
            S_OSC_ESC -> when (cp) {
                ESC -> Unit // stay; a second ESC is just another sequence start
                BACKSLASH -> state = S_GROUND
                else -> {
                    state = S_GROUND
                    handleCodePoint(cp)
                }
            }
            S_STRING_ESC -> when (cp) {
                ESC -> Unit
                BACKSLASH -> state = S_GROUND
                else -> {
                    state = S_GROUND
                    handleCodePoint(cp)
                }
            }
            else -> state = S_GROUND
        }
    }

    private fun ground(cp: Int) {
        when (cp) {
            ESC -> {
                state = S_ESCAPE
                return
            }
            0x9B -> {
                beginCsi()
                return
            }
            0x9D -> {
                state = S_OSC
                return
            }
            0x9C -> return
        }
        when {
            cp < 0x20 -> control(cp)
            cp == 0x7F -> Unit
            cp in 0x80..0x9F -> Unit
            else -> writeCodePoint(cp)
        }
    }

    private fun control(cp: Int) {
        when (cp) {
            BS -> {
                if (cursorCol > 0) cursorCol--
                wrapPending = false
                markDirty()
            }
            TAB -> {
                cursorCol = ((cursorCol / 8) + 1) * 8
                if (cursorCol > width - 1) cursorCol = width - 1
                wrapPending = false
                markDirty()
            }
            LF, 0x0B, 0x0C -> lineFeed()
            CR -> {
                cursorCol = 0
                wrapPending = false
                markDirty()
            }
            BEL -> Unit
            CAN, SUB -> state = S_GROUND
        }
    }

    private fun escape(cp: Int) {
        when (cp.toChar()) {
            '[' -> beginCsi()
            ']' -> state = S_OSC
            'P', '_', '^', 'X' -> state = S_STRING
            '7' -> {
                saveCursor()
                state = S_GROUND
            }
            '8' -> {
                restoreCursor()
                state = S_GROUND
            }
            'D' -> {
                lineFeed()
                state = S_GROUND
            }
            'E' -> {
                cursorCol = 0
                lineFeed()
                state = S_GROUND
            }
            'M' -> {
                reverseLineFeed()
                state = S_GROUND
            }
            'c' -> {
                resetAll()
                state = S_GROUND
            }
            '(', ')', '*', '+', '-', '.', '/', '#', '%', 'N', 'O' -> state = S_ESCAPE_SINGLE
            '=', '>' -> state = S_GROUND // keypad modes: nothing to track
            else -> state = S_GROUND
        }
    }

    private fun beginCsi() {
        state = S_CSI
        paramCount = 0
        digit = -1
        prefix = 0
        intermediate = 0
    }

    private fun csi(cp: Int) {
        when {
            cp == ESC -> state = S_ESCAPE
            cp < 0x20 -> control(cp)
            cp == 0x7F -> Unit
            cp in '0'.code..'9'.code -> {
                val d = cp - '0'.code
                digit = if (digit < 0) d else (digit * 10 + d).coerceAtMost(65535)
            }
            cp == ';'.code || cp == ':'.code || cp == ','.code -> pushParam()
            cp in 0x3C..0x3F -> prefix = cp
            cp in 0x20..0x2F -> intermediate = cp
            cp in 0x40..0x7E -> {
                pushParam()
                dispatchCsi(cp)
                state = S_GROUND
                prefix = 0
                intermediate = 0
            }
        }
    }

    private fun osc(cp: Int) {
        if (cp == BEL) state = S_GROUND
        else if (cp == ESC) state = S_OSC_ESC
    }

    private fun string(cp: Int) {
        if (cp == ESC) state = S_STRING_ESC
    }

    private fun pushParam() {
        if (paramCount == params.size) params = params.copyOf(params.size * 2)
        params[paramCount++] = digit
        digit = -1
    }

    private fun param(index: Int, default: Int): Int {
        val value = if (index < paramCount) params[index] else -1
        return if (value < 0) default else value
    }

    private fun paramOr1(index: Int): Int = param(index, 1).coerceAtLeast(1)

    // ------------------------------------------------------------------ CSI dispatch

    private fun dispatchCsi(final: Int) {
        when (final) {
            'A'.code -> cursorUp(paramOr1(0))
            'B'.code, 'e'.code -> cursorDown(paramOr1(0))
            'C'.code, 'a'.code -> cursorForward(paramOr1(0))
            'D'.code -> cursorBackward(paramOr1(0))
            'E'.code -> {
                cursorDown(paramOr1(0))
                cursorCol = 0
            }
            'F'.code -> {
                cursorUp(paramOr1(0))
                cursorCol = 0
            }
            'G'.code, '`'.code -> {
                cursorCol = (paramOr1(0) - 1).coerceIn(0, width - 1)
                wrapPending = false
                markDirty()
            }
            'H'.code, 'f'.code -> {
                cursorRow = (paramOr1(0) - 1).coerceIn(0, height - 1)
                cursorCol = (paramOr1(1) - 1).coerceIn(0, width - 1)
                wrapPending = false
                markDirty()
            }
            'd'.code -> {
                cursorRow = (paramOr1(0) - 1).coerceIn(0, height - 1)
                wrapPending = false
                markDirty()
            }
            'J'.code -> eraseDisplay(param(0, 0))
            'K'.code -> eraseLine(param(0, 0))
            'L'.code -> insertLines(paramOr1(0))
            'M'.code -> deleteLines(paramOr1(0))
            'P'.code -> deleteChars(paramOr1(0))
            '@'.code -> insertChars(paramOr1(0))
            'X'.code -> eraseChars(paramOr1(0))
            'S'.code -> scrollUp(scrollTop, scrollBottom, paramOr1(0))
            'T'.code -> scrollDown(scrollTop, scrollBottom, paramOr1(0))
            'm'.code -> selectGraphicRendition()
            'h'.code -> setModes(true)
            'l'.code -> setModes(false)
            'r'.code -> setScrollRegion()
            's'.code -> saveCursor()
            'u'.code -> restoreCursor()
            'g'.code, 'b'.code, 'n'.code, 't'.code, 'q'.code -> Unit
            'I'.code -> repeat(paramOr1(0)) { control(TAB) }
            'Z'.code -> repeat(paramOr1(0)) {
                cursorCol = if (cursorCol == 0) 0 else ((cursorCol - 1) / 8) * 8
                wrapPending = false
                markDirty()
            }
            'c'.code -> if (prefix == '?'.code) Unit else resetAll()
            'p'.code -> if (intermediate == '!'.code) softReset()
            'k'.code -> Unit
            else -> Unit
        }
    }

    private fun setModes(on: Boolean) {
        if (prefix != '?'.code) return
        for (i in 0 until paramCount) {
            when (params[i]) {
                1 -> appCursorKeys = on
                7 -> autowrap = on
                25 -> cursorVisible = on
                47, 1047 -> setAlternateScreen(on, clear = true, restoreCursor = false)
                1049 -> setAlternateScreen(on, clear = true, restoreCursor = true)
                2004 -> bracketedPaste = on
                6, 12, 1000, 1002, 1003, 1004, 1005, 1006 -> Unit // not emulated
                else -> Unit
            }
        }
        markDirty()
    }

    private fun setScrollRegion() {
        val top = (paramOr1(0) - 1).coerceIn(0, height - 1)
        val bottom = (param(1, height) - 1).coerceIn(0, height - 1)
        if (bottom <= top) {
            scrollTop = 0
            scrollBottom = height - 1
        } else {
            scrollTop = top
            scrollBottom = bottom
        }
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
        markDirty()
    }

    private fun selectGraphicRendition() {
        var i = 0
        if (paramCount == 0) {
            pen.reset()
            markDirty()
            return
        }
        while (i < paramCount) {
            when (val code = params[i].let { if (it < 0) 0 else it }) {
                0 -> pen.reset()
                1 -> pen.bold = true
                2 -> pen.dim = true
                3 -> pen.italic = true
                4 -> pen.underline = true
                7 -> pen.inverse = true
                22 -> {
                    pen.bold = false
                    pen.dim = false
                }
                23 -> pen.italic = false
                24 -> pen.underline = false
                27 -> pen.inverse = false
                39 -> pen.fg = DEFAULT_COLOR
                49 -> pen.bg = DEFAULT_COLOR
                in 30..37 -> pen.fg = code - 30
                in 40..47 -> pen.bg = code - 40
                in 90..97 -> pen.fg = code - 90 + 8
                in 100..107 -> pen.bg = code - 100 + 8
                38, 48 -> {
                    val isForeground = code == 38
                    when (params.getOrNull(i + 1)) {
                        5 -> {
                            val n = params.getOrNull(i + 2) ?: 0
                            if (isForeground) pen.fg = n.coerceIn(0, 255) else pen.bg = n.coerceIn(0, 255)
                            i += 2
                        }
                        2 -> {
                            val r = (params.getOrNull(i + 2) ?: 0).coerceIn(0, 255)
                            val g = (params.getOrNull(i + 3) ?: 0).coerceIn(0, 255)
                            val b = (params.getOrNull(i + 4) ?: 0).coerceIn(0, 255)
                            val rgb = TRUE_COLOR_FLAG or (r shl 16) or (g shl 8) or b
                            if (isForeground) pen.fg = rgb else pen.bg = rgb
                            i += 4
                        }
                        else -> Unit
                    }
                }
            }
            i++
        }
        markDirty()
    }

    // ------------------------------------------------------------------ text output

    private fun writeCodePoint(cp: Int) {
        val cells = charWidth(cp)
        if (cells == 0) {
            appendCombining(cp)
            return
        }
        if (wrapPending) {
            wrapPending = false
            cursorCol = 0
            lineFeed()
        }
        if (cells == 2 && cursorCol >= width - 1) {
            if (autowrap) {
                cursorCol = 0
                lineFeed()
            } else {
                cursorCol = width - 1
            }
        }
        val row = grid[cursorRow]
        clearWideNeighbour(row, cursorCol)
        val cell = row.cells[cursorCol]
        cell.reset()
        cell.cp = cp
        cell.wide = cells == 2
        cell.styleFrom(pen)
        val last = cursorCol + cells - 1
        if (cells == 2 && cursorCol + 1 < width) {
            clearWideNeighbour(row, cursorCol + 1)
            val continuation = row.cells[cursorCol + 1]
            continuation.reset()
            continuation.cont = true
            continuation.styleFrom(pen)
        }
        if (last >= width - 1) {
            cursorCol = width - 1
            if (autowrap) wrapPending = true
        } else {
            cursorCol = last + 1
        }
        markDirty()
    }

    private fun appendCombining(cp: Int) {
        var index = if (wrapPending) cursorCol else cursorCol - 1
        if (index < 0) return
        val row = grid[cursorRow]
        if (index < width && row.cells[index].cont && index > 0) index--
        val cell = row.cells[index]
        val builder = cell.combining ?: StringBuilder(4).also { cell.combining = it }
        builder.appendCodePoint(cp)
        markDirty()
    }

    /** Keep the two halves of wide characters from leaking into each other. */
    private fun clearWideNeighbour(row: Row, col: Int) {
        val cell = row.cells[col]
        if (cell.cont && col > 0) row.cells[col - 1].reset()
        if (cell.wide && col + 1 < width) row.cells[col + 1].reset()
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            scrollUp(scrollTop, scrollBottom, 1)
        } else if (cursorRow < height - 1) {
            cursorRow++
        }
        wrapPending = false
        markDirty()
    }

    private fun reverseLineFeed() {
        if (cursorRow == scrollTop) {
            scrollDown(scrollTop, scrollBottom, 1)
        } else if (cursorRow > 0) {
            cursorRow--
        }
        wrapPending = false
        markDirty()
    }

    private fun cursorUp(n: Int) {
        cursorRow = (cursorRow - n).coerceAtLeast(0)
        wrapPending = false
        markDirty()
    }

    private fun cursorDown(n: Int) {
        cursorRow = (cursorRow + n).coerceAtMost(height - 1)
        wrapPending = false
        markDirty()
    }

    private fun cursorForward(n: Int) {
        cursorCol = (cursorCol + n).coerceAtMost(width - 1)
        wrapPending = false
        markDirty()
    }

    private fun cursorBackward(n: Int) {
        cursorCol = (cursorCol - n).coerceAtLeast(0)
        wrapPending = false
        markDirty()
    }

    // ------------------------------------------------------------------ scrolling

    private fun scrollUp(top: Int, bottom: Int, count: Int) {
        if (bottom < top) return
        val lines = count.coerceIn(1, bottom - top + 1)
        repeat(lines) {
            val removed = grid[top]
            if (top == 0 && !alternate) pushScrollback(renderRow(removed))
            for (i in top until bottom) grid[i] = grid[i + 1]
            grid[bottom] = blankRow()
        }
        markDirty()
    }

    private fun scrollDown(top: Int, bottom: Int, count: Int) {
        if (bottom < top) return
        val lines = count.coerceIn(1, bottom - top + 1)
        repeat(lines) {
            for (i in bottom downTo top + 1) grid[i] = grid[i - 1]
            grid[top] = blankRow()
        }
        markDirty()
    }

    private fun pushScrollback(line: VtLine) {
        if (maxScrollback <= 0) return
        while (scrollback.size >= maxScrollback) scrollback.removeFirst()
        scrollback.addLast(line)
    }

    // ------------------------------------------------------------------ erase / edit

    private fun eraseDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseCells(cursorRow, cursorCol, width)
                for (r in cursorRow + 1 until height) eraseCells(r, 0, width)
            }
            1 -> {
                eraseCells(cursorRow, 0, cursorCol + 1)
                for (r in 0 until cursorRow) eraseCells(r, 0, width)
            }
            2 -> for (r in 0 until height) eraseCells(r, 0, width)
            3 -> {
                scrollback.clear()
                for (r in 0 until height) eraseCells(r, 0, width)
            }
        }
        markDirty()
    }

    private fun eraseLine(mode: Int) {
        when (mode) {
            0 -> eraseCells(cursorRow, cursorCol, width)
            1 -> eraseCells(cursorRow, 0, cursorCol + 1)
            2 -> eraseCells(cursorRow, 0, width)
        }
        markDirty()
    }

    private fun eraseChars(n: Int) {
        eraseCells(cursorRow, cursorCol, cursorCol + n)
        markDirty()
    }

    private fun eraseCells(row: Int, from: Int, to: Int) {
        val line = grid[row]
        for (c in from.coerceAtLeast(0) until to.coerceAtMost(width)) {
            if (line.cells[c].cont && c > 0) line.cells[c - 1].reset()
            if (line.cells[c].wide && c + 1 < width) line.cells[c + 1].reset()
            line.cells[c].reset()
            line.cells[c].bg = pen.bg
        }
    }

    private fun insertChars(n: Int) {
        val row = grid[cursorRow]
        val old = row.cells
        val shift = n.coerceIn(1, width - cursorCol)
        val fresh = Array(width) { Cell() }
        for (c in 0 until cursorCol) fresh[c] = old[c]
        for (c in cursorCol until width) {
            val source = c - shift
            if (source < cursorCol) {
                fresh[c].bg = pen.bg
            } else {
                fresh[c] = old[source]
            }
        }
        row.cells = fresh
        markDirty()
    }

    private fun deleteChars(n: Int) {
        val row = grid[cursorRow]
        val old = row.cells
        val shift = n.coerceIn(1, width - cursorCol)
        val fresh = Array(width) { Cell() }
        for (c in 0 until cursorCol) fresh[c] = old[c]
        for (c in cursorCol until width) {
            val source = c + shift
            if (source < width) {
                fresh[c] = old[source]
            } else {
                fresh[c].bg = pen.bg
            }
        }
        row.cells = fresh
        markDirty()
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - cursorRow + 1)
        for (r in scrollBottom downTo cursorRow + count) grid[r] = grid[r - count]
        for (r in cursorRow until cursorRow + count) grid[r] = blankRow()
        markDirty()
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceIn(1, scrollBottom - cursorRow + 1)
        for (r in cursorRow..scrollBottom - count) grid[r] = grid[r + count]
        for (r in scrollBottom - count + 1..scrollBottom) grid[r] = blankRow()
        markDirty()
    }

    // ------------------------------------------------------------------ screens & modes

    private fun setAlternateScreen(on: Boolean, clear: Boolean, restoreCursor: Boolean) {
        if (on == alternate) return
        if (on) {
            if (restoreCursor) alternatesReturn = captureCursor()
            primaryGrid = grid
            grid = newGrid(height, width)
            cursorRow = 0
            cursorCol = 0
            wrapPending = false
            alternate = true
        } else {
            alternate = false
            grid = primaryGrid ?: newGrid(height, width)
            primaryGrid = null
            if (restoreCursor) {
                alternatesReturn?.let { applyCursor(it) }
                alternatesReturn = null
            }
        }
        markDirty()
    }

    private fun saveCursor() {
        savedCursor = captureCursor()
    }

    private fun restoreCursor() {
        savedCursor?.let { applyCursor(it) }
        markDirty()
    }

    private fun captureCursor(): SavedCursor = SavedCursor(
        row = cursorRow,
        col = cursorCol,
        wrapPending = wrapPending,
        fg = pen.fg,
        bg = pen.bg,
        bold = pen.bold,
        dim = pen.dim,
        italic = pen.italic,
        underline = pen.underline,
        inverse = pen.inverse,
    )

    private fun applyCursor(saved: SavedCursor) {
        cursorRow = saved.row.coerceIn(0, height - 1)
        cursorCol = saved.col.coerceIn(0, width - 1)
        wrapPending = saved.wrapPending
        pen.fg = saved.fg
        pen.bg = saved.bg
        pen.bold = saved.bold
        pen.dim = saved.dim
        pen.italic = saved.italic
        pen.underline = saved.underline
        pen.inverse = saved.inverse
    }

    private fun softReset() {
        pen.reset()
        cursorVisible = true
        autowrap = true
        appCursorKeys = false
        bracketedPaste = false
        scrollTop = 0
        scrollBottom = height - 1
        wrapPending = false
        markDirty()
    }

    private fun resetAll() {
        alternate = false
        primaryGrid = null
        savedCursor = null
        alternatesReturn = null
        grid = newGrid(height, width)
        scrollback.clear()
        scrollTop = 0
        scrollBottom = height - 1
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
        softReset()
        state = S_GROUND
        carryLen = 0
    }

    // ------------------------------------------------------------------ rendering

    private fun renderRow(row: Row): VtLine {
        val runs = ArrayList<VtRun>(4)
        val text = StringBuilder(width)
        var fg = DEFAULT_COLOR
        var bg = DEFAULT_COLOR
        var bold = false
        var dim = false
        var italic = false
        var underline = false
        var inverse = false
        var started = false

        fun flush() {
            if (text.isEmpty()) return
            runs.add(
                VtRun(
                    text = text.toString(),
                    fg = if (fg == DEFAULT_COLOR) null else fg,
                    bg = if (bg == DEFAULT_COLOR) null else bg,
                    bold = bold,
                    dim = dim,
                    italic = italic,
                    underline = underline,
                    inverse = inverse,
                )
            )
            text.setLength(0)
        }

        for (cell in row.cells) {
            if (cell.cont) continue
            if (!started) {
                started = true
                fg = cell.fg
                bg = cell.bg
                bold = cell.bold
                dim = cell.dim
                italic = cell.italic
                underline = cell.underline
                inverse = cell.inverse
            } else if (
                cell.fg != fg || cell.bg != bg || cell.bold != bold || cell.dim != dim ||
                cell.italic != italic || cell.underline != underline || cell.inverse != inverse
            ) {
                flush()
                fg = cell.fg
                bg = cell.bg
                bold = cell.bold
                dim = cell.dim
                italic = cell.italic
                underline = cell.underline
                inverse = cell.inverse
            }
            text.appendCodePoint(cell.cp)
            cell.combining?.let { text.append(it) }
        }
        flush()
        return VtLine(runs)
    }

    // ------------------------------------------------------------------ grid helpers

    private fun newGrid(rows: Int, cols: Int): Array<Row> = Array(rows) { Row(cols) }

    private fun blankRow(): Row = Row(width)

    /** Resizes [source] and keeps the bottom [newHeight] rows; the caller handles the rest. */
    private fun resizedGrid(source: Array<Row>, newWidth: Int, newHeight: Int): Array<Row> {
        for (row in source) row.resize(newWidth)
        val dropped = (source.size - newHeight).coerceAtLeast(0)
        return Array(newHeight) { index ->
            val from = index + dropped
            if (from < source.size) source[from] else Row(newWidth)
        }
    }

    private fun markDirty() {
        dirty = true
    }

    // ------------------------------------------------------------------ data

    private class Cell {
        var cp: Int = SPACE
        var combining: StringBuilder? = null
        var cont: Boolean = false
        var wide: Boolean = false
        var fg: Int = DEFAULT_COLOR
        var bg: Int = DEFAULT_COLOR
        var bold: Boolean = false
        var dim: Boolean = false
        var italic: Boolean = false
        var underline: Boolean = false
        var inverse: Boolean = false

        fun reset() {
            cp = SPACE
            combining = null
            cont = false
            wide = false
            fg = DEFAULT_COLOR
            bg = DEFAULT_COLOR
            bold = false
            dim = false
            italic = false
            underline = false
            inverse = false
        }

        fun styleFrom(other: Pen) {
            fg = other.fg
            bg = other.bg
            bold = other.bold
            dim = other.dim
            italic = other.italic
            underline = other.underline
            inverse = other.inverse
        }
    }

    private class Row(cols: Int) {
        var cells: Array<Cell> = Array(cols) { Cell() }

        fun resize(cols: Int) {
            if (cells.size == cols) return
            val previous = cells
            cells = Array(cols) { index -> if (index < previous.size) previous[index] else Cell() }
        }
    }

    private class Pen {
        var fg: Int = DEFAULT_COLOR
        var bg: Int = DEFAULT_COLOR
        var bold: Boolean = false
        var dim: Boolean = false
        var italic: Boolean = false
        var underline: Boolean = false
        var inverse: Boolean = false

        fun reset() {
            fg = DEFAULT_COLOR
            bg = DEFAULT_COLOR
            bold = false
            dim = false
            italic = false
            underline = false
            inverse = false
        }
    }

    private class SavedCursor(
        val row: Int,
        val col: Int,
        val wrapPending: Boolean,
        val fg: Int,
        val bg: Int,
        val bold: Boolean,
        val dim: Boolean,
        val italic: Boolean,
        val underline: Boolean,
        val inverse: Boolean,
    )

    private companion object {
        const val DEFAULT_COLOR = -1
        const val TRUE_COLOR_FLAG = 0x1000000
        const val SPACE = 0x20
        const val REPLACEMENT = 0xFFFD

        const val ESC = 0x1B
        const val BEL = 0x07
        const val BS = 0x08
        const val TAB = 0x09
        const val LF = 0x0A
        const val CR = 0x0D
        const val CAN = 0x18
        const val SUB = 0x1A
        const val BACKSLASH = 0x5C

        const val S_GROUND = 0
        const val S_ESCAPE = 1
        const val S_ESCAPE_SINGLE = 2
        const val S_CSI = 3
        const val S_OSC = 4
        const val S_STRING = 5
        const val S_OSC_ESC = 6
        const val S_STRING_ESC = 7

        /**
         * Terminal cell width of a code point: 0 for combining/format characters, 2 for
         * East-Asian wide and emoji presentation, otherwise 1.
         */
        fun charWidth(cp: Int): Int {
            if (cp == 0 || cp < 0x20 || cp in 0x7F..0x9F) return 0
            if (cp in 0x200B..0x200F || cp == 0x2060 || cp == 0xFEFF) return 0
            if (cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF) return 0
            when (Character.getType(cp)) {
                Character.NON_SPACING_MARK.toInt(),
                Character.ENCLOSING_MARK.toInt(),
                Character.COMBINING_SPACING_MARK.toInt(),
                -> return 0
            }
            return if (isWide(cp)) 2 else 1
        }

        fun isWide(cp: Int): Boolean = when (cp) {
            in 0x1100..0x115F -> true
            in 0x2E80..0x303E -> true
            in 0x3041..0x33FF -> true
            in 0x3400..0x4DBF -> true
            in 0x4E00..0x9FFF -> true
            in 0xA000..0xA4CF -> true
            in 0xA960..0xA97F -> true
            in 0xAC00..0xD7A3 -> true
            in 0xF900..0xFAFF -> true
            in 0xFE10..0xFE19 -> true
            in 0xFE30..0xFE6F -> true
            in 0xFF00..0xFF60 -> true
            in 0xFFE0..0xFFE6 -> true
            in 0x1F300..0x1FAFF -> true
            in 0x20000..0x3FFFD -> true
            else -> false
        }
    }
}

data class VtSnapshot(
    val cols: Int,
    val rows: Int,
    val revision: Long,
    val cursorRow: Int,
    val cursorCol: Int,
    val cursorVisible: Boolean,
    /** Scrollback followed by the visible viewport, oldest first. */
    val lines: List<VtLine>,
    /** Number of leading [lines] entries that are above the viewport. */
    val scrollbackCount: Int,
    val alternateScreen: Boolean,
    val applicationCursorKeys: Boolean,
    /** DECSET 2004 is on: clipboard pastes should be wrapped in bracketed-paste markers. */
    val bracketedPaste: Boolean = false,
)

data class VtLine(val runs: List<VtRun>)

data class VtRun(
    val text: String,
    /** 0..255 xterm index, or `0x1000000 or RGB` for truecolour, or null for the default. */
    val fg: Int?,
    val bg: Int?,
    val bold: Boolean,
    val dim: Boolean,
    val italic: Boolean,
    val underline: Boolean,
    val inverse: Boolean,
)
