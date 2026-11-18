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
    fun `direct input typing strips sentinel and empty backspace emits del`() {
        val sentinel = TextFieldValue("\u2060", selection = TextRange(1))

        val typed = directInputDelta(sentinel, TextFieldValue("\u2060a"))
        assertEquals("a", typed.text)
        assertFalse(typed.deleteBackward)
        assertEquals("\u2060", typed.remaining.text)

        // Some IMEs move the selection/sentinel instead of keeping it at offset zero.
        val movedSentinel = directInputDelta(sentinel, TextFieldValue("a\u2060"))
        assertEquals("a", movedSentinel.text)
        assertFalse(movedSentinel.text.contains('\u2060'))

        val legitimateZeroWidthSpace = directInputDelta(sentinel, TextFieldValue("\u2060\u200B"))
        assertEquals("\u200B", legitimateZeroWidthSpace.text)

        val deleted = directInputDelta(sentinel, TextFieldValue(""))
        assertEquals("", deleted.text)
        assertTrue(deleted.deleteBackward)
    }

    @Test
    fun `direct input sends committed prefix while retaining composition`() {
        val previous = TextFieldValue("\u2060hao", selection = TextRange(3), composition = TextRange(1, 4))
        val mixed = TextFieldValue("\u2060你hao", selection = TextRange(4), composition = TextRange(2, 5))

        val delta = directInputDelta(previous, mixed)

        assertEquals("你", delta.text)
        assertFalse(delta.deleteBackward)
        assertEquals("\u2060hao", delta.remaining.text)
        assertEquals(TextRange(1, 4), delta.remaining.composition)
        assertEquals(TextRange(3), delta.remaining.selection)
        assertEquals("你", directSubmitText(mixed))

        val suffix = TextFieldValue("\u2060hao！", composition = TextRange(1, 4))
        val held = directInputDelta(previous, suffix)
        assertEquals("", held.text)
        assertEquals("\u2060hao！", held.remaining.text)
        assertEquals("", directSubmitText(suffix))
    }

    @Test
    fun `direct input holds composition and cancellation does not emit backspace`() {
        val composing = TextFieldValue("ni", composition = TextRange(0, 2))
        val held = directInputDelta(TextFieldValue("\u2060"), composing)
        assertEquals("", held.text)
        assertEquals("\u2060ni", held.remaining.text)

        val cancelled = directInputDelta(composing, TextFieldValue(""))
        assertEquals("", cancelled.text)
        assertFalse(cancelled.deleteBackward)

        val committed = directInputDelta(composing, TextFieldValue("你"))
        assertEquals("你", committed.text)
        assertFalse(committed.deleteBackward)
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
