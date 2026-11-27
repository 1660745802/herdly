package dev.herdwatch.domain

import dev.herdwatch.domain.TranscriptRole.APPROVAL
import dev.herdwatch.domain.TranscriptRole.ASSISTANT
import dev.herdwatch.domain.TranscriptRole.SYSTEM
import dev.herdwatch.domain.TranscriptRole.THINKING
import dev.herdwatch.domain.TranscriptRole.TOOL
import dev.herdwatch.domain.TranscriptRole.USER

enum class TranscriptRole { USER, ASSISTANT, THINKING, TOOL, SYSTEM, APPROVAL }

data class TranscriptBlock(
    val role: TranscriptRole,
    val text: String,          // cleaned, joined, no box-drawing, no trailing spaces
    val raw: String,           // the exact source lines this block came from (for "show source")
    val truncatedHead: Boolean // true when the snapshot cut the beginning of this block
)

data class Transcript(
    val blocks: List<TranscriptBlock>,
    val coverage: Float,       // 0f..1f share of non-empty source lines that ended up in blocks
    val reliable: Boolean      // true when the parse looks trustworthy enough to show as chat
)

/**
 * Turns an ANSI-stripped terminal snapshot of a TUI agent into chat-like blocks.
 *
 * Deliberately conservative: the UI falls back to the raw terminal when [Transcript.reliable]
 * is false, so we never invent text and only drop chrome we are sure about. Role markers
 * (`>`, `●`, `⎿`, ...) are kept in [TranscriptBlock.text] so a re-parse of the joined text
 * yields the same roles (idempotence).
 *
 * Heuristics, in order:
 *  1. Normalise each line (ANSI leftovers, tabs -> 8-column stops, trailing whitespace).
 *  2. Pure chrome = non-blank line with no letter/digit at all (rules, spinners, punctuation,
 *     empty composer). Dropped everywhere.
 *  3. Isolated key hints / composer placeholders ("? for shortcuts", "ctrl+o ...",
 *     "Type your message") are dropped anywhere; a "press enter to ..." row only when no prose
 *     touches it. Token counters, model lines and bare cwd paths are only dropped in a trailing
 *     footer scan that stops at the first prose line.
 *  4. Turns start at leading markers; approval menus (numbered 1..n with choice words or a
 *     preceding question) and `(y/n)` questions become one APPROVAL block, absorbing the
 *     question line from the current prose block.
 *  5. Unmarked prose continues the current block (wrapped lines, indented continuations,
 *     and — for prose roles — further paragraphs after a blank). After TOOL/APPROVAL a
 *     blank + unmarked prose starts a SYSTEM block rather than inventing an assistant turn.
 *     Unmarked prose at the very start is ASSISTANT (possibly truncated head).
 */
object AgentTranscript {

