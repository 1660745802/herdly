package dev.herdwatch.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

    BackHandler(enabled = ui.screen is Screen.Detail || ui.screen is Screen.Structure) { vm.back() }

    val tabs = remember {
        listOf(
            Tab("收件箱", Icons.AutoMirrored.Filled.List, Screen.Inbox),
            Tab("机器", Icons.Default.Home, Screen.Hosts),
            Tab("设置", Icons.Default.Settings, Screen.Settings),
        )
    }
    val showTabs = ui.screen !is Screen.Detail
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
                    text = { Text("新任务") },
                )
            }
        },
        bottomBar = {
            if (showTabs) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.8f))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .height(60.dp)
                            .padding(horizontal = 16.dp)
                            .selectableGroup(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { tab ->
                            val selected = ui.screen == tab.screen || (tab.screen == Screen.Hosts && ui.screen is Screen.Structure)
                            val tint by animateColorAsState(
                                targetValue = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                animationSpec = tween(180),
                                label = "tab-tint",
                            )
                            val pill by animateColorAsState(
                                targetValue = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent,
                                animationSpec = tween(180),
                                label = "tab-pill",
                            )
                            val iconScale by animateFloatAsState(
                                targetValue = if (selected) 1.06f else 1f,
                                animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
                                label = "tab-scale",
                            )
                            Column(
                                Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .selectable(selected = selected, role = Role.Tab, onClick = { vm.navigate(tab.screen) }),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    Modifier
                                        .width(42.dp)
                                        .height(26.dp)
                                        .background(pill, RoundedCornerShape(13.dp)),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Icon(
                                        tab.icon,
                                        contentDescription = null,
                                        tint = tint,
                                        modifier = Modifier.size(19.dp).graphicsLayer { scaleX = iconScale; scaleY = iconScale },
                                    )
                                }
                                Text(
                                    tab.label,
                                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 10.5.sp),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = tint,
                                )
                            }
                        }
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
                        Screen.Settings -> SettingsScreen(ui.repo.hosts)
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
