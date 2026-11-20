package dev.herdwatch.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCommandCatalogTest {

    @Test
    fun detectionStringsMapToTheRightCli() {
        assertEquals(AgentCli.CLAUDE, cliOf("claude"))
        assertEquals(AgentCli.CLAUDE, cliOf("Claude Code"))
        assertEquals(AgentCli.CODEX, cliOf("codex"))
        assertEquals(AgentCli.OPENCODE, cliOf("opencode"))
        assertEquals(AgentCli.GEMINI, cliOf("gemini-cli"))
        // pi paints its own name with the Greek letter in a terminal.
        assertEquals(AgentCli.PI, cliOf("π"))
        assertEquals(AgentCli.PI, cliOf("pi"))
        // Anything else is unknown on purpose: guessing would offer another CLI's commands.
        assertEquals(AgentCli.UNKNOWN, cliOf("bash"))
        assertEquals(AgentCli.UNKNOWN, cliOf(""))
        assertEquals(AgentCli.UNKNOWN, cliOf(null))
    }

    @Test
    fun commandsAreReadFromTheScreenTheAgentPaints() {
        val screen = listOf(
            "│ /help      列出这个版本支持的命令",
            "│ /model     切换模型",
            "  › /compact - 压缩上下文",
            "  • /clear",
        ).joinToString("\n")
        val found = discoverCommands(screen)
        assertEquals(listOf("/help", "/model", "/compact", "/clear"), found.map { it.text })
        assertEquals("列出这个版本支持的命令", found[0].purpose)
        assertEquals("压缩上下文", found[2].purpose)
        assertEquals(null, found[3].purpose)
    }

    @Test
    fun pathsAndUrlsAreNotCommands() {
        val screen = listOf(
            "/usr/bin/env must not be offered",
            "/bin/sh",
            "see https://example.com/docs for more",
            "/model",
        ).joinToString("\n")
        assertEquals(listOf("/model"), discoverCommands(screen).map { it.text })
    }

    @Test
    fun aBareMentionIsUpgradedWhenTheDescriptionAppearsLater() {
        val screen = listOf("/model", "/model    切换模型").joinToString("\n")
        val found = discoverCommands(screen)
        assertEquals(1, found.size)
        assertEquals("切换模型", found[0].purpose)
    }

    @Test
    fun discoveryIsCappedSoTheSheetCannotScrollForever() {
        val screen = (1..80).joinToString("\n") { "/cmd$it 说明 $it" }
        assertEquals(40, discoverCommands(screen).size)
    }

    @Test
    fun anUnknownCliGetsNoBorrowedCommands() {
        assertTrue(curatedCommands(AgentCli.UNKNOWN).isEmpty())
        // The fallbacks that do exist stay short on purpose.
        assertTrue(curatedCommands(AgentCli.CLAUDE).size <= 6)
        assertTrue(curatedCommands(AgentCli.CLAUDE).any { it.text == "/model" })
        assertTrue(curatedCommands(AgentCli.GEMINI).any { it.text == "/model" })
    }
}
