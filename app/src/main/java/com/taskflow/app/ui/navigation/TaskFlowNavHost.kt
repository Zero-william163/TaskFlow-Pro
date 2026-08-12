package com.taskflow.app.ui.navigation

import android.util.Log
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskflow.app.R
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.calendar.CalendarScreen
import com.taskflow.app.ui.home.HomeScreen
import com.taskflow.app.ui.permission.PermissionScreen
import com.taskflow.app.ui.settings.SettingsScreen
import com.taskflow.app.ui.stats.StatsScreen
import com.taskflow.app.ui.pomodoro.PomodoroScreen
import com.taskflow.app.ui.task.TaskDetailScreen
import com.taskflow.app.ui.update.UpdateScreen
import com.taskflow.app.ui.update.UpdateViewModel
import kotlinx.coroutines.launch

private const val TAG = "TaskFlowNavHost"

@Composable
fun TaskFlowNavHost(
    openTaskId: Long? = null,
    onTaskConsumed: () -> Unit = {},
    markCompleteTaskId: Long? = null,
    onMarkCompleteConsumed: () -> Unit = {},
    newTaskRequested: Boolean = false,
    onNewTaskConsumed: () -> Unit = {},
    openPomodoroTaskId: Long? = null,
    onPomodoroConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ====== Auto update check on startup (silent, 24h-throttled) ======
    val updateViewModel: UpdateViewModel = viewModel(factory = AppViewModelFactory)
    val autoUpdateInfo by updateViewModel.autoUpdateInfo.collectAsState()
    LaunchedEffect(Unit) {
        updateViewModel.autoCheck()
    }

    // ====== Lifecycle safety: dismiss auto-update dialog on background ======
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (autoUpdateInfo != null) {
            updateViewModel.dismissAutoUpdate()
        }
    }

    // ====== Guard for LaunchedEffect(openTaskId): track already-navigated IDs
    // to prevent duplicate navigation when the Activity is resumed ======
    var navigatedTaskId by remember { mutableStateOf<Long?>(null) }

    val showBottomBar = currentRoute in bottomItems.map { it.route }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp
                ) {
                    bottomItems.forEach { item ->
                        val selected = currentRoute == item.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.startDestinationId) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    if (selected) item.selectedIcon else item.unselectedIcon,
                                    contentDescription = null
                                )
                            },
                            label = { Text(stringResource(item.labelRes)) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Destinations.Home.route,
            modifier = Modifier.padding(padding),
            enterTransition = { fadeIn(tween(220)) },
            exitTransition = { fadeOut(tween(180)) },
            popEnterTransition = { fadeIn(tween(220)) },
            popExitTransition = { fadeOut(tween(180)) }
        ) {
            composable(Destinations.Home.route) {
                HomeScreen(
                    onTaskClick = { id -> navController.navigate(Destinations.TaskDetail.create(id)) },
                    onPomodoroClick = { id -> navController.navigate(Destinations.Pomodoro.create(id)) },
                    newTaskRequested = newTaskRequested,
                    onNewTaskConsumed = onNewTaskConsumed
                )
            }
            composable(Destinations.Calendar.route) {
                CalendarScreen(onTaskClick = { id -> navController.navigate(Destinations.TaskDetail.create(id)) })
            }
            composable(Destinations.Stats.route) { StatsScreen() }
            composable(Destinations.Settings.route) {
                SettingsScreen(
                    onOpenPermissions = { navController.navigate(Destinations.Permissions.route) },
                    onOpenUpdate = { navController.navigate(Destinations.Update.route) }
                )
            }
            composable(
                route = Destinations.TaskDetail.route,
                arguments = listOf(navArgument(Destinations.TaskDetail.ARG_ID) { type = NavType.LongType })
            ) { entry ->
                val id = entry.arguments?.getLong(Destinations.TaskDetail.ARG_ID) ?: -1L
                TaskDetailScreen(taskId = id, onBack = { navController.popBackStack() })
            }
            composable(
                route = Destinations.Pomodoro.route,
                arguments = listOf(navArgument(Destinations.Pomodoro.ARG_ID) { type = NavType.LongType })
            ) { entry ->
                val taskId = entry.arguments?.getLong(Destinations.Pomodoro.ARG_ID) ?: -1L
                PomodoroScreen(taskId = taskId, onBack = { navController.popBackStack() })
            }
            composable(Destinations.Permissions.route) {
                PermissionScreen(onBack = { navController.popBackStack() })
            }
            composable(Destinations.Update.route) {
                UpdateScreen(onBack = { navController.popBackStack() })
            }
        }

        // ====== Guarded navigation: only navigate if this taskId hasn't been
        // navigated to yet. Prevents duplicate navigation on resume. ======
        LaunchedEffect(openTaskId) {
            openTaskId?.let { id ->
                if (id != navigatedTaskId) {
                    navigatedTaskId = id
                    navController.navigate(Destinations.TaskDetail.create(id))
                    onTaskConsumed()
                }
            }
        }

        // ====== Widget card body click → 直接进入番茄专注模式 ======
        var navigatedPomodoroId by remember { mutableStateOf<Long?>(null) }
        LaunchedEffect(openPomodoroTaskId) {
            openPomodoroTaskId?.let { id ->
                if (id != navigatedPomodoroId) {
                    navigatedPomodoroId = id
                    navController.navigate(Destinations.Pomodoro.create(id))
                    onPomodoroConsumed()
                }
            }
        }
    }

    // ====== Auto-check update dialog ======
    val info = autoUpdateInfo
    if (info != null) {
        AlertDialog(
            onDismissRequest = { updateViewModel.dismissAutoUpdate() },
            title = { Text(stringResource(R.string.update_available)) },
            text = {
                Column {
                    Text(stringResource(R.string.update_version, info.version))
                    if (info.log.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(info.log, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    updateViewModel.startDownload(context, info)
                    updateViewModel.dismissAutoUpdate()
                }) { Text(stringResource(R.string.update_download)) }
            },
            dismissButton = {
                TextButton(onClick = { updateViewModel.dismissAutoUpdate() }) {
                    Text(stringResource(R.string.update_later))
                }
            }
        )
    }
}