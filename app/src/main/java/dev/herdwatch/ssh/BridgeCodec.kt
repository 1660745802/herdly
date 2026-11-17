package dev.herdwatch.ssh

import dev.herdwatch.model.Agent
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HerdTab
import dev.herdwatch.model.HerdWorkspace
import dev.herdwatch.model.PaneText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

class HerdrRpcException(val code: String?, message: String) : Exception(message)

data class Snapshot(
    val version: String?,
    val protocol: Int?,
    val agents: List<Agent>,
    val structure: HerdStructure,
)

object BridgeCodec {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun errorOf(line: String): String? = runCatching {
        val root = json.parseToJsonElement(line).jsonObject
        val err = root["error"]?.jsonObject ?: return null
        val code = err["code"]?.jsonPrimitive?.contentOrNull
        val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
        if (code.isNullOrBlank()) msg else "$msg ($code)"
    }.getOrNull()

    /** Returns the `result` object, or throws a readable exception. */
    fun resultOf(line: String, expectedId: String? = null): JsonObject {
        val root = json.parseToJsonElement(line).jsonObject
        val id = root["id"]?.jsonPrimitive?.contentOrNull
        if (expectedId != null && id != null && id != expectedId) {
            throw HerdrRpcException(null, "响应 id 不匹配：期望 $expectedId，收到 $id")
        }
        root["error"]?.jsonObject?.let { err ->
            val code = err["code"]?.jsonPrimitive?.contentOrNull
            val msg = err["message"]?.jsonPrimitive?.contentOrNull ?: "未知错误"
            throw HerdrRpcException(code, msg)
        }
        return root["result"]?.jsonObject
            ?: throw HerdrRpcException(null, "响应里没有 result")
    }

    fun parsePing(result: JsonObject): Pair<String?, Int?> {
        val version = result["version"]?.jsonPrimitive?.contentOrNull
        val protocol = result["protocol"]?.jsonPrimitive?.intOrNull
        return version to protocol
    }

