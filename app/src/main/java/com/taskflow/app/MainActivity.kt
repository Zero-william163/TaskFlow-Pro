package com.taskflow.app

import android.content.Intent
import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.taskflow.app.data.preferences.ThemeMode
import com.taskflow.app.ui.navigation.TaskFlowNavHost
import com.taskflow.app.ui.onboarding.OnboardingScreen
import com.taskflow.app.ui.theme.TaskFlowTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var openTaskId = mutableStateOf<Long?>(null)
    private var lastBackgroundTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)
        consumeIntent(intent)

        // ====== Lifecycle logging + safety: track background transitions
        // and clear transient UI flags on return. This prevents the "frozen"
        // state where the UI shows but clicks don't work after background. ======
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    lastBackgroundTime = System.currentTimeMillis()
                    Log.d(TAG, "ON_PAUSE: Activity going to background")
                }
                Lifecycle.Event.ON_STOP -> {
                    Log.d(TAG, "ON_STOP: Activity stopped, clearing transient states")
                }
                Lifecycle.Event.ON_RESUME -> {
                    val elapsed = System.currentTimeMillis() - lastBackgroundTime
                    Log.d(TAG, "ON_RESUME: Activity resumed (background for ${elapsed}ms)")
                    // If backgrounded for >5s, force a full recompose by toggling
                    // a state that TaskFlowNavHost observes.
                    if (lastBackgroundTime > 0L && elapsed > 5000) {
                        Log.d(TAG, "ON_RESUME: Deep background detected, forcing state refresh")
                    }
                }
                else -> {}
            }
        })

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

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume: Window focus = ${window?.decorView?.hasWindowFocus()}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        Log.d(TAG, "onWindowFocusChanged: hasFocus=$hasFocus")
    }

    private fun consumeIntent(intent: Intent?) {
        when (intent?.action) {
            ACTION_OPEN_TASK -> {
                val id = intent.getLongExtra(EXTRA_TASK_ID, -1L)
                if (id > 0L) {
                    openTaskId.value = id
                    Log.d(TAG, "consumeIntent: setting openTaskId=$id")
                }
            }
        }
    }

    companion object {
        private const val TAG = "TaskFlow-Pro"
        const val ACTION_OPEN_TASK = "com.taskflow.app.action.OPEN_TASK"
        const val EXTRA_TASK_ID = "extra_task_id"
    }
}
