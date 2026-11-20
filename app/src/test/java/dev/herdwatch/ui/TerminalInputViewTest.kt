package dev.herdwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure input-path logic of TerminalInputView: dedup gate, shadow line, code-point counting. */
class TerminalInputViewTest {
    @Test
    fun `finalize then identical commit is swallowed once`() {
        val gate = CompositionCommitGate()
        gate.composing("你")
        gate.finalized("你")
        assertTrue(gate.commit("你"))
        // The arm is consumed: a second identical commit is a legitimate repeat.
        assertFalse(gate.commit("你"))
    }

    @Test
    fun `commit of different text is never swallowed`() {
        val gate = CompositionCommitGate()
        gate.finalized("你")
        assertFalse(gate.commit("好"))
    }

    @Test
    fun `a new composition of the same word is not swallowed`() {
        // Regression: CJK/handwriting IMEs retype the same word; the second commit must go through.
        val gate = CompositionCommitGate()
        gate.composing("哈")
        gate.finalized("哈")
        assertTrue(gate.commit("哈"))
        gate.composing("哈")
        assertFalse(gate.commit("哈"))
    }

    @Test
    fun `empty composition text keeps the dedup arm`() {
        // Some IMEs clear the composition after finalizing, before the duplicate commit arrives.
        val gate = CompositionCommitGate()
        gate.finalized("你")
        gate.composing("")
        assertTrue(gate.commit("你"))
    }

    @Test
    fun `commit without a finalize always sends`() {
        val gate = CompositionCommitGate()
        assertFalse(gate.commit("a"))
        gate.composing("b")
        assertFalse(gate.commit("b"))
    }

    @Test
    fun `shadow line accumulates commits without a break`() {
        assertEquals("abc", shadowAfterCommit("ab", "c"))
        assertEquals("你", shadowAfterCommit("", "你"))
    }

    @Test
    fun `shadow line resets on any line break style`() {
        assertEquals("", shadowAfterCommit("echo", "\n"))
        // IME Enter can arrive as a lone carriage return via commitText.
        assertEquals("", shadowAfterCommit("echo", "\r"))
        assertEquals("", shadowAfterCommit("echo", "\r\n"))
        assertEquals("next", shadowAfterCommit("echo", "\rnext"))
        assertEquals("next", shadowAfterCommit("echo", "one\ntwo\rnext"))
    }

    @Test
    fun `backspace units convert to code points`() {
        // One emoji is two UTF-16 units but one character to delete.
        assertEquals(1, "ab\uD83D\uDE00".utf16ToCodePoints(at = 4, units = 2))
        assertEquals(2, "ab\uD83D\uDE00".utf16ToCodePoints(at = 4, units = 3))
        assertEquals(2, "abc".utf16ToCodePoints(at = 3, units = 2))
        assertEquals(0, "abc".utf16ToCodePoints(at = 0, units = 2))
    }

    @Test
    fun `forward delete units convert to code points`() {
        assertEquals(1, "\uD83D\uDE00ab".utf16ToCodePoints(at = 0, units = 2, forward = true))
        assertEquals(2, "\uD83D\uDE00ab".utf16ToCodePoints(at = 0, units = 3, forward = true))
        assertEquals(2, "abc".utf16ToCodePoints(at = 1, units = 2, forward = true))
        assertEquals(0, "abc".utf16ToCodePoints(at = 3, units = 2, forward = true))
    }

    @Test
    fun `terminal IME enter is sent as PTY carriage return`() {
        assertEquals("echo one\recho two\r", terminalInputPayload("echo one\necho two\r\n"))
        assertEquals("\r", terminalInputPayload("\n"))
    }
}
