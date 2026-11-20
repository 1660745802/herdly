package dev.herdwatch.data

import dev.herdwatch.i18n.TestStrings
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.HerdPane
import dev.herdwatch.model.HerdStructure
import dev.herdwatch.model.HostConfig
import dev.herdwatch.ssh.HostConnection
import dev.herdwatch.ssh.HerdrRpcException
import dev.herdwatch.ssh.HostKeyMismatchException
import dev.herdwatch.ssh.UnknownHostKeyException
import dev.herdwatch.ssh.Snapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HerdRepositoryTest {
    private val host = HostConfig("h", "original", "localhost", user = "test", fingerprint = "SHA256:test")

    @Test fun `late connect after edit cannot pin or publish old configuration`() = runTest {
        val prefs = preferences(host.copy(fingerprint = null))
        val reply = CompletableDeferred<Unit>()
        val old = connection(host, "obsolete").apply {
            onConnect = { withContext(NonCancellable) { reply.await() } }
        }
        var attempts = 0
        val repo = repository(prefs) { config ->
            if (attempts++ == 0) old else connection(config, "replacement")
        }
        val pending = launch { repo.connectHost(host.id) }
        runCurrent()
        val edited = host.copy(label = "edited", host = "new-address", fingerprint = null)
        repo.updateHost(edited)
        reply.complete(Unit)
        pending.join()

        assertTrue(old.closed)
        assertEquals(0, old.pingCalls)
        assertEquals(0, old.snapshotCalls)
        assertEquals(edited, prefs.store.load().single())
        assertEquals(ConnState.Idle, repo.state.value.hosts.single().conn)
        assertTrue(repo.state.value.agents.isEmpty())
        assertTrue(prefs.store.loadAgents(host.id).isEmpty())

        repo.connectHost(host.id)
        // The edited config has no pin left, so the first contact asks instead of trusting the
        // key the replacement client presented.
        assertFalse(repo.isHostReady(host.id))
        assertEquals("SHA256:test", repo.state.value.hosts.single().pendingKey?.fingerprint)
        assertNull(prefs.store.load().single().fingerprint)

        repo.trustNewFingerprint(host.id, "SHA256:test")
        // The reconnect is launched on the test's background scope; run it explicitly.
        runCurrent()
        advanceUntilIdle()
        assertTrue(repo.isHostReady(host.id))
        assertEquals("replacement", repo.state.value.agents.single().name)
        assertEquals("SHA256:test", prefs.store.load().single().fingerprint)
    }

    @Test fun `late snapshot after background is discarded from state and durable cache`() = runTest {
        val prefs = preferences(host)
        val cached = snapshot(host, "cached").agents
        prefs.store.saveAgents(host.id, cached)
        val reply = CompletableDeferred<Snapshot>()
        val client = connection(host).apply {
            onSnapshot = { withContext(NonCancellable) { reply.await() } }
        }
        val repo = repository(prefs) { client }
        val pending = launch { repo.refreshAll() }
        runCurrent()
        assertEquals(1, client.snapshotCalls)
        assertTrue(repo.state.value.refreshing)
        repo.setForeground(false)
        reply.complete(snapshot(host, "obsolete"))
        pending.join()

        assertTrue(client.closed)
        assertFalse(repo.state.value.foreground)
        assertFalse(repo.state.value.refreshing)
        assertEquals(ConnState.Idle, repo.state.value.hosts.single().conn)
        assertEquals(cached, repo.state.value.agents)
        assertEquals(cached, prefs.store.loadAgents(host.id))
        assertNull(repo.state.value.lastSyncAt)
    }

    @Test fun `resume refresh is not lost behind an obsolete batch`() = runTest {
        val prefs = preferences(host)
        val reply = CompletableDeferred<Snapshot>()
        val old = connection(host).apply {
            onSnapshot = { withContext(NonCancellable) { reply.await() } }
        }
        val fresh = connection(host, "resumed")
        var attempts = 0
        val repo = repository(prefs) { if (attempts++ == 0) old else fresh }
        val pending = launch { repo.refreshAll() }
        runCurrent()
        repo.setForeground(false)
        repo.setForeground(true)
        runCurrent()
        reply.complete(snapshot(host, "obsolete"))
        pending.join()
        runCurrent()

        // No clock advance: resume must refresh immediately, not wait for the next poll.
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(1, fresh.snapshotCalls)
        assertTrue(repo.isHostReady(host.id))
        assertEquals("resumed", repo.state.value.agents.single().name)
        assertEquals("resumed", prefs.store.loadAgents(host.id).single().name)
        repo.setForeground(false)
    }

    @Test fun `cancelling refresh clears flag closes client and permits retry`() = runTest {
        val prefs = preferences(host)
        val blocked = connection(host).apply { onSnapshot = { awaitCancellation() } }
        val retry = connection(host, "retry")
        var attempts = 0
        val repo = repository(prefs) { if (attempts++ == 0) blocked else retry }
        val pending = launch { repo.refreshAll() }
        runCurrent()
        assertTrue(repo.state.value.refreshing)
        pending.cancel()
        pending.join()

        assertTrue(blocked.closed)
        assertFalse(repo.state.value.refreshing)
        assertFalse(repo.isHostReady(host.id))
        assertEquals(ConnState.Idle, repo.state.value.hosts.single().conn)
        repo.refreshAll()
        assertEquals("retry", repo.state.value.agents.single().name)
        assertFalse(repo.state.value.refreshing)
    }

    @Test fun `slow and failing hosts do not prevent healthy host publication`() = runTest {
        val slowHost = host.copy(id = "slow")
        val badHost = host.copy(id = "bad")
        val goodHost = host.copy(id = "good")
        val prefs = preferences(slowHost, badHost, goodHost)
        val slow = connection(slowHost).apply { onConnect = { awaitCancellation() } }
        val bad = connection(badHost).apply { onConnect = { error("offline") } }
        val good = connection(goodHost, "healthy")
        val clients = mapOf("slow" to slow, "bad" to bad, "good" to good)
        val repo = repository(prefs, timeout = 1_000) { clients.getValue(it.id) }
        val pending = launch { repo.refreshAll() }
        runCurrent()

        assertFalse(pending.isCompleted)
        assertTrue(repo.isHostReady("good"))
        assertEquals("healthy", repo.state.value.agents.single().name)
        assertTrue(repo.state.value.hosts.first { it.host.id == "bad" }.conn is ConnState.Failed)
        assertEquals("healthy", prefs.store.loadAgents("good").single().name)
        advanceTimeBy(1_000)
        runCurrent()
        pending.join()
        assertTrue(slow.closed)
        assertTrue(repo.state.value.hosts.first { it.host.id == "slow" }.conn is ConnState.Failed)
        assertFalse(repo.state.value.refreshing)
        assertTrue(repo.isHostReady("good"))
    }

    @Test fun `refresh timeout while mutation holds host lock preserves accepted operation`() = runTest {
        val prefs = preferences(host)
        val client = connection(host)
        val repo = repository(prefs, timeout = 1_000) { client }
        repo.connectHost(host.id)
        val accepted = CompletableDeferred<Unit>()
        client.onStartAgent = { accepted.await() }
        val mutation = async { repo.startAgent(host.id, "p", "pi", "agent", emptyList()) }
        runCurrent()
        val refresh = launch { repo.refreshHost(host.id) }
        runCurrent()
        advanceTimeBy(999)
        runCurrent()
        assertFalse(refresh.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        refresh.join()

        assertFalse(client.closed)
        assertTrue(repo.isHostReady(host.id))
        assertEquals(1, client.snapshotCalls)
        assertFalse(mutation.isCompleted)
        accepted.complete(Unit)
        assertTrue(mutation.await().isSuccess)
        assertEquals(1, client.mutationCalls)
        runCurrent()
        assertTrue(repo.isHostReady(host.id))
    }

    @Test fun `a stalled read keeps the session and a dead socket does not`() = runTest {
        suspend fun exercise(history: Boolean): Pair<HerdRepository, FakeHostConnection> {
            val prefs = preferences(host)
            val client = connection(host)
            val repo = repository(prefs) { client }
            repo.connectHost(host.id)
            client.onReadPane = { kotlinx.coroutines.withTimeout(1) { awaitCancellation() } }
            runCatching { repo.readPane(snapshot(host, "agent").agents.single(), history = history) }
            return repo to client
        }

        // A slow read is a slow read. The session stays up either way, so the next tap does not
        // pay for a handshake, and the machine never flashes "disconnected" for a stalled reply.
        for (history in listOf(false, true)) {
            val (repo, client) = exercise(history)
            assertFalse("history=$history closed the client", client.closed)
            assertTrue("history=$history lost the host", repo.isHostReady(host.id))
        }

        // A dead socket is the one thing that must force a handshake: the next use reconnects
        // instead of pretending the session is fine.
        val prefs = preferences(host)
        val dead = connection(host)
        val repo = repository(prefs) { dead }
        repo.connectHost(host.id)
        val handshakes = dead.connectCalls
        dead.kill()
        runCatching { repo.readPane(snapshot(host, "agent").agents.single()) }
        runCurrent()
        assertEquals(handshakes + 1, dead.connectCalls)
        assertTrue(repo.isHostReady(host.id))

        // And when that reconnect cannot reach the machine, the host is reported failed rather
        // than left looking connected with a client behind it.
        val refused = connection(host)
        val refusedRepo = repository(preferences(host)) { refused }
        refusedRepo.connectHost(host.id)
        refused.kill()
        refused.onConnect = { throw java.net.ConnectException("Connection refused") }
        runCatching { refusedRepo.readPane(snapshot(host, "agent").agents.single()) }
        runCurrent()
        assertTrue(refused.closed)
        assertFalse(refusedRepo.isHostReady(host.id))
    }

    @Test fun `concurrent refresh in same generation coalesces without clearing active flag`() = runTest {
        val prefs = preferences(host)
        val reply = CompletableDeferred<Snapshot>()
        val client = connection(host).apply { onSnapshot = { reply.await() } }
        val repo = repository(prefs) { client }
        val first = launch { repo.refreshAll() }
        runCurrent()
        // A pull that lands mid-batch joins it: one batch against the machine, and the indicator
        // stays up until that batch is actually done.
        val second = launch { repo.refreshAll() }
        runCurrent()
        assertTrue(repo.state.value.refreshing)
        assertEquals(1, client.snapshotCalls)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        reply.complete(snapshot(host, "coalesced"))
        first.join()
        second.join()
        assertFalse(repo.state.value.refreshing)
        assertEquals("coalesced", repo.state.value.agents.single().name)
    }

    @Test fun `manual pull during a silent poll ends its own indicator`() = runTest {
        val prefs = preferences(host)
        val reply = CompletableDeferred<Snapshot>()
        val client = connection(host).apply { onSnapshot = { reply.await() } }
        val repo = repository(prefs) { client }
        val poll = launch { repo.refreshAll(silent = true) }
        runCurrent()
        assertFalse(repo.state.value.refreshing)

        val pull = launch { repo.refreshAll() }
        runCurrent()
        assertTrue(repo.state.value.refreshing)
        // Joining, not starting a second batch.
        assertEquals(1, client.snapshotCalls)

        reply.complete(snapshot(host, "polled"))
        poll.join()
        pull.join()
        assertFalse(repo.state.value.refreshing)
    }

    @Test fun `offline refresh retains last successful snapshot and restart cache`() = runTest {
        val prefs = preferences(host)
        val client = connection(host, "last known")
        val repo = repository(prefs) { client }
        repo.refreshHost(host.id)
        val before = repo.state.value
        val cached = prefs.store.loadAgents(host.id)
        client.onSnapshot = { error("offline") }
        repo.refreshAll()

        assertFalse(repo.isHostReady(host.id))
        assertTrue(repo.state.value.hosts.single().conn is ConnState.Failed)
        assertEquals(before.agents, repo.state.value.agents)
        assertEquals(before.hosts.single().structure, repo.state.value.hosts.single().structure)
        assertEquals(before.lastSyncAt, repo.state.value.lastSyncAt)
        assertEquals(cached, prefs.store.loadAgents(host.id))
        val restarted = repository(prefs) { error("restart must not contact host") }
        assertEquals(cached, restarted.state.value.agents)
        assertFalse(restarted.isHostReady(host.id))
    }

    @Test fun `accepted mutation succeeds before detached refresh fails without replay`() = runTest {
        val prefs = preferences(host)
        val client = connection(host)
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)
        val accepted = CompletableDeferred<Unit>()
        client.onStartAgent = { accepted.await() }
        client.onSnapshot = { error("refresh disconnected") }
        val request = async { repo.startAgent(host.id, "p", "pi", "agent", emptyList()) }
        runCurrent()
        assertFalse(request.isCompleted)
        accepted.complete(Unit)
        val result = request.await()
        assertTrue(result.isSuccess)
        runCurrent()

        assertTrue(result.isSuccess)
        assertEquals(1, client.mutationCalls)
        assertEquals(2, client.snapshotCalls)
        assertTrue(client.closed)
        assertTrue(repo.state.value.hosts.single().conn is ConnState.Failed)
    }

    @Test fun `failed durable confirmation refuses readiness and any host RPC`() = runTest {
        TestStrings.install()
        val unpinned = host.copy(fingerprint = null)
        val prefs = preferences(unpinned)
        val client = connection(unpinned)
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)
        // The handshake is only asked about; nothing is written until the user confirms.
        assertEquals("SHA256:test", repo.state.value.hosts.single().pendingKey?.fingerprint)

        prefs.commitSucceeds = false
        val failure = runCatching { repo.trustNewFingerprint(host.id, "SHA256:test") }

        assertTrue(failure.isFailure)
        assertEquals(0, client.pingCalls)
        assertEquals(0, client.snapshotCalls)
        assertFalse(repo.isHostReady(host.id))
        assertNull(repo.state.value.hosts.single().host.fingerprint)
        assertNull(prefs.store.load().single().fingerprint)
        assertTrue(repo.state.value.agents.isEmpty())
        assertTrue(prefs.store.loadAgents(host.id).isEmpty())
    }

    @Test fun `successful unpinned handshake is never written before confirmation`() = runTest {
        TestStrings.install()
        val unpinned = host.copy(fingerprint = null)
        val prefs = preferences(unpinned)
        val client = connection(unpinned)
        val repo = repository(prefs) { client }

        repo.connectHost(host.id)

        val runtime = repo.state.value.hosts.single()
        assertEquals("SHA256:test", runtime.pendingKey?.fingerprint)
        assertTrue(runtime.conn is ConnState.Failed)
        assertFalse(repo.isHostReady(host.id))
        assertNull(prefs.store.load().single().fingerprint)
        assertEquals(0, client.pingCalls)
        assertEquals(0, client.snapshotCalls)
    }

    @Test fun `previously pinned host connects without asking again`() = runTest {
        val prefs = preferences(host)
        val client = connection(host)
        val repo = repository(prefs) { client }

        repo.connectHost(host.id)

        val runtime = repo.state.value.hosts.single()
        assertEquals(ConnState.Connected, runtime.conn)
        assertNull(runtime.pendingKey)
        assertTrue(client.snapshotCalls >= 1)
        assertEquals("SHA256:test", prefs.store.load().single().fingerprint)
    }

    @Test fun `unreadable host store is surfaced and its payload kept`() = runTest {
        TestStrings.install()
        val prefs = RepositoryPreferences()
        prefs.values["hosts.v1"] = "broken primary"
        prefs.values["hosts.v1.bak"] = "broken backup"

        val repo = repository(prefs) { error("must not connect") }

        assertTrue(repo.state.value.hosts.isEmpty())
        assertEquals(TestStrings.text("store_unavailable"), repo.state.value.storeError)
        // The unreadable generations still hold the user's configuration: do not erase them.
        assertEquals("broken primary", prefs.values["hosts.v1"])
        assertEquals("broken backup", prefs.values["hosts.v1.bak"])
    }

    @Test fun `first connection asks before any credential and trusting pins it`() = runTest {
        TestStrings.install()
        val unpinned = host.copy(fingerprint = null)
        val prefs = preferences(unpinned)
        var confirmed = false
        // One client per attempt, as in production: a refused connection is closed and the next
        // attempt is built from the stored config, which now carries the confirmed pin.
        val clients = mutableListOf<FakeHostConnection>()
        val repo = repository(prefs) { config ->
            connection(config).apply {
                // Once confirmed, the same machine presents the key the user just accepted.
                presentedKey = if (confirmed) "SHA256:first" else "SHA256:test"
                onConnect = { if (!confirmed) throw UnknownHostKeyException("SHA256:first", "ssh-ed25519") }
            }.also { clients += it }
        }
        repo.connectHost(host.id)

        val runtime = repo.state.value.hosts.single()
        assertEquals("SHA256:first", runtime.pendingKey?.fingerprint)
        assertEquals("ssh-ed25519", runtime.pendingKey?.keyType)
        // Nothing was sent to a machine the user has not confirmed yet.
        assertEquals(0, clients.sumOf { it.pingCalls })
        assertEquals(0, clients.sumOf { it.snapshotCalls })
        assertNull(prefs.store.load().single().fingerprint)

        confirmed = true
        repo.trustNewFingerprint(host.id, "SHA256:first")
        advanceUntilIdle()

        assertEquals("SHA256:first", prefs.store.load().single().fingerprint)
        assertNull(repo.state.value.hosts.single().pendingKey)
        repo.refreshHost(host.id)
        advanceUntilIdle()
        assertEquals(ConnState.Connected, repo.state.value.hosts.single().conn)
        // Trusting reconnects on its own; the explicit refresh is the page's usual poll.
        assertTrue(clients.sumOf { it.snapshotCalls } >= 1)
    }

    @Test fun `key change exposes both fingerprints and trusting it pins only that host`() = runTest {
        val other = host.copy(id = "other", label = "other", fingerprint = "SHA256:other")
        val prefs = preferences(host, other)
        val client = connection(host).apply {
            onConnect = { throw HostKeyMismatchException("SHA256:test", "SHA256:new") }
        }
        // Only the mismatching host is contacted in this test.
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)

        val runtime = repo.state.value.hosts.single { it.host.id == host.id }
        assertTrue(runtime.conn is ConnState.Failed)
        assertEquals("SHA256:test", runtime.keyMismatch?.expected)
        assertEquals("SHA256:new", runtime.keyMismatch?.actual)
        assertNull(repo.state.value.hosts.single { it.host.id == other.id }.keyMismatch)

        repo.trustNewFingerprint(host.id, "SHA256:new")
        advanceUntilIdle()

        // Durable first: the new pin must survive a restart, and the other host keeps its own.
        assertEquals("SHA256:new", prefs.store.load().single { it.id == host.id }.fingerprint)
        assertEquals("SHA256:other", prefs.store.load().single { it.id == other.id }.fingerprint)
    }

    @Test fun `confirmation is refused when the pending key changed since the dialog opened`() = runTest {
        TestStrings.install()
        val prefs = preferences(host)
        // One client per attempt, as in production: a refused client is closed and the retry is
        // built fresh. The second attempt sees the machine's key rotated once more.
        val clients = mutableListOf<FakeHostConnection>()
        val repo = repository(prefs) { config ->
            connection(config).apply {
                onConnect = {
                    throw HostKeyMismatchException(
                        "SHA256:test",
                        if (clients.size <= 1) "SHA256:first" else "SHA256:second",
                    )
                }
            }.also { clients += it }
        }
        repo.connectHost(host.id)
        assertEquals("SHA256:first", repo.state.value.hosts.single().keyMismatch?.actual)

        // The machine changed again while the dialog was still showing SHA256:first.
        repo.refreshHost(host.id)
        advanceUntilIdle()
        assertEquals("SHA256:second", repo.state.value.hosts.single().keyMismatch?.actual)

        // Confirming the stale dialog must not pin a key the user never looked at.
        val result = runCatching { repo.trustNewFingerprint(host.id, "SHA256:first") }
        assertTrue(result.isFailure)
        assertEquals("SHA256:test", prefs.store.load().single().fingerprint)
        assertEquals("SHA256:second", repo.state.value.hosts.single().keyMismatch?.actual)
    }

    @Test fun `successful snapshot clears a previous key mismatch`() = runTest {
        val prefs = preferences(host)
        val repo = repository(prefs) { connection(it) }
        repo.connectHost(host.id)
        assertEquals(ConnState.Connected, repo.state.value.hosts.single().conn)

        // The machine is reinstalled: the same client now presents a different key.
        repo.updateHost(host.copy(fingerprint = "SHA256:rotated"))
        repo.refreshHost(host.id)
        advanceUntilIdle()
        assertEquals("SHA256:rotated", repo.state.value.hosts.single().keyMismatch?.expected)
        assertEquals("SHA256:test", repo.state.value.hosts.single().keyMismatch?.actual)

        repo.trustNewFingerprint(host.id, "SHA256:test")
        advanceUntilIdle()
        repo.refreshHost(host.id)
        advanceUntilIdle()

        val runtime = repo.state.value.hosts.single()
        assertEquals(ConnState.Connected, runtime.conn)
        assertNull(runtime.keyMismatch)
        assertEquals("SHA256:test", prefs.store.load().single().fingerprint)
    }

    @Test fun `rejected agent start in a new tab closes just that tab`() = runTest {
        val prefs = preferences(host)
        val client = connection(host).apply {
            created = HostConnection.PaneCreated("p2", tabId = "tab-9", workspaceId = "ws-1")
            onStartAgent = { throw HerdrRpcException("agent_exists", "already running") }
        }
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)

        val result = repo.startInNewTab(host.id, "ws-1", "pi", "agent", emptyList())

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is HerdrRpcException)
        assertEquals(listOf("tab-9"), client.closedTabs)
        assertTrue(client.closedWorkspaces.isEmpty())
    }

    @Test fun `rejected agent start in a new workspace closes that workspace`() = runTest {
        val prefs = preferences(host)
        val client = connection(host).apply {
            created = HostConnection.PaneCreated("p3", tabId = "tab-3", workspaceId = "ws-9")
            onStartAgent = { throw HerdrRpcException("bad_kind", "unknown agent") }
        }
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)

        val result = repo.startInNewTab(host.id, null, "pi", "agent", emptyList())

        assertTrue(result.isFailure)
        assertEquals(listOf("ws-9"), client.closedWorkspaces)
        assertTrue(client.closedTabs.isEmpty())
    }

    @Test fun `successful start in a new tab leaves the container alone`() = runTest {
        val prefs = preferences(host)
        val client = connection(host).apply {
            created = HostConnection.PaneCreated("p2", tabId = "tab-9", workspaceId = "ws-1")
        }
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)

        assertTrue(repo.startInNewTab(host.id, "ws-1", "pi", "agent", emptyList()).isSuccess)
        assertTrue(client.closedTabs.isEmpty())
        assertTrue(client.closedWorkspaces.isEmpty())
    }

    @Test fun `cleanup failure keeps the start error as the reported cause`() = runTest {
        val prefs = preferences(host)
        val client = connection(host).apply {
            created = HostConnection.PaneCreated("p2", tabId = "tab-9", workspaceId = "ws-1")
            onStartAgent = { throw HerdrRpcException("agent_exists", "already running") }
            onClose = { error("close refused") }
        }
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)

        val result = repo.startInNewTab(host.id, "ws-1", "pi", "agent", emptyList())

        assertTrue(result.exceptionOrNull() is HerdrRpcException)
        assertEquals(1, result.exceptionOrNull()!!.suppressedExceptions.size)
    }

    @Test fun `dropped transport during start performs no destructive cleanup`() = runTest {
        val prefs = preferences(host)
        val client = connection(host).apply {
            created = HostConnection.PaneCreated("p2", tabId = "tab-9", workspaceId = "ws-1")
            // The server may have accepted the start; the reply is simply lost.
            onStartAgent = { throw java.io.IOException("connection reset") }
        }
        val repo = repository(prefs) { client }
        repo.connectHost(host.id)

        assertTrue(repo.startInNewTab(host.id, "ws-1", "pi", "agent", emptyList()).isFailure)
        assertTrue(client.closedTabs.isEmpty())
        assertTrue(client.closedWorkspaces.isEmpty())
    }

    private fun preferences(vararg hosts: HostConfig) = RepositoryPreferences().also {
        it.store.save(hosts.toList())
    }

    private fun TestScope.repository(
        prefs: RepositoryPreferences,
        timeout: Long = 25_000,
        factory: (HostConfig) -> HostConnection,
    ) = HerdRepository(prefs.store, factory, backgroundScope, StandardTestDispatcher(testScheduler), timeout)

    private fun connection(config: HostConfig, name: String = "live") =
        FakeHostConnection(config, snapshot(config, name))

    private fun snapshot(config: HostConfig, name: String): Snapshot {
        val agent = Agent(
            hostId = config.id, hostLabel = config.label, paneId = "p", name = name, kind = "pi",
            status = AgentStatus.WORKING, cwd = null, workspaceLabel = null,
            stateChangeSeq = 1, completionSeq = 0, focused = false, rawStatus = "working", sinceMillis = 42,
        )
        val pane = HerdPane("p", "p", "w", "t", name, "pi", AgentStatus.WORKING, 1, null, null, null, null)
        return Snapshot("test", 1, listOf(agent), HerdStructure(panes = listOf(pane)))
    }
}
