package dev.herdwatch.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AuthType
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.PaneText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import dev.herdwatch.R
import dev.herdwatch.i18n.Str

class HostKeyMismatchException(val expected: String, val actual: String) : Exception(
    Str.get(R.string.ssh_host_key_mismatch, expected, actual)
)

/** A request deadline, distinct from cancellation by the caller or lifecycle. */
class HerdrRequestTimeoutException : Exception(Str.get(R.string.ssh_request_timeout))

/**
 * One SSH connection to a Herdr host.
 *
 * `herdr remote-api-bridge` handles exactly one request per stdio connection, so every
 * request opens its own exec channel on the kept-alive SSH session.
 */
class HerdrClient internal constructor(
    initialConfig: HostConfig,
    private val requestTimeoutMillis: Long,
) : HostConnection {
    constructor(initialConfig: HostConfig) : this(initialConfig, 20_000)
    @Volatile
    override var config: HostConfig = initialConfig
        private set

    private val mutex = Mutex()
    private val ids = AtomicLong(0)
    private val sessionGate = Any()
    @Volatile
    private var session: Session? = null

    @Volatile
    private var closed = false

    @Volatile
    override var fingerprint: String? = null
        private set

    @Volatile
    private var lastSeenType: String? = null

    /** `host:port` the live session is on; updated on every successful connect. */
    @Volatile
    override var activeAddress: String? = null
        private set

    override fun updateConfig(updated: HostConfig) {
        config = updated
    }

    override val isConnected: Boolean get() = !closed && session?.isConnected == true

    override suspend fun connect() = mutex.withLock {
        blockingIo {
            check(!closed) { Str.get(R.string.ssh_client_closed) }
            if (session?.isConnected == true) return@blockingIo
            val pinned = config.fingerprint?.takeIf { it.isNotBlank() }
            // Strict checking always runs inside the handshake. With no pin the repository still
            // rejects the key after recording it, so the bridge connection asks for confirmation
            // exactly like the interactive shell does — credentials never leave before that.
            lastSeenFingerprint = null
            lastSeenType = null
            val jsch = JSch()
            jsch.hostKeyRepository = object : HostKeyRepository {
                override fun check(host: String, key: ByteArray): Int {
                    val observed = fingerprintOf(key)
                    lastSeenFingerprint = observed
                    lastSeenType = keyAlgorithm(key)
                    return if (pinned != null && observed == pinned) {
                        HostKeyRepository.OK
                    } else {
                        HostKeyRepository.NOT_INCLUDED
                    }
                }
                override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
                override fun remove(host: String?, type: String?) = Unit
                override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
                override fun getKnownHostsRepositoryID(): String = "herdwatch-pin"
                override fun getHostKey(): Array<HostKey> = emptyArray()
                override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
            }
            when (config.authType) {
                AuthType.PASSWORD -> Unit
                AuthType.KEY -> {
                    val pass = config.passphrase.takeIf { it.isNotBlank() }?.toByteArray()
                    jsch.addIdentity(
                        "herdwatch-key",
                        config.secret.replace("\r\n", "\n").toByteArray(),
                        null,
                        pass,
                    )
                }
            }
            // One host, up to two addresses. The fallback is for the same machine reached
            // another way (LAN at home, public elsewhere); it is tried only when the primary
            // address cannot be reached, never to work around a rejected key or password.
            var lastFailure: Exception? = null
            for ((index, endpoint) in config.endpoints.withIndex()) {
                val moreAddresses = index < config.endpoints.lastIndex
                lastSeenFingerprint = null
                lastSeenType = null
                val s = jsch.getSession(config.user, endpoint.host, endpoint.port)
                // These are session settings, not JSch process-global settings. Multiple hosts may
                // have different authentication policies and connect concurrently.
                s.setConfig("PreferredAuthentications", if (config.authType == AuthType.PASSWORD) "password,keyboard-interactive" else "publickey")
                s.serverAliveInterval = 30_000
                s.serverAliveCountMax = 3
                s.setConfig("StrictHostKeyChecking", "yes")
                // Same preference as the interactive shell: without it JSch picks an ECDSA key while
                // OpenSSH shows the admin an Ed25519 one, so the two fingerprints never match.
                s.setConfig("server_host_key", HOST_KEY_ALGORITHMS)
                if (config.authType == AuthType.PASSWORD) s.setPassword(config.secret)
                s.timeout = TIMEOUT_MS
                // Publish the connecting session too, so backgrounding/cancellation can abort
                // a handshake instead of waiting for the socket timeout.
                synchronized(sessionGate) {
                    check(!closed) { Str.get(R.string.ssh_client_closed) }
                    session = s
                }
                try {
                    s.connect(12_000)
                } catch (e: Exception) {
                    s.disconnect()
                    // A mismatch is reported only when the host-key callback actually observed a
                    // different key. Auth, DNS, timeout and transport errors retain their type.
                    val observed = lastSeenFingerprint
                    if (pinned != null && observed != null && observed != pinned) {
                        throw HostKeyMismatchException(pinned, observed)
                    }
                    if (pinned == null && observed != null) {
                        throw UnknownHostKeyException(observed, lastSeenType)
                    }
                    // Reaching this point means the address did not answer at all. Another
                    // address may still work; an authentication failure cannot be fixed this way
                    // because JSch only reports it after the host key was already accepted.
                    lastFailure = e
                    if (moreAddresses && e.isUnreachableAddress()) continue
                    throw e
                }
                if (closed) {
                    s.disconnect()
                    throw IllegalStateException(Str.get(R.string.ssh_client_closed))
                }
                val actual = fingerprintOf(s)
                if (pinned != null && pinned != actual) {
                    s.disconnect()
                    throw HostKeyMismatchException(pinned, actual)
                }
                fingerprint = actual
                activeAddress = endpoint.toString()
                return@blockingIo
            }
            // Unreachable: the loop either returned on success or rethrew on the last address.
            throw lastFailure ?: IllegalStateException(Str.get(R.string.ssh_client_closed))
        }
    }

    override suspend fun ping(): Pair<String?, Int?> = withContext(Dispatchers.IO) {
        val result = request("ping")
        BridgeCodec.parsePing(result)
    }

    override suspend fun snapshot(): Snapshot {
        val result = request("session.snapshot")
        return BridgeCodec.parseSnapshot(result, config.id, config.label)
    }

    override suspend fun explain(target: String): JsonObject {
        val body = request("agent.explain", buildJsonObject { put("target", target) })
        return (body["explain"] as? JsonObject) ?: body
    }

    override suspend fun readPane(paneId: String, lines: Int, source: String): PaneText {
        val params = buildJsonObject {
            put("pane_id", paneId)
            put("source", source)
            put("lines", lines)
            // Keep the colour the remote program wrote: reading plain text turned every pane —
            // including Claude Code's own UI — into one shade of white on the phone.
            put("format", "ansi")
            put("strip_ansi", false)
        }
        return BridgeCodec.parsePaneRead(request("pane.read", params))
    }

    override suspend fun sendKeys(paneId: String, keys: List<String>) {
        val params = buildJsonObject {
            // agent.send_keys takes `target`; `pane_id` belongs to pane.send_keys.
            put("target", paneId)
            put("keys", buildJsonArray { keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        request("agent.send_keys", params)
    }

    override suspend fun sendInput(paneId: String, text: String?, keys: List<String>) {
        val params = buildJsonObject {
            put("pane_id", paneId)
            if (!text.isNullOrEmpty()) put("text", text)
            if (keys.isNotEmpty()) put("keys", buildJsonArray { keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        request("pane.send_input", params)
    }

    override suspend fun createWorkspace(label: String?, cwd: String?): HostConnection.PaneCreated? {
        val params = buildJsonObject {
            if (!label.isNullOrBlank()) put("label", label)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        val result = request("workspace.create", params)
        val paneId = result["root_pane"]?.jsonObject?.get("pane_id")?.jsonPrimitive?.contentOrNull
            ?: return null
        return HostConnection.PaneCreated(
            paneId = paneId,
            tabId = result["tab"]?.jsonObject?.stringOrNull("tab_id"),
            workspaceId = result["workspace"]?.jsonObject?.stringOrNull("workspace_id"),
        )
    }

    override suspend fun createTab(workspaceId: String, label: String?, cwd: String?): HostConnection.PaneCreated? {
        val params = buildJsonObject {
            put("workspace_id", workspaceId)
            if (!label.isNullOrBlank()) put("label", label)
            if (!cwd.isNullOrBlank()) put("cwd", cwd)
        }
        val result = request("tab.create", params)
        val paneId = result["root_pane"]?.jsonObject?.get("pane_id")?.jsonPrimitive?.contentOrNull
            ?: return null
        return HostConnection.PaneCreated(
            paneId = paneId,
            tabId = result["tab"]?.jsonObject?.stringOrNull("tab_id"),
            workspaceId = result["tab"]?.jsonObject?.stringOrNull("workspace_id") ?: workspaceId,
        )
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    override suspend fun splitPane(paneId: String, direction: String) {
        val params = buildJsonObject {
            put("target_pane_id", paneId)
            put("direction", direction)
            put("focus", false)
        }
        request("pane.split", params)
    }

    override suspend fun closePane(paneId: String) = request("pane.close", buildJsonObject { put("pane_id", paneId) })
    override suspend fun closeTab(tabId: String) = request("tab.close", buildJsonObject { put("tab_id", tabId) })
    override suspend fun closeWorkspace(workspaceId: String) =
        request("workspace.close", buildJsonObject { put("workspace_id", workspaceId) })

    override suspend fun focusPane(paneId: String) = request("pane.focus", buildJsonObject { put("pane_id", paneId) })
    override suspend fun focusTab(tabId: String) = request("tab.focus", buildJsonObject { put("tab_id", tabId) })
    override suspend fun focusWorkspace(workspaceId: String) =
        request("workspace.focus", buildJsonObject { put("workspace_id", workspaceId) })

    override suspend fun renameAgent(paneId: String, name: String) =
        request("agent.rename", buildJsonObject { put("target", paneId); put("name", name) })

    override suspend fun renameWorkspace(workspaceId: String, label: String) =
        request("workspace.rename", buildJsonObject { put("workspace_id", workspaceId); put("label", label) })

    override suspend fun renameTab(tabId: String, label: String) =
        request("tab.rename", buildJsonObject { put("tab_id", tabId); put("label", label) })

    override suspend fun renamePane(paneId: String, label: String) =
        request("pane.rename", buildJsonObject { put("pane_id", paneId); put("label", label) })

    override suspend fun startAgent(paneId: String, kind: String, name: String, args: List<String>) {
        val params = buildJsonObject {
            put("pane_id", paneId)
            put("kind", kind)
            put("name", name)
            put("args", buildJsonArray { args.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        }
        request("agent.start", params)
    }

    override suspend fun prompt(paneId: String, text: String) {
        val params = buildJsonObject {
            put("target", paneId)
            put("text", text)
        }
        request("agent.prompt", params)
    }

    private suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap())): JsonObject = try {
        withTimeout(requestTimeoutMillis) {
            mutex.withLock {
                blockingIo {
                val s = session?.takeIf { !closed && it.isConnected }
                    ?: throw IllegalStateException(Str.get(R.string.ssh_not_connected, config.label))
                val id = ids.incrementAndGet().toString()
                val payload = buildJsonObject {
                    put("id", id)
                    put("method", method)
                    put("params", params)
                }.toString() + "\n"

                var channel: ChannelExec? = null
                try {
                    channel = s.openChannel("exec") as ChannelExec
                    channel.setCommand(bridgeCommand())
                    val stderr = ByteArrayOutputStream()
                    channel.setErrStream(stderr)
                    // Feed stdin before connect: with no input stream JSch sends EOF
                    // immediately, which makes the bridge exit before it sees our request.
                    channel.setInputStream(ByteArrayInputStream(payload.toByteArray(Charsets.UTF_8)))
                    channel.connect(12_000)
                    val reader = channel.inputStream.bufferedReader(Charsets.UTF_8)
                    var attempts = 0
                    while (attempts++ < 20) {
                        val line = reader.readLine() ?: break
                        if (!line.trimStart().startsWith("{")) continue
                        val obj = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                        if (obj["id"]?.jsonPrimitive?.content != id) continue
                        return@blockingIo BridgeCodec.resultOf(line, id)
                    }
                    throw HerdrRpcException(
                        null,
                        Str.get(
                            R.string.ssh_bridge_no_reply,
                            config.label,
                            stderr.toString("UTF-8").trim().take(300),
                        ),
                    )
                } finally {
                    runCatching { channel?.disconnect() }
                }
                }
            }
        }
    } catch (timeout: TimeoutCancellationException) {
        // Only our own deadline is a request failure. Caller/lifecycle cancellation
        // must keep propagating as cancellation so no follow-up work is initiated.
        currentCoroutineContext().ensureActive()
        throw HerdrRequestTimeoutException()
    }

    private fun bridgeCommand(): String {
        val sessionArg = config.session.trim().takeIf { it.isNotEmpty() }
            ?.let { " --session ${shellQuote(it)}" }
            .orEmpty()
        val binary = config.herdrCommand.trim()
        val inner = "$binary$sessionArg remote-api-bridge"
        // Non-interactive SSH often gets a minimal PATH (no ~/.local/bin).
        // Fall back to a login shell for the default command so herdr is found.
        return if (binary == "herdr") "bash -lc ${shellQuote(inner)}" else inner
    }

    override fun close() {
        val old = synchronized(sessionGate) {
            closed = true
            session.also { session = null }
        }
        runCatching { old?.disconnect() }
    }

    /** Cancel blocking socket IO by disconnecting its session. No retry is performed. */
    private suspend fun <T> blockingIo(block: () -> T): T = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { close() }
        Dispatchers.IO.dispatch(EmptyCoroutineContext) {
            if (continuation.isActive) {
                try { continuation.resume(block()) }
                catch (t: Throwable) { continuation.resumeWithException(t) }
                finally { if (!continuation.isActive && closed) runCatching { session?.disconnect() } }
            }
        }
    }

    private fun fingerprintOf(s: Session): String {
        val encoded = s.hostKey?.key ?: error(Str.get(R.string.ssh_no_host_key))
        return fingerprintOf(Base64.getDecoder().decode(encoded))
    }

    private fun fingerprintOf(key: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(key)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    @Volatile
    private var lastSeenFingerprint: String? = null

    companion object {
        private const val TIMEOUT_MS = 15_000

        fun hasAgent(agents: List<Agent>, paneId: String) = agents.any { it.paneId == paneId }
    }
}
