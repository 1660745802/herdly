package dev.herdwatch.model

import dev.herdwatch.R

import kotlinx.serialization.Serializable

@Serializable
enum class AuthType { PASSWORD, KEY }

@Serializable
data class HostConfig(
    val id: String,
    val label: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val authType: AuthType = AuthType.PASSWORD,
    val secret: String = "",
    val passphrase: String = "",
    val session: String = "",
    val herdrCommand: String = "herdr",
    val fingerprint: String? = null,
)

@Serializable
enum class AgentStatus {
    WAITING,   // blocked — a human is required
    WORKING,   // working
    REVIEW,    // done, not yet reviewed
    IDLE,      // idle
    UNKNOWN;   // present but unclassifiable — never bury it

    /** Localized label id; UI resolves it with stringResource, no Context needed here. */
    val labelRes: Int
        get() = when (this) {
            WAITING -> R.string.status_waiting
            WORKING -> R.string.status_working
            REVIEW -> R.string.status_review
            IDLE -> R.string.status_idle
            UNKNOWN -> R.string.status_unknown
        }

    companion object {
        fun from(raw: String?): AgentStatus = when (raw?.lowercase()) {
            "blocked" -> WAITING
            "working" -> WORKING
            "done" -> REVIEW
            "idle" -> IDLE
            else -> UNKNOWN
        }
    }
}

@Serializable
data class Agent(
    val hostId: String,
    val hostLabel: String,
    val paneId: String,
    val name: String,
    val kind: String,
    val status: AgentStatus,
    val cwd: String?,
    val workspaceLabel: String?,
    val tabLabel: String? = null,
    val stateChangeSeq: Long,
    val completionSeq: Long,
    val focused: Boolean,
    val rawStatus: String,
    val stateLabels: Map<String, String> = emptyMap(),
    val tokens: Map<String, String> = emptyMap(),
    val sinceMillis: Long = System.currentTimeMillis(),
) {
    val key: String get() = "$hostId:$paneId"
    val repo: String
        get() = cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "—"

    /**
     * A pane is the unit of the detail page, but only some panes have an agent in them. A shell,
     * vim or tmux pane has no conversation to read, so it renders terminal-only instead of
     * pretending to be an empty chat.
     */
    val hasAgent: Boolean get() = kind.isNotBlank()
}

data class HerdWorkspace(
    val id: String,
    val label: String,
    val number: Int,
    val focused: Boolean,
    val tabCount: Int,
    val paneCount: Int,
    val repoName: String? = null,
    val worktreePath: String? = null,
    val linkedWorktree: Boolean = false,
)

data class HerdTab(
    val id: String,
    val workspaceId: String,
    val label: String,
    val number: Int,
    val focused: Boolean,
    val paneCount: Int,
    val status: AgentStatus,
)

data class HerdPane(
    val id: String,
    val terminalId: String,
    val workspaceId: String,
    val tabId: String,
    val title: String,
    val agentKind: String?,
    val status: AgentStatus,
    val revision: Long,
    val cwd: String?,
    val restoreError: String?,
    val sessionKind: String?,
    val sessionValue: String?,
)

data class HerdStructure(
    val workspaces: List<HerdWorkspace> = emptyList(),
    val tabs: List<HerdTab> = emptyList(),
    val panes: List<HerdPane> = emptyList(),
) {
    fun panesOf(tabId: String) = panes.filter { it.tabId == tabId }
    fun tabsOf(workspaceId: String) = tabs.filter { it.workspaceId == workspaceId }
}

/**
 * Builds the detail target for a pane that has no agent. Same page, same reading, same input — it
 * simply has no chat side, which is a property of the pane, not a degraded state.
 */
fun paneDetailAgent(host: HostRuntime, pane: HerdPane): Agent {
    val tab = host.structure.tabs.firstOrNull { it.id == pane.tabId }
    val workspace = host.structure.workspaces.firstOrNull { it.id == pane.workspaceId }
    return Agent(
        hostId = host.host.id,
        hostLabel = host.host.label,
        paneId = pane.id,
        // The terminal title of a shell is the whole "user@host" string; the directory it sits in
        // is the name a person actually recognises.
        name = pane.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: pane.title.ifBlank { pane.id },
        kind = pane.agentKind.orEmpty(),
        status = pane.status,
        cwd = pane.cwd,
        workspaceLabel = workspace?.label,
        tabLabel = tab?.label,
        stateChangeSeq = 0,
        completionSeq = 0,
        focused = false,
        rawStatus = pane.sessionKind.orEmpty(),
    )
}

data class PaneText(
    val text: String,
    val revision: Long?,
    val truncated: Boolean,
)

data class Ask(
    val question: String,
    val options: List<AskOption>,
    val allow: AskOption?,
    val deny: AskOption?,
    val risk: String?,
    val confidence: AskConfidence,
    val source: String,
)

data class AskOption(
    val label: String,
    val keys: List<String>,
    val kind: AskKind,
)

enum class AskKind { ALLOW, DENY, OTHER }

enum class AskConfidence { HIGH, MEDIUM, NONE }

sealed interface ConnState {
    data object Idle : ConnState
    data object Connecting : ConnState
    data object Connected : ConnState
    /**
     * [message] is the human sentence; [technical] keeps the transport's own words for the Details
     * panel. Collapsing them into one string made the card print a multi-line dump with the
     * fingerprint cut in half, while the diagnostic panel lost the raw cause.
     */
    data class Failed(val message: String, val technical: String? = null) : ConnState
}

data class HostRuntime(
    val host: HostConfig,
    val conn: ConnState = ConnState.Idle,
    val agentCount: Int = 0,
    val waitingCount: Int = 0,
    val lastSyncAt: Long? = null,
    val fingerprint: String? = null,
    val structure: HerdStructure = HerdStructure(),
    /**
     * Set when the host presented a different key than the saved pin. The user must decide whether
     * to trust the new key; without this the card only said "connection failed" and the only way
     * forward was the nuclear "reset every fingerprint" switch in Settings.
     */
    val keyMismatch: KeyMismatch? = null,
    /**
     * Set on the very first connection: the host presented a key we have never seen. Nothing is
     * sent to it until the user compares this fingerprint with the one their administrator reads
     * out, so this is a decision, not a notification.
     */
    val pendingKey: PendingHostKey? = null,
)

/** A host key awaiting the user's confirmation, with the algorithm name OpenSSH would print. */
data class PendingHostKey(val fingerprint: String, val keyType: String? = null)

/** Both sides of a host-key change, so the decision can be made by comparing them. */
data class KeyMismatch(val expected: String, val actual: String)

sealed interface Screen {
    data object Inbox : Screen
    data object Hosts : Screen
    data object Settings : Screen
    data class Detail(val agentKey: String) : Screen
    data class Structure(val hostId: String) : Screen
    data class Manage(val hostId: String) : Screen
    data class Terminal(val hostId: String) : Screen
}
