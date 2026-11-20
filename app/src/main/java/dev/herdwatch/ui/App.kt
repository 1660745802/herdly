package dev.herdwatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.herdwatch.ConnectionService
import dev.herdwatch.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdwatch.model.Screen
import androidx.compose.ui.unit.dp

private data class Tab(val label: String, val icon: dev.herdwatch.ui.design.NeoGlyph, val screen: Screen)

@Composable
fun HerdwatchRoot(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val seen by vm.seenMarks.collectAsStateWithLifecycle()
    val terminal by vm.terminalState.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val version = vm.version
    val snackbar = remember { SnackbarHostState() }
    val processLifecycle = ProcessLifecycleOwner.get()
    val context = LocalContext.current

    // The keep-alive service starts while the app is visible (starting a foreground service from
    // the background is restricted on Android 12+) and then survives `ON_STOP`, which is exactly
    // when the connection used to be dropped. No hosts, no reason to hold the process.
    val hasHosts = ui.repo.hosts.isNotEmpty()
    val keepAliveFailed = stringResource(R.string.service_start_failed)
    // Keyed on the Activity's foreground state too: an Android 14 `dataSync` budget can end the
    // service while the user is away, and returning must restart it. A background start is refused
    // by the platform, so only the visible transition starts the service; ON_STOP just leaves the
    // running one alone.
    LaunchedEffect(hasHosts, ui.repo.foreground) {
        if (!hasHosts) {
            runCatching { ConnectionService.stop(context) }
            return@LaunchedEffect
        }
        if (!ui.repo.foreground) return@LaunchedEffect
        // Android can refuse the start (background restrictions, a revoked notification channel on
        // some OEMs). Swallowing that left the user believing the connection would survive, so say
        // what actually happens instead: it stays up only while the app is on screen.
        runCatching { ConnectionService.start(context) }.onFailure {
            vm.say(keepAliveFailed)
        }
    }

    DisposableEffect(processLifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onForeground()
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                else -> Unit
            }
        }
        processLifecycle.lifecycle.addObserver(observer)
        onDispose { processLifecycle.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ui.message, ui.messageSeq) {
        ui.message?.let {
            snackbar.showSnackbar(it)
            vm.eatMessage()
        }
    }
    LaunchedEffect(ui.repo.error) {
        ui.repo.error?.let {
            snackbar.showSnackbar(it)
            vm.dismissError()
        }
    }

    BackHandler(
        enabled = ui.screen is Screen.Detail || ui.screen is Screen.Structure ||
            ui.screen is Screen.Terminal || ui.screen == Screen.Settings,
    ) { vm.back() }

    val tabInbox = stringResource(R.string.app_tab_inbox)
    val tabHosts = stringResource(R.string.app_tab_hosts)
    val tabSettings = stringResource(R.string.app_tab_settings)
    val newTaskLabel = stringResource(R.string.app_new_task)
    val tabs = remember(tabInbox, tabHosts, tabSettings) {
        listOf(
            Tab(tabInbox, dev.herdwatch.ui.design.NeoGlyph.Tray, Screen.Inbox),
            Tab(tabHosts, dev.herdwatch.ui.design.NeoGlyph.Server, Screen.Hosts),
            Tab(tabSettings, dev.herdwatch.ui.design.NeoGlyph.Sliders, Screen.Settings),
        )
    }
    val showTabs = ui.screen == Screen.Inbox || ui.screen == Screen.Hosts || ui.screen == Screen.Settings
    val pageState = rememberSaveableStateHolder()
    // Page transition durations, already scaled by the reduced-motion preference.
    val txEnter = motionMillis(180)
    val txFadeMid = motionMillis(160)
    val txFadeShort = motionMillis(100)
    val txExit = motionMillis(120)
    val txSlide = motionMillis(240)
    val txSlideOut = motionMillis(220)

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = dev.herdwatch.ui.design.ds().text,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                dev.herdwatch.ui.design.NeoSnackbarBody(message = data.visuals.message)
            }
        },
        bottomBar = {
            if (showTabs) {
                dev.herdwatch.ui.design.NeoTabBar(
                    tabs = tabs.map { it.label to it.icon },
                    selectedIndex = tabs.indexOfFirst { it.screen == ui.screen }.coerceAtLeast(0),
                    onSelect = { vm.navigate(tabs[it].screen) },
                )
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize()) {
            // The backdrop paints under the status bar too; only the content is inset. Insetting the
            // whole tree left the system bar showing the window's light background above a dark page.
            GlassBackdrop(Modifier.fillMaxSize())
            AnimatedContent(
                targetState = ui.screen,
                contentKey = { screen -> if (screen is Screen.Detail) "detail" else screen },
                transitionSpec = {
                    // Durations are resolved outside the lambda: transitionSpec is not a composable
                    // scope, so the reduced-motion factor has to be applied before it runs.
                    when {
                        targetState is Screen.Detail && initialState !is Screen.Detail ->
                            (fadeIn(tween(txEnter)) + slideInHorizontally(tween(txSlide)) { it / 10 }) togetherWith fadeOut(tween(txFadeShort))
                        initialState is Screen.Detail && targetState !is Screen.Detail ->
                            fadeIn(tween(txFadeMid)) togetherWith (fadeOut(tween(txExit)) + slideOutHorizontally(tween(txSlideOut)) { it / 10 })
                        else -> fadeIn(tween(txFadeMid)) togetherWith fadeOut(tween(txFadeShort))
                    }
                },
                label = "screen",
            ) { screen ->
                val pageKey = when (screen) {
                    Screen.Inbox -> "inbox"
                    Screen.Hosts -> "hosts"
                    Screen.Settings -> "settings"
                    is Screen.Structure -> "structure:${screen.hostId}"
                    is Screen.Terminal -> "terminal:${screen.hostId}"
                    is Screen.Detail -> "detail" // Drafts live in the ViewModel; avoid unbounded page state per agent.
                }
                // Full-screen surfaces own their own insets: the terminal page unions ime+nav bars
                // on its root, so adding a top inset here would push its dock past the top edge.
                // For the same reason the scaffold's bottom inset is dropped for them: applying
                // both left a dead strip of navigation bar under the terminal dock.
                val ownsInsets = screen is Screen.Terminal || screen is Screen.Detail
                val insets = if (ownsInsets) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                }
                Box(insets.padding(bottom = if (ownsInsets) 0.dp else inner.calculateBottomPadding())) {
                pageState.SaveableStateProvider(pageKey) {
                    when (screen) {
                        Screen.Inbox -> InboxScreen(
                            ui = ui,
                            seen = seen,
                            onRefresh = vm::refresh,
                            onOpenAgent = vm::openAgent,
                            onQuery = vm::setQuery,
                            onToggleSearch = vm::setSearching,
                            onAddHost = { vm.showAddHost(true) },
                            onReconnect = vm::reconnect,
                            onNewTask = { vm.showNewTask(true) },
                            onOpenHost = vm::openStructure,
                            onOpenPane = vm::openPane,
                        )
                        Screen.Hosts -> HostsScreen(
                            hosts = ui.repo.hosts,
                            onAdd = { vm.showAddHost(true) },
                            onReconnect = vm::reconnect,
                            onEdit = vm::editHost,
                            onDelete = vm::deleteHost,
                            onTerminal = vm::openTerminal,
                            // One page for the tree: split, focus, rename and start agent all live
                            // in the same place, so nobody has to learn two vocabularies.
                            onManage = vm::openStructure,
                            onOpenHelp = { vm.navigate(Screen.Settings) },
                            onTrustNewKey = vm::trustNewFingerprint,
                            agents = withSeen(ui.repo.agents, seen),
                        )
                        is Screen.Structure -> {
                            val runtime = ui.repo.hosts.firstOrNull { it.host.id == screen.hostId }
                            if (runtime == null) {
                                LaunchedEffect(screen.hostId) { vm.back() }
                            } else {
                                StructureScreen(
                                    runtime = runtime,
                                    currentAgents = ui.repo.agents,
                                    onBack = vm::back,
                                    onRefresh = { vm.reconnect(runtime.host.id) },
                                    onCreateWorkspace = { vm.createWorkspace(runtime.host.id) },
                                    onCreateTab = { ws -> vm.createTab(runtime.host.id, ws) },
                                    onSplit = { pane, dir -> vm.splitPane(runtime.host.id, pane, dir) },
                                    onClosePane = { vm.closePane(runtime.host.id, it) },
                                    onCloseTab = { vm.closeTab(runtime.host.id, it) },
                                    onCloseWorkspace = { vm.closeWorkspace(runtime.host.id, it) },
                                    onFocusPane = { vm.focusPane(runtime.host.id, it) },
                                    onFocusTab = { vm.focusTab(runtime.host.id, it) },
                                    onFocusWorkspace = { vm.focusWorkspace(runtime.host.id, it) },
                                    onStartAgent = { pane -> vm.openStartAgent(runtime.host.id, pane) },
                                    onRenameWorkspace = { id, name -> vm.renameWorkspace(runtime.host.id, id, name) },
                                    onRenameTab = { id, name -> vm.renameTab(runtime.host.id, id, name) },
                                    onRenamePane = { id, name -> vm.renamePane(runtime.host.id, id, name) },
                                    onOpenAgent = { agent -> vm.openAgent(agent) },
                                )
                            }
                        }
                        Screen.Settings -> SettingsScreen(
                            hosts = ui.repo.hosts,
                            preferences = prefs,
                            storeError = ui.repo.storeError,
                            onLanguage = vm::setLanguage,
                            onTheme = vm::setTheme,
                            onFontSize = vm::terminalFontSize,
                            onResetFingerprints = vm::resetFingerprints,
                            version = version,
                            update = update,
                            onCheckUpdate = vm::checkForUpdate,
                            onDismissUpdate = vm::dismissUpdate,
                            onInstallUpdate = vm::installUpdate,
                            onOpenInstallPermission = vm::retryInstall,
                        )
                        is Screen.Detail -> AgentScreen(
                            // The strip describes this page's agent; a finished turn already on screen
                            // is idle here too, so the row and the page never disagree.
                            detail = ui.detail.copy(agent = ui.detail.agent?.let { withSeen(it, seen) }),
                            onBack = vm::back,
                            onRefresh = vm::reloadPane,
                            onReconnect = { ui.detail.agent?.hostId?.let(vm::reconnect) },
                            onAutoRefresh = vm::refreshPaneQuietly,
                            onReadingPaused = vm::setReadingPaused,
                            // Two entry points, one difference: the automatic trigger yields to a
                            // read already in flight (`historyFailed` gates it), while an explicit
                            // tap means "try now" and supersedes it.
                            onLoadMoreHistory = { vm.loadMoreHistory() },
                            onLoadOlder = { vm.loadMoreHistory(force = true) },
                            onReadingPosition = vm::rememberReadingPosition,
                            onChoose = vm::choose,
                            onVerifyOperation = vm::confirmOperationVerified,
                            onDraftChange = vm::setDraft,
                            onSendPrompt = { vm.sendPrompt(it) },
                            onSendKeys = vm::sendRawKeys,
                            onSendInput = vm::sendInput,
                            onSendRawBytes = vm::sendRawBytes,
                            onStop = { vm.sendRawKeys(listOf("ctrl-c")) },
                            onChooseScreenRow = vm::chooseScreenRow,
                            onRename = vm::renameAgent,
                            onStartAgent = ui.detail.agent?.takeIf { !it.hasAgent }?.let { pane ->
                                { vm.openStartAgent(pane.hostId, pane.paneId) }
                            },
                        )
                        is Screen.Terminal -> TerminalScreen(
                            state = terminal,
                            onBack = vm::back,
                            onReconnect = vm::terminalReconnect,
                            onInput = vm::terminalInput,
                            onKey = vm::terminalKey,
                            onResize = vm::terminalResize,
                            onFontSize = vm::terminalFontSize,
                            onFollow = vm::terminalFollow,
                            onTrustFingerprint = vm::terminalTrustFingerprint,
                        )
                    }
                }
                }
            }
        }
    }

    ui.startAgent?.let { req ->
        val runtime = ui.repo.hosts.firstOrNull { it.host.id == req.hostId }
        if (runtime != null) {
            StartAgentSheet(
                host = runtime,
                currentAgents = ui.repo.agents,
                initialPaneId = req.paneId,
                onDismiss = vm::dismissStartAgent,
                onStart = { pane, kind, name, args -> vm.startAgent(req, pane, kind, name, args) },
            )
        }
    }

    if (ui.showNewTask) {
        NewTaskSheet(
            hosts = ui.repo.hosts,
            currentAgents = ui.repo.agents,
            initialHostId = null,
            onDismiss = { vm.showNewTask(false) },
            onStart = { hostId, workspaceId, kind, name, args ->
                vm.startNewTask(hostId, workspaceId, kind, name, args)
            },
        )
    }

    if (ui.showAddHost) {
        AddHostSheet(
            adding = ui.addingHost,
            initial = ui.editingHost,
            storeError = ui.repo.storeError,
            onDismiss = { vm.showAddHost(false) },
            onSave = vm::saveHost,
        )
    }
}
