package dev.herdwatch.ui

/** Leading markers a TUI puts before the number: pointer, bullet, arrow, selection box. */
private val PTRUNE_PREFIXES = charArrayOf(
    '❯', '›', '>', '»', '*', '∙', '•', '-', '—', ' ',
    // Radio and checkbox marks that carry the selection on their own row.
    '●', '○', '◯', '□', '▪', '▫', '◦',
).let { it + charArrayOf('\t') }

private const val MENU_WINDOW_LINES = 30

/** Longer than any model menu, short enough to exclude a numbered log. */
private const val MAX_MENU_ROWS = 20

/** How many non-menu lines may sit between two rows of the same menu (descriptions, blank lines). */
private const val MENU_GAP_LINES = 2

/**
 * A selector the pane is drawing right now, whatever it is asking about: a permission prompt, a
 * diff review, a "pick a session" list. Every TUI draws the same thing — rows with one pointer on
 * the current choice — and the app answers by moving the pointer with the terminal's own arrow keys
 * and pressing Enter, so it never invents an answer.
 */
data class ScreenMenuRow(val label: String, val selected: Boolean)

data class ScreenMenu(val rows: List<ScreenMenuRow>, val cursor: Int)

/** A row the TUI is pointing at: the pointer is the only reliable sign that this is a selector. */
private val POINTER_ROW = Regex("""^\s*[❯›>»]\s*(?:\d{1,2}[.):]\s*)?(\S.*)$""")

/** A row of the same block that the pointer is not on. Numbered or bulleted, never free text. */
private val PLAIN_ROW = Regex("""^\s*(?:\d{1,2}[.):]|[●○◯□▪▫◦])\s+(\S.*)$""")

/**
 * The selector at the bottom of [screen], or `null` when the screen is only output.
 *
 * A pointer is required. Numbered output is everywhere — build logs, `git log`, `ls -l` — so a
 * block of numbers on its own is not evidence that something is waiting for an answer.
 */
internal fun parseScreenMenu(screen: String): ScreenMenu? {
    if (screen.isBlank()) return null
    val tail = screen.replace("\r\n", "\n").split('\n')
        .filter { it.isNotBlank() }
        .takeLast(MENU_WINDOW_LINES)
    val pointer = tail.indexOfLast { POINTER_ROW.containsMatchIn(it) }
    if (pointer < 0) return null

    // Grow the block outward from the pointer, allowing a couple of non-row lines inside it (a
    // description under the highlighted choice) but not a paragraph of output.
    val found = mutableListOf<Pair<Int, ScreenMenuRow>>()
    fun collect(from: Int, step: Int) {
        var gap = 0
        var index = from
        while (index in tail.indices && found.size < MAX_MENU_ROWS) {
            val row = menuRow(tail[index])
            if (row == null) {
                if (++gap > MENU_GAP_LINES) return
            } else {
                gap = 0
                found += index to row
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
    val cursor = ordered.indexOfLast { it.selected }
    if (cursor < 0) return null
    return ScreenMenu(
        rows = ordered.mapIndexed { index, row -> row.copy(selected = index == cursor) },
        cursor = cursor,
    )
}

private fun menuRow(line: String): ScreenMenuRow? {
    val pointed = POINTER_ROW.find(line)
    val label = if (pointed != null) pointed.groupValues[1] else PLAIN_ROW.find(line)?.groupValues?.get(1)
    val text = label?.trim()?.trimStart { it in PTRUNE_PREFIXES }?.trim().orEmpty()
    if (text.isEmpty() || text.length > 80) return null
    return ScreenMenuRow(label = text, selected = pointed != null)
}
