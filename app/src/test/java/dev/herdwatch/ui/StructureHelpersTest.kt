package dev.herdwatch.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure helpers behind the Structure-page fixes: the shared name suggestion (J4-12, used by both
 * NewTaskSheet and StartAgentSheet) and the pending-edit pruning (J4-15).
 */
class StructureHelpersTest {

    // --- suggestedAgentName ---

    @Test
    fun freeKindIsUsedAsIs() {
        assertEquals("claude", suggestedAgentName("claude", emptySet()))
        assertEquals("codex", suggestedAgentName("codex", setOf("claude", "claude-2")))
    }

    @Test
    fun duplicateGetsSuffix2() {
        assertEquals("claude-2", suggestedAgentName("claude", setOf("claude")))
    }

    @Test
    fun consecutiveDuplicatesWalkUp() {
        assertEquals("claude-3", suggestedAgentName("claude", setOf("claude", "claude-2")))
        assertEquals("claude-4", suggestedAgentName("claude", setOf("claude", "claude-2", "claude-3")))
    }

    @Test
    fun gapsAreFilledFromTheLowestFreeSuffix() {
        assertEquals("claude-2", suggestedAgentName("claude", setOf("claude", "claude-3", "claude-4")))
        assertEquals("claude-3", suggestedAgentName("claude", setOf("claude", "claude-2", "claude-4")))
    }

    @Test
    fun matchingIsCaseSensitiveLikeTheDuplicateChecks() {
        // The start flows' duplicate gate compares names exactly; the suggestion must agree,
        // otherwise it would "fix" a name the gate considers free (or vice versa).
        assertEquals("claude", suggestedAgentName("claude", setOf("Claude")))
        assertEquals("Claude-2", suggestedAgentName("Claude", setOf("Claude")))
    }

    @Test
    fun unrelatedSuffixesDoNotCount() {
        assertEquals("claude-2", suggestedAgentName("claude", setOf("claude", "codex-2", "claude-x")))
    }

    // --- pruneStructurePending ---

    private fun edit(signature: String, startedAt: Long) = StructurePendingEdit("pane", signature, startedAt)

    @Test
    fun unchangedRowWithinTimeoutKeepsItsMarker() {
        val pending = mapOf("%1" to edit("p:%1", 1_000))
        val pruned = pruneStructurePending(pending, mapOf("%1" to "p:%1"), nowMillis = 2_000)
        assertEquals(pending, pruned)
    }

    @Test
    fun changedNeighbourhoodClearsTheMarker() {
        // A split landed: a sibling pane joined the tab, so the signature differs.
        val pending = mapOf("%1" to edit("p:%1", 1_000))
        val pruned = pruneStructurePending(pending, mapOf("%1" to "p:%1,%2"), nowMillis = 2_000)
        assertTrue(pruned.isEmpty())
    }

    @Test
    fun disappearedRowClearsTheMarker() {
        // A close landed: the id is gone from the live tree entirely.
        val pending = mapOf("%1" to edit("p:%1", 1_000))
        assertTrue(pruneStructurePending(pending, emptyMap(), nowMillis = 2_000).isEmpty())
        // Explicit empty signature (target gone) also clears.
        assertTrue(pruneStructurePending(pending, mapOf("%1" to ""), nowMillis = 2_000).isEmpty())
    }

    @Test
    fun timeoutClearsEvenWithoutASnapshot() {
        val pending = mapOf("%1" to edit("p:%1", 0))
        val signatures = mapOf("%1" to "p:%1")
        assertFalse(pruneStructurePending(pending, signatures, nowMillis = STRUCTURE_PENDING_TIMEOUT_MILLIS - 1).isEmpty())
        assertTrue(pruneStructurePending(pending, signatures, nowMillis = STRUCTURE_PENDING_TIMEOUT_MILLIS).isEmpty())
    }

    @Test
    fun entriesArePrunedIndependently() {
        val pending = mapOf(
            "%1" to edit("p:%1", 0),          // timed out
            "%2" to edit("p:%2", 19_000),     // fresh, unchanged -> kept
            "%3" to edit("p:%3", 19_000),     // fresh, but row gone -> cleared
        )
        val signatures = mapOf("%1" to "p:%1", "%2" to "p:%2")
        val pruned = pruneStructurePending(pending, signatures, nowMillis = 20_000)
        assertEquals(setOf("%2"), pruned.keys)
    }
}
