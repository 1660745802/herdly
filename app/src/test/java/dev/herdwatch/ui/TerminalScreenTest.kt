package dev.herdwatch.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import dev.herdwatch.term.VtRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalScreenTest {
    /** Feeds successive field values through the same diff the composer uses. */
    private fun type(vararg values: String): List<String> {
        var sent = ""
        return values.map { value ->
            val edit = directInputEdit(sent, TextFieldValue(value))
            sent = edit.sent
            edit.text
        }
    }

    @Test
    fun `arrow keys follow application cursor mode`() {
        "ABCD".forEach { final ->
            assertEquals("\u001b[$final", arrowKeySequence(final, applicationCursorKeys = false))
            assertEquals("\u001bO$final", arrowKeySequence(final, applicationCursorKeys = true))
        }
    }

    @Test
    fun `bracketed paste wraps every payload only when enabled`() {
        assertEquals("one line", bracketedPastePayload("one line", bracketedPaste = false))
        assertEquals("\u001b[200~one line\u001b[201~", bracketedPastePayload("one line", bracketedPaste = true))
    }

    @Test
    fun `inverse swaps explicit and default terminal colors`() {
        val defaultText = Color(0xFFEEEEEE)
        val defaultBackground = Color(0xFF111111)
        val explicit = run(fg = 1, bg = 4, inverse = true).spanStyle(defaultText, defaultBackground)
        assertEquals(xtermColor(4, defaultBackground), explicit.color)
        assertEquals(xtermColor(1, defaultText), explicit.background)

        val defaults = run(inverse = true).spanStyle(defaultText, defaultBackground)
        assertEquals(defaultBackground, defaults.color)
        assertEquals(defaultText, defaults.background)

        val normal = run(fg = 1, bg = 4).spanStyle(defaultText, defaultBackground)
        assertEquals(xtermColor(1, defaultText), normal.color)
        assertEquals(xtermColor(4, defaultBackground), normal.background)
    }

    @Test
    fun `direct input sends one byte per repeated character`() {
        // Regression for `cleaar`: one extra character per IME event.
        assertEquals("aaaa", type("a", "aa", "aaa", "aaaa").joinToString(""))
    }

    @Test
    fun `direct input sends each character of a word exactly once`() {
        assertEquals("clear", type("c", "cl", "cle", "clea", "clear").joinToString(""))
    }

    @Test
    fun `direct input does not resend text the PTY already has`() {
        // An IME re-delivering a value we transmitted must produce an empty diff.
        val replay = directInputEdit("clear", TextFieldValue("clear"))
        assertEquals("", replay.text)
        assertEquals("clear", replay.sent)
        assertFalse(replay.clearField)
    }

    @Test
    fun `direct input sends a multi character commit in one update`() {
        val pasted = directInputEdit("", TextFieldValue("echo AAA"))
        assertEquals("echo AAA", pasted.text)
        assertEquals("echo AAA", pasted.sent)
    }

    @Test
    fun `direct input deleting one character sends only one del`() {
        val deleted = directInputEdit("clear", TextFieldValue("clea"))
        assertEquals("\u007f", deleted.text)
        assertEquals("clea", deleted.sent)

        val emptied = directInputEdit("a", TextFieldValue(""))
        assertEquals("\u007f", emptied.text)
    }

    @Test
    fun `direct input deleting one emoji sends only one del`() {
        // 😀 is two UTF-16 units but a single character on the remote line.
        val deleted = directInputEdit("ab\uD83D\uDE00", TextFieldValue("ab"))
        assertEquals("\u007f", deleted.text)
        assertEquals("ab", deleted.sent)

        assertEquals("\u007f\u007f", directInputEdit("\uD83D\uDE00\uD83D\uDE00", TextFieldValue("")).text)
    }

    @Test
    fun `direct input replaces an emoji without splitting its surrogate pair`() {
        // Shared prefix stops before the emoji, never after its high surrogate.
        val replaced = directInputEdit("a\uD83D\uDE00", TextFieldValue("a\uD83D\uDE01"))
        assertEquals("\u007f\uD83D\uDE01", replaced.text)

        val appended = directInputEdit("a", TextFieldValue("a\uD83D\uDE00"))
        assertEquals("\uD83D\uDE00", appended.text)
        assertEquals("a\uD83D\uDE00", appended.sent)
    }

    @Test
    fun `direct submit erases a deleted emoji with one del`() {
        val edit = directSubmitEdit("a\uD83D\uDE00", TextFieldValue("a"))
        assertEquals("\u007f\r", edit.text)
        assertTrue(edit.clearField)
    }

    @Test
    fun `direct input never sends an unfinished composition`() {
        val composing = TextFieldValue("你", composition = TextRange(0, 1))
        val held = directInputEdit("", composing)
        assertEquals("", held.text)
        assertEquals("", held.sent)

        val committed = directInputEdit(held.sent, TextFieldValue("你"))
        assertEquals("你", committed.text)
    }

    @Test
    fun `direct input keeps pinyin behind a committed prefix until it commits`() {
        val composing = TextFieldValue("hao你", composition = TextRange(3, 4))
        val held = directInputEdit("hao", composing)
        assertEquals("", held.text)
        assertEquals("hao", held.sent)

        val committed = directInputEdit(held.sent, TextFieldValue("hao你"))
        assertEquals("你", committed.text)
    }

    @Test
    fun `direct input clears the mirror past its budget and stays correct afterwards`() {
        val full = "x".repeat(DIRECT_MIRROR_BUDGET)
        val atBudget = directInputEdit("", TextFieldValue(full))
        assertFalse(atBudget.clearField)
        assertEquals(full, atBudget.sent)

        val over = directInputEdit(atBudget.sent, TextFieldValue(full + "y"))
        assertEquals("y", over.text)
        assertTrue(over.clearField)
        assertEquals("", over.sent)

        val afterReset = directInputEdit(over.sent, TextFieldValue("z"))
        assertEquals("z", afterReset.text)
        assertEquals("z", afterReset.sent)
    }

    @Test
    fun `direct submit sends the committed part only while composing`() {
        val composing = TextFieldValue("haoni", composition = TextRange(3, 5))
        val edit = directSubmitEdit("", composing)
        assertEquals("hao", edit.text)
        assertFalse(edit.text.contains('\r'))
        assertFalse(edit.clearField)
        assertEquals("hao", edit.sent)

        // Nothing committed since the last flush: Enter must not duplicate it either.
        assertEquals("", directSubmitEdit("hao", composing).text)
    }

    @Test
    fun `terminal IME enter is sent as PTY carriage return`() {
        assertEquals("echo one\recho two\r", terminalInputPayload("echo one\necho two\r\n"))
        assertEquals("\r", terminalInputPayload("\n"))
    }

    @Test
    fun `direct submit presses return on a committed or empty mirror`() {
        val submit = directSubmitEdit("", TextFieldValue("echo AAA"))
        assertEquals("echo AAA\r", submit.text)
        assertTrue(submit.clearField)
        assertEquals("", submit.sent)

        assertEquals("\r", directSubmitEdit("", TextFieldValue("")).text)
    }

    @Test
    fun `direct input passes a legitimate zero width space through`() {
        assertEquals("\u200B", directInputEdit("", TextFieldValue("\u200B")).text)
    }

    private fun run(
        fg: Int? = null,
        bg: Int? = null,
        inverse: Boolean = false,
    ) = VtRun(
        text = "x",
        fg = fg,
        bg = bg,
        bold = false,
        dim = false,
        italic = false,
        underline = false,
        inverse = inverse,
    )
}
