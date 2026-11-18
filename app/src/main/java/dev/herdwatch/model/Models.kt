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
    data class Failed(val message: String) : ConnState
}

data class HostRuntime(
    val host: HostConfig,
    val conn: ConnState = ConnState.Idle,
    val agentCount: Int = 0,
    val waitingCount: Int = 0,
    val lastSyncAt: Long? = null,
    val fingerprint: String? = null,
    val structure: HerdStructure = HerdStructure(),
    val restorable: List<Agent> = emptyList(),
)

sealed interface Screen {
    data object Inbox : Screen
    data object Hosts : Screen
    data object Settings : Screen
    data object StyleGuide : Screen
    data class Detail(val agentKey: String) : Screen
    data class Structure(val hostId: String) : Screen
    data class Terminal(val hostId: String) : Screen
}
