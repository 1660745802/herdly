package dev.herdwatch.ui

import androidx.compose.ui.graphics.Color
import dev.herdwatch.term.VtRun
import org.junit.Assert.assertEquals
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
        // Inverse swaps the roles; the legibility floor may then lift the ink, so the invariant is
        // the swap (and that the pair stays readable), not the exact RGB the program asked for.
        assertEquals(xtermColor(1, defaultText), explicit.background)
        assertTrue(contrastRatio(explicit.color, explicit.background) >= 3f)

        val defaults = run(inverse = true).spanStyle(defaultText, defaultBackground)
        assertEquals(defaultText, defaults.background)
        assertTrue(contrastRatio(defaults.color, defaults.background) >= 3f)

        val normal = run(fg = 1, bg = 4).spanStyle(defaultText, defaultBackground)
        assertEquals(xtermColor(4, defaultBackground), normal.background)
        assertTrue(contrastRatio(normal.color, normal.background) >= 3f)
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

    @Test
    fun searchMarksEveryHitOnTheLineItSearches() {
        val line = dev.herdwatch.term.VtLine(listOf(dev.herdwatch.term.VtRun("dev@devbox", null, null, false, false, false, false, false)))
        val marked = line.annotated(Color.White, Color.Black, query = "@dev", strong = true, mark = Color.Red)
        // The mark is translucent, the program's own background is opaque: only the mark moves.
        val backgrounds = marked.spanStyles.filter { it.item.background.alpha in 0.01f..0.99f }
        assertEquals(1, backgrounds.size)
        assertEquals("@dev", marked.text.substring(backgrounds.single().start, backgrounds.single().end))
    }

    @Test
    fun searchIsCaseInsensitiveAndFindsRepeats() {
        val line = dev.herdwatch.term.VtLine(listOf(dev.herdwatch.term.VtRun("Abc abc", null, null, false, false, false, false, false)))
        val marked = line.annotated(Color.White, Color.Black, query = "abc", mark = Color.Red)
        assertEquals(2, marked.spanStyles.count { it.item.background.alpha in 0.01f..0.99f })
    }
}
