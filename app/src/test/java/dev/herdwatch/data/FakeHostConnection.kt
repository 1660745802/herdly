package dev.herdwatch.data

import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.PaneText
import dev.herdwatch.ssh.HostConnection
import dev.herdwatch.ssh.Snapshot
import kotlinx.serialization.json.JsonObject

internal class FakeHostConnection(initialConfig: HostConfig, initialSnapshot: Snapshot) : HostConnection {
    override var config = initialConfig
        private set
    /** What this machine presents in the handshake; tests rotate it to model a reinstall. */
    var presentedKey = "SHA256:test"
    override val fingerprint: String? get() = presentedKey
    override var isConnected = false
        private set
    var closed = false
        private set
    var pingCalls = 0
        private set
    var snapshotCalls = 0
        private set
    var mutationCalls = 0
        private set
    var onConnect: suspend () -> Unit = {}
    var onSnapshot: suspend () -> Snapshot = { initialSnapshot }
    var onStartAgent: suspend () -> Unit = {}
    var onReadPane: suspend () -> PaneText = { PaneText("", null, false) }
    /** Every readPane call, so tests can prove who was read, how much, and how often. */
    data class PaneRead(val paneId: String, val lines: Int, val source: String)
    val paneReads = mutableListOf<PaneRead>()
    /** Workspaces/tabs handed back by the fake create calls, and what cleanup closed. */
    var created = HostConnection.PaneCreated("root", tabId = "tab-1", workspaceId = "ws-1")
    var onCreate: suspend () -> Unit = {}
    var onClose: suspend () -> Unit = {}
    val closedTabs = mutableListOf<String>()
    val closedWorkspaces = mutableListOf<String>()

    override fun updateConfig(updated: HostConfig) { config = updated }
    var connectCalls = 0
        private set

    override suspend fun connect() {
        check(!closed)
        connectCalls++
        onConnect()
        if (!closed) isConnected = true
    }
    override suspend fun ping(): Pair<String?, Int?> { pingCalls++; return "test" to 1 }
    override suspend fun snapshot(): Snapshot { snapshotCalls++; return onSnapshot() }
    override fun close() { closed = true; isConnected = false }

    /** The socket died without this client being closed: the case that must force a reconnect. */
    fun kill() { isConnected = false }
    override suspend fun startAgent(paneId: String, kind: String, name: String, args: List<String>) {
        mutationCalls++
        onStartAgent()
    }

    // Unexpected RPCs fail loudly so tests cannot pass by silently accepting the wrong operation.
    override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
        paneReads += PaneRead(paneId, lines, source)
        return onReadPane()
    }
    override suspend fun sendKeys(paneId: String, keys: List<String>): Unit = unexpected()
    override suspend fun sendInput(paneId: String, text: String?, keys: List<String>): Unit = unexpected()
    override suspend fun createWorkspace(label: String?, cwd: String?): HostConnection.PaneCreated {
        mutationCalls++
        onCreate()
        return created
    }
    override suspend fun createTab(workspaceId: String, label: String?, cwd: String?): HostConnection.PaneCreated {
        mutationCalls++
        onCreate()
        return created
    }
    override suspend fun splitPane(paneId: String, direction: String): Unit = unexpected()
    override suspend fun closePane(paneId: String): JsonObject = unexpected()
    override suspend fun closeTab(tabId: String): JsonObject {
        mutationCalls++
        onClose()
        closedTabs += tabId
        return JsonObject(emptyMap())
    }
    override suspend fun closeWorkspace(workspaceId: String): JsonObject {
        mutationCalls++
        onClose()
        closedWorkspaces += workspaceId
        return JsonObject(emptyMap())
    }
    override suspend fun focusPane(paneId: String): JsonObject = unexpected()
    override suspend fun focusTab(tabId: String): JsonObject = unexpected()
    override suspend fun focusWorkspace(workspaceId: String): JsonObject = unexpected()
    override suspend fun renameAgent(paneId: String, name: String): JsonObject = unexpected()
    override suspend fun renameWorkspace(workspaceId: String, label: String): JsonObject = unexpected()
    override suspend fun renameTab(tabId: String, label: String): JsonObject = unexpected()
    override suspend fun renamePane(paneId: String, label: String): JsonObject = unexpected()
    override suspend fun prompt(paneId: String, text: String): Unit = unexpected()
    private fun unexpected(): Nothing = error("Unexpected connection RPC")
}
