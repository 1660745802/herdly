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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Answering a menu is a write to somebody's running program. Two things must hold: the walk is
 * rebuilt from the screen as it is NOW, and a refusal is something the reader actually sees —
 * a message that the next quiet poll erases is the same as sending nothing and saying nothing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MenuAnswerTest {

    @Before
    fun installStrings() {
        TestStrings.install()
    }

    private fun agent() = Agent(
        "h", "local", "p", "task", "claude", AgentStatus.WAITING,
        cwd = null, workspaceLabel = null, stateChangeSeq = 1,
        completionSeq = 0, focused = false, rawStatus = "blocked",
    )

    private val menu = "Do you want to proceed?\n❯ 1. Yes\n  2. No\n"

    @Test
    fun `a changed screen refuses the answer and the refusal survives the next poll`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            var screen = menu
            val sent = mutableListOf<List<String>>()
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText =
                    PaneText(screen, 7, false)

                override suspend fun sendInput(paneId: String, text: String?, keys: List<String>) {
                    sent += keys
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            val parsed = parseScreenMenu(vm.ui.value.detail.text)
            assertNotNull(parsed)
            // Somebody else answered while this sheet was open.
            screen = "Approval accepted. Simulated task complete.\n"
            vm.chooseScreenRow(parsed!!, 1)
            runCurrent()
            assertTrue("nothing may be sent to a screen that changed", sent.isEmpty())
            val refused = vm.ui.value.detail.error ?: vm.ui.value.detail.operationNotice
            assertNotNull("the refusal must be visible", refused)
            // The page keeps polling once the sheet closes; that must not swallow the message.
            vm.refreshPaneQuietly()
            runCurrent()
            val stillThere = vm.ui.value.detail.error ?: vm.ui.value.detail.operationNotice
            assertEquals(refused, stillThere)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `an unchanged screen is answered from the pointer's current position`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            // The pointer moved down since the sheet was opened.
            var screen = menu
            val sent = mutableListOf<List<String>>()
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText =
                    PaneText(screen, 7, false)

                override suspend fun sendInput(paneId: String, text: String?, keys: List<String>) {
                    sent += keys
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()
            val parsed = parseScreenMenu(vm.ui.value.detail.text)!!
            screen = "Do you want to proceed?\n  1. Yes\n❯ 2. No\n"
            vm.chooseScreenRow(parsed, 1)
            runCurrent()
            assertEquals(listOf(listOf("enter")), sent)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
