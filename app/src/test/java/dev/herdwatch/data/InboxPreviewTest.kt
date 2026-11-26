package dev.herdwatch.data

import dev.herdwatch.i18n.TestStrings
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.PaneText
import dev.herdwatch.ssh.HostConnection
import dev.herdwatch.ssh.Snapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Inbox triage previews (J3-1): the foreground poll reads a small tail for WAITING/UNKNOWN
 * agents only, capped per cycle, and the parsed question lands on the model. A preview is
 * decoration: it must never fail the host, drop agents, or run in the background.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InboxPreviewTest {
    private val host = HostConfig("h", "host", "localhost", user = "test", fingerprint = "SHA256:test")

    @Before
    fun installStrings() {
        TestStrings.install()
    }

    private val questionScreen = """
        Claude wants to run:
          npm test -- --watch=false

        Do you want to proceed?
        ❯ 1. Yes
          2. Yes, and don't ask again for npm test in /repo
          3. No, and tell Claude what to do differently (esc)
    """.trimIndent()

    @Test
    fun `only waiting and unknown agents get preview reads`() = runTest {
        val fleet = snapshotOf(
            agent("w1", AgentStatus.WAITING),
            agent("u1", AgentStatus.UNKNOWN),
            agent("work", AgentStatus.WORKING),
            agent("idle", AgentStatus.IDLE),
            agent("rev", AgentStatus.REVIEW),
        )
        val client = FakeHostConnection(host, fleet).apply {
            onReadPane = { PaneText(questionScreen, 1, false) }
        }
        val repo = repository(preferences()) { client }
        repo.setVisible(true)
        launch { repo.connectAll() }
        runCurrent()
        // The connect itself never previews; previews ride the poll that follows it.
        assertTrue(client.paneReads.isEmpty())

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(setOf("w1", "u1"), client.paneReads.map { it.paneId }.toSet())
        assertTrue(client.paneReads.all { it.lines == 60 })
        assertTrue(client.paneReads.all { it.source == "recent_unwrapped" })
        val agents = repo.state.value.agents.associateBy { it.paneId }
        assertEquals("Do you want to proceed?", agents.getValue("w1").previewQuestion)
        assertEquals("Do you want to proceed?", agents.getValue("u1").previewQuestion)
        assertNull(agents.getValue("work").previewQuestion)
        assertNull(agents.getValue("idle").previewQuestion)
        assertNull(agents.getValue("rev").previewQuestion)
    }

    @Test
    fun `preview reads are capped at six per cycle`() = runTest {
        val fleet = snapshotOf(
            *(1..8).map { agent("w$it", AgentStatus.WAITING) }.toTypedArray(),
            agent("u1", AgentStatus.UNKNOWN),
        )
        val client = FakeHostConnection(host, fleet).apply {
            onReadPane = { PaneText(questionScreen, 1, false) }
        }
        val repo = repository(preferences()) { client }
        repo.setVisible(true)
        launch { repo.connectAll() }
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(6, client.paneReads.size)
        // The cap takes the list's own order: the six oldest waiting agents, not a random subset.
        assertEquals((1..6).map { "w$it" }, client.paneReads.map { it.paneId })

        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(12, client.paneReads.size)
        // Nine agents needing attention still cost exactly six reads per cycle.
        assertEquals(6, client.paneReads.drop(6).size)
    }

    @Test
    fun `failing preview leaves the host connected and its agents intact`() = runTest {
        val fleet = snapshotOf(
            agent("w1", AgentStatus.WAITING),
            agent("work", AgentStatus.WORKING),
        )
        val client = FakeHostConnection(host, fleet).apply {
            onReadPane = { throw java.io.IOException("preview refused") }
        }
        val prefs = preferences()
        var clientsBuilt = 0
        val repo = repository(prefs) { clientsBuilt++; client }
        repo.setVisible(true)
        launch { repo.connectAll() }
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        assertEquals(1, client.paneReads.size)
        assertFalse(client.closed)
        assertTrue(repo.isHostReady(host.id))
        assertEquals(ConnState.Connected, repo.state.value.hosts.single().conn)
        assertEquals(2, repo.state.value.agents.size)
        assertTrue(repo.state.value.agents.all { it.previewQuestion == null })
        // The next poll keeps working on the same session: no reconnect was forced.
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(1, clientsBuilt)
        assertFalse(client.closed)
        assertEquals(2, client.paneReads.size)
    }

    @Test
    fun `stalled preview times out without touching the host`() = runTest {
        val fleet = snapshotOf(agent("w1", AgentStatus.WAITING))
        val client = FakeHostConnection(host, fleet).apply {
            onReadPane = { delay(60_000); PaneText(questionScreen, 1, false) }
        }
        val repo = repository(preferences()) { client }
        repo.setVisible(true)
        launch { repo.connectAll() }
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        advanceTimeBy(4_000)
        runCurrent()

        assertFalse(client.closed)
        assertTrue(repo.isHostReady(host.id))
        assertEquals(ConnState.Connected, repo.state.value.hosts.single().conn)
        assertNull(repo.state.value.agents.single().previewQuestion)
    }

    @Test
    fun `parsed question lands on the model as one short line and is never persisted`() = runTest {
        val longQuestion = "Do you want to proceed with " + "x".repeat(200) + "?"
        val screen = "Claude wants to run something\n$longQuestion\n❯ 1. Yes\n  2. No (esc)"
        val fleet = snapshotOf(agent("w1", AgentStatus.WAITING))
        val client = FakeHostConnection(host, fleet).apply {
            onReadPane = { PaneText(screen, 1, false) }
        }
        val prefs = preferences()
        val repo = repository(prefs) { client }
        repo.setVisible(true)
        launch { repo.connectAll() }
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()

        val preview = repo.state.value.agents.single().previewQuestion
        assertEquals(longQuestion.take(120), preview)
        assertFalse(preview!!.contains('\n'))
        // Triage text is memory-only: the durable cache must not remember a screen.
        assertTrue(prefs.store.loadAgents(host.id).all { it.previewQuestion == null })

        // A later successful read with no question clears the preview: the ask is gone.
        client.onReadPane = { PaneText("build finished\nnothing to answer", 1, false) }
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(repo.state.value.agents.single().previewQuestion)
    }

    @Test
    fun `failed read keeps the last question but a status change drops it`() = runTest {
        val fleet = snapshotOf(agent("w1", AgentStatus.WAITING))
        val client = FakeHostConnection(host, fleet).apply {
            onReadPane = { PaneText(questionScreen, 1, false) }
        }
        val repo = repository(preferences()) { client }
        repo.setVisible(true)
        launch { repo.connectAll() }
        runCurrent()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("Do you want to proceed?", repo.state.value.agents.single().previewQuestion)

        // A failed preview keeps what was there; the snapshot refresh must not flicker it away.
        client.onReadPane = { throw java.io.IOException("busy") }
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("Do you want to proceed?", repo.state.value.agents.single().previewQuestion)

        // The agent went back to work: the question belonged to the old state and must go.
        client.onSnapshot = { snapshotOf(agent("w1", AgentStatus.WORKING)) }
        advanceTimeBy(5_000)
        runCurrent()
        assertNull(repo.state.value.agents.single().previewQuestion)
        assertEquals(2, client.paneReads.size)
    }

    @Test
    fun `background polling never reads previews`() = runTest {
        val fleet = snapshotOf(agent("w1", AgentStatus.WAITING))
        val client = FakeHostConnection(host, fleet).apply {
            onReadPane = { PaneText(questionScreen, 1, false) }
        }
        val repo = repository(preferences()) { client }
        repo.setVisible(false)
        launch { repo.connectAll() }
        runCurrent()

        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(client.snapshotCalls >= 2)
        assertTrue(client.paneReads.isEmpty())
        assertNull(repo.state.value.agents.single().previewQuestion)

        // Back on screen: the next cycle previews again, no setting involved. The in-flight
        // background delay still runs out first; the cadence is re-read on the next iteration.
        repo.setVisible(true)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(1, client.paneReads.size)
        assertEquals("Do you want to proceed?", repo.state.value.agents.single().previewQuestion)
    }

    private fun preferences() = RepositoryPreferences().also { it.store.save(listOf(host)) }

    private fun TestScope.repository(
        prefs: RepositoryPreferences,
        factory: (HostConfig) -> HostConnection,
    ) = HerdRepository(prefs.store, factory, backgroundScope, StandardTestDispatcher(testScheduler))

    private fun agent(paneId: String, status: AgentStatus) = Agent(
        hostId = host.id, hostLabel = host.label, paneId = paneId, name = paneId, kind = "pi",
        status = status, cwd = null, workspaceLabel = null,
        stateChangeSeq = 1, completionSeq = 0, focused = false,
        rawStatus = status.name.lowercase(), sinceMillis = 42,
    )

    private fun snapshotOf(vararg agents: Agent) = Snapshot(
        "test", 1, agents.toList(),
        HerdStructure(panes = agents.map {
            HerdPane(it.paneId, it.paneId, "w", "t", it.name, "pi", it.status, 1, null, null, null, null)
        }),
    )
}
