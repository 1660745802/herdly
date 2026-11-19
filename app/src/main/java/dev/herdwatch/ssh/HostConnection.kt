package dev.herdwatch.ssh

import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.PaneText
import kotlinx.serialization.json.JsonObject

/** The connection operations used by the repository; one instance belongs to one host lease. */
interface HostConnection : AutoCloseable {
    /** The config this lease was built from; the pin is updated in place after a confirmation. */
    val config: HostConfig
    val fingerprint: String?
    val isConnected: Boolean
    fun updateConfig(updated: HostConfig)
    suspend fun connect()
    suspend fun ping(): Pair<String?, Int?>
    suspend fun snapshot(): Snapshot
    suspend fun explain(target: String): JsonObject
    suspend fun readPane(paneId: String, lines: Int = 400, source: String = "recent"): PaneText
    suspend fun sendKeys(paneId: String, keys: List<String>)

    /** Types literal text into a pane's PTY (optionally followed by key tokens such as Enter). */
    suspend fun sendInput(paneId: String, text: String?, keys: List<String>)
    suspend fun createWorkspace(label: String?, cwd: String? = null): String?
    suspend fun createTab(workspaceId: String, label: String?, cwd: String? = null): String?
    suspend fun splitPane(paneId: String, direction: String)
    suspend fun closePane(paneId: String): JsonObject
    suspend fun closeTab(tabId: String): JsonObject
    suspend fun closeWorkspace(workspaceId: String): JsonObject
    suspend fun focusPane(paneId: String): JsonObject
    suspend fun focusTab(tabId: String): JsonObject
    suspend fun focusWorkspace(workspaceId: String): JsonObject
    suspend fun renameAgent(paneId: String, name: String): JsonObject
    suspend fun renameWorkspace(workspaceId: String, label: String): JsonObject
    suspend fun renameTab(tabId: String, label: String): JsonObject
    suspend fun renamePane(paneId: String, label: String): JsonObject
    suspend fun startAgent(paneId: String, kind: String, name: String, args: List<String>)
    suspend fun prompt(paneId: String, text: String)
}
