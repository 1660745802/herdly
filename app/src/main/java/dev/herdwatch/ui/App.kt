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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdwatch.model.Screen

private data class Tab(val label: String, val icon: ImageVector, val screen: Screen)

@Composable
fun HerdwatchRoot(vm: AppViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
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
        enabled = ui.screen is Screen.Detail || ui.screen is Screen.Structure || ui.screen == Screen.Settings,
    ) { vm.back() }

    val tabs = remember {
        listOf(
            Tab("值守", Icons.AutoMirrored.Filled.List, Screen.Inbox),
            Tab("机器", Icons.Default.Home, Screen.Hosts),
        )
    }
    val showTabs = ui.screen == Screen.Inbox || ui.screen == Screen.Hosts
    val pageState = rememberSaveableStateHolder()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            AnimatedVisibility(
                visible = ui.screen == Screen.Inbox && ui.repo.hosts.isNotEmpty(),
                enter = scaleIn(tween(180)) + fadeIn(tween(140)),
                exit = scaleOut(tween(120)) + fadeOut(tween(90)),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { vm.showNewTask(true) },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(18.dp),
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("派新任务") },
                )
            }
        },
        bottomBar = {
            if (showTabs) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = ui.screen == tab.screen,
                            onClick = { vm.navigate(tab.screen) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = inner.calculateBottomPadding())
                .statusBarsPadding(),
        ) {
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
                    is Screen.Structure -> "structure:${screen.hostId}"
                    is Screen.Detail -> "detail" // Drafts live in the ViewModel; avoid unbounded page state per agent.
                }
                pageState.SaveableStateProvider(pageKey) {
                    when (screen) {
                        Screen.Inbox -> InboxScreen(
                            ui = ui,
                            onRefresh = vm::refresh,
                            onOpenAgent = vm::openAgent,
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
                        Screen.Settings -> SettingsScreen(ui.repo.hosts, onBack = vm::back)
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
                            onStop = { vm.sendRawKeys(listOf("ctrl-c")) },
                            onExplain = vm::explainAgent,
                            onDismissExplain = vm::dismissExplain,
                            onRename = vm::renameAgent,
                        )
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
