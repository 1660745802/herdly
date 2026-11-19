package dev.herdwatch.ui

import androidx.lifecycle.ViewModelStore
import dev.herdwatch.data.FakeHostConnection
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.PreferencesStore
import dev.herdwatch.data.RepositoryPreferences
import dev.herdwatch.i18n.TestStrings
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.PaneText
import dev.herdwatch.ssh.HostConnection
import dev.herdwatch.ssh.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReadingPauseTest {

    @Before
    fun installStrings() {
        TestStrings.install()
    }
    private fun agent() = Agent(
        "h", "local", "p", "task", "claude", AgentStatus.WAITING,
        cwd = null, workspaceLabel = null, stateChangeSeq = 1,
        completionSeq = 0, focused = false, rawStatus = "blocked",
    )

    @Test fun `agent detail always reads the whole snapshot and keeps evidence on failure`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            val requests = mutableListOf<Int>()
            var fail = false
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    requests += lines
                    if (fail) error("fixture read failure")
                    return PaneText("recent $lines", requests.size.toLong(), false)
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            vm.refreshPaneQuietly()
            runCurrent()
            // Reading is never something the user has to size: one request, the widest one.
            assertEquals(listOf(1200, 1200), requests)
            assertEquals("recent 1200", vm.ui.value.detail.text)
            fail = true
            vm.reloadPane()
            runCurrent()
            assertEquals("recent 1200", vm.ui.value.detail.text)
            assertTrue(vm.ui.value.detail.stale)
            assertTrue(vm.ui.value.detail.error!!.contains(TestStrings.text("vm_read_failed", "").trim()))
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `paused evidence ignores quiet reads and changed status until explicit refresh`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            var agent = agent()
            fun snapshot() = Snapshot("test", 1, listOf(agent), HerdStructure())
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, snapshot()).apply { onSnapshot = { snapshot() } }
            var reads = 0
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    reads++
                    return PaneText("evidence $reads", reads.toLong(), false)
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            assertEquals("evidence 1", vm.ui.value.detail.text)
            vm.setReadingPaused(true)
            vm.refreshPaneQuietly()
            agent = agent.copy(stateChangeSeq = 2)
            repo.refreshHost(host.id)
            runCurrent()
            assertEquals(1, reads)
            assertEquals("evidence 1", vm.ui.value.detail.text)
            assertTrue(vm.ui.value.detail.stale)
            assertFalse(vm.ui.value.detail.canChoose)
            vm.reloadPane()
            runCurrent()
            assertEquals("evidence 2", vm.ui.value.detail.text)
            assertTrue(vm.ui.value.detail.readingPaused)
            vm.setReadingPaused(false)
            runCurrent()
            assertEquals(3, reads)
            assertFalse(vm.ui.value.detail.readingPaused)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `slow quiet read is not restarted by another poll and cannot overwrite frozen evidence`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            val reply = CompletableDeferred<PaneText>()
            var reads = 0
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    reads++
                    if (reads == 1) return PaneText("visible evidence", 1, false)
                    return withContext(NonCancellable) { reply.await() }
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            vm.refreshPaneQuietly()
            runCurrent()
            vm.refreshPaneQuietly()
            runCurrent()
            assertEquals(2, reads)
            assertFalse(base.closed)
            vm.setReadingPaused(true)
            reply.complete(PaneText("late replacement", 2, false))
            runCurrent()
            assertEquals("visible evidence", vm.ui.value.detail.text)
            assertTrue(vm.ui.value.detail.readingPaused)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `status transition during paused initial read keeps returned evidence stale`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            var agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure())).apply {
                onSnapshot = { Snapshot("test", 1, listOf(agent), HerdStructure()) }
            }
            val first = CompletableDeferred<PaneText>()
            var reads = 0
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    reads++
                    return if (reads == 1) first.await() else PaneText("current evidence", 2, false)
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            vm.setReadingPaused(true)
            agent = agent.copy(stateChangeSeq = 2)
            repo.refreshHost(host.id)
            runCurrent()
            first.complete(PaneText("evidence with unknown ordering", 1, false))
            runCurrent()
            assertTrue(vm.ui.value.detail.stale)
            assertFalse(vm.ui.value.detail.canChoose)
            assertEquals(1L, vm.ui.value.detail.shownSeq)
            assertEquals(2L, vm.ui.value.detail.agent?.stateChangeSeq)
            vm.reloadPane()
            runCurrent()
            assertFalse(vm.ui.value.detail.stale)
            assertTrue(vm.ui.value.detail.readingPaused)
            assertEquals(2L, vm.ui.value.detail.shownSeq)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `pausing during first load still provides evidence for accessible reading`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            val first = CompletableDeferred<PaneText>()
            var reads = 0
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    reads++
                    return first.await()
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            vm.setReadingPaused(true)
            first.complete(PaneText("first evidence", 1, false))
            runCurrent()
            assertEquals("first evidence", vm.ui.value.detail.text)
            assertTrue(vm.ui.value.detail.readingPaused)
            vm.refreshPaneQuietly()
            runCurrent()
            assertEquals(1, reads)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
