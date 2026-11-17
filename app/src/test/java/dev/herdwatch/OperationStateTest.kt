package dev.herdwatch

import dev.herdwatch.domain.ApprovalPolicy
import dev.herdwatch.domain.OperationState
import dev.herdwatch.domain.PageTicket
import dev.herdwatch.model.PaneText
import org.junit.Assert.*
import org.junit.Test

class OperationStateTest {
    private fun evidence(text: String = "cat /safe") = ApprovalPolicy.evidence(
        PaneText("$text\nProceed?\n1. Yes\n2. No", null, false),
    )

    @Test fun `per agent lock survives switching away and back`() {
        val state = OperationState()
        val a = state.begin("a")!!
        assertNotNull(state.begin("b"))
        assertNull(state.begin("a"))
        state.finish("a", a.id, "accepted", false)
        assertNotNull(state.begin("a"))
        assertNotEquals(PageTicket("a", 1), PageTicket("a", 3))
    }

    @Test fun `old operation cannot finish a newer request`() {
        val state = OperationState()
        val old = state.begin("a")!!
        state.finish("a", old.id, "done", false)
        val current = state.begin("a")!!
        state.finish("a", old.id, "stale", true)
        assertEquals(current, state.record("a"))
    }

    @Test fun `accepted approval stays blocked across reread and revision only changes`() {
        val state = OperationState()
        val record = state.begin("a")!!
        val frozen = evidence()
        state.finish("a", record.id, "accepted", false, frozen)
        assertTrue(state.blocksApproval("a", evidence()))
        state.observe("a", frozen.copy(pane = frozen.pane.copy(revision = 999)))
        assertTrue(state.blocksApproval("a", evidence()))
        state.verified("a")
        assertFalse(state.blocksApproval("a", evidence()))
    }

    @Test fun `uncertain write needs explicit verification even with new evidence`() {
        val state = OperationState()
        val record = state.begin("a")!!
        state.finish("a", record.id, "unknown", true, evidence())
        assertNull(state.begin("a"))
        state.observe("a", evidence())
        assertNull(state.begin("a"))
        state.observe("a", evidence("cat /new"))
        assertNull(state.begin("a"))
        state.verified("a")
        assertNotNull(state.begin("a"))
    }

    @Test fun `draft edit away and back does not clear captured draft`() {
        val state = OperationState()
        state.setDraft("a", "first")
        val captured = state.draft("a")
        state.setDraft("a", "second")
        state.setDraft("a", "first")
        state.clearDraft("a", captured)
        assertEquals("first", state.draft("a").text)
        assertEquals("", state.draft("b").text)
        state.clearDraft("a", state.draft("a"))
        assertEquals("", state.draft("a").text)
    }

    @Test fun `truncated changed evidence cannot release binding or uncertainty`() {
        val state = OperationState()
        val operation = state.begin("a")!!
        val frozen = evidence()
        state.finish("a", operation.id, "unknown", true, frozen)
        val incomplete = evidence("different").let { it.copy(pane = it.pane.copy(truncated = true)) }
        state.observe("a", incomplete)
        assertTrue(state.blocksApproval("a", frozen))
        assertNull(state.begin("a"))
    }

    @Test fun `failure keeps captured draft available`() {
        val state = OperationState()
        state.setDraft("a", "keep me")
        val operation = state.begin("a")!!
        state.finish("a", operation.id, "unknown", true)
        assertEquals("keep me", state.draft("a").text)
        state.verified("a")
        assertNotNull(state.begin("a"))
    }
}
