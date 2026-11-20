package dev.herdwatch.data

import com.jcraft.jsch.JSchException
import dev.herdwatch.R
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.HostRuntime
import dev.herdwatch.model.KeyMismatch
import dev.herdwatch.model.PaneText
import dev.herdwatch.model.PendingHostKey
import dev.herdwatch.ssh.HostConnection
import dev.herdwatch.ssh.HerdrClient
import dev.herdwatch.ssh.HerdrRpcException
import dev.herdwatch.ssh.HostKeyMismatchException
import dev.herdwatch.ssh.UnknownHostKeyException
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
    private val batchTimeoutMillis: Long = 30_000,
    private val refreshJoinPollMillis: Long = 100,
    private val pollIntervalMillis: Long = 5_000,
    // Background polling is still polling, just cheaper. Connections stay up; the cadence drops.
    private val backgroundPollIntervalMillis: Long = 20_000,
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
    private val unknownKeys = HashMap<String, PendingHostKey>()
    private val marks = HashMap<String, Seen>()
    private var refreshingGeneration: Long? = null
    private val networkSlots = Semaphore(4)
    private var generation = 0L
    private var pollJob: Job? = null
    @Volatile private var visible = true
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
        unknownKeys.remove(hostId)
        return clients.remove(hostId)
    }

    suspend fun bootstrap() = connectAll()

    /**
     * Whether the UI is on screen. Unlike [setForeground] this does not tear anything down: the
     * keep-alive service owns the process precisely so a switch to another app stops costing a
     * reconnect. Background work runs at a slower cadence instead of stopping.
     */
    fun setVisible(visible: Boolean) {
        this.visible = visible
    }

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
        // Coalesce within a lifecycle generation: the five-second poll and a manual pull must not
        // open two batches against the same machines.
        val started = synchronized(gate) {
            if (!_state.value.foreground) return
            if (!silent) _state.update { it.copy(refreshing = true, error = null) }
            if (refreshingGeneration == generation) null else {
                refreshingGeneration = generation
                generation
            }
        }
        if (started == null) {
            // A pull that arrives while a poll is in flight used to return immediately and leave
            // `refreshing = true` behind: the poll's own cleanup skipped the reset because it was
            // silent, so the indicator stayed on screen until some later manual pull happened to
            // land between polls. Join the running batch instead and end with it.
            awaitRefresh()
            return
        }
        try {
            val ids = synchronized(gate) { storedHosts.map { it.id } }
            // The batch has its own deadline. Per-host timeouts bound one attempt only; a transport
            // stuck behind the network semaphore could otherwise keep the indicator alive forever.
            withTimeoutOrNull(batchTimeoutMillis) {
                supervisorScope {
                    ids.map { id -> async { networkSlots.withPermit {
                        if (synchronized(gate) { generation == started }) connectHost(id, silent = true)
                    } } }.awaitAll()
                }
            }
        } finally {
            synchronized(gate) { if (refreshingGeneration == started) refreshingGeneration = null }
            finishRefresh()
        }
    }

    /** Wait for the batch that is already running, without starting a second one. */
    private suspend fun awaitRefresh() {
        val epoch = synchronized(gate) { generation }
        while (currentCoroutineContext().isActive) {
            val running = synchronized(gate) {
                _state.value.foreground && generation == epoch && refreshingGeneration == epoch
            }
            if (!running) break
            delay(refreshJoinPollMillis)
        }
        finishRefresh()
    }

    /**
     * Clear the indicator. Whatever the lifecycle generation is now: leaving the flag set would
     * keep a spinner on screen for work that has already finished.
     */
    private fun finishRefresh() {
        _state.update { if (it.refreshing) it.copy(refreshing = false) else it }
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
            if (error is UnknownHostKeyException) {
                unknownKeys[lease.hostId] = PendingHostKey(error.fingerprint, error.keyType)
            }
            _state.update { s -> s.copy(hosts = s.hosts.map {
                if (it.host.id != lease.hostId) return@map it
                it.copy(
                    conn = error?.let { e -> ConnState.Failed(humanize(e), e.message) } ?: ConnState.Idle,
                    keyMismatch = (error as? HostKeyMismatchException)?.let { e -> KeyMismatch(e.expected, e.actual) },
                    pendingKey = unknownKeys[lease.hostId],
                )
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

    private suspend fun <T> useHost(
        hostId: String,
        acceptedMutation: Boolean = false,
        failFast: Boolean = true,
        block: suspend (HostConnection) -> T,
    ): T {
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
            // A cancelled request is not a broken connection. Superseding a read (a newer poll,
            // a history fetch, a page leave) used to close the shared client and mark the host
            // failed, which is why scrolling for history looked like the machine had dropped.
            catch (e: CancellationException) { throw e }
            catch (e: TimeoutCancellationException) {
                if (!failFast) {
                    // Reading further back is a guess about how much the host can serve. When the
                    // guess is too big, keep the session and let the next poll decide its health.
                    currentCoroutineContext().ensureActive()
                    throw e
                }
                fail(lease, e); throw e
            }
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

    suspend fun createWorkspace(hostId: String, label: String?, cwd: String? = null) =
        mutate(hostId) { it.createWorkspace(label, cwd)?.paneId }
    suspend fun createTab(hostId: String, workspaceId: String, label: String?, cwd: String? = null) =
        mutate(hostId) { it.createTab(workspaceId, label, cwd)?.paneId }
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
            val newWorkspace = workspaceId == null
            val created = (if (newWorkspace) client.createWorkspace(null) else client.createTab(workspaceId, null))
                ?: throw IllegalStateException(Str.get(R.string.repo_no_new_pane))
            try {
                client.startAgent(created.paneId, kind, name, args)
            } catch (e: HerdrRpcException) {
                // The server answered "no" to the start, so the container we just created is empty
                // and retrying would pile up duplicates. Undo it — for a new workspace that means
                // the workspace, not its root tab, or an empty workspace would be left behind.
                // A timeout or dropped transport is different: the start may already have happened,
                // and closing would kill it.
                val containerId = if (newWorkspace) created.workspaceId else created.tabId
                try {
                    if (containerId != null) {
                        if (newWorkspace) client.closeWorkspace(containerId) else client.closeTab(containerId)
                    }
                } catch (t: Exception) {
                    e.addSuppressed(t)
                }
                throw e
            }
        }
    }

    suspend fun startAgent(hostId: String, paneId: String, kind: String, name: String, args: List<String>): Result<Unit> = accepted {
        mutate(hostId) { it.startAgent(paneId, kind, name, args) }
    }

    suspend fun explain(agent: Agent): kotlinx.serialization.json.JsonObject = useHost(agent.hostId) { it.explain(agent.paneId) }
    // "recent_unwrapped" asks the bridge to undo the TUI's hard wrapping before we get the text.
    // Reading prose on a phone means every wrapped line otherwise becomes its own line, which is
    // what made both the raw view and the chat segmentation look shredded.
    suspend fun readPane(agent: Agent, lines: Int = 400, history: Boolean = false): PaneText =
        useHost(agent.hostId, failFast = !history) {
            it.readPane(agent.paneId, lines, "recent_unwrapped")
        }
    suspend fun sendKeys(agent: Agent, keys: List<String>): Result<Unit> = accepted {
        mutate(agent.hostId) { it.sendKeys(agent.paneId, keys) }
    }

    /**
     * Writes literal text (optionally followed by what the caller wants) into the pane's PTY.
     * Unlike [prompt] this is not interpreted by an agent — it is what makes the detail page work
     * for panes that have no agent in them (shell, vim, tmux).
     */
    suspend fun sendInput(agent: Agent, text: String?, keys: List<String> = emptyList()): Result<Unit> = accepted {
        mutate(agent.hostId) { it.sendInput(agent.paneId, text, keys) }
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
                // The cached client keeps the config it was built with: replacing it here would
                // hand the next handshake a null pin again, so the confirmation the user just gave
                // would be silently lost and the machine would ask a second time.
                lease.client.updateConfig(lease.client.config.copy(fingerprint = fingerprint))
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

    /**
     * Pins [fingerprint] for [hostId] — but only while it is still the key awaiting confirmation.
     *
     * The confirmation dialog renders a snapshot of the pending key, and the background poll keeps
     * reconnecting every few seconds. Without this check a dialog showing key A could be confirmed
     * after a retry had already observed key B, silently pinning B — the exact key the user never
     * looked at. Comparing against the value captured when the dialog opened makes the confirmation
     * all-or-nothing: if the remote changed in between, the user is asked again.
     */
    fun trustNewFingerprint(hostId: String, fingerprint: String) {
        val pending = synchronized(gate) {
            mismatches[hostId]?.actual ?: unknownKeys[hostId]?.fingerprint
        } ?: throw IllegalStateException(Str.get(R.string.repo_no_new_fingerprint))
        if (!pending.equals(fingerprint, ignoreCase = true)) {
            throw IllegalStateException(Str.get(R.string.repo_stale_fingerprint))
        }
        // Store the decision, then invalidate the lease that produced it: updateHost clears the
        // pending key and closes the refused client, so the reconnect below is built from the
        // config that now carries the pin instead of re-asking for the same confirmation.
        updateFingerprint(hostId, pending)
        synchronized(gate) { unknownKeys.remove(hostId) }
        scope.launch { connectHost(hostId) }
    }

    // Called only under gate; returns a stable copy for optional cache persistence.
    private fun applySnapshot(lease: Lease, snapshot: Snapshot, fingerprint: String): List<Agent> {
        val hostId = lease.hostId
        unknownKeys.remove(hostId)
        mismatches.remove(hostId)
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
                lastSyncAt = now, fingerprint = fingerprint, activeAddress = lease.client.activeAddress,
                structure = snapshot.structure,
                keyMismatch = null,
                pendingKey = null,
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
                delay(if (visible) pollIntervalMillis else backgroundPollIntervalMillis)
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
    // One short line: the card has room for a sentence, and the review dialog shows both keys.
    is UnknownHostKeyException -> Str.get(R.string.repo_host_key_first)
    is HostKeyMismatchException -> Str.get(R.string.repo_host_key_mismatch)
    is HerdrRpcException -> Str.get(R.string.repo_rpc_error, t.message ?: "")
    is JSchException -> when {
        t.message?.contains("Auth fail", true) == true || t.message?.contains("auth cancel", true) == true -> Str.get(R.string.repo_auth_failed)
        t.message?.contains("Connection refused", true) == true -> Str.get(R.string.repo_connection_refused)
        t.message?.contains("UnknownHost", true) == true -> Str.get(R.string.repo_unknown_host)
        t.message?.contains("timeout", true) == true -> Str.get(R.string.repo_connect_timeout)
        t.message?.contains("session is down", true) == true -> Str.get(R.string.repo_ssh_session_down)
        t.message?.contains("reject HostKey", true) == true -> Str.get(R.string.repo_host_key_rejected)
        // A plain SSH machine accepts the login and then refuses the exec channel, or runs a shell
        // that has no bridge command. The transport words ("failed to send channel request") name
        // neither the cause nor the next step, so say what to check on the host instead.
        t.message?.contains("channel is not opened", true) == true ||
            t.message?.contains("failed to send channel request", true) == true ->
            Str.get(R.string.repo_bridge_unavailable)
        else -> Str.get(R.string.repo_ssh_failed, t.message ?: Str.get(R.string.repo_unknown_error))
    }
    is java.net.SocketTimeoutException -> Str.get(R.string.repo_request_timeout)
    is kotlinx.coroutines.TimeoutCancellationException -> Str.get(R.string.repo_operation_timeout)
    else -> t.message ?: t.javaClass.simpleName
}
