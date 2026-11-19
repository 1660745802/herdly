package dev.herdwatch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * Turns a captured terminal snapshot that still carries SGR codes into styled text.
 *
 * Why this exists: the pane detail page used to read snapshots with `strip_ansi = true`, so every
 * colour the remote program had written was thrown away before it reached the phone. A Claude Code
 * or `git status` screen then arrived as one shade of white — which is exactly what "this doesn't
 * look like a terminal" means.
 *
 * Scope is deliberately narrow: SGR (colours, weight, slant, underline, inverse) is honoured,
 * cursor movement and screen clearing are dropped because a snapshot is already a flat picture of
 * the screen, and unsupported private sequences are ignored without leaving debris. The emulator in
 * `dev.herdwatch.term.VtScreen` remains the authority for live byte streams; this is the cheap
 * path for a text snapshot.
 */
fun ansiAnnotated(text: String, defaultText: Color, defaultBackground: Color): AnnotatedString {
    if (text.indexOf(Csi.ESC) < 0) return AnnotatedString(text)
    return buildAnnotatedString {
        var state = Sgr()
        var index = 0
        val buffer = StringBuilder()

        fun flush() {
            if (buffer.isEmpty()) return
            pushStyle(state.spanStyle(defaultText, defaultBackground))
            append(buffer)
            pop()
            buffer.setLength(0)
        }

        while (index < text.length) {
            val ch = text[index]
            if (ch != Csi.ESC) {
                // A carriage return means "overwrite this line": keep the tail, which is what the
                // screen shows after a progress bar redraw.
                if (ch == '\r') buffer.setLength(0) else buffer.append(ch)
                index++
                continue
            }
            when (text.getOrNull(index + 1)) {
                '[' -> {
                    flush()
                    val end = skipCsi(text, index)
                    if (text.getOrNull(end) == 'm') state = state.apply(text.substring(index + 2, end))
                    index = if (end < text.length) end + 1 else end
                }
                ']' -> {
                    flush()
                    index = skipOsc(text, index)
                }
                null -> index = text.length
                else -> index += 2
            }
        }
        flush()
    }
}

/**
 * Drops the padding that fills a captured screen line out to the full terminal width.
 *
 * A pane snapshot is a rectangle: every row carries trailing spaces (often with a background
 * colour) up to the remote 120-column width. On a phone those spaces are the difference between
 * text that wraps naturally and text that looks double-spaced and half-empty. Escape sequences are
 * preserved, so colour survives the trim.
 */
fun trimTrailingSpaces(text: String): String {
    if (text.isEmpty()) return text
    return text.lineSequence().joinToString("\n") { line -> trimLine(line) }
}

/** Removes trailing padding and any escape tail from one line, keeping everything before it. */
private fun trimLine(line: String): String {
    var end = line.length
    while (end > 0) {
        val ch = line[end - 1]
        if (ch == ' ' || ch == '\t' || ch == '\r') {
            end--
            continue
        }
        // An escape sequence that only sets style carries no visible content.
        if (ch.isLetter() || ch == 'm') {
            var start = end - 1
            while (start > 0 && (line[start - 1].isDigit() || line[start - 1] in ";:?[")) start--
            if (start > 0 && line[start - 1].code == 0x1B) {
                end = start - 1
                continue
            }
        }
        break
    }
    return line.substring(0, end)
}

/**
 * Same input, no styling: what the screen says, with the control sequences gone.
 *
 * Used by the "fold decorations" reading mode, which judges a line by its characters — an escape
 * code inside a box-drawing rule would otherwise look like content.
 */
fun stripAnsi(text: String): String {
    if (text.indexOf(Csi.ESC) < 0) return text
    return buildString(text.length) {
        var index = 0
        while (index < text.length) {
            val ch = text[index]
            if (ch != Csi.ESC) {
                append(ch)
                index++
                continue
            }
            when (text.getOrNull(index + 1)) {
                '[' -> {
                    val end = skipCsi(text, index)
                    index = if (end < text.length) end + 1 else end
                }
                ']' -> index = skipOsc(text, index)
                null -> index = text.length
                else -> index += 2
            }
        }
    }
}

/**
 * Escape vocabulary shared by both renderers. Written as code points so no literal in this file has
 * to contain a raw control character.
 */
private object Csi {
    const val ESC = '\u001b'

    const val BEL = 0x07
    const val ST_BACKSLASH = 0x5C
    const val PARAM_END = 0x6D // 'm'
}

/** Index of the byte that ends a CSI sequence (its final character), starting at [escapeIndex]. */
private fun skipCsi(text: String, escapeIndex: Int): Int {
    var end = escapeIndex + 2
    while (end < text.length) {
        val at = text[end]
        if (at.isDigit() || at in ";:?<>=!") end++ else break
    }
    return end
}

