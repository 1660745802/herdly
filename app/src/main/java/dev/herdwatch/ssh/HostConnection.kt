package dev.herdwatch.ssh

import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.PaneText
import kotlinx.serialization.json.JsonObject

/** The connection operations used by the repository; one instance belongs to one host lease. */
interface HostConnection : AutoCloseable {
    /**
     * What the server reports after it created a container.
     *
     * The pane is what the app needs to start an agent, but undoing a failed start needs the
     * container the server actually made — otherwise the app either leaves an empty tab behind or
     * closes a container it guessed at.
     */
    data class PaneCreated(
        val paneId: String,
        val tabId: String? = null,
        val workspaceId: String? = null,
    )

    /** The config this lease was built from; the pin is updated in place after a confirmation. */
    val config: HostConfig
    val fingerprint: String?

    /**
     * The address the live session ended up on, as `host:port`.
     *
     * A config may list a fallback address, so "connected" alone does not say which route is in
     * use — and for the LAN/public case that is exactly what the user wants to confirm.
     */
    val activeAddress: String?
        get() = null
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
    suspend fun createWorkspace(label: String?, cwd: String? = null): PaneCreated?
    suspend fun createTab(workspaceId: String, label: String?, cwd: String? = null): PaneCreated?
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
