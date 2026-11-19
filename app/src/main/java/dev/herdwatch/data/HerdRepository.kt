package dev.herdwatch.data

import com.jcraft.jsch.JSchException
import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.model.PaneText
import dev.herdwatch.ssh.HostConnection
import dev.herdwatch.ssh.HerdrClient
import dev.herdwatch.ssh.HerdrRpcException
import dev.herdwatch.ssh.HostKeyMismatchException
import dev.herdwatch.ssh.Snapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

data class RepoState(
    val hosts: List<HostRuntime> = emptyList(),
    val agents: List<Agent> = emptyList(),
    val refreshing: Boolean = false,
    val lastSyncAt: Long? = null,
    val error: String? = null,
    val foreground: Boolean = true,
)

private data class Seen(val status: AgentStatus, val since: Long)

class HerdRepository internal constructor(
    private val store: HostStore,
    private val clientFactory: (HostConfig) -> HostConnection,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val connectTimeoutMillis: Long = 25_000,
    private val pollIntervalMillis: Long = 5_000,
) {
    constructor(store: HostStore) : this(
        store, ::HerdrClient, CoroutineScope(SupervisorJob() + Dispatchers.IO), Dispatchers.IO,
    )
    // Only small in-memory transitions use gate. Never hold it across IO or suspension.
    private val gate = Any()
    // Serializes durable configuration transactions, separately from network/state work.
    private val configGate = Any()
    private val clients = HashMap<String, HostConnection>()
    private val hostLocks = HashMap<String, Mutex>()
    private val versions = HashMap<String, Long>()
    private val mismatches = HashMap<String, HostKeyMismatchException>()
    private val marks = HashMap<String, Seen>()
    private var refreshingGeneration: Long? = null
    private val networkSlots = Semaphore(4)
    private var generation = 0L
    private var pollJob: Job? = null
    private var storedHosts = store.load()
    private val cached = storedHosts.flatMap { host ->
        store.loadAgents(host.id).filter { it.hostId == host.id }.map { it.copy(hostLabel = host.label) }
    }.distinctBy { it.key }.also { agents ->
        agents.forEach { marks[it.key] = Seen(it.status, it.sinceMillis) }
    }
    private val _state = MutableStateFlow(RepoState(
        hosts = storedHosts.map { HostRuntime(it) },
        agents = cached.sortedWith(agentOrder),
    ))
    val state: StateFlow<RepoState> = _state.asStateFlow()

    private data class Lease(val hostId: String, val generation: Long, val version: Long, val client: HostConnection)

    private fun current(lease: Lease): Boolean =
        _state.value.foreground && generation == lease.generation &&
            versions[lease.hostId].orZero() == lease.version && clients[lease.hostId] === lease.client

    private fun Long?.orZero() = this ?: 0L

    // Call under gate, disconnect outside it. A closed client is never reused.
    private fun invalidate(hostId: String): HostConnection? {
        versions[hostId] = versions[hostId].orZero() + 1
        mismatches.remove(hostId)
        return clients.remove(hostId)
    }

    suspend fun bootstrap() = connectAll()

    fun setForeground(foreground: Boolean) {
        val close = synchronized(gate) {
            if (_state.value.foreground == foreground) return
            generation++
            pollJob?.cancel()
            pollJob = null
            val old = clients.values.toList()
            clients.clear()
            _state.update { s -> s.copy(
                foreground = foreground, refreshing = false,
                hosts = s.hosts.map { it.copy(conn = ConnState.Idle) },
            ) }
            old
        }
        close.forEach { it.close() }
        if (foreground) scope.launch { connectAll() }
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    suspend fun addHost(config: HostConfig): HostConfig {
        withContext(dispatcher) {
            synchronized(configGate) {
                val updated = synchronized(gate) {
                    require(storedHosts.none { it.id == config.id }) { Str.get(R.string.repo_host_exists) }
                    storedHosts + config
                }
                store.save(updated)
                synchronized(gate) {
                    storedHosts = updated
                    _state.update { it.copy(hosts = it.hosts + HostRuntime(config)) }
                }
            }
        }
        connectHost(config.id)
        return synchronized(gate) { storedHosts.first { it.id == config.id } }
    }

    fun updateHost(config: HostConfig) {
        synchronized(configGate) {
            val updated = synchronized(gate) {
                require(storedHosts.any { it.id == config.id }) { Str.get(R.string.repo_host_deleted) }
                storedHosts.map { if (it.id == config.id) config else it }
            }
            store.save(updated)
            val close = synchronized(gate) {
                storedHosts = updated
                val old = invalidate(config.id)
                _state.update { s -> s.copy(
                    hosts = s.hosts.map { if (it.host.id == config.id) HostRuntime(config) else it },
                    agents = s.agents.map { if (it.hostId == config.id) it.copy(hostLabel = config.label) else it },
                ) }
                old
            }
            close?.close()
        }
    }

    fun disconnectHost(hostId: String) {
        val close = synchronized(gate) {
            val old = invalidate(hostId)
            _state.update { s -> s.copy(hosts = s.hosts.map {
                if (it.host.id == hostId) it.copy(conn = ConnState.Idle) else it
            }) }
            old
        }
        close?.close()
    }

    suspend fun removeHost(hostId: String) = withContext(dispatcher) {
        synchronized(configGate) {
            val updated = synchronized(gate) { storedHosts.filterNot { it.id == hostId } }
            store.save(updated, removedHostIds = setOf(hostId))
            val close = synchronized(gate) {
                storedHosts = updated
                val old = invalidate(hostId)
                marks.keys.removeAll { it.startsWith("$hostId:") }
                _state.update { s -> s.copy(
                    hosts = s.hosts.filterNot { it.host.id == hostId },
                    agents = s.agents.filterNot { it.hostId == hostId },
                ) }
                old
            }
            close?.close()
        }
    }

    suspend fun connectAll() {
        refreshAll(silent = true)
        startPolling()
    }

    suspend fun refreshAll(silent: Boolean = false) {
        // Coalesce within a lifecycle generation. An obsolete batch must not suppress
        // the immediate refresh on resume, even if its transport completes late.
        val epoch = synchronized(gate) {
            if (!_state.value.foreground || refreshingGeneration == generation) return
            refreshingGeneration = generation
            if (!silent) _state.update { it.copy(refreshing = true, error = null) }
            generation
        }
        try {
            val ids = synchronized(gate) { storedHosts.map { it.id } }
            supervisorScope {
                ids.map { id -> async { networkSlots.withPermit {
                    if (synchronized(gate) { generation == epoch }) connectHost(id, silent = true)
                } } }.awaitAll()
            }
        } finally {
            synchronized(gate) {
                if (refreshingGeneration == epoch) refreshingGeneration = null
                if (generation == epoch) _state.update { it.copy(refreshing = false) }
            }
        }
    }

    suspend fun connectHost(hostId: String, silent: Boolean = false) = withContext(dispatcher) {
        val lock = synchronized(gate) { hostLocks.getOrPut(hostId) { Mutex() } }
        val (epoch, version) = synchronized(gate) { generation to versions[hostId].orZero() }
        // A timeout here is local to this attempt, including the per-host lock wait.
        val finished = withTimeoutOrNull(connectTimeoutMillis) {
            lock.withLock {
                val lease = synchronized(gate) {
                    val host = storedHosts.firstOrNull { it.id == hostId }
                    if (host == null || !_state.value.foreground || generation != epoch || versions[hostId].orZero() != version) return@withLock
                    val client = clients.getOrPut(hostId) { clientFactory(host) }
                    if (!silent) _state.update { s -> s.copy(hosts = s.hosts.map {
                        if (it.host.id == hostId) it.copy(conn = ConnState.Connecting) else it
                    }) }
                    Lease(hostId, generation, versions[hostId].orZero(), client)
                }
                try {
                    lease.client.connect()
                    val actual = lease.client.fingerprint ?: error(Str.get(R.string.repo_fingerprint_unreadable))
                    persistFingerprint(lease, actual)
                    lease.client.ping()
                    val snapshot = lease.client.snapshot()
                    currentCoroutineContext().ensureActive()
                    val cache = synchronized(gate) {
                        if (!current(lease)) return@withLock
                        applySnapshot(lease, snapshot, actual)
                    }
                    // Cache failure does not turn a successful network snapshot into failure.
                    // configGate orders this against deletion so an old refresh cannot recreate
                    // a cache after its host has been removed.
                    synchronized(configGate) {
                        if (synchronized(gate) { current(lease) }) {
                            try { store.saveAgents(hostId, cache) }
                            catch (t: Exception) { _state.update { it.copy(error = Str.get(R.string.repo_cache_save_failed, humanize(t))) } }
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    // A request's own deadline is a host failure. The enclosing host or
                    // caller cancellation must still propagate to its owner.
                    if (!currentCoroutineContext().isActive) {
                        fail(lease, null)
                        throw e
                    }
                    fail(lease, e)
                } catch (e: CancellationException) {
                    fail(lease, null)
                    throw e
                } catch (t: Exception) {
                    fail(lease, t)
                }
            }
            true
        }
        if (finished == null) synchronized(gate) {
            if (generation == epoch && versions[hostId].orZero() == version && _state.value.foreground) _state.update { s -> s.copy(hosts = s.hosts.map {
                if (it.host.id == hostId && it.conn != ConnState.Connected) it.copy(conn = ConnState.Failed(Str.get(R.string.repo_operation_timeout))) else it
            }) }
        }
    }

    private fun fail(lease: Lease, error: Exception?) {
        val close = synchronized(gate) {
            if (!current(lease)) return
            clients.remove(lease.hostId)
            if (error is HostKeyMismatchException) mismatches[lease.hostId] = error
            _state.update { s -> s.copy(hosts = s.hosts.map {
                if (it.host.id == lease.hostId) it.copy(conn = error?.let { e -> ConnState.Failed(humanize(e)) } ?: ConnState.Idle) else it
            }) }
            lease.client
        }
        close.close()
    }

    suspend fun refreshHost(hostId: String) = connectHost(hostId, silent = true)

    fun isHostReady(hostId: String): Boolean = synchronized(gate) {
        _state.value.foreground && clients[hostId]?.isConnected == true &&
            _state.value.hosts.any { it.host.id == hostId && it.conn == ConnState.Connected }
    }

    private suspend fun <T> useHost(hostId: String, acceptedMutation: Boolean = false, block: suspend (HostConnection) -> T): T {
        val (epoch, version) = synchronized(gate) { generation to versions[hostId].orZero() }
        if (!isHostReady(hostId)) connectHost(hostId, silent = true)
        val lock = synchronized(gate) { hostLocks.getOrPut(hostId) { Mutex() } }
        return lock.withLock {
            val lease = synchronized(gate) {
                check(generation == epoch && versions[hostId].orZero() == version && isHostReady(hostId)) { Str.get(R.string.repo_connection_stale_reconnect) }
                Lease(hostId, generation, versions[hostId].orZero(), clients.getValue(hostId))
            }
            try {
                val result = block(lease.client)
                // An accepted mutation keeps its result; its caller must not retry it.
                if (!acceptedMutation) synchronized(gate) { check(current(lease)) { Str.get(R.string.repo_connection_stale) } }
                result
            }
            catch (e: CancellationException) { fail(lease, null); throw e }
            catch (e: Exception) { fail(lease, e); throw e }
        }
    }

    // RPC acceptance is the operation's result. Refresh belongs to a separate task;
    // an offline/failed refresh must never invite retry of an accepted mutation.
    private fun refreshAfterAcceptance(hostId: String) {
        val epoch = synchronized(gate) { generation }
        scope.launch {
            if (synchronized(gate) { generation == epoch && _state.value.foreground }) refreshHost(hostId)
        }
    }

    private suspend fun mutate(hostId: String, block: suspend (HostConnection) -> Unit) {
        useHost(hostId, acceptedMutation = true, block = block)
        refreshAfterAcceptance(hostId)
    }

    private suspend fun accepted(block: suspend () -> Unit): Result<Unit> = try {
        block()
        Result.success(Unit)
    } catch (e: TimeoutCancellationException) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    } catch (e: CancellationException) { throw e }
      catch (e: Exception) { Result.failure(e) }

    suspend fun createWorkspace(hostId: String, label: String?) = mutate(hostId) { it.createWorkspace(label) }
    suspend fun createTab(hostId: String, workspaceId: String, label: String?) =
        mutate(hostId) { it.createTab(workspaceId, label) }
    suspend fun splitPane(hostId: String, paneId: String, direction: String) =
        mutate(hostId) { it.splitPane(paneId, direction) }
    suspend fun closePane(hostId: String, paneId: String) = mutate(hostId) { it.closePane(paneId) }
    suspend fun closeTab(hostId: String, tabId: String) = mutate(hostId) { it.closeTab(tabId) }
    suspend fun closeWorkspace(hostId: String, workspaceId: String) = mutate(hostId) { it.closeWorkspace(workspaceId) }
    suspend fun focusPane(hostId: String, paneId: String) = mutate(hostId) { it.focusPane(paneId) }
    suspend fun focusTab(hostId: String, tabId: String) = mutate(hostId) { it.focusTab(tabId) }
    suspend fun focusWorkspace(hostId: String, workspaceId: String) = mutate(hostId) { it.focusWorkspace(workspaceId) }
    suspend fun renameAgent(agent: Agent, name: String) = mutate(agent.hostId) { it.renameAgent(agent.paneId, name) }
    suspend fun renameWorkspace(hostId: String, workspaceId: String, label: String) = mutate(hostId) { it.renameWorkspace(workspaceId, label) }
    suspend fun renameTab(hostId: String, tabId: String, label: String) = mutate(hostId) { it.renameTab(tabId, label) }
    suspend fun renamePane(hostId: String, paneId: String, label: String) = mutate(hostId) { it.renamePane(paneId, label) }

    suspend fun startInNewTab(hostId: String, workspaceId: String?, kind: String, name: String, args: List<String>): Result<Unit> = accepted {
        mutate(hostId) { client ->
            val paneId = (if (workspaceId == null) client.createWorkspace(null) else client.createTab(workspaceId, null))
                ?: throw IllegalStateException(Str.get(R.string.repo_no_new_pane))
            client.startAgent(paneId, kind, name, args)
        }
    }

    suspend fun startAgent(hostId: String, paneId: String, kind: String, name: String, args: List<String>): Result<Unit> = accepted {
        mutate(hostId) { it.startAgent(paneId, kind, name, args) }
    }

    suspend fun explain(agent: Agent): kotlinx.serialization.json.JsonObject = useHost(agent.hostId) { it.explain(agent.paneId) }
    suspend fun readPane(agent: Agent, lines: Int = 400): PaneText = useHost(agent.hostId) { it.readPane(agent.paneId, lines, "recent") }
    suspend fun sendKeys(agent: Agent, keys: List<String>): Result<Unit> = accepted {
        mutate(agent.hostId) { it.sendKeys(agent.paneId, keys) }
    }
    suspend fun prompt(agent: Agent, text: String): Result<Unit> = accepted {
        mutate(agent.hostId) { it.prompt(agent.paneId, text) }
    }

    private fun persistFingerprint(lease: Lease, fingerprint: String) {
        synchronized(configGate) {
            val host = synchronized(gate) {
                check(current(lease)) { Str.get(R.string.repo_connection_stale) }
                storedHosts.first { it.id == lease.hostId }
            }
            if (!host.fingerprint.isNullOrBlank()) {
                if (host.fingerprint != fingerprint) throw HostKeyMismatchException(host.fingerprint, fingerprint)
                return
            }
            val updated = synchronized(gate) { storedHosts.map {
                if (it.id == host.id) it.copy(fingerprint = fingerprint) else it
            } }
            store.save(updated) // Must succeed before readiness, ping or snapshot publication.
            synchronized(gate) {
                storedHosts = updated
                lease.client.updateConfig(host.copy(fingerprint = fingerprint))
                _state.update { s -> s.copy(hosts = s.hosts.map {
                    if (it.host.id == host.id) it.copy(host = host.copy(fingerprint = fingerprint)) else it
                }) }
            }
        }
    }

    fun updateFingerprint(hostId: String, fingerprint: String) = synchronized(configGate) {
        require(fingerprint.isNotBlank()) { Str.get(R.string.repo_fingerprint_not_clearable) }
        val host = synchronized(gate) { storedHosts.firstOrNull { it.id == hostId } } ?: return@synchronized
        updateHost(host.copy(fingerprint = fingerprint))
    }

    /**
     * Deliberate user action ("我只在确实换了机器时用"): forget every pinned host key so the next
     * handshake asks again. The pin is cleared durably *before* anything is forgotten in memory, so a
     * failed write never leaves the app trusting an unpinned host it silently forgot about.
     */
    suspend fun resetFingerprints() = withContext(dispatcher) {
        synchronized(configGate) {
            val pinned = synchronized(gate) { storedHosts.filter { !it.fingerprint.isNullOrBlank() } }
            if (pinned.isEmpty()) return@withContext
            val ids = pinned.map { it.id }.toSet()
            val updated = synchronized(gate) {
                storedHosts.map { if (it.id in ids) it.copy(fingerprint = null) else it }
            }
            store.save(updated)
            val closes = synchronized(gate) {
                storedHosts = updated
                _state.update { s -> s.copy(hosts = s.hosts.map { runtime ->
                    if (runtime.host.id in ids) {
                        runtime.copy(host = runtime.host.copy(fingerprint = null), conn = ConnState.Idle)
                    } else runtime
                }) }
                ids.mapNotNull { invalidate(it) }
            }
            closes.forEach { it.close() }
        }
    }

    fun trustNewFingerprint(hostId: String) {
        val fingerprint = synchronized(gate) { mismatches[hostId]?.actual }
            ?: throw IllegalStateException(Str.get(R.string.repo_no_new_fingerprint))
        updateFingerprint(hostId, fingerprint)
        scope.launch { connectHost(hostId) }
    }

    // Called only under gate; returns a stable copy for optional cache persistence.
    private fun applySnapshot(lease: Lease, snapshot: Snapshot, fingerprint: String): List<Agent> {
        val hostId = lease.hostId
        val now = System.currentTimeMillis()
        val incoming = snapshot.agents.distinctBy { it.key }.map { agent ->
            val prev = marks[agent.key]
            val seen = if (prev?.status == agent.status) prev else Seen(agent.status, now)
            marks[agent.key] = seen
            agent.copy(sinceMillis = seen.since)
        }
        val liveKeys = incoming.map { it.key }.toSet()
        marks.keys.removeAll { it.startsWith("$hostId:") && it !in liveKeys }
        _state.update { s -> s.copy(
            agents = (s.agents.filterNot { it.hostId == hostId } + incoming).sortedWith(agentOrder),
            hosts = s.hosts.map { runtime -> if (runtime.host.id == hostId) runtime.copy(
                conn = ConnState.Connected,
                agentCount = incoming.size,
                waitingCount = incoming.count { it.status == AgentStatus.WAITING || it.status == AgentStatus.UNKNOWN },
                lastSyncAt = now, fingerprint = fingerprint,
                structure = snapshot.structure,
            ) else runtime },
            lastSyncAt = now,
        ) }
        // Cache only agents that are actually present in the latest successful snapshot.
        // Stale/remembered agents are intentionally not persisted or offered for restore.
        return incoming
    }

    private fun startPolling() = synchronized(gate) {
        if (!_state.value.foreground || pollJob?.isActive == true) return@synchronized
        val epoch = generation
        pollJob = scope.launch {
            while (isActive && synchronized(gate) { generation == epoch && _state.value.foreground }) {
                delay(pollIntervalMillis)
                refreshAll(silent = true)
            }
        }
    }

    fun newId(): String = "h_" + UUID.randomUUID().toString().take(8)

    companion object {
        val agentOrder: Comparator<Agent> = compareBy<Agent>(
            { when (it.status) {
                AgentStatus.WAITING -> 0
                AgentStatus.REVIEW -> 1
                AgentStatus.WORKING -> 2
                AgentStatus.UNKNOWN -> 3
                AgentStatus.IDLE -> 4
            } },
            { if (it.status == AgentStatus.WAITING) it.sinceMillis else -it.stateChangeSeq },
            { it.name.lowercase() },
        )
    }
}

fun humanize(t: Throwable): String = when (t) {
    is HostKeyMismatchException -> t.message ?: Str.get(R.string.repo_host_key_mismatch)
    is HerdrRpcException -> Str.get(R.string.repo_rpc_error, t.message ?: "")
    is JSchException -> when {
        t.message?.contains("Auth fail", true) == true || t.message?.contains("auth cancel", true) == true -> Str.get(R.string.repo_auth_failed)
        t.message?.contains("Connection refused", true) == true -> Str.get(R.string.repo_connection_refused)
        t.message?.contains("UnknownHost", true) == true -> Str.get(R.string.repo_unknown_host)
        t.message?.contains("timeout", true) == true -> Str.get(R.string.repo_connect_timeout)
        t.message?.contains("session is down", true) == true -> Str.get(R.string.repo_ssh_session_down)
        t.message?.contains("reject HostKey", true) == true -> Str.get(R.string.repo_host_key_rejected)
        else -> Str.get(R.string.repo_ssh_failed, t.message ?: Str.get(R.string.repo_unknown_error))
    }
    is java.net.SocketTimeoutException -> Str.get(R.string.repo_request_timeout)
    is kotlinx.coroutines.TimeoutCancellationException -> Str.get(R.string.repo_operation_timeout)
    else -> t.message ?: t.javaClass.simpleName
}
