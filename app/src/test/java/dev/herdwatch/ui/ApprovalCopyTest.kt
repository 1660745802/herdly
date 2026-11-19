package dev.herdwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The decision panel renders the same screen text as the transcript block. While the panel is open
 * the flow must keep the question but not repeat the numbered options, otherwise the user sees two
 * copies of the same three choices and the inert one looks tappable.
 */
class ApprovalCopyTest {
    private val ask = """
        Allow this command?
        › 1. Allow once
        2. Always allow for this session
        3. Deny
    """.trimIndent()

    @Test
    fun `cursor glyph variants are stripped too`() {
        // Screen scrapers mark the highlighted choice with ❯ or › depending on the TUI.
        val marked = "Allow this command?\n❯ 1. Allow once\n  2. Deny"
        assertEquals("Allow this command?", approvalQuestionOnly(marked))
    }

    @Test
    fun `question survives without the option list`() {
        val question = approvalQuestionOnly(ask)
        assertEquals("Allow this command?", question)
        assertFalse(question.contains("Allow once"))
        assertFalse(question.contains("Deny"))
    }

    @Test
    fun `multi-line prompts keep their body`() {
        val prompt = """
            Write tests for the parser before touching the encoder.

            Which scope?
            1. parser only
            2. both
        """.trimIndent()
        assertEquals(
            "Write tests for the parser before touching the encoder.\n\nWhich scope?",
            approvalQuestionOnly(prompt),
        )
    }

    @Test
    fun `text without options is unchanged`() {
        assertEquals("Proceed?", approvalQuestionOnly("  Proceed?  "))
    }

    @Test
    fun `an option-only block falls back to the original text`() {
        // Losing the block entirely would leave a hole in the conversation, so a pathological parse
        // keeps the raw text instead of becoming blank.
        assertEquals("1. yes\n2. no", approvalQuestionOnly("1. yes\n2. no"))
    }
}
