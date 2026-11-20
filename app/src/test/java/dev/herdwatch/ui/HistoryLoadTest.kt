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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Reading further back is the difference between "this page has my history" and "scroll up does
 * nothing". These pin the three answers the page must be able to tell apart: more is coming, the
 * read failed and can be retried, and the host really has nothing older.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryLoadTest {

    @Before
    fun installStrings() {
        TestStrings.install()
    }

    private fun agent() = Agent(
        "h", "local", "p", "task", "claude", AgentStatus.WAITING,
        cwd = null, workspaceLabel = null, stateChangeSeq = 1,
        completionSeq = 0, focused = false, rawStatus = "blocked",
    )

    @Test
    fun `each read doubles the tail instead of asking for the same buffer again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            val requests = mutableListOf<Int>()
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    requests += lines
                    return PaneText("screen of $lines", requests.size.toLong(), true)
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            vm.loadMoreHistory()
            runCurrent()
            vm.loadMoreHistory()
            runCurrent()
            assertEquals(listOf(1200, 2400, 4800), requests)
            assertEquals("screen of 4800", vm.ui.value.detail.text)
            assertFalse(vm.ui.value.detail.historyEnded)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a complete buffer ends history instead of asking again`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            val requests = mutableListOf<Int>()
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
                    requests += lines
                    // The host had the whole buffer: nothing older exists, whatever we ask for.
                    return PaneText("only screen", requests.size.toLong(), false)
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            vm.loadMoreHistory()
            runCurrent()
            assertTrue(vm.ui.value.detail.historyEnded)
            // Asking again would be a round trip for an answer already known.
            val asked = requests.size
            vm.loadMoreHistory()
            runCurrent()
            assertEquals(asked, requests.size)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `a failed wider read stays retryable and an explicit tap retries it`() = runTest {
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
                    // A too-wide tail read times out on the host side. The repository treats that
                    // as a guess that was too big and keeps the session, which is the case the
                    // retry affordance exists for.
                    if (fail) withTimeout(1) { delay(60_000) }
                    return PaneText("screen of $lines", requests.size.toLong(), true)
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            fail = true
            vm.loadMoreHistory()
            // The read suspends, then the request deadline expires: a real timeout rather than a
            // synthetic exception, so the page state is produced by the production path.
            runCurrent()
            advanceTimeBy(2)
            runCurrent()
            assertTrue(vm.ui.value.detail.historyFailed)
            assertFalse(vm.ui.value.detail.historyEnded)
            // The visible text survived the failed tail read: a timeout must not blank the page.
            assertEquals("screen of 1200", vm.ui.value.detail.text)
            // Scrolling again must not hammer a failing host...
            val asked = requests.size
            vm.loadMoreHistory()
            runCurrent()
            assertEquals(asked, requests.size)
            // ...but the retry the user tapped goes out immediately.
            fail = false
            vm.loadMoreHistory(force = true)
            runCurrent()
            assertEquals(asked + 1, requests.size)
            assertFalse(vm.ui.value.detail.historyFailed)
            assertEquals("screen of 2400", vm.ui.value.detail.text)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
