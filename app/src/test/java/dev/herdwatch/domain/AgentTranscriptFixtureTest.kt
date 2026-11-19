package dev.herdwatch.domain

import org.junit.Assert.assertEquals

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests against real captures from the host, not invented strings.
 *
 * The fixtures in `app/src/test/resources` are verbatim `pane.read` payloads
 * (source `recent_unwrapped`, ANSI kept) taken from a live agent pane. The chat view keeps its
 * promise — "对话 works whenever an agent is running" — only if these parse reliably.
 */
class AgentTranscriptFixtureTest {

    private fun fixture(name: String): String =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes().toString(Charsets.UTF_8)

    @Test
    fun `a live agent pane parses into tool and assistant turns`() {
        val transcript = AgentTranscript.parse(fixture("snapshot-w4-p1.txt"))

        assertTrue(transcript.reliable)
        assertTrue(transcript.blocks.size >= 3)
        assertTrue(transcript.blocks.any { it.role == TranscriptRole.TOOL })
        val prose = transcript.blocks.filter { it.role == TranscriptRole.ASSISTANT }
        assertTrue(prose.isNotEmpty())
        // The agent's report is the only human-readable message on that screen; it must not be
        // filed as system chrome, and it must survive intact.
        assertTrue(prose.any { it.text.contains("Pushed successfully") })
    }

    @Test
    fun `parsing is idempotent on the joined text`() {
        val transcript = AgentTranscript.parse(fixture("snapshot-w4-p1.txt"))
        val again = AgentTranscript.parse(transcript.blocks.joinToString("\n\n") { it.text })
        assertEquals(
            transcript.blocks.map { it.role }.filter { it != TranscriptRole.SYSTEM },
            again.blocks.map { it.role }.filter { it != TranscriptRole.SYSTEM },
        )
    }

    @Test
    fun `rule rows carrying a spinner status are furniture, not prose`() {
        val rule = "─".repeat(16)
        val screen = listOf(
            "> summarise the change",
            "",
            rule + " ✻ Working " + rule,
            rule,
            "",
            "The change is pushed and the checks pass.",
        ).joinToString("\n")
        val transcript = AgentTranscript.parse(screen)
        assertTrue(transcript.blocks.none { it.text.contains("Working") })
        assertTrue(transcript.blocks.none { it.text.contains("─") })
        assertTrue(transcript.blocks.any { it.text.contains("checks pass") })
    }

    @Test
    fun `reasoning is its own folded block, not part of the answer`() {
        val text = listOf(
            "✻ Thinking: the user asked about the CI status",
            "  the last run finished 4 minutes ago",
            "",
            "⏺ All five CLs are pushed and green.",
        ).joinToString("\n")
        val t = AgentTranscript.parse(text)
        assertTrue("a turn must be recognised", t.reliable)
        val thinking = t.blocks.filter { it.role == TranscriptRole.THINKING }
        assertEquals(1, thinking.size)
        assertTrue(thinking.single().text.contains("CI status"))
        val answer = t.blocks.first { it.role == TranscriptRole.ASSISTANT }
        assertTrue(answer.text.contains("green"))
        assertFalse("reasoning must not leak into the answer", answer.text.contains("CI status"))
    }
}