/** Index just past an OSC string (ESC ] … BEL, or ESC ] … ESC \), starting at [escapeIndex]. */
private fun skipOsc(text: String, escapeIndex: Int): Int {
    var end = escapeIndex + 2
    while (end < text.length) {
        val at = text[end].code
        if (at == Csi.BEL) return end + 1
        if (at == Csi.ESC.code && text.getOrNull(end + 1)?.code == Csi.ST_BACKSLASH) return end + 2
        end++
    }
    return text.length
}

/** Current SGR state. Colour values follow [xtermColor]'s convention: 0..255, or 0x1000000 or RGB. */
private data class Sgr(
    val fg: Int? = null,
    val bg: Int? = null,
    val bold: Boolean = false,
    val dim: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
) {
    fun spanStyle(defaultText: Color, defaultBackground: Color): SpanStyle {
        val foreground = fg?.let { xtermColor(it, defaultText) } ?: defaultText
        val background = bg?.let { xtermColor(it, defaultBackground) } ?: defaultBackground
        val effectiveForeground = if (inverse) background else foreground
        val effectiveBackground = if (inverse) foreground else background
        return SpanStyle(
            color = if (dim) effectiveForeground.copy(alpha = 0.7f) else effectiveForeground,
            background = if (inverse || bg != null) effectiveBackground else Color.Unspecified,
            fontWeight = if (bold) FontWeight.Bold else null,
            fontStyle = if (italic) FontStyle.Italic else null,
            textDecoration = if (underline) TextDecoration.Underline else null,
        )
    }

    fun apply(params: String): Sgr {
        if (params.isEmpty()) return Sgr()
        val parts = params.split(';').map { it.substringBefore(':') }.filter { it.isNotEmpty() }
        var next = this
        var i = 0
        fun num(at: Int): Int? = parts.getOrNull(at)?.toIntOrNull()
        while (i < parts.size) {
            when (val code = num(i) ?: 0) {
                0 -> next = Sgr()
                1 -> next = next.copy(bold = true)
                2 -> next = next.copy(dim = true)
                3 -> next = next.copy(italic = true)
                4 -> next = next.copy(underline = true)
                7 -> next = next.copy(inverse = true)
                22 -> next = next.copy(bold = false, dim = false)
                23 -> next = next.copy(italic = false)
                24 -> next = next.copy(underline = false)
                27 -> next = next.copy(inverse = false)
                in 30..37 -> next = next.copy(fg = code - 30)
                in 90..97 -> next = next.copy(fg = code - 90 + 8)
                in 40..47 -> next = next.copy(bg = code - 40)
                in 100..107 -> next = next.copy(bg = code - 100 + 8)
                39 -> next = next.copy(fg = null)
                49 -> next = next.copy(bg = null)
                38, 48 -> {
                    val extended = readExtended(parts, i) ?: break
                    next = if (code == 38) next.copy(fg = extended.first) else next.copy(bg = extended.first)
                    i += extended.second
                }
                else -> Unit
            }
            i++
        }
        return next
    }

    /** Reads `38;5;n` / `38;2;r;g;b`; returns the colour and how many extra tokens it consumed. */
    private fun readExtended(parts: List<String>, at: Int): Pair<Int, Int>? {
        return when (parts.getOrNull(at + 1)?.toIntOrNull()) {
            5 -> {
                val index = parts.getOrNull(at + 2)?.toIntOrNull() ?: return null
                index to 2
            }
            2 -> {
                val r = parts.getOrNull(at + 2)?.toIntOrNull() ?: return null
                val g = parts.getOrNull(at + 3)?.toIntOrNull() ?: return null
                val b = parts.getOrNull(at + 4)?.toIntOrNull() ?: return null
                (0x1000000 or (r shl 16) or (g shl 8) or b) to 4
            }
            else -> null
        }
    }
}

/**
 * Collapses the parts of a captured screen that carry no information on a phone.
 *
 * A full-screen TUI paints its whole rectangle: box rules, padding rows and the vertical filler
 * between panels. Kept verbatim, eight empty rows sit between two sentences and the reader has to
 * scroll through nothing. Rule-only lines and runs of blank lines are dropped; nothing else is
 * touched, so coloured text, alignment and wrapping stay exactly as the remote program wrote them.
 */
fun condenseScreen(text: String): String {
    if (text.isEmpty()) return text
    val out = ArrayList<String>()
    var blanks = 0
    for (line in text.split("\n")) {
        val plain = stripAnsi(line).trim()
        val rule = plain.length >= 16 && plain.all { it in "─━═-_=· " }
        if (rule) continue
        if (plain.isEmpty() && stripAnsi(line).isBlank()) {
            blanks++
            if (blanks > 1) continue
        } else {
            blanks = 0
        }
        out.add(line)
    }
    return out.joinToString("\n")
}
