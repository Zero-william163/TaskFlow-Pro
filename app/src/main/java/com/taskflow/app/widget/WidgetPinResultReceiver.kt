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
        Log.d(TAG, "================ PinResult onReceive ================")
        Log.d(TAG, "onReceive: action=${intent.action}")
        Log.d(TAG, "onReceive: extras=${intent.extras}")

        val ids = intent.getIntArrayExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS)
        val singleId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        )

        Log.d(TAG, "onReceive: EXTRA_APPWIDGET_IDS=${ids?.toList()}, " +
            "EXTRA_APPWIDGET_ID=$singleId, " +
            "INVALID_APPWIDGET_ID=${AppWidgetManager.INVALID_APPWIDGET_ID}")

        // 系统在用户确认放置后才会传递有效的 widget id
        val hasValidId = (ids != null && ids.isNotEmpty()) ||
            singleId != AppWidgetManager.INVALID_APPWIDGET_ID

        if (!hasValidId) {
            Log.w(TAG, "onReceive: no valid widget id → 用户可能取消了系统确认弹窗，" +
                "或厂商 ROM 静默拒绝了请求")
            return
        }

        // 二次验证：通过系统 API 确认 Widget 真实存在
        val manager = AppWidgetManager.getInstance(context)
        val provider = ComponentName(context, TaskWidgetProvider::class.java)
        val actualIds = try {
            manager.getAppWidgetIds(provider)
        } catch (e: Throwable) {
            Log.e(TAG, "onReceive: getAppWidgetIds failed", e)
            return
        }
        Log.d(TAG, "onReceive: provider=${provider.flattenToShortString()}, " +
            "actual appWidgetIds from system=${actualIds.toList()}")

        if (actualIds.isEmpty()) {
            Log.w(TAG, "onReceive: 系统回调传了 widget id，但 getAppWidgetIds 返回空 → " +
                "Launcher 接受了请求但未真正创建 Widget（部分国产 ROM 行为）")
            return
        }

        Log.d(TAG, "onReceive: ✅ Widget 确认创建成功，widgetCount=${actualIds.size}")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Log.d(TAG, "onReceive: 触发 WidgetHelper.refresh 让新 Widget 渲染内容")
                WidgetHelper.refresh(context)
                Log.d(TAG, "onReceive: refresh 完成")
            } catch (e: Throwable) {
                Log.e(TAG, "onReceive: refresh 异常", e)
            } finally {
                pendingResult.finish()
                Log.d(TAG, "onReceive: pendingResult.finish() called")
            }
        }
    }
}
