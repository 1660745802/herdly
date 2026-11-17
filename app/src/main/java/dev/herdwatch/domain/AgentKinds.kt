package dev.herdwatch.domain

/**
 * Herdr 支持的 agent 很多，但手机端只需要把常用的放在前面。
 * 加新类型只改这里，UI 和启动参数都跟着走。
 */
object AgentKinds {
    val common = listOf("claude", "codex", "pi", "opencode", "gemini")

    fun resumeArgs(kind: String): List<String> = when (kind) {
        "claude" -> listOf("--continue")
        "codex" -> listOf("resume", "--last")
        "pi" -> listOf("--continue")
        "opencode" -> listOf("--continue")
        "gemini" -> listOf("--resume", "latest")
        else -> emptyList()
    }
}
