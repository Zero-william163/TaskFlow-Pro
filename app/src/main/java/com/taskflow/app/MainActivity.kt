package com.taskflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.taskflow.app.data.preferences.ThemeMode
import com.taskflow.app.ui.navigation.TaskFlowNavHost
import com.taskflow.app.ui.onboarding.OnboardingScreen
import com.taskflow.app.ui.theme.TaskFlowTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var openTaskId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)
        consumeIntent(intent)

        setContent {
            val prefs = ServiceLocator.userPreferences
            val themeMode by prefs.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dynamicColor by prefs.dynamicColor.collectAsState(initial = true)
            val onboardingCompleted by prefs.onboardingCompleted.collectAsState(initial = true)
            val scope = rememberCoroutineScope()

            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            TaskFlowTheme(darkTheme = darkTheme, dynamicColor = dynamicColor) {
                if (!onboardingCompleted) {
                    OnboardingScreen(onComplete = {
                        scope.launch { prefs.setOnboardingCompleted(true) }
                    })
                } else {
                    val taskToOpen by remember { openTaskId }
                    TaskFlowNavHost(
                        openTaskId = taskToOpen,
                        onTaskConsumed = { openTaskId.value = null }
                    )
                }
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

    companion object {
        const val ACTION_OPEN_TASK = "com.taskflow.app.action.OPEN_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
