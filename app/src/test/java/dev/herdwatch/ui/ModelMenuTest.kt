package dev.herdwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun `colour codes around the pointer do not hide the menu`() {
        // A real pane arrives with the escapes the program wrote; matching raw bytes found nothing.
        val screen = "\u001b[1mDo you want to proceed?\u001b[0m\n" +
            "\u001b[36m\u001b[1m❯\u001b[0m 1. Yes\n" +
            "  2. Yes, and don't ask again\n" +
            "\u001b[90m  3. No\u001b[0m"
        val menu = parseScreenMenu(screen)!!
        assertEquals(listOf("Yes", "Yes, and don't ask again", "No"), menu.rows.map { it.label })
        assertEquals(0, menu.cursor)
    }

    @Test fun `a menu drawn inside a box is still a menu`() {
        val screen = listOf(
            "╭──────────────────────────────╮",
            "│ Permission required          │",
            "│ ❯ 1. Allow once              │",
            "│   2. Allow for this session  │",
            "│   3. Deny                    │",
            "╰──────────────────────────────╯",
        ).joinToString("\n")
        val menu = parseScreenMenu(screen)!!
        assertEquals(listOf("Allow once", "Allow for this session", "Deny"), menu.rows.map { it.label })
        assertEquals(0, menu.cursor)
    }

    @Test fun `a long option keeps its row so the arrows still land on it`() {
        val long = "Yes, and add this exact command prefix to the project settings file so this permission is never asked again for any future run in this repository"
        val menu = parseScreenMenu("❯ 1. Yes\n  2. $long\n  3. No")!!
        assertEquals(3, menu.rows.size)
        assertEquals(2, menu.rows.indexOfFirst { it.label == "No" })
        assertTrue(menu.rows[1].label.endsWith("…"))
    }

    @Test fun `a numbered log above the pointer cannot starve the options below it`() {
        val log = (1..19).joinToString("\n") { "$it. build step finished" }
        val menu = parseScreenMenu("$log\n❯ 1. Yes\n  2. No")!!
        assertEquals(listOf("Yes", "No"), menu.rows.map { it.label })
        assertEquals(0, menu.cursor)
    }

    @Test fun `two menus separated by a blank line stay separate`() {
        val screen = "❯ 1. earlier choice\n  2. another earlier\n\n❯ 1. Yes\n  2. No"
        val menu = parseScreenMenu(screen)!!
        assertEquals(listOf("Yes", "No"), menu.rows.map { it.label })
    }

    @Test fun `a markdown quote above a numbered answer is not a selector`() {
        val screen = "Here is the plan:\n\n1. Run the tests\n2. Stop the server\n\n> Shall I continue?"
        assertNull(parseScreenMenu(screen))
    }

    @Test fun `a shell prompt wearing a pointer glyph is not a selector`() {
        assertNull(parseScreenMenu("~/project\n❯ vim notes.md"))
        assertNull(parseScreenMenu("1. one\n2. two\n❯ dev@box:~$"))
    }

    @Test fun `a numbered prose paragraph next to a quote stays prose`() {
        val screen = "Results:\n1. 42 tests passed\n2. 3 skipped\n> All green, nothing to do."
        assertNull(parseScreenMenu(screen))
    }

    @Test fun `duplicate labels stay two distinct rows`() {
        val menu = parseScreenMenu("❯ 1. Retry\n  2. Retry\n  3. Abort")!!
        // The caller addresses the tapped position; equal labels must not collapse into one row.
        assertEquals(listOf("Retry", "Retry", "Abort"), menu.rows.map { it.label })
        assertEquals(3, menu.rows.size)
    }
}
