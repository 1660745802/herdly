package dev.herdwatch.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VtScreenTest {

    private fun screen(
        cols: Int = 20,
        rows: Int = 5,
        scrollback: Int = 2000,
    ) = VtScreen(cols = cols, rows = rows, maxScrollback = scrollback)

    private fun VtScreen.feedText(text: String) = feed(text.toByteArray(Charsets.UTF_8))

    private fun VtSnapshot.visible(row: Int): String =
        lines[scrollbackCount + row].runs.joinToString("") { it.text }.trimEnd()

    private fun VtSnapshot.line(index: Int): String =
        lines[index].runs.joinToString("") { it.text }.trimEnd()

    private fun VtSnapshot.screen(): List<String> = (0 until rows).map { visible(it) }

    @Test
    fun `plain text and CRLF move to the next line`() {
        val vt = screen()
        vt.feedText("hello\r\nworld")

        val snapshot = vt.snapshot()
        assertEquals("hello", snapshot.visible(0))
        assertEquals("world", snapshot.visible(1))
        assertEquals(1, snapshot.cursorRow)
        assertEquals(5, snapshot.cursorCol)
        assertEquals(0, snapshot.scrollbackCount)
        assertTrue(snapshot.revision > 0)
    }

    @Test
    fun `utf8 split across feed chunks is decoded once`() {
        val vt = screen()
        val bytes = "中文ok".toByteArray(Charsets.UTF_8)
        // Split in the middle of the first 3-byte character and of the second one.
        vt.feed(bytes, 1)
        vt.feed(bytes.copyOfRange(1, bytes.size), bytes.size - 1)

        val snapshot = vt.snapshot()
        assertEquals("中文ok", snapshot.visible(0))
        assertEquals(6, snapshot.cursorCol)
    }

    @Test
    fun `invalid and overlong utf8 are replaced without creating controls`() {
        val vt = screen(cols = 12, rows = 2)
        vt.feed(byteArrayOf(0xC0.toByte(), 0x80.toByte(), 'A'.code.toByte()))
        vt.feed(byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0x80.toByte(), 'B'.code.toByte()))

        val text = vt.snapshot().visible(0)
        assertTrue(text.endsWith("A���B"))
        assertEquals(7, vt.snapshot().cursorCol)
    }

    @Test
    fun `cjk occupies two cells and the second is a continuation`() {
        val vt = screen()
        vt.feedText("中文A")

        val snapshot = vt.snapshot()
        val runs = snapshot.lines[snapshot.scrollbackCount].runs
        // The continuation cells contribute no text: three glyphs, four cells.
        assertEquals("中文A", runs.joinToString("") { it.text }.trimEnd())
        assertEquals(5, snapshot.cursorCol)
    }

    @Test
    fun `combining marks attach to the previous cell`() {
        val vt = screen()
        vt.feedText("e\u0301x")

        val snapshot = vt.snapshot()
        assertEquals("e\u0301x", snapshot.visible(0))
        assertEquals(2, snapshot.cursorCol)
    }

    @Test
    fun `sgr colours 256 and truecolour and reset`() {
        val vt = screen()
        vt.feedText("\u001b[31mred\u001b[0m\u001b[38;5;196mhot\u001b[48;5;21mbg\u001b[0m\u001b[38;2;255;136;0mrgb\u001b[0mplain")

        val runs = vt.snapshot().lines[vt.snapshot().scrollbackCount].runs
        assertEquals("red", runs[0].text)
        assertEquals(1, runs[0].fg)
        assertEquals("hot", runs[1].text)
        assertEquals(196, runs[1].fg)
        assertEquals(21, runs[2].bg)
        assertEquals("rgb", runs[3].text)
        assertEquals(0x1000000 or 0xFF8800, runs[3].fg)
        assertEquals("plain", runs[4].text.trimEnd())
        assertNull(runs[4].fg)
        assertNull(runs[4].bg)
    }

    @Test
    fun `sgr attributes toggle with bold italic underline inverse`() {
        val vt = screen()
        vt.feedText("\u001b[1;3;4;7mstyled\u001b[22;23;24;27mnormal")

        val runs = vt.snapshot().lines[vt.snapshot().scrollbackCount].runs
        assertTrue(runs[0].bold)
        assertTrue(runs[0].italic)
        assertTrue(runs[0].underline)
        assertTrue(runs[0].inverse)
        assertEquals("normal", runs[1].text.trimEnd())
        assertFalse(runs[1].bold)
        assertFalse(runs[1].italic)
        assertFalse(runs[1].underline)
        assertFalse(runs[1].inverse)
    }

    @Test
    fun `cup and ed el position and erase`() {
        val vt = screen(cols = 10, rows = 4)
        vt.feedText("abcdefghij")
        vt.feedText("\u001b[2;1Hsecond")
        vt.feedText("\u001b[2;4H") // row 2, column 4
        vt.feedText("\u001b[K") // erase to end of line

        var snapshot = vt.snapshot()
        assertEquals("sec", snapshot.visible(1))
        assertEquals("", snapshot.visible(1).drop(3))

        vt.feedText("\u001b[1;1H\u001b[2J") // ED 2 clears everything
        snapshot = vt.snapshot()
        assertTrue(snapshot.screen().all { it.isEmpty() })
        assertEquals(0, snapshot.cursorRow)
        assertEquals(0, snapshot.cursorCol)
    }

    @Test
    fun `erase line modes and erase chars`() {
        val vt = screen(cols = 8, rows = 2)
        vt.feedText("abcdefgh")
        vt.feedText("\u001b[1;2H\u001b[2X") // erase b and c
        assertEquals("a  defgh", vt.snapshot().line(0))

        vt.feedText("\u001b[1;4H\u001b[1K") // erase start..cursor inclusive
        assertEquals("    efgh", vt.snapshot().line(0))

        vt.feedText("\u001b[1;1H\u001b[2K")
        assertEquals("", vt.snapshot().visible(0))
    }

    @Test
    fun `insert and delete characters and lines`() {
        val vt = screen(cols = 8, rows = 3)
        vt.feedText("one\r\ntwo\r\nthree")
        vt.feedText("\u001b[1;1H\u001b[2P") // delete "on"
        assertEquals("e", vt.snapshot().visible(0))
        vt.feedText("\u001b[1;1H\u001b[2@") // insert two blanks
        assertEquals("  e", vt.snapshot().visible(0))

        vt.feedText("\u001b[2;1H\u001b[L") // insert one blank line at row 2
        var snapshot = vt.snapshot()
        assertEquals("  e", snapshot.visible(0))
        assertEquals("", snapshot.visible(1))
        assertEquals("two", snapshot.visible(2))

        vt.feedText("\u001b[2;1H\u001b[M") // delete it again
        snapshot = vt.snapshot()
        assertEquals("two", snapshot.visible(1))
        assertEquals("", snapshot.visible(2))
    }

    @Test
    fun `autowrap wraps at the last column with deferred wrap`() {
        val vt = screen(cols = 5, rows = 3)
        vt.feedText("abcde")

        var snapshot = vt.snapshot()
        assertEquals("abcde", snapshot.visible(0))
        assertEquals(0, snapshot.cursorRow)
        assertEquals(4, snapshot.cursorCol) // deferred: still on the last cell

        vt.feedText("f")
        snapshot = vt.snapshot()
        assertEquals("abcde", snapshot.visible(0))
        assertEquals("f", snapshot.visible(1))
        assertEquals(1, snapshot.cursorRow)
        assertEquals(1, snapshot.cursorCol)
    }

    @Test
    fun `decawn off keeps writing in the last column`() {
        val vt = screen(cols = 5, rows = 3)
        vt.feedText("\u001b[?7labcdefg")

        val snapshot = vt.snapshot()
        assertEquals("abcdg", snapshot.visible(0))
        assertEquals("", snapshot.visible(1))
        assertEquals(0, snapshot.cursorRow)
    }

    @Test
    fun `scrolling pushes the top line into scrollback and the cap holds`() {
        val vt = screen(cols = 6, rows = 2, scrollback = 2)
        vt.feedText("1\r\n2\r\n3\r\n4\r\n5")

        var snapshot = vt.snapshot()
        assertEquals(2, snapshot.scrollbackCount)
        assertEquals("2", snapshot.line(0))
        assertEquals("3", snapshot.line(1))
        assertEquals("4", snapshot.visible(0))
        assertEquals("5", snapshot.visible(1))
    }

    @Test
    fun `alternate screen hides the primary buffer and restores it`() {
        val vt = screen(cols = 10, rows = 3)
        vt.feedText("primary")
        vt.feedText("\u001b[?1049h")

        var snapshot = vt.snapshot()
        assertTrue(snapshot.alternateScreen)
        assertTrue(snapshot.visible(0).isEmpty())

        vt.feedText("alt")
        assertEquals("alt", vt.snapshot().visible(0))

        vt.feedText("\u001b[?1049l")
        snapshot = vt.snapshot()
        assertFalse(snapshot.alternateScreen)
        assertEquals("primary", snapshot.visible(0))
        assertEquals(0, snapshot.cursorRow) // cursor restored with the screen
        assertEquals(7, snapshot.cursorCol)
    }

    @Test
    fun `osc title sequences are swallowed`() {
        val vt = screen()
        vt.feedText("\u001b]0;my title\u0007ok")
        vt.feedText("\u001b]2;another\u001b\\done")

        val snapshot = vt.snapshot()
        assertEquals("okdone", snapshot.visible(0))
        assertEquals(0, snapshot.scrollbackCount)
    }

    @Test
    fun `resize keeps content and shrinking rows feeds scrollback`() {
        val vt = screen(cols = 10, rows = 3)
        vt.feedText("aaa\r\nbbb\r\nccc")

        vt.resize(6, 5)
        var snapshot = vt.snapshot()
        assertEquals(6, snapshot.cols)
        assertEquals(5, snapshot.rows)
        assertEquals("aaa", snapshot.visible(0))
        assertEquals("", snapshot.visible(4))
        assertEquals(0, snapshot.scrollbackCount)

        val shrink = screen(cols = 10, rows = 3)
        shrink.feedText("aaa\r\nbbb\r\nccc")
        shrink.resize(10, 2)
        snapshot = shrink.snapshot()
        assertEquals(1, snapshot.scrollbackCount)
        assertEquals("aaa", snapshot.line(0))
        assertEquals("bbb", snapshot.visible(0))
        assertEquals("ccc", snapshot.visible(1))
    }

    @Test
    fun `cursor visibility and application cursor keys follow dec modes`() {
        val vt = screen()
        assertTrue(vt.snapshot().cursorVisible)
        assertFalse(vt.snapshot().applicationCursorKeys)

        vt.feedText("\u001b[?25l\u001b[?1h")
        assertFalse(vt.snapshot().cursorVisible)
        assertTrue(vt.snapshot().applicationCursorKeys)

        vt.feedText("\u001b[?25h\u001b[?1l")
        assertTrue(vt.snapshot().cursorVisible)
        assertFalse(vt.snapshot().applicationCursorKeys)
    }

    @Test
    fun `csi device attributes query does not reset the screen`() {
        val vt = screen(cols = 10, rows = 2)
        vt.feedText("hello\u001b[c!")

        assertEquals("hello!", vt.snapshot().visible(0))
    }

    @Test
    fun `overlong csi and excess params are swallowed and parsing recovers`() {
        val vt = screen(cols = 20, rows = 2)
        val atParamLimit = List(32) { "1" }.joinToString(";")
        vt.feedText("\u001b[${atParamLimit}mbefore")
        vt.feedText("\u001b[" + "1;".repeat(40) + "31mafter")
        vt.feedText("\u001b[" + "1".repeat(300) + "mend")
        vt.feedText("\u001b[" + "1".repeat(256) + "Xtail")

        assertEquals("beforeafterendtail", vt.snapshot().visible(0))
    }

    @Test
    fun `csi recovery controls win at the length boundary`() {
        val vt = screen(cols = 20, rows = 2)
        val boundary = "1".repeat(256)

        vt.feedText("\u001b[$boundary\u001b[31mred")
        var runs = vt.snapshot().lines[vt.snapshot().scrollbackCount].runs
        assertEquals("red", runs.joinToString("") { it.text }.trimEnd())
        assertEquals(1, runs.first { it.text.contains("red") }.fg)

        vt.feedText("\r\u001b[2K\u001b[$boundary\u0018plain")
        assertEquals("plain", vt.snapshot().visible(0))
    }

    @Test
    fun `origin mode positions and moves the cursor inside the scroll region`() {
        val vt = screen(cols = 10, rows = 6)
        vt.feedText("\u001b[2;5r\u001b[?6h\u001b[1;1HX")
        assertEquals("X", vt.snapshot().visible(1))

        vt.feedText("\u001b[99B!")
        assertEquals("!", vt.snapshot().visible(4).drop(1))

        vt.feedText("\u001b[?6l\u001b[1;1HY")
        assertEquals("Y", vt.snapshot().visible(0))
    }

    @Test
    fun `cursor is rendered as inverse only while visible`() {
        val vt = screen(cols = 5, rows = 2)
        vt.feedText("a")

        var line = vt.snapshot().lines[0]
        assertTrue(line.runs.any { it.inverse && it.text.contains(' ') })

        vt.feedText("\u001b[?25l")
        line = vt.snapshot().lines[0]
        assertFalse(line.runs.any { it.inverse })
    }

    @Test
    fun `scroll region confines line feeds`() {
        val vt = screen(cols = 4, rows = 4)
        vt.feedText("1\r\n2\r\n3\r\n4")
        vt.feedText("\u001b[2;3r\u001b[3;1H") // region rows 2..3, cursor at row 3
        vt.feedText("\n5")

        val snapshot = vt.snapshot()
        assertEquals("1", snapshot.visible(0))
        assertEquals("3", snapshot.visible(1))
        assertEquals("5", snapshot.visible(2))
        assertEquals("4", snapshot.visible(3))
        assertEquals(0, snapshot.scrollbackCount)
    }

    @Test
    fun `esc seven and eight save and restore the cursor`() {
        val vt = screen(cols = 10, rows = 4)
        vt.feedText("\u001b7")
        vt.feedText("\u001b[3;5Hmoved")
        vt.feedText("\u001b8!")

        val snapshot = vt.snapshot()
        assertEquals("moved", snapshot.visible(2).trim())
        assertEquals("!", snapshot.visible(0))
        assertEquals(0, snapshot.cursorRow)
        assertEquals(1, snapshot.cursorCol)
    }

    @Test
    fun `csi split across chunks is parsed once`() {
        val vt = screen(cols = 10, rows = 3)
        vt.feedText("\u001b[3")
        vt.feedText("1mred")

        val runs = vt.snapshot().lines[vt.snapshot().scrollbackCount].runs
        assertEquals("red", runs[0].text)
        assertEquals(1, runs[0].fg)
    }

    @Test
    fun `dec 2004 toggles the bracketed paste flag`() {
        val vt = screen()
        assertFalse(vt.snapshot().bracketedPaste)

        vt.feedText("\u001b[?2004h")
        assertTrue(vt.snapshot().bracketedPaste)

        vt.feedText("\u001b[?2004l")
        assertFalse(vt.snapshot().bracketedPaste)
    }

    @Test
    fun `bracketed paste markers are swallowed instead of drawn`() {
        val vt = screen(cols = 20, rows = 4)
        vt.feedText("\u001b[?2004h")
        // Exactly the bytes the client wraps around a multi-line clipboard paste.
        vt.feedText("\u001b[200~echo hi\u001b[201~")

        val snapshot = vt.snapshot()
        assertEquals("echo hi", snapshot.visible(0))
        assertEquals(7, snapshot.cursorCol)
        assertEquals(0, snapshot.scrollbackCount)
        assertTrue(snapshot.bracketedPaste)
        assertFalse(snapshot.screen().any { it.contains("200") || it.contains("201") })
    }

    @Test
    fun `large feed stays consistent`() {
        val vt = screen(cols = 40, rows = 10, scrollback = 50)
        val line = "0123456789abcdefghij\r\n"
        repeat(2_000) { vt.feedText(line) }

        val snapshot = vt.snapshot()
        assertEquals(50, snapshot.scrollbackCount)
        assertEquals(50 + 10, snapshot.lines.size)
        assertTrue(snapshot.visible(0).startsWith("0123456789"))
    }
}
