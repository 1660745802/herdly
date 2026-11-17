package dev.herdwatch

import dev.herdwatch.domain.AskParser
import dev.herdwatch.domain.requiresStandingConfirmation
import dev.herdwatch.model.AskKind
import dev.herdwatch.model.AskOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AskParserTest {

    @Test
    fun `parses claude permission menu`() {
        val screen = """
            Claude wants to run:
              npm test -- --watch=false

            Do you want to proceed?
            ❯ 1. Yes
              2. Yes, and don't ask again for npm test in /repo
              3. No, and tell Claude what to do differently (esc)
        """.trimIndent()

        val ask = AskParser.parse(screen)
        assertEquals("Do you want to proceed?", ask?.question)
        assertEquals(listOf("1"), ask?.allow?.keys)
        assertEquals(listOf("3"), ask?.deny?.keys)
        assertEquals(AskKind.ALLOW, ask?.allow?.kind)
        assertEquals("含长期授权选项", ask?.risk)
    }

    @Test
    fun `parses yes no prompt and appends enter`() {
        val ask = AskParser.parse("Delete build/? This cannot be undone. [y/N]")
        assertEquals("是", ask?.allow?.label)
        assertEquals(listOf("y"), ask?.allow?.keys)
        assertEquals(listOf("n"), ask?.deny?.keys)
    }

    @Test
    fun `does not invent options from ordinary numbered output`() {
        val screen = """
            Build finished.
            1. tests passed
            2. lint warnings
            3. deploy skipped
        """.trimIndent()
        assertNull(AskParser.parse(screen))
    }

    @Test
    fun `strips ansi before parsing`() {
        val screen = "\u001B[32mProceed?\u001B[0m\n\u001B[36m[y/n]\u001B[0m"
        val ask = AskParser.parse(screen)
        assertTrue(ask != null)
        assertEquals(listOf("y"), ask?.allow?.keys)
    }

    @Test
    fun `deny line containing tell is not allow`() {
        val screen = """
            Do you want to proceed?
            1. Yes
            2. No, and tell Claude what to do differently
        """.trimIndent()
        val ask = AskParser.parse(screen)
        assertEquals(listOf("2"), ask?.deny?.keys)
    }

    @Test
    fun `old menu above new output is not actionable`() {
        assertNull(AskParser.parse("Proceed?\n1. Yes\n2. No\nRunning tests now"))
        assertNull(AskParser.parse("Delete? [y/N]\nDeleted successfully"))
        assertNull(AskParser.parse("Press Enter to confirm\nOperation completed"))
    }

    @Test
    fun `incomplete or duplicate numbered menu is rejected`() {
        assertNull(AskParser.parse("Proceed?\n2. Yes\n3. No"))
        assertNull(AskParser.parse("Proceed?\n1. Yes\n1. No"))
        assertNull(AskParser.parse("Proceed?\n1. Yes\n3. No"))
    }

    @Test
    fun `ordinary words containing allow substrings do not grant permission`() {
        assertNull(AskParser.parse("Which output?\n1. token result\n2. runner log"))
        val ask = AskParser.parse("Proceed?\n1. Yes\n2. Do not allow this command")
        assertEquals(AskKind.DENY, ask?.deny?.kind)
    }

    @Test
    fun `long question and grant scope stay intact`() {
        val question = "Do you want to proceed with " + "important-path/".repeat(30) + "?"
        assertEquals(question, AskParser.parse("$question\n1. Yes\n2. No")?.question)
    }

    @Test
    fun `yes no on own line retains question and footer is allowed`() {
        assertEquals("Delete the cache?", AskParser.parse("Delete the cache?\n[y/N]")?.question)
        assertTrue(AskParser.parse("Proceed?\n1. Yes\n2. No\nEsc to cancel") != null)
    }

    @Test
    fun `standing grant is detected in english and chinese`() {
        assertTrue(requiresStandingConfirmation(AskOption("Yes, and don't ask again", emptyList(), AskKind.ALLOW)))
        assertTrue(requiresStandingConfirmation(AskOption("总是允许", emptyList(), AskKind.ALLOW)))
        assertTrue(requiresStandingConfirmation(AskOption("Always allow this tool", emptyList(), AskKind.ALLOW)))
        assertFalse(requiresStandingConfirmation(AskOption("Allow once", emptyList(), AskKind.ALLOW)))
    }
}