    private val ansi = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007]*(?:\u0007|\u001B\\\\))")
    private val optionLine = Regex("""^\s*(?:[❯>●○▸▹]\s*)?(\d{1,2})[.)]\s+(.+)$""")
    private val yesNo = Regex("""[(\[]\s*(y/n|yes/no)\s*[)\]]""", RegexOption.IGNORE_CASE)
    private val choiceWords = Regex("""\b(yes|no|allow|deny|proceed|cancel|accept|reject|ok|continue|run|stop)\b""")
    private val tookRe = Regex("""^took\s+\d""", RegexOption.IGNORE_CASE)
    private val earlierLines = Regex("""^[.…]{0,3}\s*\(?\d+\s+earlier lines""", RegexOption.IGNORE_CASE)
    private val modelLine = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:\s*[•·|]\s*[A-Za-z0-9_.-]+)*$""")
    private val tokenSignal = Regex("""[↑↓]\s*\d|\$\d|\d+(?:\.\d+)?%|(?:^|\s)(?:R|CH)\d""")
    private val versionRe = Regex("""^v?\d+\.\d+""")
    private val rangeRe = Regex("""\d+\s*[~×x]\s*\d+""")
    private val listItemRe = Regex("""^(?:\d{1,2}[.)、]|[①②③④⑤⑥⑦⑧⑨]|[-*·•])\s*""")
    /**
     * How many rows the startup banner may occupy. The turn-marker break below is what actually
     * ends the region; this is only a guard for a banner that never ends.
     */
    private const val BANNER_LINES = 80

    /** Banner section headings a CLI prints before its first turn: `[Skills]`, `[Pi 1.2]`. */
    private val sectionHead = Regex("""^\[[A-Za-z][A-Za-z0-9 ._/-]{0,28}\]$""")

    /**
     * One entry in a banner's indented list: a bare name or path (`android-cli`,
     * `billion-context:dist/agent/billion-context.js`). No internal spaces, so a sentence,
     * a code line or a `- list item` never matches and is kept as real content.
     */
    private val bannerEntry = Regex("""^[A-Za-z0-9_./~][A-Za-z0-9_.:/@+~-]*$""")

    /**
     * Whether an indented line under a banner heading is itself furniture. A comma-separated
     * run of bare names/paths is the CLI listing its skills/extensions; model, token, cwd and
     * status lines are furniture too. Anything else (prose, code, a list item) is the answer
     * starting right after the heading and must survive.
     */
    private fun bannerContinuation(t: String): Boolean {
        if (t.isEmpty()) return false
        if (t.split(',').all { bannerEntry.matches(it.trim()) }) return true
        return modelLine.matches(t) || tokenSignal.findAll(t).count() >= 2 || informational(t)
    }

    /**
     * The screen with the furniture removed, still as plain lines.
     *
     * A snapshot the parser cannot cut into turns is shown as one block (a shell has no turns),
     * and that block used to be the raw screen — so a CLI's startup banner arrived as the first
     * message of every conversation. Dropping the same lines here keeps the fallback honest.
     */
    fun readable(text: String): String {
        val lines = splitLines(text)
        markFurniture(lines)
        val out = StringBuilder(text.length)
        var pendingBlank = false
        for (ln in lines) {
            if (ln.dropped || ln.chrome) continue
            if (ln.blank) {
                pendingBlank = out.isNotEmpty()
                continue
            }
            if (out.isNotEmpty()) out.append('\n')
            if (pendingBlank) out.append('\n')
            out.append(ln.trimmed)
            pendingBlank = false
        }
        return out.toString()
    }

    /** Split once, keeping byte offsets into the original so `raw` is a verbatim slice. */
    private fun splitLines(text: String): ArrayList<Line> {
        val lines = ArrayList<Line>()
        var first = 0
        for (piece in text.split("\n")) {
            val end = first + piece.length
            val rawEnd = if (piece.endsWith("\r")) end - 1 else end
            lines.add(Line(first, rawEnd, normalize(piece)))
            first = end + 1
        }
        return lines
    }

    /** Mark everything that is furniture: prompts, banners, footers. */
    private fun markFurniture(lines: ArrayList<Line>) {
        for (idx in lines.indices) {
            val ln = lines[idx]
            if (ln.blank || ln.chrome) continue
            val t = ln.trimmed
            if (isolatedChrome(t)) {
                ln.isolated = true
                ln.dropped = true
            } else if (t.length <= 60 && t.lowercase().startsWith("press enter to") &&
                hintIsolated(lines, idx)
            ) {
                // A "press enter to ..." row is only furniture when it stands alone; inside a
                // paragraph it is the agent telling the user what to press, so it stays.
                ln.isolated = true
                ln.dropped = true
            }
        }

        // Startup banner: a CLI prints `[Skills]`, `[Extensions]`, `[Context]` and an indented
        // list under each before the first prompt. That is the agent describing itself, not a
        // turn, and on a phone it fills the first screen. Only the region before the first real
        // line is touched, so a bracketed heading inside a conversation survives.
        var b = 0
        while (b < lines.size && b < BANNER_LINES) {
            val ln = lines[b]
            // A turn marker ends the banner region: from here a bracketed line is content.
            if (ln.trimmed.startsWith(">") || ln.trimmed.startsWith("❯") || ln.trimmed.startsWith("›")) break
            if (!ln.blank && sectionHead.matches(ln.trimmed)) {
                ln.dropped = true
                b++
                // Only drop continuation lines that are themselves banner furniture. The first
                // indented line that is real content (code, a list, prose) ends the region and
                // is kept, so an answer that opens with an indented block loses nothing.
                while (b < lines.size) {
                    val cont = lines[b]
                    if (cont.blank) { cont.dropped = true; b++; continue }
                    if (cont.norm.startsWith("  ") && bannerContinuation(cont.trimmed)) {
                        cont.dropped = true
                        b++
                        continue
                    }
                    break
                }
                continue
            }
            b++
        }

        // Hotkey legend: `escape interrupt · ctrl+c/ctrl+d clear/exit · ctrl+o more`. Two control
        // hints in one line is a legend, not a sentence, so it never survives as a message.
        for (ln in lines.take(BANNER_LINES)) {
            if (!ln.blank && !ln.dropped && headerLegend(ln.trimmed)) ln.dropped = true
        }

        // Trailing footer scan: skip blank/chrome, drop footer-ish lines, stop at real prose.
        // footerSeen records that we are inside the bottom status strip, so a bare path is only
        // treated as a cwd footer when it sits on top of an already-dropped token/model line.
        var tail = lines.size - 1
        var footerSeen = false
        while (tail >= 0) {
            val ln = lines[tail]
            if (ln.blank || ln.chrome || ln.dropped) { tail--; continue }
            if (footerish(ln.trimmed, footerSeen)) {
                ln.dropped = true
                footerSeen = true
                tail--
                continue
            }
            break
        }
    }

    fun parse(text: String): Transcript {
        val lines = splitLines(text)
        markFurniture(lines)
        val blocks = ArrayList<TranscriptBlock>()
        val cur = ArrayList<Line>()
        var curRole = SYSTEM
        var curMarked = false
        var pendingBlank = false

        fun flush() {
            if (cur.isEmpty()) return
            blocks.add(makeBlock(curRole, cur, text, blocks.isEmpty(), curMarked))
            cur.clear()
        }

        fun begin(role: TranscriptRole, marked: Boolean, ln: Line) {
            flush()
            curRole = role
            curMarked = marked
            cur.add(ln)
            ln.consumed = true
            pendingBlank = false
        }

        var i = 0
        var inFence = false
        while (i < lines.size) {
            val ln = lines[i]
            if (ln.fence) {
                if (!inFence) {
                    begin(TOOL, true, ln)
                    inFence = true
                } else {
                    // Both fences stay in the block text: re-parsing the joined text has to see a
                    // closed fence, or every later turn gets swallowed. The UI strips them.
                    cur.add(ln)
                    ln.consumed = true
                    flush()
                    inFence = false
                }
                i++
                continue
            }
            if (inFence) {
                cur.add(ln)
                ln.consumed = true
                i++
                continue
            }
            if (ln.dropped || ln.chrome) { i++; continue }
            if (ln.blank) { pendingBlank = true; i++; continue }
            val t = unpipe(ln.norm)

            // Numbered approval menu: a run of 1..n options with choice words or a question.
            if (optionLine.matchEntire(t)?.groupValues?.get(1) == "1") {
                val run = menuRun(lines, i)
                if (run != null) {
                    var question: Line? = null
                    if (cur.isNotEmpty() && curRole != TOOL && curRole != APPROVAL &&
                        questionLike(unpipe(cur.last().norm))
                    ) {
                        question = cur.removeAt(cur.size - 1)
                    }
                    flush()
                    curRole = APPROVAL
                    curMarked = true
                    if (question != null) cur.add(question)
                    for (j in run) {
                        cur.add(lines[j])
                        lines[j].consumed = true
                    }
                    pendingBlank = false
                    i = run.last() + 1
                    continue
                }
            }

            if (yesNo.containsMatchIn(t)) { begin(APPROVAL, true, ln); i++; continue }

            val marker = markerRole(t)
            if (marker != null) {
                // A prompt echoed over several `>` lines is still one message; beginning a block per
                // line turned one brief into a stack of identical-looking bubbles. Explicit markers
                // (`You:`, `❯`, `user:`) are per-turn and still start their own block.
                val quoteContinuation = marker == USER && t.startsWith(">") && curRole == USER &&
                    curMarked && cur.firstOrNull()?.let { unpipe(it.norm).trimStart().startsWith(">") } == true
                if (quoteContinuation) {
                    cur.add(ln)
                    ln.consumed = true
                } else {
                    begin(marker, true, ln)
                }
                i++
                continue
            }

            val indented = ln.norm.startsWith("  ")
            if (cur.isEmpty()) {
                curRole = if (informational(t)) SYSTEM else ASSISTANT
                curMarked = false
                cur.add(ln)
                ln.consumed = true
            } else if (curRole == USER && structured(t)) {
                // The quoted prompt is the user talking; the report underneath it is the answer.
                // Gluing them together rendered a whole turn as one user bubble: no emphasis, no
                // paragraph rhythm, and the reply owned by the wrong speaker.
                flush()
                curRole = ASSISTANT
                curMarked = false
                cur.add(ln)
                ln.consumed = true
            } else if (indented || !pendingBlank || curRole == USER || curRole == ASSISTANT || curRole == SYSTEM) {
                // Wrapped prose / indented continuation / further prose paragraphs.
                cur.add(ln)
                ln.consumed = true
            } else {
                // After TOOL/APPROVAL, unmarked prose past a blank is the agent talking again.
                // Labelling it SYSTEM was wrong: in these TUIs the agent's report is plain prose
                // with no marker, so calling it "system" hid the only actual message on screen.
                flush()
                curRole = if (informational(t)) SYSTEM else ASSISTANT
                curMarked = false
                cur.add(ln)
                ln.consumed = true
            }
            pendingBlank = false
            i++
        }
        flush()

        var denom = 0
        var num = 0
        for (ln in lines) {
            if (ln.blank || ln.chrome || ln.isolated) continue
            denom++
            if (ln.consumed) num++
        }
        val coverage = if (denom == 0) 0f else num.toFloat() / denom.toFloat()
        val reliable = coverage >= 0.6f && blocks.size >= 2 &&
            blocks.any { it.role == USER || it.role == ASSISTANT }
        // The agent's own TUI advertises its own folds ("(12 more lines, ctrl+o to expand)").
        // On a phone that hotkey does not exist, so the sentence is a promise we never keep.
        val cleaned = blocks.map { block ->
            val text = foreignFold.replace(block.text, "")
            if (text == block.text) block else block.copy(text = text)
        }
        return Transcript(cleaned, coverage, reliable)
    }

    private val foreignFold = Regex("\\s*\\(\\s*\\d+\\s+more lines?[^)]*\\)", RegexOption.IGNORE_CASE)

    // ---------------------------------------------------------------- line classification

    private class Line(val start: Int, val rawEnd: Int, val norm: String) {
        val trimmed: String = norm.trim()
        val blank: Boolean = trimmed.isEmpty()
        // Rules, spinners, braille, punctuation-only lines, empty composer: no letter/digit.
        // A bare code fence is chrome too, but a fence with a language (```bash) opens a code
        // block and must survive, otherwise the commands below it lose their context.
        val chrome: Boolean = !blank &&
            (trimmed.none { it.isLetterOrDigit() } || trimmed.all { it == '`' } || ruleish(trimmed))
        // Fenced code: inside a fence every line is literal, so prompt markers, numbered lists
        // and comment prose must not be re-interpreted as chat turns.
        val fence: Boolean = trimmed.startsWith("```")
        var isolated = false
        var dropped = false
        var consumed = false
    }

    private fun normalize(piece: String): String {
        var t = piece.replace("\r", "")
        t = ansi.replace(t, "").replace("\u001B", "")
        if (t.indexOf('\t') >= 0) {
            val sb = StringBuilder(t.length + 8)
            for (c in t) {
                if (c == '\t') {
                    do { sb.append(' ') } while (sb.length % 8 != 0)
                } else {
                    sb.append(c)
                }
            }
            t = sb.toString()
        }
        return t.trimEnd()
    }

    /** Strip box borders wrapping a line: `│ > hello │` -> `> hello`. */
    private fun unpipe(norm: String): String {
        var t = norm.trim()
        while (t.isNotEmpty() && (t[0] == '│' || t[0] == '┃')) t = t.substring(1).trim()
        while (t.isNotEmpty() && (t.last() == '│' || t.last() == '┃')) t = t.substring(0, t.length - 1).trim()
        return t
    }

    /**
     * A horizontal rule, with or without a spinner glyph or a one-word status on it.
     *
     * Agent TUIs draw `──────── ✻ Working ────────` between turns. In a reading view those rows are
     * furniture: six of them filled half a screen and pushed the actual answer out of sight.
     */
    private fun ruleish(t: String): Boolean {
        val rules = t.count { it in "─━═┄┈-_=·" }
        if (rules < 8) return false
        val words = t.filter { it.isLetterOrDigit() || it.isWhitespace() }.trim()
        // Long rules with at most a short status word are chrome; a sentence with a dash is not.
        return words.length <= 24 && rules >= t.length / 3
    }

    private val controlHint = Regex("""\b(?:ctrl|alt|shift|cmd|meta)\+|\besc(?:ape)?\b""", RegexOption.IGNORE_CASE)
    private val keyLead = Regex("""^(?:ctrl|alt|shift|cmd|meta|esc)\b""", RegexOption.IGNORE_CASE)

    /** The terse status vocabulary a hotkey legend uses; prose words are deliberately absent. */
    private val legendWords = setOf(
        "press", "to", "for", "more", "help", "show", "full", "startup", "interrupt",
        "clear", "exit", "quit", "expand", "collapse", "shortcuts", "toggle", "menu",
        "scroll", "copy", "paste", "send", "newline", "resume", "retry",
    )

    /** A line listing keyboard shortcuts: a header legend, never prose. */
    private fun headerLegend(t: String): Boolean {
        if (t.length > 140 || t.isEmpty()) return false
        // A turn marker means an agent sentence, even when it names one or two keys.
        if (t.first() in "●•⏺>❯›⎿└") return false
        val hits = controlHint.findAll(t).count()
        if (hits == 0) return false
        // A single hint only counts when the line does not lead with a letter (pi's logo row).
        if (hits == 1 && t.first().isLetter()) return false
        // Genuine legends are short runs of key combos and status words. If any word is ordinary
        // prose ("the", "run", "partial", ...) this is a sentence that mentions a shortcut, not
        // a legend, so it survives.
        val words = t.lowercase().split(Regex("[\\s·|/]+")).filter { it.isNotEmpty() }
        return words.all { w ->
            controlHint.containsMatchIn(w) || versionRe.containsMatchIn(w) ||
                w in legendWords || w.none { it.isLetterOrDigit() }
        }
    }

    /**
     * A "press enter to ..." hint is isolated only when no prose touches it on either side:
     * both nearest non-blank neighbours are chrome/dropped or absent. Between two rules or at
     * the very bottom it is furniture; inside a paragraph it is part of the answer.
     */
    private fun hintIsolated(lines: List<Line>, idx: Int): Boolean {
        var above = idx - 1
        while (above >= 0 && lines[above].blank) above--
        var below = idx + 1
        while (below < lines.size && lines[below].blank) below--
        val aboveClear = above < 0 || lines[above].chrome || lines[above].dropped
        val belowClear = below >= lines.size || lines[below].chrome || lines[below].dropped
        return aboveClear && belowClear
    }

    /** Whole-line chrome that is safe to drop anywhere (never part of a sentence). */
    private fun isolatedChrome(t: String): Boolean {
        val l = t.lowercase()
        if (l.length > 60) return false
        return l.startsWith("? for shortcuts") || l.startsWith("esc to interrupt") ||
            l.startsWith("ctrl+") || l.startsWith("shift+tab") || l.startsWith("alt+") ||
            l.startsWith("type your message") || l.startsWith("type a message") ||
            l == ">" || l == "❯"
    }

    /**
     * Footer-ish: only applied by the trailing scan, so prose is never touched. [footerSeen]
     * means a stronger footer line (token counter, model, hint) was already dropped below, i.e.
     * we are inside the bottom status strip; only then is a bare path read as a cwd footer.
     */
    private fun footerish(t: String, footerSeen: Boolean): Boolean {
        // A marked turn is never a footer, even if it mentions a shortcut or ends in a path.
        if (t.length > 1 && t.first() in "●•⏺>❯›⎿└") return false
        if (isolatedChrome(t)) return true
        val l = t.lowercase()
        if (l.length <= 60 && l.startsWith("press enter to")) return true
        // Expand/collapse/interrupt hints are footer vocabulary only when the line leads with a
        // key combo or a glyph; a sentence like "Run ctrl+o to expand the log." is prose.
        val keyLed = keyLead.containsMatchIn(l) || (t.isNotEmpty() && !t.first().isLetter())
        if (l.length <= 80 && keyLed &&
            (l.contains("for shortcuts") || l.contains("esc to interrupt") ||
                l.contains("to expand") || l.contains("to collapse") || l.contains("to interrupt"))
        ) return true
        if (l.contains("type your message") || l.contains("type a message")) return true
        if (modelLine.matches(t)) return true
        if (t.length <= 80 && l.startsWith("model:")) return true
        if (footerSeen && isCwd(t)) return true
        // Require two independent counter signals so a sentence mentioning "$0.30" survives.
        return tokenSignal.findAll(t).count() >= 2
    }

    private fun isCwd(t: String): Boolean {
        if (t.contains(' ') || t.length < 3) return false
        if (t.startsWith("~/") || t.startsWith("/") || t.startsWith("./") || t.startsWith("../")) return true
        return t[0].isLetter() && t[1] == ':' && (t[2] == '\\' || t[2] == '/')
    }

    private fun informational(t: String): Boolean {
        val l = t.lowercase()
        return l.startsWith("welcome") || l.startsWith("tip:") || l.startsWith("hint:") ||
            l.startsWith("note:") || l.startsWith("loaded ") || l.startsWith("connected ") ||
            versionRe.containsMatchIn(l)
    }

    private fun questionLike(t: String): Boolean =
        t.endsWith("?") || yesNo.containsMatchIn(t) || t.lowercase().contains("do you want")

    /** A line that can only belong to a report: heading, bullet, bold lead-in or a fence. */
    private fun structured(t: String): Boolean {
        if (t.isEmpty()) return false
        return t.startsWith("#") || t.startsWith("- ") || t.startsWith("* ") ||
            t.startsWith("**") || t.startsWith("```") || numberedItem.matches(t.trim())
    }

    private val numberedItem = Regex("^\\d+[.)]\\s+\\S.*$")

    private fun markerRole(t: String): TranscriptRole? {
        if (t.isEmpty()) return null
        val l = t.lowercase()
        // Reasoning is not the answer. Agents mark it with a sparkle or the word itself; keeping it
        // in the same bubble as the reply made every turn read twice as long as it was.
        if (t.startsWith("✻") || t.startsWith("✽") || t.startsWith("✢") ||
            l.startsWith("thinking:") || l.startsWith("thinking ") || l == "thinking" ||
            l.startsWith("thought:") || l.startsWith("reasoning:")
        ) return THINKING
        if (t.startsWith("⎿") || t.startsWith("└") || l.startsWith("tool:") ||
            l.startsWith("bash(") || l.startsWith("read(") || l.startsWith("edit(") ||
            l.startsWith("write(") || l.startsWith("glob(") || l.startsWith("grep(") ||
            t.startsWith("$ ") || l.startsWith("running ") || l == "running" ||
            tookRe.containsMatchIn(t) || earlierLines.containsMatchIn(t)
        ) return TOOL
        if (t.startsWith("●") || t.startsWith("•") || t.startsWith("⏺") || l.startsWith("assistant:")) {
            return ASSISTANT
        }
        if ((t.startsWith(">") || t.startsWith("❯")) && t.substring(1).isNotBlank()) {
            // A quoted line is not a user turn: agents quote tables, logs and code with "> " all
            // the time, and labelling those "You" puts words in the user's mouth. A real user turn
            // is short prose; a quoted table row is full of separators or runs on like output.
            // Look at the whole line, not the un-piped body: a table row keeps its │ separators
            // inside the text, and those are exactly the signal that this is quoted output.
            val body = t.substring(1).trim()
            val quoted = t.any { it in "│┃|├┤┼╭╮╰╯─━" } ||
                body.length > 160 ||
                body.startsWith("$ ") ||
                rangeRe.containsMatchIn(body)
            if (!quoted) return USER
        }
        if (l.startsWith("you:") || l.startsWith("user:")) return USER
        return null
    }

    /** Contiguous 1..n option run that looks like a choice menu, else null. */
    private fun menuRun(lines: List<Line>, start: Int): List<Int>? {
        val idx = ArrayList<Int>()
        val texts = ArrayList<String>()
        var expected = 1
        var j = start
        while (j < lines.size) {
            val ln = lines[j]
            if (ln.dropped || ln.chrome) { j++; continue }
            if (ln.blank) break
            val m = optionLine.matchEntire(unpipe(ln.norm)) ?: break
            if (m.groupValues[1].toInt() != expected) break
            idx.add(j)
            texts.add(m.groupValues[2])
            expected++
            j++
        }
        if (idx.size < 2) return null
        if (texts.any { choiceWords.containsMatchIn(it.lowercase()) }) return idx
        var k = start - 1
        while (k >= 0 && (lines[k].blank || lines[k].chrome || lines[k].dropped)) k--
        if (k >= 0 && questionLike(unpipe(lines[k].norm))) return idx
        return null
    }

    // ---------------------------------------------------------------- block assembly

    private fun makeBlock(
        role: TranscriptRole,
        ls: List<Line>,
        src: String,
        first: Boolean,
        marked: Boolean,
    ): TranscriptBlock {
        val parts = ls.map { unpipe(it.norm) }
        val raw = src.substring(ls.first().start, ls.last().rawEnd)
        var truncated = false
        if (first) {
            val t = parts.first()
            truncated = when {
                earlierLines.containsMatchIn(t) -> true
                role == TOOL -> t.startsWith("⎿") || t.startsWith("└")
                marked -> false
                else -> {
                    val c = t.firstOrNull()
                    c != null && (c.isLowerCase() || c == ',' || c == ')' || c == '…' || t.startsWith("..."))
                }
            }
        }
        return TranscriptBlock(role, joinText(role, parts), raw, truncated)
    }

    /**
     * Join cleaned lines into readable text.
     *
     * Two rules, both earned from real captures:
     *  - A line that opens a list item, a heading or a label starts its own line; the terminal
     *    already broke it there and gluing it onto the previous sentence destroyed the structure
     *    (a `Push` label followed by its command became "Push git push origin ...").
     *  - Everything else is a soft wrap. Join with a space when the boundary is inside a Latin
     *    word ("...new patch" + "set on CL" -> "...new patch set on CL") and with nothing when
     *    the text is CJK, because CJK paragraphs are not space separated.
     */
    private fun joinText(role: TranscriptRole, parts: List<String>): String {
        if (role == TOOL || role == APPROVAL || role == THINKING) return parts.joinToString("\n")
        val sb = StringBuilder()
        for ((idx, part) in parts.withIndex()) {
            if (idx == 0) {
                sb.append(part)
                continue
            }
            val prev = sb.lastOrNull()
            val next = part.firstOrNull()
            val own = startsOwnLine(part) || (prev != null && endsOwnLine(sb))
            if (own) {
                sb.append('\n')
            } else if (prev != null && next != null && prev.isAsciiWordChar() && next.isAsciiWordChar()) {
                sb.append(' ')
            }
            sb.append(part)
        }
        return sb.toString()
    }

    private fun Char.isAsciiWordChar(): Boolean = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

    private fun endsOwnLine(sb: StringBuilder): Boolean {
        val t = sb.trimEnd()
        if (t.isEmpty()) return true
        val last = t.last()
        return last == ':' || last == '：'
    }

    /** A line the writer meant to stand alone: list item, heading, label or short bullet. */
    private fun startsOwnLine(line: String): Boolean {
        val t = line.trimStart()
        if (t.isEmpty()) return true
        if (listItemRe.containsMatchIn(t)) return true
        if (t.startsWith("- ") || t.startsWith("* ") || t.startsWith("• ") || t.startsWith("#")) return true
        if (t.length <= 12 && t.endsWith(":") || t.length <= 12 && t.endsWith("：")) return true
        return false
    }
}
