package dev.herdwatch

import dev.herdwatch.data.RepoState
import dev.herdwatch.i18n.TestStrings
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.HostRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The keep-alive notification is a promise about the connection. It must not claim one while every
 * host is offline, and it must not re-post identical text (that flickered the shade on some OEMs).
 */
class ConnectionSummaryTest {

    @Before
    fun installStrings() {
        TestStrings.install()
    }

    private fun host(id: String, conn: ConnState) = HostRuntime(
        host = HostConfig(id, id, "localhost", user = "test"),
        conn = conn,
    )

    private fun agent(status: AgentStatus) = Agent(
        "h", "h", "w:p", "pi", "pi", status,
        cwd = null, workspaceLabel = null, stateChangeSeq = 1,
        completionSeq = 0, focused = false, rawStatus = status.name,
    )

    @Test
    fun noHostsAndNoConnectionBothReadOffline() {
        val offline = TestStrings.text("service_summary_offline")
        assertEquals(offline, connectionSummary(RepoState()))
        assertEquals(offline, connectionSummary(RepoState(hosts = listOf(host("h", ConnState.Idle)))))
    }

    @Test
    fun aConnectedHostWithNothingWaitingSaysSo() {
        val state = RepoState(
            hosts = listOf(host("h", ConnState.Connected)),
            agents = listOf(agent(AgentStatus.IDLE)),
        )
        val summary = connectionSummary(state)
        assertTrue(summary, summary.contains(TestStrings.text("service_summary_idle")))
        assertEquals("same state, same text", summary, connectionSummary(state))
    }

    @Test
    fun waitingWorkIsCountedInTheNotification() {
        val state = RepoState(
            hosts = listOf(host("h", ConnState.Connected)),
            agents = listOf(agent(AgentStatus.WAITING), agent(AgentStatus.REVIEW), agent(AgentStatus.IDLE)),
        )
        val summary = connectionSummary(state)
        assertTrue(summary, summary.contains("2"))
        assertTrue(summary, !summary.contains(TestStrings.text("service_summary_idle")))
    }
}
