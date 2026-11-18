package dev.herdwatch.domain

import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.Ask
import dev.herdwatch.model.AskConfidence
import dev.herdwatch.model.AskKind
import dev.herdwatch.model.AskOption

/**
 * Best-effort parser for the last screen of a pane.
 *
 * This is deliberately conservative: when it is not sure, it returns null and the UI only
 * offers "open the screen". A wrong option is worse than no option at all.
 */
object AskParser {

    private val ansi = Regex("\u001B(?:\\[[0-?]*[ -/]*[@-~]|\\][^\u0007]*\u0007)")
    private val optionLine = Regex("""^\s*(?:[❯>●○▸▹])?\s*(\d{1,2})[.)]\s+(.{1,160})$""")
    private val yesNo = Regex("""(?:\[|\(|\b)(y/n|Y/n|y/N|Y/N|yes/no)(?:\]|\)|\b)""", RegexOption.IGNORE_CASE)
    private val pressEnter = Regex("""press\s+enter\s+to\s+confirm|enter\s+to\s+confirm.*esc\s+to\s+cancel""", RegexOption.IGNORE_CASE)

    private val allowWords = listOf(
        "yes", "allow", "approve", "approve", "proceed", "accept", "continue", "run", "ok",
        "允许", "同意", "继续", "确认", "是",
    )
    private val denyWords = listOf(
        "no", "deny", "reject", "cancel", "abort", "stop", "don't", "do not",
        "拒绝", "取消", "停止", "否",
    )

    fun parse(rawText: String): Ask? {
        val text = rawText.replace(ansi, "").replace("\r", "")
        val lines = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return null
        val tail = lines.takeLast(50)

        parseEnterEsc(tail)?.let { return it }
        parseNumbered(tail)?.let { return it }
        parseYesNo(tail)?.let { return it }
        return null
    }

    private fun parseNumbered(lines: List<String>): Ask? {
        // Find the last contiguous run of numbered option lines.
        var end = -1
        for (i in lines.indices.reversed()) {
            if (optionLine.matches(lines[i])) { end = i; break }
        }
        if (end < 0) return null
        var start = end
        while (start - 1 >= 0 && (optionLine.matches(lines[start - 1]) || lines[start - 1].isBlank())) {
            start--
        }
        val block = lines.subList(start, end + 1)
            .mapNotNull { line -> optionLine.matchEntire(line)?.let { it.groupValues[1] to it.groupValues[2].trim() } }
        if (block.size < 2) return null
        // Do not present an old menu above newer output, or a partially captured menu.
        if (block.map { it.first.toInt() } != (1..block.size).toList()) return null
        if (lines.drop(end + 1).any { !isNavigationHint(it) }) return null

        // Only treat it as a prompt when there is a question-ish line above, or a cancel hint.
        val above = lines.subList(maxOf(0, start - 5), start).filter { it.isNotBlank() }
        val context = above.joinToString(" ")
        val looksLikePrompt = above.isNotEmpty() && (
            context.contains("?", ignoreCase = false) ||
                context.contains("want", true) || context.contains("allow", true) ||
                context.contains("proceed", true) || context.contains("confirm", true) ||
                context.contains("cancel", true) || context.contains("permission", true) ||
                context.contains("esc", true) || context.any { it in "？请" }
            )
        if (!looksLikePrompt) return null

        val options = block.map { (num, label) ->
            AskOption(
                label = label,
                keys = listOf(num),
                kind = classify(label),
            )
        }
        val allow = options.firstOrNull { it.kind == AskKind.ALLOW }
        val deny = options.firstOrNull { it.kind == AskKind.DENY }
        if (allow == null && deny == null) return null

        val question = above.lastOrNull { !it.startsWith("─") && !it.startsWith("╭") && !it.startsWith("│") }
            ?: Str.get(R.string.ask_default_question)
        val risk = when {
            block.any { it.second.contains("don't ask again", true) || it.second.contains("always", true) } ->
                Str.get(R.string.ask_risk_standing)
            else -> null
        }
        return Ask(
            question = question,
            options = options,
            allow = allow,
            deny = deny,
            risk = risk,
            confidence = AskConfidence.HIGH,
            source = "numbered-menu",
        )
    }

    private fun parseYesNo(lines: List<String>): Ask? {
        val last = lines.takeLast(8)
        val idx = last.indexOfLast { yesNo.containsMatchIn(it) }
        if (idx < 0 || last.drop(idx + 1).any { !isNavigationHint(it) }) return null
        val line = last[idx]
        val question = if (yesNo.replace(line, "").trim().isBlank()) {
            last.take(idx).lastOrNull() ?: Str.get(R.string.ask_default_question)
        } else line
        val yes = AskOption(Str.get(R.string.ask_yes), listOf("y"), AskKind.ALLOW)
        val no = AskOption(Str.get(R.string.ask_no), listOf("n"), AskKind.DENY)
        return Ask(
            question = question,
            options = listOf(yes, no),
            allow = yes,
            deny = no,
            risk = null,
            confidence = AskConfidence.MEDIUM,
            source = "yes-no",
        )
    }

    private fun parseEnterEsc(lines: List<String>): Ask? {
        val last = lines.takeLast(6)
        val idx = last.indexOfLast { pressEnter.containsMatchIn(it) }
        if (idx < 0 || last.drop(idx + 1).any { !isNavigationHint(it) }) return null
        val allow = AskOption(Str.get(R.string.ask_confirm), listOf("enter"), AskKind.ALLOW)
        val deny = AskOption(Str.get(R.string.common_cancel), listOf("esc"), AskKind.DENY)
        return Ask(
            question = last.take(idx).lastOrNull() ?: Str.get(R.string.ask_default_question),
            options = listOf(allow, deny),
            allow = allow,
            deny = deny,
            risk = null,
            confidence = AskConfidence.MEDIUM,
            source = "enter-esc",
        )
    }

    private fun isNavigationHint(line: String): Boolean {
        val hint = line.trim().lowercase()
        return hint.length <= 100 && (
            hint.startsWith("esc to cancel") || hint.startsWith("escape to cancel") ||
                hint.startsWith("enter to select") || hint.startsWith("use arrow keys") ||
                hint.startsWith("↑/↓")
            )
    }

    private fun classify(label: String): AskKind {
        val s = label.trim().lowercase()
        fun beginsWithWord(word: String): Boolean {
            if (!s.startsWith(word)) return false
            // English substrings such as 'token', 'runner' and 'yesterday' are not choices.
            return word.any { it.code > 127 } || s.length == word.length || !s[word.length].isLetterOrDigit()
        }
        if (denyWords.any(::beginsWithWord)) return AskKind.DENY
        if (allowWords.any(::beginsWithWord)) return AskKind.ALLOW
        return AskKind.OTHER
    }
}

/**
 * Options that grant a standing permission deserve a second look even when the
 * parser labelled them as a normal allow. Kept here (not in the UI) so it is unit-tested.
 */
fun requiresStandingConfirmation(option: AskOption): Boolean {
    val t = option.label.lowercase()
    return listOf(
        "don't ask", "do not ask", "always allow", "always", "never ask",
        "without asking", "allow all", "for all", "this session",
        "不再询问", "无需询问", "总是允许", "永久", "记住", "本次会话", "全部允许",
    ).any { t.contains(it) }
}
