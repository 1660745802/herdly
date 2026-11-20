package dev.herdwatch.ui

import dev.herdwatch.data.SeenMarks
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `done` and `seen` are different facts. The bridge only reports the first one, so the client keeps
 * the second: an agent that finished while the screen was elsewhere stays at the top of the queue,
 * and one whose result has been on screen drops to idle instead of demanding a manual dismissal.
 */
class SeenMarksTest {
    private fun agent(
        paneId: String,
        status: AgentStatus,
        completionSeq: Long,
        stateChangeSeq: Long = completionSeq,
    ) = Agent(
        hostId = "h", hostLabel = "local", paneId = paneId, name = paneId, kind = "claude",
        status = status, cwd = null, workspaceLabel = null, stateChangeSeq = stateChangeSeq,
        completionSeq = completionSeq, focused = false, rawStatus = status.name.lowercase(),
    )

    @Test
    fun `a read finished turn becomes idle and leaves the attention set`() {
        val done = agent("p1", AgentStatus.REVIEW, completionSeq = 7)

        assertEquals(AgentStatus.REVIEW, done.markSeen(emptyMap()).status)
        assertTrue(isAttention(done.markSeen(emptyMap())))

        val seen = done.markSeen(mapOf(done.key to 7L))
        assertEquals(AgentStatus.IDLE, seen.status)
        assertFalse(isAttention(seen))
        // Nothing else about the row changes, so the list keeps its identity and its place.
        assertEquals(done.name, seen.name)
        assertEquals(done.completionSeq, seen.completionSeq)
    }

    @Test
    fun `an older mark does not hide a newer result`() {
        val done = agent("p1", AgentStatus.REVIEW, completionSeq = 9)

        assertEquals(AgentStatus.REVIEW, done.markSeen(mapOf(done.key to 8L)).status)
    }

    @Test
    fun `marks never touch agents that are not finished`() {
        val marks = mapOf("h:p1" to 100L)
        listOf(AgentStatus.WAITING, AgentStatus.WORKING, AgentStatus.UNKNOWN).forEach { status ->
            val live = agent("p1", status, completionSeq = 1)
            assertEquals(status, live.markSeen(marks).status)
        }
    }

    @Test
    fun `a read agent sorts below one still working`() {
        val finishedUnseen = agent("p-done", AgentStatus.REVIEW, completionSeq = 4)
        val working = agent("p-work", AgentStatus.WORKING, completionSeq = 0)
        val queue = inboxQueue(listOf(working, finishedUnseen).markSeen(mapOf(finishedUnseen.key to 4L)), "")

        assertEquals(listOf(working.key, finishedUnseen.key), queue.map { it.key })
    }

    @Test
    fun `marks only raise and ignore empty or unknown sequences`() {
        SeenMarks.clear()
        try {
            SeenMarks.mark("h:p1", 5)
            SeenMarks.mark("h:p1", 3)
            assertEquals(5L, SeenMarks.marks.value["h:p1"])
            SeenMarks.mark("h:p2", 0)
            SeenMarks.mark("", 9)
            assertEquals(setOf("h:p1"), SeenMarks.marks.value.keys)
        } finally {
            SeenMarks.clear()
        }
    }
}
