package com.taskflow.app.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taskflow.app.ServiceLocator
import com.taskflow.app.ui.AppViewModelFactory
import com.taskflow.app.ui.task.AddEditTaskSheet
import com.taskflow.app.ui.theme.TaskFlowTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 从 Widget 小加号直接打开的「新建任务」对话框式窗口。
 *
 * 设计要点：
 * - Activity 主题为半透明背景（Dialog 风格），不启动 MainActivity
 * - 内容是一个居中的 Card，里面直接嵌入 AddEditTaskSheet（完全复用应用内的创建任务 UI）
 * - 保存成功后立即关闭窗口，并触发 Widget 数据刷新
 */
class WidgetNewTaskActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.init(this)

        // 点击窗口外部（半透明区域）自动关闭
        setFinishOnTouchOutside(true)

        setContent {
            TaskFlowTheme(darkTheme = false, dynamicColor = false) {
                WidgetNewTaskDialog(
                    onDismiss = { finish() },
                    onSaved = { _, _ ->
                        // 保存成功：刷新 Widget 列表 → 关闭窗口
                        CoroutineScope(Dispatchers.Default).launch {
                            WidgetHelper.refresh(this@WidgetNewTaskActivity)
                        }
                        finish()
                    }
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        super.onBackPressed()
        // 确保返回键也关闭窗口（兼容老版本）
    }
}

@Composable
private fun WidgetNewTaskDialog(
    onDismiss: () -> Unit,
    onSaved: (taskId: Long, isNew: Boolean) -> Unit
) {
    // 半透明背景 + 居中卡片
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0x66000000))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp,
            shadowElevation = 16.dp
        ) {
            var externalSaveTrigger by remember { mutableStateOf(false) }
            AddEditTaskSheet(
                task = null,
                onSaved = onSaved,
                onDismiss = onDismiss,
                onCancel = { onDismiss() },
                externalSaveTrigger = externalSaveTrigger,
                onExternalSaveTriggered = { externalSaveTrigger = false },
                showTitle = true
            )
        }
    }
}
