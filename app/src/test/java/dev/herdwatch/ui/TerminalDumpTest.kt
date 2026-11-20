package dev.herdwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure dump logic: cell width measurement, stable line identities, top-reach edge trigger. */
class TerminalDumpTest {
    @Test
    fun `ascii counts one cell per character`() {
        assertEquals(5, terminalCells("hello"))
        assertEquals(0, terminalCells(""))
    }

    @Test
    fun `cjk counts two cells per character`() {
        assertEquals(4, terminalCells("你好"))
        assertEquals(2, terminalCells("ｘ")) // fullwidth form
    }

    @Test
    fun `emoji surrogate pairs count as one wide character`() {
        assertEquals(2, terminalCells("\uD83D\uDE00"))
        assertEquals(4, terminalCells("\uD83D\uDE00\uD83D\uDE00"))
    }

    @Test
    fun `combining marks and zero width characters count nothing`() {
        assertEquals(1, terminalCells("e\u0301")) // e + combining acute
        assertEquals(1, terminalCells("\u00e9"))
        assertEquals(0, terminalCells("\u200B"))
        // Variation selector adds no cell to its base emoji.
        assertEquals(2, terminalCells("\uD83D\uDE00\uFE0F"))
    }

    @Test
    fun `mixed row sums narrow wide and combining`() {
        // "ls " (3) + 你好 (4) + 😀 (2) + e\u0301 (1) = 10
        assertEquals(10, terminalCells("ls 你好\uD83D\uDE00e\u0301"))
    }

    @Test
    fun `ansi cells ignore colour escapes but keep wide widths`() {
        assertEquals(2, ansiCells("\u001b[31m你\u001b[0m"))
        assertEquals(5, ansiCells("\u001b[1;32mhello\u001b[0m"))
        assertEquals(2, ansiCells("\u001b[38;2;10;20;30m\uD83D\uDE00\u001b[0m"))
    }

    @Test
    fun `appending lines keeps existing ordinals`() {
        val first = advanceLineIndex(emptyLineIndex(), listOf("a", "b"))
        val second = advanceLineIndex(first, listOf("a", "b", "c"))
        assertEquals(first.keys, second.keys.take(2))
        assertEquals(3, second.keys.size)
        assertEquals(second.keys.distinct(), second.keys)
    }

    @Test
    fun `prepending history keeps the identity of existing lines`() {
        val first = advanceLineIndex(emptyLineIndex(), listOf("a", "b"))
        val second = advanceLineIndex(first, listOf("old1", "old2", "a", "b"))
        assertEquals(first.keys, second.keys.takeLast(2))
        // The prepended lines get fresh ordinals, never reused ones.
        assertTrue(second.keys[0] !in first.keys)
        assertTrue(second.keys[1] !in first.keys)
        assertEquals(second.keys.distinct(), second.keys)
    }

    @Test
    fun `sliding window keeps the overlap`() {
        val first = advanceLineIndex(emptyLineIndex(), listOf("a", "b", "c"))
        val second = advanceLineIndex(first, listOf("b", "c", "d"))
        assertEquals(first.keys.takeLast(2), second.keys.take(2))
    }

    @Test
    fun `duplicate lines keep positional identity across a prepend`() {
        val first = advanceLineIndex(emptyLineIndex(), listOf("x", "x"))
        val second = advanceLineIndex(first, listOf("x", "x", "x"))
        // The old window matches the new head, so the two on-screen rows keep their keys.
        assertEquals(first.keys, second.keys.take(2))
    }

    @Test
    fun `unrelated content gets fresh ordinals`() {
        val first = advanceLineIndex(emptyLineIndex(), listOf("a", "b"))
        val second = advanceLineIndex(first, listOf("c", "d"))
        assertTrue(second.keys.none { it in first.keys })
        assertEquals(first.nextOrdinal + 2, second.nextOrdinal)
    }

    @Test
    fun `identical content is a no-op`() {
        val first = advanceLineIndex(emptyLineIndex(), listOf("a", "b"))
        assertEquals(first, advanceLineIndex(first, listOf("a", "b")))
    }

    @Test
    fun `top trigger stays silent on short content`() {
        val trigger = TopReachTrigger()
        // At the top but nothing to scroll: no history request.
        assertFalse(trigger.update(atTop = true, scrollable = false))
        assertFalse(trigger.update(atTop = true, scrollable = false))
    }

    @Test
    fun `top trigger fires once per visit to the top`() {
        val trigger = TopReachTrigger()
        assertFalse(trigger.update(atTop = false, scrollable = true))
        assertTrue(trigger.update(atTop = true, scrollable = true))
        // Parked at the top: never again until the reader scrolls away.
        assertFalse(trigger.update(atTop = true, scrollable = true))
        assertFalse(trigger.update(atTop = true, scrollable = true))
        // Scroll away, then back: one more request.
        assertFalse(trigger.update(atTop = false, scrollable = true))
        assertTrue(trigger.update(atTop = true, scrollable = true))
    }

    @Test
    fun `top trigger re-arms when content shrinks below the viewport`() {
        val trigger = TopReachTrigger()
        assertTrue(trigger.update(atTop = true, scrollable = true))
        assertFalse(trigger.update(atTop = true, scrollable = false))
        assertTrue(trigger.update(atTop = true, scrollable = true))
    }
}