    fun parseSnapshot(result: JsonObject, hostId: String, hostLabel: String): Snapshot {
        // The bridge wraps the payload: {"type":"session_snapshot","snapshot":{...}}.
        // Accept a flat payload too so older/newer shapes don't silently yield zero agents.
        val body = result["snapshot"]?.jsonObject ?: result
        val workspaceNames = body["workspaces"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { w ->
                val id = w["workspace_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                id to (w["label"]?.jsonPrimitive?.contentOrNull ?: id)
            }?.toMap().orEmpty()
        val tabNames = body["tabs"]?.jsonArray
            ?.mapNotNull { it as? JsonObject }
            ?.mapNotNull { t ->
                val id = t["tab_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                id to (t["label"]?.jsonPrimitive?.contentOrNull ?: t["number"]?.jsonPrimitive?.contentOrNull ?: id)
            }?.toMap().orEmpty()

        val agents = body["agents"]?.jsonArray?.mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            val paneId = a.string("pane_id") ?: a.string("terminal_id") ?: return@mapNotNull null
            val statusRaw = a.string("agent_status") ?: "unknown"
            val cwd = a.string("cwd") ?: a.string("foreground_cwd")
            val wsId = a.string("workspace_id")
            val kind = a.string("display_agent") ?: a.string("agent") ?: "unknown"
            // Prefer a human label (user name or terminal title) over the agent kind,
            // otherwise every pi/claude row reads just "pi"/"claude".
            val name = a.string("name")
                ?: a.string("terminal_title_stripped")
                ?: a.string("terminal_title")
                ?: a.string("display_agent")
                ?: a.string("agent")
                ?: paneId
            Agent(
                hostId = hostId,
                hostLabel = hostLabel,
                paneId = paneId,
                name = name,
                kind = kind,
                status = dev.herdwatch.model.AgentStatus.from(statusRaw),
                cwd = cwd,
                workspaceLabel = wsId?.let { workspaceNames[it] ?: it },
                tabLabel = a.string("tab_id")?.let { tabNames[it] },
                stateChangeSeq = a.long("state_change_seq") ?: 0L,
                completionSeq = a.long("completion_seq") ?: 0L,
                focused = a.bool("focused") ?: false,
                stateLabels = stringMap(a["state_labels"]),
                tokens = stringMap(a["tokens"]),
                rawStatus = statusRaw,
            )
        }.orEmpty().distinctBy { it.paneId }

        return Snapshot(
            version = body.string("version"),
            protocol = body["protocol"]?.jsonPrimitive?.intOrNull,
            agents = agents,
            structure = parseStructure(body),
        )
    }

    private fun stringMap(el: kotlinx.serialization.json.JsonElement?): Map<String, String> =
        (el as? JsonObject)?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }.orEmpty()

    private fun parseStructure(body: JsonObject): HerdStructure {
        val workspaces = body["workspaces"]?.jsonArray?.mapNotNull { el ->
            val w = el as? JsonObject ?: return@mapNotNull null
            val id = w.string("workspace_id") ?: return@mapNotNull null
            HerdWorkspace(
                id = id,
                label = w.string("label") ?: id,
                number = w.long("number")?.toInt() ?: 0,
                focused = w.bool("focused") ?: false,
                tabCount = w.long("tab_count")?.toInt() ?: 0,
                paneCount = w.long("pane_count")?.toInt() ?: 0,
                repoName = (w["worktree"] as? JsonObject)?.string("repo_name"),
                worktreePath = (w["worktree"] as? JsonObject)?.string("checkout_path"),
                linkedWorktree = (w["worktree"] as? JsonObject)?.bool("is_linked_worktree") ?: false,
            )
        }.orEmpty()

        val tabs = body["tabs"]?.jsonArray?.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val id = t.string("tab_id") ?: return@mapNotNull null
            HerdTab(
                id = id,
                workspaceId = t.string("workspace_id") ?: return@mapNotNull null,
                label = t.string("label") ?: id,
                number = t.long("number")?.toInt() ?: 0,
                focused = t.bool("focused") ?: false,
                paneCount = t.long("pane_count")?.toInt() ?: 0,
                status = dev.herdwatch.model.AgentStatus.from(t.string("agent_status")),
            )
        }.orEmpty()

        val panes = body["panes"]?.jsonArray?.mapNotNull { el ->
            val p = el as? JsonObject ?: return@mapNotNull null
            val id = p.string("pane_id") ?: return@mapNotNull null
            val session = p["agent_session"] as? JsonObject
            HerdPane(
                id = id,
                terminalId = p.string("terminal_id") ?: id,
                workspaceId = p.string("workspace_id") ?: return@mapNotNull null,
                tabId = p.string("tab_id") ?: return@mapNotNull null,
                title = p.string("label")
                    ?: p.string("title")
                    ?: p.string("display_agent")
                    ?: p.string("terminal_title_stripped")
                    ?: p.string("terminal_title")
                    ?: id,
                agentKind = p.string("display_agent") ?: p.string("agent"),
                status = dev.herdwatch.model.AgentStatus.from(p.string("agent_status")),
                revision = p.long("revision") ?: 0L,
                cwd = p.string("foreground_cwd") ?: p.string("cwd"),
                restoreError = p.string("restore_error"),
                sessionKind = session?.string("kind"),
                sessionValue = session?.string("value"),
            )
        }.orEmpty()

        return HerdStructure(workspaces, tabs, panes)
    }

    fun parsePaneRead(result: JsonObject): PaneText {
        val read = result["read"]?.jsonObject
            ?: throw HerdrRpcException(null, "pane.read 响应缺少 read")
        val text = read.string("text").orEmpty()
        return PaneText(
            text = text,
            revision = read.long("revision"),
            truncated = read.bool("truncated") ?: false,
        )
    }

    private fun JsonObject.string(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull
    private fun JsonObject.long(key: String): Long? =
        this[key]?.jsonPrimitive?.longOrNull
    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull
}
