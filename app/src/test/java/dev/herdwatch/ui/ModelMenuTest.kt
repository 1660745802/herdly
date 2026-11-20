package dev.herdwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

internal class ScreenMenuTest {
    @Test fun `pointer plus numbered rows becomes a selectable list`() {
        val screen = """
             How should I proceed?
             ❯ 1. Apply the patch
               2. Show the diff first
               3. Abort
        """.trimIndent()
        val menu = parseScreenMenu(screen)!!
        assertEquals(listOf("Apply the patch", "Show the diff first", "Abort"), menu.rows.map { it.label })
        assertEquals(0, menu.cursor)
    }

    @Test fun `the pointer can sit below the first row`() {
        val menu = parseScreenMenu("❯ 1. one\n  2. two\n  3. three".replace("❯ 1. one", "  1. one").replace("  2. two", "❯ 2. two"))!!
        assertEquals(1, menu.cursor)
    }

    @Test fun `bulleted choices are recognised without numbers`() {
        val menu = parseScreenMenu("Pick a target\n❯ ● staging\n  ○ production")!!
        assertEquals(listOf("staging", "production"), menu.rows.map { it.label })
        assertEquals(0, menu.cursor)
    }

    @Test fun `output without a pointer is not a prompt`() {
        assertNull(parseScreenMenu("1. first\n2. second\n3. third"))
        assertNull(parseScreenMenu("❯ ls -la"))
        assertNull(parseScreenMenu(""))
    }

    @Test fun `a lone pointer row is not enough to drive`() {
        assertNull(parseScreenMenu("❯ Continue? (y/n)"))
    }

    @Test fun `only the lowest pointer is the live choice`() {
        val menu = parseScreenMenu("> 1. alpha\n> 2. beta\n> 3. gamma")!!
        assertEquals(2, menu.cursor)
        assertEquals(listOf(false, false, true), menu.rows.map { it.selected })
    }

    @Test fun `only the last pointed row is the cursor`() {
        // Some TUIs prefix every choice with `>`; Enter still only takes the last one.
        val menu = parseScreenMenu("> 1. alpha\n> 2. beta\n> 3. gamma")!!
        assertEquals(listOf(false, false, true), menu.rows.map { it.selected })
        assertEquals(2, menu.cursor)
    }
}
