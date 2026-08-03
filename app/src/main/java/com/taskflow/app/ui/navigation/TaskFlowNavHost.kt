package com.taskflow.app.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.taskflow.app.ui.calendar.CalendarScreen
import com.taskflow.app.ui.home.HomeScreen
import com.taskflow.app.ui.permission.PermissionScreen
import com.taskflow.app.ui.settings.SettingsScreen
import com.taskflow.app.ui.stats.StatsScreen
import com.taskflow.app.ui.task.TaskDetailScreen
import com.taskflow.app.ui.update.UpdateScreen

@Composable
fun TaskFlowNavHost(
    openTaskId: Long? = null,
    onTaskConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route

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
                    onTaskClick = { id -> navController.navigate(Destinations.TaskDetail.create(id)) }
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
            composable(Destinations.Permissions.route) {
                PermissionScreen(onBack = { navController.popBackStack() })
            }
            composable(Destinations.Update.route) {
                UpdateScreen(onBack = { navController.popBackStack() })
            }
        }

        androidx.compose.runtime.LaunchedEffect(openTaskId) {
            openTaskId?.let {
                navController.navigate(Destinations.TaskDetail.create(it))
                onTaskConsumed()
            }
        }
    }
}
