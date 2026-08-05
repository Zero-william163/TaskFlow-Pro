package com.taskflow.app.widget

import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "WidgetPinCallback"

/**
 * 系统 requestPinAppWidget 回调。
 *
 * 关键：只有在收到此回调且 EXTRA_APPWIDGET_ID 有效时，才表示 Widget 真正创建。
 * 不要在 requestPinWidget() 返回 true 时就认为成功。
 *
 * v6 重构：不再写入 UserPreferences 本地缓存。Widget 放置状态唯一可信源是
 * AppWidgetManager.getAppWidgetIds()，避免"APP 显示已创建但桌面无组件"的不一致。
 */
class WidgetPinResultReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "onReceive: action=${intent.action}")

        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
        val singleId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        Log.d(TAG, "onReceive: ids=${ids?.toList()}, singleId=$singleId")

        // 系统在用户确认放置后才会传递有效的 widget id
        val hasValidId = (ids != null && ids.isNotEmpty()) ||
            singleId != AppWidgetManager.INVALID_APPWIDGET_ID

        if (!hasValidId) {
            Log.w(TAG, "onReceive: no valid widget id, user may have cancelled")
            return
        }

        // 二次验证：通过系统 API 确认 Widget 真实存在
        val manager = AppWidgetManager.getInstance(context)
        val actualIds = manager.getAppWidgetIds(
            ComponentName(context, TaskWidgetProvider::class.java)
        )
        Log.d(TAG, "onReceive: actual appWidgetIds from system=${actualIds.toList()}")

        if (actualIds.isEmpty()) {
            Log.w(TAG, "onReceive: system reports no widget, treating as failed")
            return
        }

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // 不再写入本地缓存——状态由系统 API 实时提供，避免双源不一致。
                Log.d(TAG, "onReceive: widget confirmed by system (state tracked via AppWidgetManager)")

                // 刷新 Widget 显示
                WidgetHelper.refresh(context)
                Log.d(TAG, "onReceive: refresh triggered")
            } catch (e: Throwable) {
                Log.e(TAG, "onReceive: error", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
