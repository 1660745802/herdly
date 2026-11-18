package dev.herdwatch.ui

import androidx.lifecycle.ViewModelStore
import dev.herdwatch.data.FakeHostConnection
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.RepositoryPreferences
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.PaneText
import dev.herdwatch.model.Screen
import dev.herdwatch.ssh.HostConnection
import dev.herdwatch.ssh.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test

/**
 * Long-use regressions: the inbox queue is the traversal queue, hiding search keeps its
 * filter, back from structure returns where the user came from, reading bookmarks survive
 * re-entry, and repeated reconnect taps coalesce while a connection attempt is suspended.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DailyWorkflowTest {
    private val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")

    private fun agent(
        paneId: String,
        name: String,
        status: AgentStatus,
        seq: Long,
        since: Long = seq,
    ) = Agent(
        hostId = "h", hostLabel = "local", paneId = paneId, name = name, kind = "claude",
        status = status, cwd = null, workspaceLabel = null, stateChangeSeq = seq, completionSeq = 0,
        focused = false, rawStatus = status.name.lowercase(), sinceMillis = since,
    )

    private fun snapshot(vararg agents: Agent) = Snapshot("test", 1, agents.toList(), HerdStructure())

    /** Delegating client so a detail read is served instead of failing the host connection. */
    private fun reader(base: FakeHostConnection) = object : HostConnection by base {
        override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText =
            PaneText("evidence for $paneId", 1, false)
    }

    private fun TestScope.viewModel(prefs: RepositoryPreferences, client: HostConnection): AppViewModel =
        AppViewModel(HerdRepository(prefs.store, { client }, backgroundScope, StandardTestDispatcher(testScheduler)))

    @Test fun `inbox queue orders attention statuses and searches pane ids`() {
        val waiting = agent("p-wait", "waiting", AgentStatus.WAITING, seq = 3, since = 10)
        val unknown = agent("p-unknown", "unknown", AgentStatus.UNKNOWN, seq = 8)
        val review = agent("p-review", "review", AgentStatus.REVIEW, seq = 7)
        val working = agent("p-working", "working", AgentStatus.WORKING, seq = 5)
        val idle = agent("p-idle", "idle", AgentStatus.IDLE, seq = 9)
        val agents = listOf(idle, working, review, unknown, waiting)

        assertEquals(
            listOf(waiting.key, unknown.key, review.key, working.key, idle.key),
            inboxQueue(agents, InboxFilter.ALL, "").map { it.key },
        )
        assertEquals(
            listOf(waiting.key, unknown.key, review.key),
            inboxQueue(agents, InboxFilter.ATTENTION, "").map { it.key },
        )
        // Searching is a locating action: it must reach agents outside the default attention view.
        assertEquals(listOf(working.key), inboxQueue(agents, InboxFilter.ATTENTION, "P-WORK").map { it.key })
    }

    @Test fun `hiding search keeps the query and its queue filtering`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(
                host,
                snapshot(agent("p1", "alpha", AgentStatus.WORKING, 1), agent("p2", "beta", AgentStatus.WORKING, 2)),
            )
            val vm = viewModel(prefs, reader(base))
            models.put("vm", vm)
            runCurrent()

            vm.setQuery("alpha")
            vm.setSearching(true)
            vm.setSearching(false)
            runCurrent()

            assertEquals("alpha", vm.ui.value.query)
            assertFalse(vm.ui.value.searching)
            val alpha = vm.ui.value.repo.agents.single { it.name == "alpha" }
            vm.openAgent(alpha)
            runCurrent()
            // The hidden filter still scopes the traversal queue to the single match.
            assertEquals(1, vm.ui.value.detail.queueSize)
            vm.openAdjacentAgent(1)
            runCurrent()
            assertEquals(alpha.key, vm.ui.value.detail.agent?.key)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `structure back returns to the screen it was opened from`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            // No stored host, so this client is never contacted; only screen routing is under test.
            val vm = viewModel(RepositoryPreferences(), FakeHostConnection(host, snapshot()))
            models.put("vm", vm)
            runCurrent()

            assertEquals(Screen.Inbox, vm.ui.value.screen)
            vm.openStructure(host.id)
            runCurrent()
            assertTrue(vm.ui.value.screen is Screen.Structure)
            vm.back()
            assertEquals(Screen.Inbox, vm.ui.value.screen)

            vm.navigate(Screen.Hosts)
            vm.openStructure(host.id)
            // Re-opening structure must keep the earliest origin, not the structure screen.
            vm.openStructure(host.id)
            vm.back()
            assertEquals(Screen.Hosts, vm.ui.value.screen)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `help back returns to machines`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val vm = viewModel(RepositoryPreferences(), FakeHostConnection(host, snapshot()))
            models.put("vm", vm)
            runCurrent()

            vm.navigate(Screen.Hosts)
            vm.navigate(Screen.Settings)
            vm.back()

            assertEquals(Screen.Hosts, vm.ui.value.screen)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `reading bookmark survives leaving and reopening the agent`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, snapshot(agent("p1", "alpha", AgentStatus.WORKING, 1)))
            val vm = viewModel(prefs, reader(base))
            models.put("vm", vm)
            runCurrent()

            val live = vm.ui.value.repo.agents.single()
            val bookmark = ReadingPosition(pageOffset = 12, paneOffset = 40, following = false, paused = true)
            vm.rememberReadingPosition(live.key, bookmark)

            vm.openAgent(live)
            runCurrent()
            assertEquals(bookmark, vm.ui.value.detail.readingPosition)
            assertTrue(vm.ui.value.detail.readingPaused)

            vm.back()
            vm.openAgent(live)
            runCurrent()
            assertEquals(bookmark, vm.ui.value.detail.readingPosition)
            assertTrue(vm.ui.value.detail.readingPaused)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `reconnect coalesces while the attempt is still suspended`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val gate = CompletableDeferred<Unit>()
            var attempts = 0
            val base = FakeHostConnection(host, snapshot(agent("p1", "alpha", AgentStatus.WORKING, 1))).apply {
                onConnect = { attempts++; gate.await() }
            }
            var reads = 0
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    reads++
                    return PaneText("evidence $reads", reads.toLong(), false)
                }
            }
            val vm = viewModel(prefs, client)
            models.put("vm", vm)
            runCurrent()
            assertEquals(1, attempts) // initial bootstrap attempt is suspended
            gate.complete(Unit)
            runCurrent()
            assertEquals(ConnState.Connected, vm.ui.value.repo.hosts.single().conn)
            vm.openAgent(vm.ui.value.repo.agents.single())
            runCurrent()
            vm.setDraft("keep this")
            assertEquals(1, reads)

            val second = CompletableDeferred<Unit>()
            base.onConnect = { attempts++; second.await() }
            vm.reconnect(host.id)
            runCurrent()
            assertEquals(2, attempts)
            assertTrue(vm.ui.value.detail.hostConnecting)
            vm.reconnect(host.id)
            vm.reconnect(host.id)
            runCurrent()
            // Extra taps while the attempt is suspended must not start another attempt.
            assertEquals(2, attempts)

            second.complete(Unit)
            runCurrent()
            assertEquals(ConnState.Connected, vm.ui.value.repo.hosts.single().conn)
            assertEquals(2, reads)
            assertEquals("evidence 2", vm.ui.value.detail.text)
            assertEquals("keep this", vm.ui.value.detail.draft)
            assertFalse(vm.ui.value.detail.hostConnecting)
            vm.reconnect(host.id)
            runCurrent()
            assertEquals(3, attempts) // after settling, a new reconnect is allowed again
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
