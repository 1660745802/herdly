package dev.herdwatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdwatch.HerdwatchApp
import dev.herdwatch.R
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.LanguageMode
import dev.herdwatch.data.AppPreferences
import dev.herdwatch.data.PreferencesStore
import dev.herdwatch.data.RepoState
import dev.herdwatch.data.ThemeMode
import dev.herdwatch.data.InvalidApkException
import dev.herdwatch.data.UpdateChecker
import dev.herdwatch.data.UpdateInstaller
import dev.herdwatch.data.AndroidUpdateInstaller
import java.io.File
import dev.herdwatch.domain.ApprovalEvidence
import dev.herdwatch.domain.ApprovalPolicy
import dev.herdwatch.domain.OperationState
import dev.herdwatch.domain.PageTicket
import dev.herdwatch.domain.operationRpc
import dev.herdwatch.i18n.Str
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.Ask
import dev.herdwatch.model.AskOption
import dev.herdwatch.model.PaneText
import dev.herdwatch.model.paneDetailAgent
import dev.herdwatch.model.ConnState
import dev.herdwatch.model.AuthType
import dev.herdwatch.model.HostConfig
import dev.herdwatch.model.Screen
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.launch


/** One thing the user sent from this page. `echoed` means the screen already shows it. */
data class SentMessage(val id: Long, val text: String, val at: Long, val echoed: Boolean = false)

data class DetailState(
    val agent: Agent? = null,
    val text: String = "",
    val ask: Ask? = null,
    val loading: Boolean = false,
    val sending: Boolean = false,
    val revision: Long? = null,
    val shownSeq: Long? = null,
    val truncated: Boolean = false,
    val error: String? = null,
    val loadedAt: Long? = null,
    /** How many lines from the tail we ask for. Grows when the reader scrolls to the top. */
    val historyLines: Int = PANE_READ_LINES,
    /**
     * What this app has sent to the pane, newest last.
     *
     * The bridge returns a screen, not a transcript: our own message only becomes visible once the
     * agent echoes it, and a slow agent can take seconds. Remembering the send locally is what makes
     * the conversation feel answered the moment Send is pressed.
     */
    val sent: List<SentMessage> = emptyList(),
    val explain: String? = null,
    val explainLoading: Boolean = false,
    val draft: String = "",
    val operationNotice: String? = null,
    val operationUncertain: Boolean = false,
    val canChoose: Boolean = false,
    val canSend: Boolean = false,
    val hostConnected: Boolean = false,
    val hostConnecting: Boolean = false,
    val canVerifyOperation: Boolean = false,
    val stale: Boolean = true,
    val readingPaused: Boolean = false,
    val readingPosition: ReadingPosition = ReadingPosition(),
)

data class UiState(
    val repo: RepoState = RepoState(),
    val screen: Screen = Screen.Inbox,
    /** 「全部」在前，也是默认视图；需要处理只是其中一个筛选。 */
    val query: String = "",
    val searching: Boolean = false,
    val detail: DetailState = DetailState(),
    val addingHost: Boolean = false,
    val message: String? = null,
    val showAddHost: Boolean = false,
    val editingHost: HostConfig? = null,
    val startAgent: StartAgentRequest? = null,
    val showNewTask: Boolean = false,
)

data class StartAgentRequest(
    val hostId: String,
    val paneId: String? = null,
    val kind: String = "claude",
    val name: String = "claude",
    val resume: Boolean = false,
)

/**
 * Manual update check plus the in-app install that follows it: never runs on its own, so there is
 * no polling state to model.
 */
data class UpdateUi(
    val checking: Boolean = false,
    val result: UpdateChecker.Result? = null,
    /** Whole-percent download progress; [downloading] is false after the file is on disk. */
    val downloading: Boolean = false,
    val progress: Int = 0,
    val installing: Boolean = false,
    /** Android blocks the installer until the user allows this app to install packages. */
    val needsPermission: Boolean = false,
    val error: String? = null,
    /** Set once an APK is downloaded and verified; the next tap goes straight to the installer. */
    val readyVersion: String? = null,
)

/** Agent detail always asks for the widest recent snapshot once; the bridge has no paging. */
private const val PANE_READ_LINES = 1200

/** Monotonic id for local sends; only used to key list items. */
private var nextSendId: Long = 1

/**
 * Ceiling for the "read further back" request.
 *
 * The bridge has no cursor, so every step re-reads the whole tail: 19,200 lines was roughly a
 * megabyte of ANSI on a long agent run and people still hit the wall mid-conversation. 48,000 costs
 * a few megabytes at worst (the text is parsed once per load, and rows are rendered lazily) and
 * covers a day of terminal scrollback. Past this the honest answer is "this is a pane, not an
 * archive" — durable history would have to live on the host.
 */
private const val MAX_HISTORY_LINES = 48_000

