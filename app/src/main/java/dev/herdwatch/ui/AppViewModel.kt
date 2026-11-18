package dev.herdwatch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.herdwatch.HerdwatchApp
import dev.herdwatch.data.HerdRepository
import dev.herdwatch.data.RepoState
import dev.herdwatch.domain.ApprovalEvidence
import dev.herdwatch.domain.ApprovalPolicy
import dev.herdwatch.domain.OperationState
import dev.herdwatch.domain.PageTicket
import dev.herdwatch.domain.operationRpc
import dev.herdwatch.model.Agent
import dev.herdwatch.model.AgentStatus
import dev.herdwatch.model.Ask
import dev.herdwatch.model.AskOption
import dev.herdwatch.model.PaneText
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

enum class InboxFilter { ALL, ATTENTION }

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
    val canOpenPrevious: Boolean = false,
    val canOpenNext: Boolean = false,
    val queuePosition: Int = 0,
    val queueSize: Int = 0,
    val readingPosition: ReadingPosition = ReadingPosition(),
)

data class UiState(
    val repo: RepoState = RepoState(),
    val screen: Screen = Screen.Inbox,
    val filter: InboxFilter = InboxFilter.ATTENTION,
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

class AppViewModel(private val repository: HerdRepository) : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var loadJob: Job? = null
    private var explainJob: Job? = null
    private var pageGeneration = 0L
    private var readGeneration = 0L
    private var explainGeneration = 0L
    private val operations = OperationState()
    private var traversal = emptyList<String>()
    private var structureOrigin: Screen = Screen.Hosts
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
        repo.agents.any { it.key == agent.key }

    private var foreground = true

    private fun decorate(detail: DetailState, repo: RepoState = _ui.value.repo): DetailState {
        val agent = detail.agent ?: return detail
        val record = operations.record(agent.key)
        val index = traversal.indexOf(agent.key)
        val liveKeys = repo.agents.map { it.key }.toSet()
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
            canOpenPrevious = index > 0 && traversal.take(index).any { it in liveKeys },
            canOpenNext = index >= 0 && traversal.drop(index + 1).any { it in liveKeys },
            queuePosition = traversal.filter { it in liveKeys }.indexOf(agent.key) + 1,
            queueSize = traversal.count { it in liveKeys },
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
                            decorate(current.detail.copy(
                                agent = fresh ?: current.detail.agent,
                                stale = current.detail.stale || fresh == null ||
                                    (current.detail.readingPaused && fresh.stateChangeSeq != (current.detail.shownSeq ?: fresh.stateChangeSeq)) ||
                                    repo.hosts.none { it.host.id == fresh.hostId && it.conn == ConnState.Connected },
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
        repository.setForeground(true)
        val capturedPage = ticket()
        viewModelScope.launch {
            repository.connectAll()
            currentCoroutineContext().ensureActive()
            capturedPage?.let { page ->
                if (matches(page)) _ui.value.detail.agent?.let { loadPane(it) }
            }
        }
    }

    fun onBackground() {
        foreground = false
        leavePage()
        _ui.update { it.copy(detail = decorate(it.detail.copy(stale = true, loading = false, explainLoading = false))) }
        repository.setForeground(false)
    }

    fun refresh() {
        viewModelScope.launch { repository.refreshAll() }
    }

    fun dismissError() = repository.dismissError()

    fun setFilter(filter: InboxFilter) = _ui.update { it.copy(filter = filter) }
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
            _ui.value.repo.agents.firstOrNull { it.key == screen.agentKey }?.let { openAgent(it) }
            return
        }
        leavePage()
        _ui.update { it.copy(screen = screen, detail = DetailState()) }
    }

    fun back() {
        leavePage()
        traversal = emptyList()
        val current = _ui.value.screen
        val destination = when (current) {
            is Screen.Structure -> structureOrigin
            Screen.Settings -> Screen.Hosts
            else -> Screen.Inbox
        }
        _ui.update {
            it.copy(
                screen = destination,
                detail = DetailState(),
            )
        }
    }

    fun openStructure(hostId: String) {
        if (_ui.value.screen !is Screen.Structure) structureOrigin = _ui.value.screen
        navigate(Screen.Structure(hostId))
    }

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
        screenMutation("启动请求已接受；请核对新任务。") {
            repository.startInNewTab(hostId, workspaceId, kind, name.ifBlank { kind }, args).getOrThrow()
        }
    }

    fun startAgent(request: StartAgentRequest, paneId: String, kind: String, name: String, args: List<String>) {
        _ui.update { it.copy(startAgent = null) }
        screenMutation("启动请求已接受：$name；请核对远端状态。") {
            repository.startAgent(request.hostId, paneId, kind, name.ifBlank { kind }, args).getOrThrow()
        }
    }

    fun restoreAgent(agent: Agent) {
        val args = resumeArgs(agent.kind)
        screenMutation("恢复请求已接受：${agent.name}；请核对远端状态。") {
            repository.startAgent(agent.hostId, agent.paneId, agent.kind, agent.name, args).getOrThrow()
        }
    }

    fun createWorkspace(hostId: String) = structureAction(hostId, "已新建 workspace") {
        repository.createWorkspace(hostId, null)
    }

    fun createTab(hostId: String, workspaceId: String) = structureAction(hostId, "已新建 tab") {
        repository.createTab(hostId, workspaceId, null)
    }

    fun splitPane(hostId: String, paneId: String, direction: String) =
        structureAction(hostId, if (direction == "right") "已向右拆分" else "已向下拆分") {
            repository.splitPane(hostId, paneId, direction)
        }

    fun closePane(hostId: String, paneId: String) = structureAction(hostId, "已关闭 pane") {
        repository.closePane(hostId, paneId)
    }

    fun closeTab(hostId: String, tabId: String) = structureAction(hostId, "已关闭 tab") {
        repository.closeTab(hostId, tabId)
    }

    fun closeWorkspace(hostId: String, workspaceId: String) = structureAction(hostId, "已关闭 workspace") {
        repository.closeWorkspace(hostId, workspaceId)
    }

    fun focusPane(hostId: String, paneId: String) = structureAction(hostId, "已在主机上聚焦") {
        repository.focusPane(hostId, paneId)
    }

    fun focusTab(hostId: String, tabId: String) = structureAction(hostId, "已切换到 tab") {
        repository.focusTab(hostId, tabId)
    }

    fun focusWorkspace(hostId: String, workspaceId: String) = structureAction(hostId, "已切换到 workspace") {
        repository.focusWorkspace(hostId, workspaceId)
    }

    fun renameWorkspace(hostId: String, workspaceId: String, label: String) =
        structureAction(hostId, "已重命名工作区") { repository.renameWorkspace(hostId, workspaceId, label) }

    fun renameTab(hostId: String, tabId: String, label: String) =
        structureAction(hostId, "已重命名标签页") { repository.renameTab(hostId, tabId, label) }

    fun renamePane(hostId: String, paneId: String, label: String) =
        structureAction(hostId, "已重命名分屏") { repository.renamePane(hostId, paneId, label) }

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
                finishOperation(agent, operation, if (accepted) "重命名请求已接受。" else "重命名结果不确定，请核对远端状态。", !accepted)
            }
        }
    }

    private fun structureAction(hostId: String, success: String, block: suspend () -> Unit) {
        screenMutation("$success（请求已接受，请核对同步状态）", block)
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
                show(success)
            } catch (cancelled: CancellationException) {
                show("请求已中断，结果可能不确定；请核对远端状态，勿直接重复操作。")
                throw cancelled
            } catch (t: Throwable) {
                show("操作结果待核实，请检查远端状态：${dev.herdwatch.data.humanize(t)}")
            }
        }
    }

    private fun humanizeExplain(json: kotlinx.serialization.json.JsonObject): String {
        fun str(key: String) = json[key]?.jsonPrimitive?.contentOrNull
        val lines = mutableListOf<String>()
        lines += "Herdr 判定：${str("state") ?: "unknown"}"
        str("matched_rule")?.takeIf { it.isNotBlank() }?.let { lines += "命中规则：$it" }
        if (json["screen_detection_skipped"]?.jsonPrimitive?.booleanOrNull == true) {
            val why = str("screen_detection_skip_reason")
            lines += if (why == "full_lifecycle_hook_authority") {
                "屏幕识别已跳过：状态直接来自 agent 的生命周期钩子，不靠猜屏幕。"
            } else {
                "屏幕识别已跳过" + (why?.let { "：$it" } ?: "。")
            }
        }
        listOf("visible_blocker" to "屏幕上有阻塞", "visible_idle" to "屏幕上是空闲", "visible_working" to "屏幕上在工作")
            .filter { json[it.first]?.jsonPrimitive?.booleanOrNull == true }
            .forEach { lines += "可见信号：${it.second}" }
        str("fallback_reason")?.takeIf { it.isNotBlank() }?.let { lines += "降级原因：$it" }
        str("warning")?.takeIf { it.isNotBlank() }?.let { lines += "注意：$it" }
        return lines.joinToString("\n")
    }

    companion object {
        fun resumeArgs(kind: String): List<String> =
            dev.herdwatch.domain.AgentKinds.resumeArgs(kind.lowercase())
    }

    fun openAdjacentAgent(delta: Int) {
        if (delta == 0) return
        val current = _ui.value.detail.agent?.key ?: return
        val index = traversal.indexOf(current)
        if (index < 0) return
        val candidates = if (delta > 0) traversal.drop(index + 1) else traversal.take(index).asReversed()
        val live = _ui.value.repo.agents.associateBy { it.key }
        val target = candidates.firstNotNullOfOrNull { live[it] } ?: return
        showAgent(target)
    }

    fun openAgent(agent: Agent) {
        val state = _ui.value
        val live = state.repo.agents.firstOrNull { it.key == agent.key } ?: return
        traversal = inboxQueue(state.repo.agents, state.filter, state.query).map { it.key }
            .let { if (live.key in it) it else listOf(live.key) }
        showAgent(live)
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
        updateDetail(page) { it.copy(explainLoading = true, explain = "Herdr 正在解释这个状态…") }
        explainJob = viewModelScope.launch {
            try {
                val json = repository.explain(agent)
                currentCoroutineContext().ensureActive()
                if (request == explainGeneration) updateDetail(page) { it.copy(explainLoading = false, explain = humanizeExplain(json)) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                if (request == explainGeneration) updateDetail(page) { it.copy(explainLoading = false, explain = "读不到解释：${dev.herdwatch.data.humanize(t)}") }
            }
        }
    }

    fun dismissExplain() {
        explainGeneration++
        explainJob?.cancel()
        _ui.update { it.copy(detail = it.detail.copy(explain = null, explainLoading = false)) }
    }

    fun loadMore() {
        val agent = _ui.value.detail.agent ?: return
        loadPane(agent, 600)
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
        val freezeVisible = paused && _ui.value.detail.loadedAt != null
        if (freezeVisible) {
            readGeneration++
            loadJob?.cancel()
        }
        updateDetail(page) { it.copy(readingPaused = paused, loading = if (freezeVisible) false else it.loading) }
        if (!paused) _ui.value.detail.agent?.let { loadPane(it, quiet = true) }
    }

    private fun applyPane(page: PageTicket, pane: PaneText, shownSeq: Long) {
        if (!matches(page)) return
        operations.observe(page.agentKey, ApprovalPolicy.evidence(pane))
        updateDetail(page) {
            it.copy(
                text = pane.text, ask = ApprovalPolicy.evidence(pane).ask,
                loading = false, revision = pane.revision, shownSeq = shownSeq,
                truncated = pane.truncated, loadedAt = System.currentTimeMillis(),
                // A pane read and the agent snapshot are separate requests. A status
                // transition during the read leaves their ordering unknown.
                stale = !foreground || _ui.value.repo.agents.none { agent ->
                    agent.key == page.agentKey && agent.stateChangeSeq == shownSeq
                }, error = null,
            )
        }
    }

    private fun loadPane(agent: Agent, lines: Int = 400, quiet: Boolean = false) {
        val page = ticket() ?: return
        if (!matches(page) || page.agentKey != agent.key || !foreground) return
        val request = ++readGeneration
        loadJob?.cancel()
        updateDetail(page) { it.copy(loading = !quiet, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val pane = repository.readPane(agent, lines)
                currentCoroutineContext().ensureActive()
                if (request == readGeneration) applyPane(page, pane, agent.stateChangeSeq)
            } catch (cancelled: CancellationException) {
                if (request == readGeneration) updateDetail(page) { it.copy(loading = false, stale = true, error = "读取已取消，显示上次内容。") }
                throw cancelled
            } catch (t: Throwable) {
                if (request == readGeneration) updateDetail(page) {
                    it.copy(loading = false, stale = true, error = "读取失败，显示上次内容：${dev.herdwatch.data.humanize(t)}")
                }
            }
        }
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
            updateDetail(page) { it.copy(error = invalid ?: "请先核对远端操作结果或刷新当前证据，再进行辅助审批。") }
            return
        }
        val operation = beginOperation(agent) ?: return
        val preflightRequest = readGeneration
        viewModelScope.launch {
            var attempted = false
            var accepted = false
            var notice = "审批已取消，没有发送。"
            var uncertain = false
            var blocked: ApprovalEvidence? = null
            try {
                val fresh = ApprovalPolicy.evidence(repository.readPane(agent))
                currentCoroutineContext().ensureActive()
                // A preflight completing on another page must never initiate a write.
                if (!matches(page) || !connected(agent) || preflightRequest != readGeneration) return@launch
                val current = _ui.value.repo.agents.firstOrNull { it.key == agent.key }
                val reason = ApprovalPolicy.validate(frozen, option, fresh)
                    ?: if (current?.status != AgentStatus.WAITING && current?.status != AgentStatus.UNKNOWN) "它已不在等待审批，没有发送。" else null
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
                notice = "按键请求已接受：${option.label}。远端执行结果待核实。"
                uncertain = true
                delay(700)
                if (!matches(page) || !foreground) return@launch
                val postRequest = ++readGeneration
                loadJob?.cancel()
                val after = repository.readPane(agent)
                currentCoroutineContext().ensureActive()
                if (!matches(page) || postRequest != readGeneration) return@launch
                applyPane(page, after, agent.stateChangeSeq)
                if (after.truncated) {
                    notice = "按键请求已接受，但后续屏幕不完整；执行结果待核实，请勿重复审批。"
                } else if (ApprovalPolicy.sameContext(fresh, ApprovalPolicy.evidence(after))) {
                    notice = "按键请求已接受，屏幕暂未变化；执行结果待核实，请勿重复审批。"
                } else {
                    notice = "按键请求已接受，已观察到屏幕变化；这不代表远端任务执行成功。"
                    uncertain = false
                }
            } catch (cancelled: CancellationException) {
                notice = when {
                    accepted -> "按键请求已接受，后续核对已取消；执行结果不确定，请勿重复审批。"
                    attempted -> "审批请求已中断，是否已接受不确定；请核对后再操作。"
                    else -> "审批核对已取消，没有发送。"
                }
                uncertain = attempted
                throw cancelled
            } catch (t: Throwable) {
                uncertain = attempted
                notice = when {
                    accepted -> "按键请求已接受，但后续读取失败；执行结果待核实。${dev.herdwatch.data.humanize(t)}"
                    attempted -> "审批结果不确定，请核对远端状态，勿直接重发。${dev.herdwatch.data.humanize(t)}"
                    else -> "没能重读屏幕，没有发送。${dev.herdwatch.data.humanize(t)}"
                }
            } finally {
                finishOperation(agent, operation, notice, uncertain, blocked)
            }
        }
    }

    fun sendRawKeys(keys: List<String>) {
        if (keys.isEmpty()) return
        val capturedKeys = keys.toList()
        sendManual("按键", rpc = { repository.sendKeys(it, capturedKeys) })
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
        sendManual("回复", rpc = { repository.prompt(it, value) }, onRpcAccepted = {
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
            var notice = "$label 已取消，没有发送。"
            try {
                currentCoroutineContext().ensureActive()
                if (!matches(page) || !connected(agent)) return@launch
                attempted = true
                operationRpc(request = { rpc(agent) }) {
                    accepted = true
                    onRpcAccepted()
                }
                notice = "$label 请求已接受；远端执行结果仍需核对。"
                onAccepted(page)
                delay(600)
                if (matches(page) && foreground) loadPane(agent)
            } catch (cancelled: CancellationException) {
                notice = if (accepted) "$label 请求已接受，后续核对已取消。" else if (attempted) "$label 请求中断，是否已接受不确定；请核对远端状态。" else "$label 已取消，没有发送。"
                throw cancelled
            } catch (t: Throwable) {
                notice = if (accepted) "$label 请求已接受，但后续处理失败：${dev.herdwatch.data.humanize(t)}" else if (attempted) "$label 结果不确定，请核对后再操作：${dev.herdwatch.data.humanize(t)}" else "$label 没有发送：${dev.herdwatch.data.humanize(t)}"
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
                        message = if (id == null) "已添加 ${config.label}" else "已更新 ${config.label}",
                    )
                }
                if (id != null) repository.connectHost(config.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                _ui.update {
                    it.copy(addingHost = false, message = "保存失败：${dev.herdwatch.data.humanize(t)}")
                }
            } finally {
                _ui.update { it.copy(addingHost = false) }
            }
        }
    }

    fun deleteHost(hostId: String) {
        screenMutation("已移除主机") { repository.removeHost(hostId) }
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
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AppViewModel(app.repository) as T
    }
}
