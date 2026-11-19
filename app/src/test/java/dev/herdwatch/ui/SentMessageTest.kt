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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * What the user sent must show up immediately, and must stop showing up once the screen has it.
 *
 * The bridge hands back a screen, not a transcript. Without a local record the conversation looks
 * unanswered for as long as the agent takes to echo, and with a naive record every message is drawn
 * twice once it does.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SentMessageTest {

    @Before
    fun installStrings() {
        TestStrings.install()
    }

    private fun agent() = Agent(
        "h", "local", "p", "task", "claude", AgentStatus.IDLE,
        cwd = null, workspaceLabel = null, stateChangeSeq = 1,
        completionSeq = 0, focused = false, rawStatus = "idle",
    )

    @Test fun `a send appears at once and is dropped once the screen echoes it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val models = ViewModelStore()
        try {
            val host = HostConfig("h", "local", "localhost", user = "test", fingerprint = "SHA256:test")
            val agent = agent()
            val prefs = RepositoryPreferences().apply { store.save(listOf(host)) }
            val base = FakeHostConnection(host, Snapshot("test", 1, listOf(agent), HerdStructure()))
            var screen = "agent is idle"
            val prompts = mutableListOf<String>()
            val client = object : HostConnection by base {
                override suspend fun readPane(paneId: String, lines: Int, source: String) =
                    PaneText(screen, 1L, false)

                override suspend fun prompt(paneId: String, text: String) {
                    prompts += text
                }
            }
            val repo = HerdRepository(prefs.store, { client }, backgroundScope, dispatcher)
            val vm = AppViewModel(repo, PreferencesStore.inMemory())
            models.put("vm", vm)
            runCurrent()
            vm.openAgent(agent)
            runCurrent()

            vm.sendPrompt("run the tests please")
            runCurrent()
            assertEquals(listOf("run the tests please"), prompts)
            assertEquals(1, vm.ui.value.detail.sent.size)
            assertFalse("not on the screen yet", vm.ui.value.detail.sent.single().echoed)

            // The agent echoes the prompt, wrapped across rows as a TUI would.
            screen = "run the tests\nplease\n\nworking..."
            vm.reloadPane()
            runCurrent()
            assertTrue("the screen now carries it", vm.ui.value.detail.sent.single().echoed)
        } finally {
            models.clear()
            Dispatchers.resetMain()
        }
    }
}
