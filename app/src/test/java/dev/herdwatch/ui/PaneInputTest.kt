package dev.herdwatch.ui

import androidx.lifecycle.ViewModelStore
import dev.herdwatch.data.FakeHostConnection
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.PreferencesStore
import dev.herdwatch.data.RepositoryPreferences
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HerdTab
import dev.herdwatch.model.HerdWorkspace
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
import org.junit.Assert.*
import org.junit.Test

/**
 * Typing into a pane sends the text and the Enter key separately.
 *
 * `pane.send_input` types literal characters: a newline inside `text` is dropped, so the command
 * used to sit at the prompt unexecuted until the reader pressed the key strip. Submitting is a key
 * press, not a character, so the composer has to say so.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PaneInputTest {
    private val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")

    private val structure = HerdStructure(
        workspaces = listOf(
            HerdWorkspace(
                id = "w", label = "main", number = 1, focused = true, tabCount = 1, paneCount = 1,
            ),
        ),
        tabs = listOf(
            HerdTab(
                id = "w:t", workspaceId = "w", label = "main", number = 1, focused = true,
                paneCount = 1, status = AgentStatus.IDLE,
            ),
        ),
        panes = listOf(
            HerdPane(
                id = "w:p", terminalId = "term_1", workspaceId = "w", tabId = "w:t",
                title = "shell", agentKind = null,
                status = AgentStatus.IDLE, revision = 1, cwd = "/tmp",
                restoreError = null, sessionKind = null, sessionValue = null,
            ),
        ),
    )

    private class Recording(base: HostConnection) : HostConnection by base {
        val inputs = mutableListOf<Pair<String?, List<String>>>()
        override suspend fun sendInput(paneId: String, text: String?, keys: List<String>) {
            inputs += text to keys
        }

        override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText =
            PaneText("likunhong@host:/tmp$ ", 1, false)
    }

    @Test fun `composer submit types the command and presses enter`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val client = Recording(FakeHostConnection(host, Snapshot("test", 1, emptyList(), structure)))
            val vm = AppViewModel(
                HerdRepository(prefs.store, { client }, backgroundScope, dispatcher),
                PreferencesStore.inMemory(),
            )
            models.put("vm", vm)
            runCurrent()

            vm.openPane(host.id, "w:p")
            runCurrent()

            vm.sendInput("seq 1 3", submit = true)
            runCurrent()

            val (text, keys) = client.inputs.single()
            assertEquals("seq 1 3", text)
            assertEquals(listOf("enter"), keys)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `typing without submit sends no key press`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val client = Recording(FakeHostConnection(host, Snapshot("test", 1, emptyList(), structure)))
            val vm = AppViewModel(
                HerdRepository(prefs.store, { client }, backgroundScope, dispatcher),
                PreferencesStore.inMemory(),
            )
            models.put("vm", vm)
            runCurrent()

            vm.openPane(host.id, "w:p")
            runCurrent()

            vm.sendInput("seq 1 3", submit = false)
            runCurrent()

            val (text, keys) = client.inputs.single()
            assertEquals("seq 1 3", text)
            assertTrue(keys.isEmpty())
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
