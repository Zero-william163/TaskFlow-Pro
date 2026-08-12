package com.taskflow.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Destinations(val route: String) {
    data object Home : Destinations("home")
    data object Calendar : Destinations("calendar")
    data object Stats : Destinations("stats")
    data object Settings : Destinations("settings")
    data object TaskDetail : Destinations("task/{id}") {
        const val ARG_ID = "id"
        fun create(id: Long) = "task/$id"
    }
    data object Pomodoro : Destinations("pomodoro/{taskId}") {
        const val ARG_ID = "taskId"
        fun create(taskId: Long) = "pomodoro/$taskId"
    }
    data object Permissions : Destinations("permissions")
    data object Update : Destinations("update")
}

data class BottomItem(
    val route: String,
    val labelRes: Int,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
)

val bottomItems = listOf(
    BottomItem(Destinations.Home.route, com.taskflow.app.R.string.nav_home, Icons.Rounded.Home, Icons.Outlined.Home),
    BottomItem(Destinations.Calendar.route, com.taskflow.app.R.string.nav_calendar, Icons.Rounded.CalendarMonth, Icons.Outlined.CalendarMonth),
    BottomItem(Destinations.Stats.route, com.taskflow.app.R.string.nav_stats, Icons.Rounded.QueryStats, Icons.Outlined.QueryStats),
    BottomItem(Destinations.Settings.route, com.taskflow.app.R.string.nav_settings, Icons.Rounded.Settings, Icons.Outlined.Settings),
)
