package dev.herdwatch.domain

/**
 * Slash commands, per agent.
 *
 * The rule this file exists to enforce: there is no universal command list. Claude Code, Codex,
 * pi, OpenCode and Gemini each ship their own set, and every release of each CLI changes it, so a
 * single hard-coded list would be wrong for at least four of the five.
 *
 * Two sources, in this order of trust:
 *  1. [discoverCommands] reads the commands the running agent is printing right now — when the
 *     pane shows a help screen or the `/` menu, those entries are authoritative for that version.
 *  2. [curatedCommands] is a small fallback of commands that have been stable across releases,
 *     used only when the screen shows nothing.
 *
 * Nothing here sends anything. A tap puts the text in the composer, because a slash command typed
 * into the wrong pane is still a command someone has to live with.
 */
internal enum class AgentCli(val id: String) {
    CLAUDE("claude"),
    CODEX("codex"),
    PI("pi"),
    OPENCODE("opencode"),
    GEMINI("gemini"),
    UNKNOWN(""),
}

internal data class AgentCommand(
    val text: String,
    val purpose: String? = null,
)

/**
 * Maps whatever Herdr detected onto a known CLI.
 *
 * Detection strings vary by bridge version and by how the agent was launched
 * (`claude`, `Claude Code`, `π`, `gemini-cli`), so matching is forgiving; anything unrecognised
 * stays [AgentCli.UNKNOWN] instead of being guessed into a family. Unknown is not a failure —
 * it means the palette shows only what that pane is actually displaying.
 */
internal fun cliOf(kind: String?): AgentCli {
    val raw = kind?.trim()?.lowercase().orEmpty()
    if (raw.isEmpty()) return AgentCli.UNKNOWN
    // The Greek letter is how the pi agent renders its own name in a terminal.
    if (raw.contains('π') || raw == "pi" || raw.startsWith("pi ")) return AgentCli.PI
    return when {
        raw.startsWith("claude") -> AgentCli.CLAUDE
        raw.startsWith("codex") -> AgentCli.CODEX
        raw.startsWith("opencode") || raw == "oc" -> AgentCli.OPENCODE
        raw.startsWith("gemini") -> AgentCli.GEMINI
        else -> AgentCli.UNKNOWN
    }
}

/** Directories that look like `/word` but are paths, never commands. */
private val pathWords = setOf(
    "bin", "usr", "dev", "var", "tmp", "etc", "home", "opt", "proc", "sys", "run", "srv",
    "root", "mnt", "media", "lib", "lib64", "boot", "sbin", "snap", "nix",
)

/** Leading decoration a TUI puts in front of a menu entry: box drawing, bullets, quotes, spaces. */
private val leadingDecoration = Regex("^[\\s│┃|┆┊>›»•·*●○◦\\-–—]+")

/** `/command` then an optional separator and the purpose. */
private val commandLine = Regex("^/([a-z][a-z0-9:_-]{0,24})(?:[ \\t]+|[ \\t]*[-–—:][ \\t]*)?(.*)$")

/**
 * Commands visible on the current screen.
 *
 * Deliberately conservative: the token must start the line, must not be a known path word, and the
 * remainder must not look like the rest of a path (`/usr/bin/env`). A false positive here means an
 * offer that types garbage into a live agent, so silence beats recall.
 */
internal fun discoverCommands(snapshot: String, limit: Int = 40): List<AgentCommand> {
    val found = LinkedHashMap<String, AgentCommand>()
    for (rawLine in snapshot.lineSequence()) {
        if (found.size >= limit) break
        val line = rawLine.replace(leadingDecoration, "")
        val match = commandLine.find(line) ?: continue
        val word = match.groupValues[1]
        val rest = match.groupValues[2].trim()
        if (word in pathWords) continue
        if (rest.startsWith("/") || rest.startsWith("~")) continue
        val purpose = rest
            .replace(Regex("^[-–—:|]+\\s*"), "")
            .trim()
            .take(90)
            .takeIf { it.isNotEmpty() && it.any { ch -> ch.isLetter() } }
        val existing = found[word]
        if (existing == null || (existing.purpose == null && purpose != null)) {
            found[word] = AgentCommand("/$word", purpose)
        }
    }
    return found.values.toList()
}

/**
 * The fallback list, intentionally short.
 *
 * Only commands that have held across the releases this app has been tested against: a wrong
 * suggestion costs the same as a wrong keystroke, and an exhaustive list that is 20% stale teaches
 * people to stop trusting it.
 */
internal fun curatedCommands(cli: AgentCli): List<AgentCommand> = when (cli) {
    AgentCli.CLAUDE -> listOf(
        AgentCommand("/help", "列出这个版本支持的命令"),
        AgentCommand("/model", "切换模型"),
        AgentCommand("/compact", "压缩上下文"),
        AgentCommand("/clear", "清空当前对话"),
        AgentCommand("/status", "查看会话状态"),
    )

    AgentCli.CODEX -> listOf(
        AgentCommand("/help", "列出这个版本支持的命令"),
        AgentCommand("/model", "切换模型"),
        AgentCommand("/approvals", "调整审批策略"),
        AgentCommand("/new", "开始新会话"),
    )

    AgentCli.PI -> listOf(
        AgentCommand("/help", "列出这个版本支持的命令"),
        AgentCommand("/model", "切换模型"),
        AgentCommand("/clear", "清空当前对话"),
    )

    AgentCli.OPENCODE -> listOf(
        AgentCommand("/help", "列出这个版本支持的命令"),
        AgentCommand("/model", "切换模型"),
        AgentCommand("/new", "开始新会话"),
        AgentCommand("/undo", "撤销上一步"),
    )

    AgentCli.GEMINI -> listOf(
        AgentCommand("/help", "列出这个版本支持的命令"),
        AgentCommand("/model", "切换模型"),
        AgentCommand("/clear", "清空当前对话"),
        AgentCommand("/stats", "查看本次会话统计"),
    )

    // Unknown CLI: show nothing rather than somebody else's commands.
    AgentCli.UNKNOWN -> emptyList()
}
