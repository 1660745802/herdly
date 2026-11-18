package dev.herdwatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.ui.res.stringResource
import dev.herdwatch.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdwatch.model.Screen

private data class Tab(val label: String, val icon: dev.herdwatch.ui.design.NeoGlyph, val screen: Screen)

@Composable
fun HerdwatchRoot(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prefs by vm.preferences.collectAsStateWithLifecycle()
    val terminal by vm.terminalState.collectAsStateWithLifecycle()
    val update by vm.update.collectAsStateWithLifecycle()
    val version = vm.version
    val snackbar = remember { SnackbarHostState() }
    val processLifecycle = ProcessLifecycleOwner.get()

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

    LaunchedEffect(ui.message) {
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
            ui.screen is Screen.Terminal || ui.screen == Screen.Settings || ui.screen == Screen.StyleGuide,
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

    Scaffold(
        containerColor = Color.Transparent,
        contentColor = dev.herdwatch.ui.design.ds().text,
        snackbarHost = {
            SnackbarHost(snackbar) { data ->
                dev.herdwatch.ui.design.NeoSnackbarBody(message = data.visuals.message)
            }
        },
        floatingActionButton = {
            val showNewTask = ui.screen == Screen.Inbox && ui.repo.hosts.isNotEmpty()
            val hasPendingApproval = ui.repo.agents.any { it.status == dev.herdwatch.model.AgentStatus.WAITING }
            AnimatedVisibility(
                visible = showNewTask,
                enter = scaleIn(tween(180)) + fadeIn(tween(140)),
                exit = scaleOut(tween(120)) + fadeOut(tween(90)),
            ) {
                // Same chassis language as the rest of the app: one lifted control, not a
                // Material tonal pill with a ripple from a different design system.
                dev.herdwatch.ui.design.NeoButton(
                    label = newTaskLabel,
                    onClick = { vm.showNewTask(true) },
                    tone = dev.herdwatch.ui.design.NeoButtonTone.Primary,
                    icon = dev.herdwatch.ui.design.NeoGlyph.Plus,
                    compact = true,
                    modifier = Modifier.semantics { contentDescription = newTaskLabel },
                )
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
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = inner.calculateBottomPadding()),
        ) {
            // The backdrop paints under the status bar too; only the content is inset. Insetting the
            // whole tree left the system bar showing the window's light background above a dark page.
            GlassBackdrop(Modifier.fillMaxSize())
            AnimatedContent(
                targetState = ui.screen,
                contentKey = { screen -> if (screen is Screen.Detail) "detail" else screen },
                transitionSpec = {
                    when {
                        targetState is Screen.Detail && initialState !is Screen.Detail ->
                            (fadeIn(tween(180)) + slideInHorizontally(tween(240)) { it / 10 }) togetherWith fadeOut(tween(100))
                        initialState is Screen.Detail && targetState !is Screen.Detail ->
                            fadeIn(tween(160)) togetherWith (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { it / 10 })
                        else -> fadeIn(tween(160)) togetherWith fadeOut(tween(100))
                    }
                },
                label = "screen",
            ) { screen ->
                val pageKey = when (screen) {
                    Screen.Inbox -> "inbox"
                    Screen.Hosts -> "hosts"
                    Screen.Settings -> "settings"
                    Screen.StyleGuide -> "style-guide"
                    is Screen.Structure -> "structure:${screen.hostId}"
                    is Screen.Terminal -> "terminal:${screen.hostId}"
                    is Screen.Detail -> "detail" // Drafts live in the ViewModel; avoid unbounded page state per agent.
                }
                // Full-screen surfaces own their own insets: the terminal page unions ime+nav bars
                // on its root, so adding a top inset here would push its dock past the top edge.
                val insets = if (screen is Screen.Terminal || screen is Screen.Detail) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)
                }
                Box(insets) {
                pageState.SaveableStateProvider(pageKey) {
                    when (screen) {
                        Screen.Inbox -> InboxScreen(
                            ui = ui,
                            onRefresh = vm::refresh,
                            onOpenAgent = { agent, hostId -> vm.openAgent(agent, hostId) },
                            onFilter = vm::setFilter,
                            onQuery = vm::setQuery,
                            onToggleSearch = vm::setSearching,
                            onAddHost = { vm.showAddHost(true) },
                            onReconnect = vm::reconnect,
                            onNewTask = { vm.showNewTask(true) },
                            onOpenHost = vm::openStructure,
                        )
                        Screen.Hosts -> HostsScreen(
                            hosts = ui.repo.hosts,
                            onAdd = { vm.showAddHost(true) },
                            onReconnect = vm::reconnect,
                            onEdit = vm::editHost,
                            onDelete = vm::deleteHost,
                            onOpen = vm::openStructure,
                            onTerminal = vm::openTerminal,
                            onOpenHelp = { vm.navigate(Screen.Settings) },
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
                                    onRestore = vm::restoreAgent,
                                    onRenameWorkspace = { id, name -> vm.renameWorkspace(runtime.host.id, id, name) },
                                    onRenameTab = { id, name -> vm.renameTab(runtime.host.id, id, name) },
                                    onRenamePane = { id, name -> vm.renamePane(runtime.host.id, id, name) },
                                )
                            }
                        }
                        Screen.Settings -> SettingsScreen(
                            hosts = ui.repo.hosts,
                            preferences = prefs,
                            onBack = vm::back,
                            onOpenStyleGuide = { vm.navigate(Screen.StyleGuide) },
                            onLanguage = vm::setLanguage,
                            onTheme = vm::setTheme,
                            onMotion = vm::setMotion,
                            onDensity = vm::setDensity,
                            onFontSize = vm::terminalFontSize,
                            onSnapshotLines = vm::setDefaultSnapshotLines,
                            onKeyBarRows = vm::setKeyBarRows,
                            onLongPressRepeat = vm::setLongPressRepeat,
                            onInputMode = vm::setInputMode,
                            onResetFingerprints = vm::resetFingerprints,
                            version = version,
                            update = update,
                            onCheckUpdate = vm::checkForUpdate,
                            onDismissUpdate = vm::clearUpdateResult,
                        )
                        Screen.StyleGuide -> dev.herdwatch.ui.design.StyleGalleryScreen(onBack = vm::back)
                        is Screen.Detail -> AgentScreen(
                            detail = ui.detail,
                            onBack = vm::back,
                            onRefresh = vm::reloadPane,
                            onReconnect = { ui.detail.agent?.hostId?.let(vm::reconnect) },
                            onAutoRefresh = vm::refreshPaneQuietly,
                            onReadingPaused = vm::setReadingPaused,
                            onReadingPosition = vm::rememberReadingPosition,
                            onChoose = vm::choose,
                            onVerifyOperation = vm::confirmOperationVerified,
                            onDraftChange = vm::setDraft,
                            onSendPrompt = { vm.sendPrompt(it) },
                            onSendKeys = vm::sendRawKeys,
                            onSwipeAgent = vm::openAdjacentAgent,
                            canOpenPrevious = ui.detail.canOpenPrevious,
                            canOpenNext = ui.detail.canOpenNext,
                            onLoadMore = vm::loadMore,
                            onSetSnapshotLines = vm::setSnapshotLines,
                            onStop = { vm.sendRawKeys(listOf("ctrl-c")) },
                            onExplain = vm::explainAgent,
                            onDismissExplain = vm::dismissExplain,
                            onRename = vm::renameAgent,
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
                            inputMode = prefs.inputMode,
                            onInputMode = vm::setInputMode,
                            keyBarRows = prefs.keyBarRows,
                            longPressRepeat = prefs.longPressRepeat,
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
            onDismiss = { vm.showAddHost(false) },
            onSave = vm::saveHost,
        )
    }
}
