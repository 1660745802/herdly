package dev.herdwatch.ui

import androidx.lifecycle.ViewModelStore
import dev.herdwatch.data.FakeHostConnection
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.PreferencesStore
import dev.herdwatch.data.RepositoryPreferences
import dev.herdwatch.i18n.TestStrings
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HerdTab
import dev.herdwatch.model.HerdWorkspace
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.Screen
import dev.herdwatch.ssh.Snapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Back must retrace the trail, not teleport to the inbox.
 *
 * Machines → structure → pane → back should land on the structure tree the person was working in.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PageStackTest {

    @Before
    fun installStrings() {
        TestStrings.install()
    }

    @Test fun `back retraces machines to structure to pane`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val structure = HerdStructure(
                workspaces = listOf(HerdWorkspace("w", "main", number = 1, focused = true, tabCount = 1, paneCount = 1)),
                tabs = listOf(HerdTab("w:t", "w", "shell", number = 1, focused = true, paneCount = 1, status = AgentStatus.IDLE)),
                panes = listOf(
                    HerdPane(
                        id = "w:p", terminalId = "t", workspaceId = "w", tabId = "w:t", title = "shell",
                        agentKind = null, status = AgentStatus.IDLE, revision = 1, cwd = "/tmp",
                        restoreError = null, sessionKind = null, sessionValue = null,
                    ),
                ),
            )
            val client = FakeHostConnection(host, Snapshot("test", 1, emptyList(), structure))
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()

            vm.navigate(Screen.Hosts)
            vm.openStructure(host.id)
            runCurrent()
            assertTrue(vm.ui.value.screen is Screen.Structure)

            vm.openPane(host.id, "w:p")
            runCurrent()
            assertTrue("the pane opened", vm.ui.value.screen is Screen.Detail)
            vm.back()
            assertTrue("back returns to the tree", vm.ui.value.screen is Screen.Structure)
            vm.back()
            assertEquals(Screen.Hosts, vm.ui.value.screen)
            vm.back()
            assertEquals("the trail root is the tab we started from", Screen.Hosts, vm.ui.value.screen)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }

    @Test fun `a pane that vanished does not cost an extra back press`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val client = FakeHostConnection(host, Snapshot("test", 1, emptyList(), HerdStructure()))
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()

            vm.navigate(Screen.Hosts)
            vm.openStructure(host.id)
            runCurrent()
            vm.openPane(host.id, "w:gone")
            runCurrent()
            assertEquals("the page did not move", Screen.Structure(host.id), vm.ui.value.screen)
            vm.back()
            assertEquals("one back leaves the tree", Screen.Hosts, vm.ui.value.screen)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
