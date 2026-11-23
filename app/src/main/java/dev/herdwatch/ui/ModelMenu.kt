package dev.herdwatch.ui

/** Leading markers a TUI puts before the number: pointer, bullet, arrow, selection box. */
private val PTRUNE_PREFIXES = charArrayOf(
    '❯', '›', '>', '»', '▸', '*', '∙', '•', '-', '—', ' ',
    // Radio and checkbox marks that carry the selection on their own row.
    '●', '○', '◯', '□', '▪', '▫', '◦',
).let { it + charArrayOf('\t') }

private const val MENU_WINDOW_LINES = 40

/** Longer than any model menu, short enough to exclude a numbered log. */
private const val MAX_MENU_ROWS = 20

/** How many non-menu lines may sit between two rows of the same menu (descriptions). */
private const val MENU_GAP_LINES = 2

/**
 * Labels longer than this are shortened for display only. The row itself is kept: dropping a long
 * option shifted every row below it, so tapping the third choice sent the arrows for the second.
 */
private const val LABEL_LIMIT = 120

/**
 * A selector the pane is drawing right now, whatever it is asking about: a permission prompt, a
 * diff review, a "pick a session" list. Every TUI draws the same thing — rows with one pointer on
 * the current choice — and the app answers by moving the pointer with the terminal's own arrow keys
 * and pressing Enter, so it never invents an answer.
 */
data class ScreenMenuRow(val label: String, val selected: Boolean)

data class ScreenMenu(val rows: List<ScreenMenuRow>, val cursor: Int)

/** A row the TUI is pointing at. These glyphs are selectors, never prose. */
private val POINTER_ROW = Regex("""^\s*[❯›»▸]\s*(.*)$""")

/**
 * `>` is only a pointer when a number follows it. Bare `> text` is a Markdown quote in an agent's
 * answer, and treating it as a live choice offered to send arrow keys at a paragraph.
 */
private val QUOTED_POINTER_ROW = Regex("""^\s*>\s*(\d{1,2}[.):]\s*.+)$""")

/** A row of the same block that the pointer is not on. Numbered or bulleted, never free text. */
private val PLAIN_ROW = Regex("""^\s*(\d{1,2}[.):]\s+\S.*|[●○◯□▪▫◦]\s*\S.*)$""")

/** The same shape on both kinds of row, so `1.Yes` is refused everywhere or accepted everywhere. */
private val NUMBER_PREFIX = Regex("""^(\d{1,2})[.):]\s+(\S.*)$""")

/** A shell prompt wearing a pointer glyph: `❯ vim file`, `dev@box:~$`, a lone `❯`. */
private val SHELL_PROMPT = Regex("""(?:^\S+@\S+|[$#%]\s*$|^~?/[\w./-]*\s*$)""")

private class Parsed(val number: Int?, val label: String, val pointed: Boolean)

/**
 * The selector at the bottom of [screen], or `null` when the screen is only output.
 *
 * A pointer is required, and it needs at least one numbered or bulleted neighbour: numbered output
 * is everywhere — build logs, `git log`, `ls -l` — and a pointer alone is also what a shell prompt
 * looks like. The screen arrives with the colour codes the remote program wrote, so every line is
 * stripped and unboxed before it is matched; matching the raw bytes found no menu at all on a real
 * pane, because the pointer sat behind an escape sequence.
 */
internal fun parseScreenMenu(screen: String): ScreenMenu? {
    if (screen.isBlank()) return null
    // Blank lines stay in the list: they are the boundary between two menus. Filtering them out
    // first let a numbered log above a prompt merge into one block with a gap of zero.
    val tail = screen.replace("\r\n", "\n").split('\n').takeLast(MENU_WINDOW_LINES)
    val parsed = tail.map { menuRow(it) }
    val pointer = parsed.indexOfLast { it != null && it.pointed }
    if (pointer < 0) return null
    val live = parsed[pointer] ?: return null
    if (SHELL_PROMPT.containsMatchIn(live.label)) return null

    // Grow outward from the pointer. Each direction gets its own budget: sharing one let nineteen
    // numbered log lines above the cursor starve the real options below it.
    val found = mutableListOf<Pair<Int, Parsed>>()
    fun collect(from: Int, step: Int) {
        var gap = 0
        var index = from
        // The pointer's own number anchors the sequence in both directions: a menu numbered from
        // 1 has nothing above it, so a numbered log sitting there is not part of this selector.
        var lastNumber: Int? = live.number
        var first = true
        var taken = 0
        while (index in tail.indices && taken < MAX_MENU_ROWS / 2) {
            if (tail[index].isBlank()) return
            val row = parsed[index]
            if (row == null) {
                if (++gap > MENU_GAP_LINES) return
            } else {
                // Numbers must run one way. A restart means a second menu, or a log that happens
                // to sit next to this one; either way its rows are not choices for this pointer.
                val n = row.number
                if (!first && n != null && lastNumber != null && (if (step < 0) n >= lastNumber else n <= lastNumber)) return
                first = false
                if (n != null) lastNumber = n
                gap = 0
                found += index to row
                taken++
            }
            index += step
        }
    }
    collect(pointer, -1)
    collect(pointer + 1, 1)
    if (found.size < 2) return null
    val ordered = found.sortedBy { it.first }.map { it.second }
    // A repaint can leave several pointer glyphs on screen; only the lowest one is live. Treating
    // every pointer row as "current" would mark the whole menu as selected and make the arrow-key
    // walk start from the wrong place.
    val cursor = ordered.indexOfLast { it.pointed }
    if (cursor < 0) return null
    return ScreenMenu(
        rows = ordered.mapIndexed { index, row ->
            ScreenMenuRow(label = displayLabel(row.label), selected = index == cursor)
        },
        cursor = cursor,
    )
}

private fun displayLabel(text: String): String =
    if (text.length <= LABEL_LIMIT) text else text.take(LABEL_LIMIT - 1).trimEnd() + "…"

private fun menuRow(raw: String): Parsed? {
    val line = unbox(stripAnsi(raw)).trimEnd()
    if (line.isBlank()) return null
    val pointed = POINTER_ROW.find(line)?.groupValues?.get(1)
        ?: QUOTED_POINTER_ROW.find(line)?.groupValues?.get(1)
    val isPointer = pointed != null
    val body = pointed ?: PLAIN_ROW.find(line)?.groupValues?.get(1) ?: return null
    val numbered = NUMBER_PREFIX.find(body.trim())
    val text = (numbered?.groupValues?.get(2) ?: body).trim().trimStart { it in PTRUNE_PREFIXES }.trim()
    if (text.isEmpty()) return null
    // A pointer on a bare command is a prompt, not a choice; the caller checks the shape too.
    return Parsed(number = numbered?.groupValues?.get(1)?.toIntOrNull(), label = text, pointed = isPointer)
}

/** Strip the box borders a TUI draws around its prompt: `│ ❯ 1. Yes │` -> `❯ 1. Yes`. */
private fun unbox(line: String): String {
    var t = line.trim()
    while (t.isNotEmpty() && (t[0] == '│' || t[0] == '┃')) t = t.substring(1).trim()
    while (t.isNotEmpty() && (t.last() == '│' || t.last() == '┃')) t = t.substring(0, t.length - 1).trim()
    return t
}