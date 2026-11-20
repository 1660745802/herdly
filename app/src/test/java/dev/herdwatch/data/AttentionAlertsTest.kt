package dev.herdwatch.data

import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alert rule is asymmetric on purpose: being blocked always matters, finishing only matters
 * while the person is not looking. These tests pin both halves, plus the "we did not see it happen"
 * case that keeps a reconnect from firing a burst of stale alerts.
 */
class AttentionAlertsTest {
    private fun agent(paneId: String, status: AgentStatus) = Agent(
        hostId = "h", hostLabel = "devbox", paneId = paneId, name = paneId, kind = "claude",
        status = status, cwd = null, workspaceLabel = null, stateChangeSeq = 1, completionSeq = 1,
        focused = false, rawStatus = status.name.lowercase(),
    )

    @Test
    fun `the first snapshot is seeded silently`() {
        val alerts = AttentionAlerts()

        assertTrue(alerts.transitions(listOf(agent("p1", AgentStatus.WAITING))).isEmpty())
    }

    @Test
    fun `a new block alerts even while the app is in front`() {
        val alerts = AttentionAlerts()
        alerts.transitions(listOf(agent("p1", AgentStatus.WORKING)))

        val news = alerts.transitions(listOf(agent("p1", AgentStatus.WAITING)), foreground = true)

        assertEquals(1, news.size)
        assertTrue(news.single().needsYou)
        assertEquals("h:p1", news.single().agentKey)
    }

    @Test
    fun `unknown is treated as needing you`() {
        val alerts = AttentionAlerts()
        alerts.transitions(listOf(agent("p1", AgentStatus.IDLE)))

        assertTrue(alerts.transitions(listOf(agent("p1", AgentStatus.UNKNOWN))).single().needsYou)
    }

    @Test
    fun `a finish alerts only when its page is not being watched`() {
        val watched = AttentionAlerts()
        watched.transitions(listOf(agent("p1", AgentStatus.WORKING)))
        assertTrue(
            "watched page must not notify",
            watched.transitions(
                listOf(agent("p1", AgentStatus.REVIEW)),
                watching = { it == "h:p1" },
                foreground = true,
            ).isEmpty(),
        )

        val unseen = AttentionAlerts()
        unseen.transitions(listOf(agent("p1", AgentStatus.WORKING)))
        assertEquals(
            "same transition, screen off",
            1,
            unseen.transitions(listOf(agent("p1", AgentStatus.REVIEW)), foreground = false).size,
        )
    }

    @Test
    fun `a reconnect that brings back an agent we never watched stays quiet`() {
        val alerts = AttentionAlerts()
        alerts.transitions(listOf(agent("p1", AgentStatus.IDLE)))

        val news = alerts.transitions(
            listOf(agent("p1", AgentStatus.IDLE), agent("p2", AgentStatus.WAITING)),
            foreground = false,
        )

        assertTrue("no history means no news", news.isEmpty())
    }

    @Test
    fun `reset forgets history so the next snapshot seeds again`() {
        val alerts = AttentionAlerts()
        alerts.transitions(listOf(agent("p1", AgentStatus.IDLE)))
        alerts.reset()
        alerts.transitions(listOf(agent("p1", AgentStatus.IDLE)))

        assertTrue(alerts.transitions(listOf(agent("p1", AgentStatus.WAITING))).single().needsYou)
    }
}
