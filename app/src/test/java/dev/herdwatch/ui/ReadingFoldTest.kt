package dev.herdwatch.ui

import dev.herdwatch.domain.AgentTranscript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reading budget must be honest: what is folded has to be counted the way the screen wraps it,
 * and a paragraph cut by the budget must end at a boundary a person would stop at.
 */
class ReadingFoldTest {

    @Test
    fun latinTextIsCountedByColumns() {
        assertEquals(5, visualWidth("hello"))
        // A Chinese glyph costs two columns, which is why a character count lied about the wall.
        assertEquals(6, visualWidth("问题聚"))
    }

    @Test
    fun wholeParagraphsAreKeptWhileTheyFit() {
        val paragraphs = listOf("a".repeat(40), "b".repeat(40), "c".repeat(400))
        val (shown, cut) = foldToBudget(paragraphs, 100)

        assertEquals(listOf("a".repeat(40), "b".repeat(40)), shown)
        assertTrue(cut)
    }

    @Test
    fun aSingleHugeParagraphIsCutAtASentenceBoundary() {
        val paragraph = "First sentence is here. Second sentence follows. Third is way past the budget."
        val (shown, cut) = foldToBudget(listOf(paragraph), 40)

        assertTrue(cut)
        assertEquals(1, shown.size)
        assertTrue(shown.first().endsWith("…"))
        // Never mid-word: the cut keeps the sentence and its full stop before the ellipsis.
        assertTrue(shown.first().startsWith("First sentence is here."))
    }

    @Test
    fun shortBlocksAreNotFolded() {
        val (shown, cut) = foldToBudget(listOf("ok", "done"), 100)

        assertEquals(listOf("ok", "done"), shown)
        assertFalse(cut)
    }

    @Test
    fun theAgentsOwnFoldMarkerDoesNotSurvive() {
        val text = "占 64%\n… (12 more lines, ctrl+o to expand)"
        val transcript = AgentTranscript.parse(text)

        assertFalse(transcript.blocks.any { it.text.contains("ctrl+o") })
        assertFalse(transcript.blocks.any { it.text.contains("more lines") })
    }

    @Test
    fun durationsAreCoarseAndHuman() {
        // "137 秒" is what a clock says, not what a person says.
        assertEquals("3", relativeSeconds(3_400))
        assertEquals("59", relativeSeconds(59_900))
        assertEquals("1", relativeSeconds(60_000))
        assertEquals("2", relativeSeconds(137_000))
    }

    @Test
    fun `copied screen collapses padding but keeps one blank between blocks`() {
        val screen = listOf(
            "first line" + " ".repeat(40),
            "",
            "",
            "",
            "second block",
            "",
            "",
        ).joinToString("\n")
        val out = condenseScreenText(screen)
        assertEquals("first line" + "\n" + "\n" + "second block", out)
    }

    @Test
    fun aRepaintedParagraphIsCollapsedWithItsCount() {
        val paragraph = "The literal newline inside the JS string broke it. Use an escape."
        val parts = listOf("Intro", paragraph, paragraph, paragraph, "Done")
        val collapsed = collapseRepeats(parts)
        assertEquals(listOf("Intro", paragraph + "  ×3", "Done"), collapsed)
    }

    @Test
    fun shortRepeatsAreLeftAlone() {
        val parts = listOf("ok", "ok", "ok")
        assertEquals(parts, collapseRepeats(parts))
    }

    @Test
    fun aSentenceRepeatedInsideOneParagraphIsCollapsedOnce() {
        val s = "The literal newline inside the JS string broke it. Use an escape." +
            "The literal newline inside the JS string broke it. Use an escape." +
            " Then the build passed."
        val out = collapseRepeatedBlock(s)
        assertTrue(out.contains("×2"))
        assertTrue(out.endsWith("Then the build passed."))
        assertEquals(1, Regex("The literal newline").findAll(out).count())
    }
}
