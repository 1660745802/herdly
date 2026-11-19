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

    override fun updateConfig(updated: HostConfig) { config = updated }
    override suspend fun connect() {
        check(!closed)
        onConnect()
        if (!closed) isConnected = true
    }
    override suspend fun ping(): Pair<String?, Int?> { pingCalls++; return "test" to 1 }
    override suspend fun snapshot(): Snapshot { snapshotCalls++; return onSnapshot() }
    override fun close() { closed = true; isConnected = false }
    override suspend fun startAgent(paneId: String, kind: String, name: String, args: List<String>) {
        mutationCalls++
        onStartAgent()
    }

    // Unexpected RPCs fail loudly so tests cannot pass by silently accepting the wrong operation.
    override suspend fun explain(target: String): JsonObject = unexpected()
    override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText = unexpected()
    override suspend fun sendKeys(paneId: String, keys: List<String>): Unit = unexpected()
    override suspend fun sendInput(paneId: String, text: String?, keys: List<String>): Unit = unexpected()
    override suspend fun createWorkspace(label: String?, cwd: String?): String? = unexpected()
    override suspend fun createTab(workspaceId: String, label: String?, cwd: String?): String? = unexpected()
    override suspend fun splitPane(paneId: String, direction: String): Unit = unexpected()
    override suspend fun closePane(paneId: String): JsonObject = unexpected()
    override suspend fun closeTab(tabId: String): JsonObject = unexpected()
    override suspend fun closeWorkspace(workspaceId: String): JsonObject = unexpected()
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