class AppViewModel(
    private val repository: HerdRepository,
    private val preferencesStore: PreferencesStore,
    private val appVersion: String = "0.0.0",
    /**
     * Android side of updating: download, the "install unknown apps" grant, the system installer.
     * Null in JVM tests, where none of it can run; the update row then only offers the check.
     */
    private val updateInstaller: UpdateInstaller? = null,
) : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val preferences: StateFlow<AppPreferences> = preferencesStore.state

    private val terminal = TerminalController(
        scope = viewModelScope,
        onFingerprintTrusted = { hostId, fingerprint -> repository.updateFingerprint(hostId, fingerprint) },
    )
    val terminalState: StateFlow<TerminalUiState> = terminal.state

    private var loadJob: Job? = null
    private var explainJob: Job? = null
    private var pageGeneration = 0L
    private var readGeneration = 0L
    private var explainGeneration = 0L
    private val operations = OperationState()
    /**
     * The page stack above the current tab root.
     *
     * A single `screen` value could only ever go "back to Inbox", so opening a pane from the
     * structure page and pressing back threw the user out of the tree they were working in. A real
     * stack keeps the trail: detail → structure → machines.
     */
    private val pageStack = mutableListOf<Screen>()

    /** The tab the current trail started from; back lands here once the stack is empty. */
    private var trailRoot: Screen = Screen.Inbox
    private val readingPositions = object : LinkedHashMap<String, ReadingPosition>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ReadingPosition>?) = size > 80
    }
    private val reconnecting = mutableSetOf<String>()

    fun rememberReadingPosition(agentKey: String, position: ReadingPosition) {
        readingPositions[agentKey] = position
    }

    private fun ticket(): PageTicket? = (_ui.value.screen as? Screen.Detail)?.let {
        PageTicket(it.agentKey, pageGeneration)
    }

    private fun matches(page: PageTicket): Boolean = ticket() == page &&
        _ui.value.detail.agent?.key == page.agentKey

    private fun updateDetail(page: PageTicket, block: (DetailState) -> DetailState) {
        if (matches(page)) _ui.update { it.copy(detail = decorate(block(it.detail))) }
    }

    private fun evidence(detail: DetailState) = ApprovalEvidence(
        PaneText(detail.text, detail.revision, detail.truncated), detail.ask,
    )

    private fun connected(agent: Agent, repo: RepoState = _ui.value.repo): Boolean = foreground &&
        repo.hosts.any { it.host.id == agent.hostId && it.conn == ConnState.Connected } &&
        // A pane without an agent is still a legitimate target: the host connection is all it needs.
        (!agent.hasAgent || repo.agents.any { it.key == agent.key })

    private var foreground = true

    private fun decorate(detail: DetailState, repo: RepoState = _ui.value.repo): DetailState {
        val agent = detail.agent ?: return detail
        val record = operations.record(agent.key)
        val canSend = connected(agent, repo) && record?.inFlight != true && record?.verificationRequired != true
        return detail.copy(
            sending = record?.inFlight == true,
            draft = operations.draft(agent.key).text,
            operationNotice = record?.notice ?: if (detail.ask != null) ApprovalPolicy.unavailable(evidence(detail)) else null,
            operationUncertain = record?.uncertain == true,
            canSend = canSend,
            hostConnected = connected(agent, repo),
            hostConnecting = agent.hostId in reconnecting || repo.hosts.any { it.host.id == agent.hostId && it.conn == ConnState.Connecting },
            canVerifyOperation = connected(agent, repo) && record?.inFlight != true && !detail.loading && !detail.stale &&
                (record?.verificationRequired == true || operations.blocksApproval(agent.key, evidence(detail))),
            canChoose = canSend && !detail.stale && !detail.loading &&
                (agent.status == AgentStatus.WAITING || agent.status == AgentStatus.UNKNOWN) &&
                ApprovalPolicy.unavailable(evidence(detail)) == null &&
                !operations.blocksApproval(agent.key, evidence(detail)),
        )
    }

    private fun syncOperation(key: String) {
        if (_ui.value.detail.agent?.key == key && _ui.value.screen is Screen.Detail) {
            _ui.update { it.copy(detail = decorate(it.detail)) }
        }
    }

    private fun leavePage() {
        pageGeneration++
        readGeneration++
        explainGeneration++
        loadJob?.cancel()
        explainJob?.cancel()
    }

    init {
        viewModelScope.launch {
            repository.state.collect { repo ->
                var reload: Agent? = null
                _ui.update { current ->
                    val fresh = current.detail.agent?.let { a ->
                        repo.agents.firstOrNull { it.key == a.key }
                    }
                    // A status transition means the screen probably changed (new question,
                    // finished work). Re-read the pane so the user doesn't answer a stale prompt.
                    if (current.screen is Screen.Detail && fresh != null &&
                        fresh.stateChangeSeq != (current.detail.shownSeq ?: current.detail.agent?.stateChangeSeq) &&
                        !current.detail.sending && !current.detail.loading && loadJob?.isActive != true && !current.detail.readingPaused
                    ) {
                        reload = fresh
                    }
                    current.copy(
                        repo = repo,
                        detail = if (current.screen is Screen.Detail) {
                            val target = fresh ?: current.detail.agent
                            // A pane with no agent has no status sequence to compare against, so
                            // "stale" can only come from the connection, not from a missing agent.
                            val paneOnly = current.detail.agent?.hasAgent == false
                            val hostDown = target != null &&
                                repo.hosts.none { it.host.id == target.hostId && it.conn == ConnState.Connected }
                            val movedOn = !paneOnly && fresh != null && current.detail.readingPaused &&
                                fresh.stateChangeSeq != (current.detail.shownSeq ?: fresh.stateChangeSeq)
                            decorate(current.detail.copy(
                                agent = target,
                                stale = current.detail.stale || (!paneOnly && fresh == null) || movedOn || hostDown,
                            ), repo)
                        } else current.detail,
                    )
                }
                reload?.let { loadPane(it) }
            }
        }
        viewModelScope.launch { repository.bootstrap() }
    }

    fun onForeground() {
        foreground = true
        repository.setVisible(true)
        repository.setForeground(true)
        val capturedPage = ticket()
        viewModelScope.launch {
            repository.connectAll()
            currentCoroutineContext().ensureActive()
            capturedPage?.let { page ->
                if (matches(page)) _ui.value.detail.agent?.let { loadPane(it) }
            }
            if (_ui.value.screen is Screen.Terminal) reopenTerminal()
        }
    }

    fun onBackground() {
        foreground = false
        leavePage()
        // The terminal and the bridge clients stay open: the keep-alive service holds the process
        // and the connection. Closing them here was what made every return a reconnect.
        repository.setVisible(false)
        _ui.update { it.copy(detail = decorate(it.detail.copy(stale = true, loading = false, explainLoading = false))) }
    }

    fun refresh() {
        viewModelScope.launch { repository.refreshAll() }
    }

    fun dismissError() = repository.dismissError()

    fun setQuery(query: String) = _ui.update { it.copy(query = query) }
    fun setSearching(searching: Boolean) = _ui.update { it.copy(searching = searching) }
    fun showAddHost(show: Boolean) = _ui.update {
        it.copy(showAddHost = show, editingHost = if (show) it.editingHost else null)
    }

    fun editHost(host: HostConfig) = _ui.update {
        it.copy(showAddHost = true, editingHost = host)
    }

    fun navigate(screen: Screen) {
        if (screen is Screen.Detail) {
            val key = screen.agentKey
            val split = key.indexOf(':')
            if (split > 0) openPane(key.substring(0, split), key.substring(split + 1))
            return
        }
        if (screen is Screen.Terminal) {
            openTerminal(screen.hostId)
            return
        }
        if (isRoot(screen)) {
            // Switching tabs starts a fresh trail; the old stack belonged to another tab.
            // Settings is reached from Machines far more often than from the tab bar, so back
            // returns to the tab we came from instead of forcing a tap on a tab.
            if (screen == Screen.Settings) {
                val from = _ui.value.screen
                trailRoot = if (isRoot(from) && from != Screen.Settings) from else trailRoot
                pageStack.clear()
                leavePage()
                _ui.update { it.copy(screen = screen, detail = DetailState()) }
                return
            }
            pageStack.clear()
            trailRoot = screen
            if (_ui.value.screen is Screen.Terminal) terminal.close()
            leavePage()
            _ui.update { it.copy(screen = screen, detail = DetailState()) }
            return
        }
        push(screen)
    }

    private fun isRoot(screen: Screen): Boolean =
        screen == Screen.Inbox || screen == Screen.Hosts || screen == Screen.Settings

    /** Pushes a page, remembering where we came from so back returns there. */
    private fun push(screen: Screen) {
        val current = _ui.value.screen
        if (current == screen) return
        rememberPage()
        if (current is Screen.Terminal) terminal.close()
        leavePage()
        _ui.update { it.copy(screen = screen, detail = DetailState()) }
    }

    /** Records the page we are leaving, unless it is a tab root (roots are never stacked). */
    private fun rememberPage() {
        val current = _ui.value.screen
        if (!isRoot(current) && pageStack.lastOrNull() != current) pageStack.add(current)
    }

    fun back() {
        leavePage()
        if (_ui.value.screen is Screen.Terminal) terminal.close()
        val destination = if (pageStack.isNotEmpty()) pageStack.removeAt(pageStack.lastIndex) else tabRoot()
        when (destination) {
            is Screen.Detail -> {
                val key = destination.agentKey
                val split = key.indexOf(':')
                if (split > 0) openPane(key.substring(0, split), key.substring(split + 1), pushPage = false)
                else _ui.update { it.copy(screen = destination, detail = DetailState()) }
            }
            is Screen.Terminal -> openTerminal(destination.hostId, pushPage = false)
            else -> _ui.update { it.copy(screen = destination, detail = DetailState()) }
        }
    }

    /** Where back lands when the stack is empty: the tab this trail started from. */
    private fun tabRoot(): Screen = trailRoot

    fun openStructure(hostId: String) {
        navigate(Screen.Structure(hostId))
    }

    /**
     * Opens a plain SSH shell on the host. This is deliberately independent of Herdr: it works on
     * any SSH host, and Herdr keeps its own bridge connection.
     */
    fun openTerminal(hostId: String, pushPage: Boolean = true) {
        val runtime = _ui.value.repo.hosts.firstOrNull { it.host.id == hostId }
        if (runtime == null) {
            _ui.update { it.copy(message = Str.get(R.string.vm_host_not_found)) }
            return
        }
        if (pushPage) rememberPage()
        leavePage()
        _ui.update { it.copy(screen = Screen.Terminal(hostId), detail = DetailState()) }
        terminal.open(runtime.host, preferencesStore.state.value.terminalFontSize)
    }

    private fun reopenTerminal() {
        val hostId = (_ui.value.screen as? Screen.Terminal)?.hostId ?: return
        // Returning to the foreground reloads the page we are already on; pushing it again made the
        // first Back re-open the same terminal instead of leaving it.
        openTerminal(hostId, pushPage = false)
    }

    fun closeTerminal() {
        terminal.close()
        back()
    }

    fun terminalInput(text: String, submit: Boolean = false) = terminal.write(text, submit)

    fun terminalKey(sequence: String) = terminal.key(sequence)

    fun terminalResize(cols: Int, rows: Int) = terminal.resize(cols, rows)

    fun terminalFontSize(size: Float) {
        preferencesStore.setTerminalFontSize(size)
        terminal.setFontSize(size)
    }

    /** The user confirmed the fingerprint shown for this first connection. */
    fun terminalTrustFingerprint() = terminal.trustFingerprint()

    /**
     * Language is applied by the Activity through the platform locale service; the ViewModel only
     * persists the choice so the switch survives process death.
     */
    fun setLanguage(mode: LanguageMode) = preferencesStore.setLanguage(mode)

    /** Result of the last manual 「检查更新」, null before the first check. */
    private val _update = MutableStateFlow<UpdateUi>(UpdateUi())
    val update: StateFlow<UpdateUi> = _update.asStateFlow()
    private var updateJob: Job? = null
    /** The APK this session already downloaded, so a retry never re-fetches it. */
    private var pendingApk: File? = null

    fun checkForUpdate() {
        if (_update.value.checking) return
        updateJob?.cancel()
        _update.value = UpdateUi(checking = true)
        updateJob = viewModelScope.launch {
            val result = UpdateChecker(appVersion).check()
            _update.update { it.copy(checking = false, result = result) }
        }
    }

    fun clearUpdateResult() {
        updateJob?.cancel()
        updateJob = null
        pendingApk = null
        _update.value = UpdateUi()
    }

    /**
     * Downloads the newer release and hands it to the installer.
     *
     * A second tap after a successful download skips the network: the APK is already in the cache,
     * and re-downloading ten megabytes to install the same file would be a strange punishment for
     * having pressed the button twice.
     */
    fun installUpdate() {
        val installer = updateInstaller ?: return
        val state = _update.value
        if (state.checking || state.downloading || state.installing) return
        val release = (state.result as? UpdateChecker.Result.Newer)?.release ?: return
        val cached = pendingApk
        if (cached != null && cached.exists() && state.readyVersion == release.version) {
            launchInstaller(installer, cached)
            return
        }
        val url = release.apkUrl
        if (url == null) {
            // A release without an APK asset cannot be installed from here; say so instead of
            // downloading the release page (HTML) and failing later inside the installer.
            _update.update { it.copy(error = Str.get(R.string.update_no_apk)) }
            return
        }
        updateJob?.cancel()
        _update.update { it.copy(downloading = true, progress = 0, error = null, needsPermission = false) }
        updateJob = viewModelScope.launch {
            try {
                val apk = installer.download(url, release.version) { percent ->
                    _update.update { it.copy(progress = percent) }
                }
                pendingApk = apk
                _update.update { it.copy(downloading = false, progress = 100, readyVersion = release.version) }
                launchInstaller(installer, apk)
            } catch (cancelled: CancellationException) {
                _update.update { it.copy(downloading = false) }
                throw cancelled
            } catch (t: Throwable) {
                _update.update {
                    it.copy(
                        downloading = false,
                        error = t.message ?: Str.get(R.string.update_failed_download),
                    )
                }
            }
        }
    }

    /** Called after the user returns from the system settings screen. */
    fun retryInstall() {
        val installer = updateInstaller ?: return
        val apk = pendingApk
        if (apk == null) {
            installer.requestInstallPermission()
            _update.update { it.copy(needsPermission = true) }
            return
        }
        if (!apk.exists()) {
            pendingApk = null
            _update.update { it.copy(readyVersion = null) }
            return
        }
        launchInstaller(installer, apk)
    }

    fun openInstallPermission() {
        updateInstaller?.requestInstallPermission()
        _update.update { it.copy(needsPermission = true) }
    }

    private fun launchInstaller(installer: UpdateInstaller, apk: File) {
        if (!installer.canInstall()) {
            // Ask once, then leave the state visible: the user comes back from Settings and the
            // row still says what it is waiting for instead of having silently reset.
            _update.update { it.copy(needsPermission = true, installing = false) }
            installer.requestInstallPermission()
            return
        }
        _update.update { it.copy(installing = true, needsPermission = false, error = null) }
        try {
            installer.install(apk)
        } catch (invalid: InvalidApkException) {
            pendingApk = null
            _update.update {
                it.copy(installing = false, readyVersion = null, error = invalid.message)
            }
        } catch (t: Throwable) {
            _update.update {
                it.copy(installing = false, error = t.message ?: Str.get(R.string.update_failed_install))
            }
        }
    }

    /** Current installed version, for the about block. */
    val version: String get() = appVersion

    fun setTheme(mode: ThemeMode) = preferencesStore.setTheme(mode)



    /**
     * Accepts the new key for ONE host after the user compared both fingerprints.
     *
     * Before this existed the only escape from a key change was the global reset in Settings, which
     * throws away every other host's pin too — a bad trade for a single reinstalled machine.
     */
    fun trustNewFingerprint(hostId: String, fingerprint: String) {
        runCatching { repository.trustNewFingerprint(hostId, fingerprint) }.onFailure { error ->
            _ui.update { it.copy(message = error.message ?: Str.get(R.string.vm_trust_failed)) }
        }
    }

    /** Forgets every pinned host key; the next connection asks for confirmation again. */
    fun resetFingerprints() {
        viewModelScope.launch { repository.resetFingerprints() }
    }

    fun terminalReconnect() = terminal.reconnect()

    fun terminalFollow() = terminal.follow()

    fun openStartAgent(hostId: String, paneId: String? = null) = _ui.update {
        it.copy(
            startAgent = StartAgentRequest(hostId = hostId, paneId = paneId),
        )
    }

    fun dismissStartAgent() = _ui.update { it.copy(startAgent = null) }

    fun showNewTask(show: Boolean) = _ui.update { it.copy(showNewTask = show) }

    fun startNewTask(
        hostId: String,
        workspaceId: String?,
        kind: String,
        name: String,
        args: List<String>,
    ) {
        _ui.update { it.copy(showNewTask = false) }
        screenMutation(Str.get(R.string.vm_start_accepted)) {
            repository.startInNewTab(hostId, workspaceId, kind, name.ifBlank { kind }, args).getOrThrow()
        }
    }

    fun startAgent(request: StartAgentRequest, paneId: String, kind: String, name: String, args: List<String>) {
        _ui.update { it.copy(startAgent = null) }
        screenMutation(Str.get(R.string.vm_start_accepted_named, name)) {
            repository.startAgent(request.hostId, paneId, kind, name.ifBlank { kind }, args).getOrThrow()
        }
    }

    fun createWorkspace(hostId: String, cwd: String? = null) = structureAction(hostId, Str.get(R.string.vm_created_workspace)) {
        repository.createWorkspace(hostId, null, cwd)
    }

    fun createTab(hostId: String, workspaceId: String, cwd: String? = null) = structureAction(hostId, Str.get(R.string.vm_created_tab)) {
        repository.createTab(hostId, workspaceId, null, cwd)
    }

    fun splitPane(hostId: String, paneId: String, direction: String) =
        structureAction(hostId, if (direction == "right") Str.get(R.string.vm_split_right) else Str.get(R.string.vm_split_down)) {
            repository.splitPane(hostId, paneId, direction)
        }

    fun closePane(hostId: String, paneId: String) = structureAction(hostId, Str.get(R.string.vm_closed_pane)) {
        repository.closePane(hostId, paneId)
    }

    fun closeTab(hostId: String, tabId: String) = structureAction(hostId, Str.get(R.string.vm_closed_tab)) {
        repository.closeTab(hostId, tabId)
    }

    fun closeWorkspace(hostId: String, workspaceId: String) = structureAction(hostId, Str.get(R.string.vm_closed_workspace)) {
        repository.closeWorkspace(hostId, workspaceId)
    }

    fun focusPane(hostId: String, paneId: String) = structureAction(hostId, Str.get(R.string.vm_focused_pane)) {
        repository.focusPane(hostId, paneId)
    }

    fun focusTab(hostId: String, tabId: String) = structureAction(hostId, Str.get(R.string.vm_focused_tab)) {
        repository.focusTab(hostId, tabId)
    }

    fun focusWorkspace(hostId: String, workspaceId: String) = structureAction(hostId, Str.get(R.string.vm_focused_workspace)) {
        repository.focusWorkspace(hostId, workspaceId)
    }

    fun renameWorkspace(hostId: String, workspaceId: String, label: String) =
        structureAction(hostId, Str.get(R.string.vm_renamed_workspace)) { repository.renameWorkspace(hostId, workspaceId, label) }

    fun renameTab(hostId: String, tabId: String, label: String) =
        structureAction(hostId, Str.get(R.string.vm_renamed_tab)) { repository.renameTab(hostId, tabId, label) }

    fun renamePane(hostId: String, paneId: String, label: String) =
        structureAction(hostId, Str.get(R.string.vm_renamed_pane)) { repository.renamePane(hostId, paneId, label) }

    fun renameAgent(name: String) {
        val page = ticket() ?: return
        val agent = _ui.value.detail.agent ?: return
        val operation = beginOperation(agent) ?: return
        viewModelScope.launch {
            var accepted = false
            try {
                currentCoroutineContext().ensureActive()
                if (!matches(page) || !connected(agent)) return@launch
                repository.renameAgent(agent, name)
                accepted = true
                currentCoroutineContext().ensureActive()
                updateDetail(page) { it.copy(agent = it.agent?.copy(name = name)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // The API includes a refresh after mutation; failure does not prove rejection.
            } finally {
                finishOperation(agent, operation, if (accepted) Str.get(R.string.vm_rename_accepted) else Str.get(R.string.vm_rename_uncertain), !accepted)
            }
        }
    }

    private fun structureAction(hostId: String, success: String, block: suspend () -> Unit) {
        // No success toast: the workspace/tab/pane appearing in the list is the receipt, and a
        // "request accepted, verify the sync state" banner made every ordinary edit feel uncertain.
        screenMutation(success = "", block = block)
    }

    private fun screenMutation(success: String, block: suspend () -> Unit) {
        val screen = _ui.value.screen
        val generation = pageGeneration
        fun show(text: String) {
            if (_ui.value.screen == screen && pageGeneration == generation) {
                _ui.update { it.copy(message = text) }
            }
        }
        viewModelScope.launch {
            try {
                currentCoroutineContext().ensureActive()
                block()
                currentCoroutineContext().ensureActive()
                if (success.isNotBlank()) show(success)
            } catch (cancelled: CancellationException) {
                show(Str.get(R.string.vm_request_interrupted))
                throw cancelled
            } catch (t: Throwable) {
                show(Str.get(R.string.vm_operation_check_remote, dev.herdwatch.data.humanize(t)))
            }
        }
    }

    private fun humanizeExplain(json: kotlinx.serialization.json.JsonObject): String {
        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull
        val lines = mutableListOf<String>()
        lines += Str.get(R.string.vm_explain_state, str("state") ?: "unknown")
        str("matched_rule")?.takeIf { it.isNotBlank() }?.let { lines += Str.get(R.string.vm_explain_matched_rule, it) }
        if (json["screen_detection_skipped"]?.jsonPrimitive?.booleanOrNull == true) {
            val why = str("screen_detection_skip_reason")
            lines += if (why == "full_lifecycle_hook_authority") {
                Str.get(R.string.vm_explain_detect_skipped_hook)
            } else {
                why?.let { Str.get(R.string.vm_explain_detect_skipped_reason, it) }
                    ?: Str.get(R.string.vm_explain_detect_skipped)
            }
        }
        listOf(
            "visible_blocker" to Str.get(R.string.vm_visible_blocker),
            "visible_idle" to Str.get(R.string.vm_visible_idle),
            "visible_working" to Str.get(R.string.vm_visible_working),
        )
            .filter { json[it.first]?.jsonPrimitive?.booleanOrNull == true }
            .forEach { lines += Str.get(R.string.vm_visible_signal, it.second) }
        str("fallback_reason")?.takeIf { it.isNotBlank() }?.let { lines += Str.get(R.string.vm_explain_fallback_reason, it) }
        str("warning")?.takeIf { it.isNotBlank() }?.let { lines += Str.get(R.string.vm_explain_warning, it) }
        return lines.joinToString("\n")
    }

    companion object {
        fun resumeArgs(kind: String): List<String> =
            dev.herdwatch.domain.AgentKinds.resumeArgs(kind.lowercase())
    }

    fun openAgent(agent: Agent, hostFilterId: String? = null) {
        val state = _ui.value
        val live = state.repo.agents.firstOrNull { it.key == agent.key } ?: return
        rememberPage()
        showAgent(live)
    }

    /**
     * Opens the detail page for any pane of a host, agent or not. A shell pane reads and types
     * through the same page: the page entity is the pane, the agent is only its chat side.
     */
    fun openPane(hostId: String, paneId: String, pushPage: Boolean = true) {
        val runtime = _ui.value.repo.hosts.firstOrNull { it.host.id == hostId }
        if (runtime == null) {
            _ui.update { it.copy(message = Str.get(R.string.vm_host_not_found)) }
            return
        }
        if (pushPage) rememberPage()
        val key = "$hostId:$paneId"
        val live = _ui.value.repo.agents.firstOrNull { it.key == key }
        if (live != null) {
            showAgent(live)
            return
        }
        val pane = runtime.structure.panes.firstOrNull { it.id == paneId }
        if (pane == null) {
            _ui.update { it.copy(message = Str.get(R.string.vm_pane_not_found)) }
            return
        }
        showAgent(paneDetailAgent(runtime, pane))
    }

    private fun showAgent(agent: Agent) {
        leavePage()
        _ui.update {
            it.copy(screen = Screen.Detail(agent.key), detail = decorate(DetailState(agent = agent, loading = true,
                readingPosition = readingPositions[agent.key] ?: ReadingPosition(),
                readingPaused = readingPositions[agent.key]?.paused == true)))
        }
        loadPane(agent)
    }

    fun reloadPane() {
        val agent = _ui.value.detail.agent ?: return
        loadPane(agent)
    }

    fun explainAgent() {
        val page = ticket() ?: return
        val agent = _ui.value.detail.agent ?: return
        if (_ui.value.detail.explainLoading || !foreground) return
        val request = ++explainGeneration
        updateDetail(page) { it.copy(explainLoading = true, explain = Str.get(R.string.vm_explain_loading)) }
        explainJob = viewModelScope.launch {
            try {
                val json = repository.explain(agent)
                currentCoroutineContext().ensureActive()
                if (request == explainGeneration) updateDetail(page) { it.copy(explainLoading = false, explain = humanizeExplain(json)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (request == explainGeneration) updateDetail(page) { it.copy(explainLoading = false, explain = Str.get(R.string.vm_explain_failed, dev.herdwatch.data.humanize(t))) }
            }
        }
    }

    fun dismissExplain() {
        explainGeneration++
        explainJob?.cancel()
        _ui.update { it.copy(detail = it.detail.copy(explain = null, explainLoading = false)) }
    }

    /** Quiet reads still have their own request generation and retain visible read errors. */
    fun refreshPaneQuietly() {
        val detail = _ui.value.detail
        val agent = detail.agent ?: return
        if (!detail.readingPaused && !detail.sending && !detail.loading && loadJob?.isActive != true) loadPane(agent, quiet = true)
    }

    /** Freeze evidence during selection or deliberate reading; explicit refresh remains available. */
    fun setReadingPaused(paused: Boolean) {
        val page = ticket() ?: return
        if (_ui.value.detail.readingPaused == paused) return
        // The first load still supplies evidence when TalkBack pauses a newly opened page.
        // Once evidence is visible, invalidate an in-flight quiet read before freezing it.
        // Freezing the view must not invalidate an approval's preflight/post-read.
        val freezeVisible = paused && _ui.value.detail.loadedAt != null && !_ui.value.detail.sending
        if (freezeVisible) {
            readGeneration++
            loadJob?.cancel()
        }
        updateDetail(page) {
            val sameSequence = _ui.value.repo.agents.any { live -> live.key == page.agentKey && live.stateChangeSeq == it.shownSeq }
            it.copy(readingPaused = paused, loading = if (freezeVisible) false else it.loading,
                stale = it.stale || (freezeVisible && !sameSequence))
        }
        if (!paused && !_ui.value.detail.sending) _ui.value.detail.agent?.let { loadPane(it, quiet = true) }
    }

    private fun applyPane(page: PageTicket, pane: PaneText, shownSeq: Long, paneOnly: Boolean = false) {
        if (!matches(page)) return
        operations.observe(page.agentKey, ApprovalPolicy.evidence(pane))
        updateDetail(page) { current ->
            reconcileSent(current, pane.text).copy(
                text = pane.text, ask = ApprovalPolicy.evidence(pane).ask,
                loading = false, revision = pane.revision, shownSeq = shownSeq,
                truncated = pane.truncated, loadedAt = System.currentTimeMillis(),
                // A pane read and the agent snapshot are separate requests. A status
                // transition during the read leaves their ordering unknown. A pane with no
                // agent has no status to compare, so only foreground affects its freshness.
                stale = !foreground || (!paneOnly && _ui.value.repo.agents.none { agent ->
                    agent.key == page.agentKey && agent.stateChangeSeq == shownSeq
                }), error = null,
            )
        }
    }

    private fun loadPane(agent: Agent, quiet: Boolean = false) {
        val page = ticket() ?: return
        if (!matches(page) || page.agentKey != agent.key || !foreground) return
        val lines = _ui.value.detail.historyLines
        val request = ++readGeneration
        loadJob?.cancel()
        updateDetail(page) { it.copy(loading = !quiet, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val pane = repository.readPane(agent, lines)
                currentCoroutineContext().ensureActive()
                if (request == readGeneration) applyPane(page, pane, agent.stateChangeSeq, paneOnly = !agent.hasAgent)
            } catch (cancelled: CancellationException) {
                if (request == readGeneration) updateDetail(page) { it.copy(loading = false, stale = true, error = Str.get(R.string.vm_read_cancelled)) }
                throw cancelled
            } catch (t: Throwable) {
                if (request == readGeneration) updateDetail(page) {
                    it.copy(loading = false, stale = true, error = Str.get(R.string.vm_read_failed, dev.herdwatch.data.humanize(t)))
                }
            }
        }
    }

    /**
     * Reads further back in the terminal buffer.
     *
     * The bridge has no cursor, so "older" means "ask for a bigger tail". Doubling the request is
     * the honest way to do it: every response is a superset of the previous one, and the reader
     * keeps its place because the page re-anchors on the new height.
     */
    fun loadMoreHistory() {
        val detail = _ui.value.detail
        val agent = detail.agent ?: return
        if (detail.loading || detail.sending) return
        // `truncated == false` means the host already returned its whole buffer; there is nothing
        // further back to fetch and asking again would be a round trip for nothing.
        if (!detail.truncated && detail.text.isNotBlank()) return
        val next = (detail.historyLines * 2).coerceAtMost(MAX_HISTORY_LINES)
        if (next == detail.historyLines) return
        updateDetail(ticket() ?: return) { it.copy(historyLines = next) }
        loadPane(agent, quiet = true)
    }

    private fun recordSent(agentKey: String, text: String) {
        updateDetail(PageTicket(agentKey, pageGeneration)) {
            it.copy(sent = (it.sent + SentMessage(nextSendId++, text, System.currentTimeMillis())).takeLast(12))
        }
    }

    /**
     * Marks a local send as echoed once the screen carries it.
     *
     * Matching is on the tail of the message, because a TUI wraps long prompts across rows and the
     * snapshot may only hold the last few lines of it.
     */
    private fun reconcileSent(detail: DetailState, screen: String): DetailState {
        if (detail.sent.isEmpty()) return detail
        val flat = screen.filterNot { it.isWhitespace() }
        var changed = false
        val next = detail.sent.map { message ->
            if (message.echoed) return@map message
            val probe = message.text.filterNot { it.isWhitespace() }.takeLast(24)
            if (probe.isNotEmpty() && flat.contains(probe)) {
                changed = true
                message.copy(echoed = true)
            } else message
        }
        return if (changed) detail.copy(sent = next) else detail
    }

    fun setDraft(text: String) {
        val page = ticket() ?: return
        operations.setDraft(page.agentKey, text)
        syncOperation(page.agentKey)
    }

    private fun beginOperation(agent: Agent): OperationState.Record? {
        if (!connected(agent)) return null
        val record = operations.begin(agent.key) ?: return null
        // Invalidate reads already in flight, so they cannot restore pre-write evidence.
        readGeneration++
        loadJob?.cancel()
        _ui.update { it.copy(detail = decorate(it.detail.copy(loading = false, stale = true, error = null))) }
        return record
    }

    private fun finishOperation(agent: Agent, record: OperationState.Record, notice: String, uncertain: Boolean, blocked: ApprovalEvidence? = null) {
        operations.finish(agent.key, record.id, notice, uncertain, blocked)
        syncOperation(agent.key)
    }

    /** Call only after the user has read the remote state and explicitly acknowledged it. */
    fun confirmOperationVerified() {
        val page = ticket() ?: return
        val detail = _ui.value.detail
        if (!decorate(detail).canVerifyOperation) return
        operations.verified(page.agentKey)
        syncOperation(page.agentKey)
    }

    fun choose(option: AskOption) {
        val page = ticket() ?: return
        val detail = decorate(_ui.value.detail)
        val agent = detail.agent ?: return
        val frozen = evidence(detail)
        val invalid = ApprovalPolicy.validate(frozen, option, frozen)
        if (invalid != null || !detail.canChoose) {
            updateDetail(page) { it.copy(error = invalid ?: Str.get(R.string.vm_approval_verify_first)) }
            return
        }
        val operation = beginOperation(agent) ?: run {
            updateDetail(page) { it.copy(error = Str.get(R.string.vm_approval_not_sent)) }
            return
        }
        val preflightRequest = readGeneration
        viewModelScope.launch {
            var attempted = false
            var accepted = false
            var notice = Str.get(R.string.vm_approval_cancelled)
            var uncertain = false
            var blocked: ApprovalEvidence? = null
            try {
                val fresh = ApprovalPolicy.evidence(repository.readPane(agent, PANE_READ_LINES))
                currentCoroutineContext().ensureActive()
                // A preflight completing on another page must never initiate a write.
                if (!matches(page) || !connected(agent) || preflightRequest != readGeneration) return@launch
                val current = _ui.value.repo.agents.firstOrNull { it.key == agent.key }
                val reason = ApprovalPolicy.validate(frozen, option, fresh)
                    ?: if (current?.status != AgentStatus.WAITING && current?.status != AgentStatus.UNKNOWN) Str.get(R.string.vm_not_waiting_not_sent) else null
                if (reason != null) {
                    applyPane(page, fresh.pane, agent.stateChangeSeq)
                    notice = reason
                    updateDetail(page) { it.copy(error = reason) }
                    return@launch
                }
                attempted = true

                blocked = fresh
                repository.sendKeys(agent, option.keys).getOrThrow()
                accepted = true
                currentCoroutineContext().ensureActive()
                // Successful writes stay silent; the screen itself is the receipt. Only an
                // unresolved outcome earns a line, because a disclaimer makes people doubt the app.
                notice = ""
                uncertain = true
                delay(700)
                if (!matches(page) || !foreground) return@launch
                val postRequest = ++readGeneration
                loadJob?.cancel()
                val after = repository.readPane(agent, PANE_READ_LINES)
                currentCoroutineContext().ensureActive()
                if (!matches(page) || postRequest != readGeneration) return@launch
                applyPane(page, after, agent.stateChangeSeq)
                if (after.truncated) {
                    notice = ""
                } else if (ApprovalPolicy.sameContext(fresh, ApprovalPolicy.evidence(after))) {
                    notice = Str.get(R.string.vm_key_accepted_unchanged)
                } else {
                    notice = ""
                    uncertain = false
                }
            } catch (cancelled: CancellationException) {
                notice = when {
                    accepted -> Str.get(R.string.vm_key_accepted_verify_cancelled)
                    attempted -> Str.get(R.string.vm_approval_interrupted)
                    else -> Str.get(R.string.vm_approval_verify_cancelled)
                }
                uncertain = attempted
                throw cancelled
            } catch (t: Throwable) {
                uncertain = attempted
                notice = when {
                    accepted -> Str.get(R.string.vm_key_accepted_read_failed, dev.herdwatch.data.humanize(t))
                    attempted -> Str.get(R.string.vm_approval_uncertain, dev.herdwatch.data.humanize(t))
                    else -> Str.get(R.string.vm_reread_failed_not_sent, dev.herdwatch.data.humanize(t))
                }
            } finally {
                finishOperation(agent, operation, notice, uncertain, blocked)
            }
        }
    }

    fun sendRawKeys(keys: List<String>) {
        if (keys.isEmpty()) return
        val capturedKeys = keys.toList()
        sendManual(Str.get(R.string.vm_label_keys), rpc = { repository.sendKeys(it, capturedKeys) })
    }

    /** Terminal input for a pane: literal text into the PTY, no agent interpretation. */
    fun sendInput(text: String, submit: Boolean = true) {
        val value = text.trimEnd()
        if (value.isEmpty()) return
        val agent = _ui.value.detail.agent ?: return
        // A newline inside `text` is not Enter: `pane.send_input` types the characters and drops the
        // line break, so the command sat at the prompt unexecuted until the user pressed the key
        // strip. Submit is a key press, not a character.
        val payload = value
        val submitKeys = if (submit) listOf("enter") else emptyList()
        // Clear only once the RPC was accepted: clearing up front threw the typed command away
        // whenever the pane was offline, so the user lost text and nothing was sent.
        operations.setDraft(agent.key, text)
        syncOperation(agent.key)
        val captured = operations.draft(agent.key)
        sendManual(Str.get(R.string.vm_label_input), rpc = { repository.sendInput(it, payload, submitKeys) }, onRpcAccepted = {
            if (operations.draft(agent.key) == captured) operations.clearDraft(agent.key, captured)
            syncOperation(agent.key)
        })
    }

    fun sendPrompt(text: String = _ui.value.detail.draft, onSent: () -> Unit = {}) {
        val value = text.trim()
        if (value.isEmpty()) return
        val agent = _ui.value.detail.agent ?: return
        if (!decorate(_ui.value.detail).canSend) return
        // Explicit-text callers also get failure-safe draft storage.
        operations.setDraft(agent.key, text)
        syncOperation(agent.key)
        val captured = operations.draft(agent.key)
        var unchanged = false
        sendManual(Str.get(R.string.vm_label_reply), rpc = { repository.prompt(it, value) }, onRpcAccepted = {
            recordSent(agent.key, value)
            unchanged = operations.draft(agent.key) == captured
            if (captured.text == text) operations.clearDraft(agent.key, captured)
            syncOperation(agent.key)
        }) { page ->
            // Legacy composition-local callbacks must never clear another page or a newer draft.
            if (matches(page) && unchanged && (captured.text == text || captured.text.isEmpty())) onSent()
        }
    }

    private fun sendManual(
        label: String,
        rpc: suspend (Agent) -> Result<Unit>,
        onRpcAccepted: () -> Unit = {},
        onAccepted: (PageTicket) -> Unit = {},
    ) {
        val page = ticket() ?: return
        val agent = _ui.value.detail.agent ?: return
        val frozen = evidence(_ui.value.detail)
        val operation = beginOperation(agent) ?: return
        viewModelScope.launch {
            var attempted = false
            var accepted = false
            var notice = Str.get(R.string.vm_send_cancelled, label)
            try {
                currentCoroutineContext().ensureActive()
                if (!matches(page) || !connected(agent)) return@launch
                attempted = true
                operationRpc(request = { rpc(agent) }) {
                    accepted = true
                    onRpcAccepted()
                }
                notice = ""
                onAccepted(page)
                delay(600)
                if (matches(page) && foreground) loadPane(agent)
            } catch (cancelled: CancellationException) {
                notice = if (accepted) Str.get(R.string.vm_send_accepted_verify_cancelled, label) else if (attempted) Str.get(R.string.vm_send_interrupted, label) else Str.get(R.string.vm_send_cancelled, label)
                throw cancelled
            } catch (t: Throwable) {
                notice = if (accepted) Str.get(R.string.vm_send_accepted_followup_failed, label, dev.herdwatch.data.humanize(t)) else if (attempted) Str.get(R.string.vm_send_uncertain, label, dev.herdwatch.data.humanize(t)) else Str.get(R.string.vm_send_not_sent, label, dev.herdwatch.data.humanize(t))
            } finally {
                finishOperation(agent, operation, notice, attempted && !accepted, if (attempted) frozen else null)
            }
        }
    }

    fun saveHost(
        id: String?,
        label: String,
        host: String,
        port: String,
        user: String,
        authType: AuthType,
        secret: String,
        passphrase: String,
        session: String,
        herdrCommand: String,
        fallbackHost: String = "",
        fallbackPort: String = "",
    ) {
        if (_ui.value.addingHost) return
        _ui.update { it.copy(addingHost = true) }
        viewModelScope.launch {
            try {
                val config = HostConfig(
                    id = id ?: repository.newId(),
                    label = label.trim().ifBlank { host.trim() },
                    host = host.trim(),
                    port = port.toIntOrNull() ?: 22,
                    user = user.trim(),
                    authType = authType,
                    secret = secret,
                    passphrase = passphrase,
                    session = session.trim(),
                    herdrCommand = herdrCommand.trim().ifBlank { "herdr" },
                    fingerprint = _ui.value.editingHost?.fingerprint,
                    fallbackHost = fallbackHost.trim(),
                    fallbackPort = fallbackPort.toIntOrNull()?.takeIf { it in 1..65535 } ?: 0,
                )
                if (id == null) {
                    repository.addHost(config)
                } else {
                    repository.disconnectHost(config.id)
                    repository.updateHost(config)
                }
                _ui.update {
                    it.copy(
                        addingHost = false,
                        showAddHost = false,
                        editingHost = null,
                        message = if (id == null) Str.get(R.string.vm_host_added, config.label) else Str.get(R.string.vm_host_updated, config.label),
                    )
                }
                if (id != null) repository.connectHost(config.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(addingHost = false, message = Str.get(R.string.vm_save_failed, dev.herdwatch.data.humanize(t)))
                }
            } finally {
                _ui.update { it.copy(addingHost = false) }
            }
        }
    }

    fun deleteHost(hostId: String) {
        screenMutation(Str.get(R.string.vm_host_removed)) { repository.removeHost(hostId) }
    }

    fun reconnect(hostId: String) {
        if (!reconnecting.add(hostId)) return
        val page = ticket()
        _ui.update { it.copy(detail = decorate(it.detail)) }
        viewModelScope.launch {
            try {
                repository.connectHost(hostId)
                currentCoroutineContext().ensureActive()
                if (page != null && matches(page) && foreground && repository.isHostReady(hostId)) {
                    _ui.value.detail.agent?.takeIf { it.hostId == hostId }?.let { loadPane(it) }
                }
            } finally {
                reconnecting.remove(hostId)
                _ui.update { it.copy(detail = decorate(it.detail)) }
            }
        }
    }

    fun eatMessage() = _ui.update { it.copy(message = null) }

    class Factory(private val app: HerdwatchApp) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            AppViewModel(
                app.repository,
                app.preferencesStore,
                app.appVersion,
                AndroidUpdateInstaller(app),
            ) as T
    }
}
