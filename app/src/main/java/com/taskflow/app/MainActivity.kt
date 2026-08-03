package com.taskflow.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.taskflow.app.data.preferences.ThemeMode
import com.taskflow.app.ui.navigation.TaskFlowNavHost
import com.taskflow.app.ui.theme.TaskFlowTheme

class MainActivity : ComponentActivity() {

    private var openTaskId = mutableStateOf<Long?>(null)

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result ignored */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)
        consumeIntent(intent)
        requestNotificationPermissionIfNeeded()

        setContent {
            val prefs = ServiceLocator.userPreferences
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by prefs.dynamicColor.collectAsState(initial = true)

            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            TaskFlowTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                val taskToOpen by remember { openTaskId }
                TaskFlowNavHost(
                    openTaskId = taskToOpen,
                    onTaskConsumed = { openTaskId.value = null }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        if (intent?.action == ACTION_OPEN_TASK) {
            val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
            if (id > 0L) openTaskId.value = id
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    companion object {
        const val ACTION_OPEN_TASK = "com.taskflow.app.action.OPEN_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
