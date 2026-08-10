package com.taskflow.app.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.taskflow.app.MainActivity
import com.taskflow.app.R
import com.taskflow.app.data.repository.TaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val TAG = "WidgetHelper"

/**
 * Widget 全链路调试入口。所有日志统一 TAG="WidgetHelper"，便于 Logcat 过滤。
 *
 * v7 修复核心问题：
 * - requestPinWidget 不再静默吞掉异常，全部打印完整 stacktrace 到 Logcat
 * - 暴露 [lastPinError] 供 UI 层读取并展示给用户
 * - 全流程日志：API、supported、ComponentName、accepted、异常类型、当前 Widget 数量
 */
object WidgetHelper {

    /**
     * 上一次 requestPinWidget 失败的异常（如果有）。
     * UI 层可读取此字段向用户展示真实失败原因，而不是"假装成功"。
     */
    @Volatile
    var lastPinError: Throwable? = null
        private set

    /**
     * 上一次 requestPinWidget 的诊断信息（无论成功失败）。
     * 包含 API、Launcher 包名、isRequestPinAppWidgetSupported 等。
     */
    @Volatile
    var lastPinDiagnostic: String = ""
        private set

    fun refresh(context: Context) {
        CoroutineScope(Dispatchers.Default).launch {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(providerComponent(context))
            Log.d(TAG, "refresh: appWidgetIds=${ids.toList()}")
            if (ids.isEmpty()) return@launch
            val pending = try {
                withContext(Dispatchers.IO) {
                    TaskRepository.get(context).getPinnedPending().size
                }
            } catch (e: Throwable) {
                Log.e(TAG, "refresh: TaskRepository.getPinnedPending FAILED", e)
                -1  // 标记为 Room 失败，后续 buildForId 会走 fallback
            }
            Log.d(TAG, "refresh: pinnedPending count=$pending")
            ids.forEach { id ->
                try {
                    val views = buildForIdInternal(context, id, pending)
                    manager.updateAppWidget(id, views)
                    // ===== 断点 #1 修复：updateAppWidget 后必须 notifyAppWidgetViewDataChanged =====
                    // Collection Widget 的 ListView 数据由 RemoteViewsFactory 管理，
                    // updateAppWidget 只刷新 RemoteViews 本体（header/count_text），
                    // 不会触发 Launcher 重新调用 onDataSetChanged()。
                    // notifyAppWidgetViewDataChanged 是唯一能强制 Factory 重新读 DB 的 API。
                    try {
                        manager.notifyAppWidgetViewDataChanged(id, R.id.task_list)
                        Log.d(TAG, "refresh: widget $id ✅ OK (已 notify ListView 刷新)")
                    } catch (e: Throwable) {
                        Log.e(TAG, "refresh: widget $id ❌ notifyAppWidgetViewDataChanged FAILED", e)
                    }
                } catch (e: Throwable) {
                    // 最后保障：连 buildSafeFallback 都抛异常？这种情况极端罕见
                    Log.e(TAG, "refresh: widget $id ❌ buildSafeFallback 也失败", e)
                    try {
                        manager.updateAppWidget(id, buildSafeFallback(context, e))
                        Log.d(TAG, "refresh: widget $id 使用终极 white-screen fallback")
                    } catch (e2: Throwable) {
                        Log.e(TAG, "refresh: widget $id ❌ ultimate inflate FAILED", e2)
                    }
                }
            }
        }
    }

    /**
     * Provider 调用入口（suspend）。内部同样走分层 fallback。
     */
    suspend fun buildForId(context: Context, appWidgetId: Int): RemoteViews {
        val pending = try {
            withContext(Dispatchers.IO) {
                TaskRepository.get(context).getPinnedPending().size
            }
        } catch (e: Throwable) {
            Log.e(TAG, "buildForId[$appWidgetId]: TaskRepository FAILED → 使用 fallback", e)
            -1
        }
        return buildForIdInternal(context, appWidgetId, pending)
    }

    /**
     * 分层 fallback 构建逻辑：
     *   Level 1) buildViews: Room + ListView + RemoteViewsService + PendingIntent (完整版本)
     *   Level 2) buildViews 传 remaining=0: 不依赖 Room 实际数量 (仅当 Level1=Room异常以外的情况失败时)
     *   Level 3) buildNoListView: 去掉 ListView/RemoteViewsService，只渲染 widget_content 的 header + 空状态
     *   Level 4) buildSafeFallback: widget_test.xml 最小化布局 (纯白+TextView) —— 最后保障
     *
     * 任何一层成功都直接返回，Launcher 拿到合法 RemoteViews 就不会显示 Problem loading widget。
     * 失败的每一层都打印完整 stacktrace 到 Logcat，便于定位到底卡在哪一层。
     */
    private fun buildForIdInternal(
        context: Context,
        appWidgetId: Int,
        rawRemaining: Int
    ): RemoteViews {
        val tagId = appWidgetId
        // ---------- Level 1: 完整版本 ----------
        if (rawRemaining >= 0) {
            return try {
                Log.d(TAG, "buildForId[$tagId]: === Level 1 buildViews (Room=$rawRemaining, ListView) ===")
                val result = buildViews(context, tagId, rawRemaining)
                Log.d(TAG, "buildForId[$tagId]: ✅ Level 1 成功")
                result
            } catch (e: Throwable) {
                Log.e(TAG, "buildForId[$tagId]: ❌ Level 1 失败", e)
                Log.e(TAG, "buildForId[$tagId]:   type=${e.javaClass.name}, msg=${e.message}")
                // Level 1 失败继续降级
                level234Fallback(context, tagId, e)
            }
        } else {
            // rawRemaining < 0 表示上游明确知道 Room 已经失败，直接跳到 Level 2
            Log.w(TAG, "buildForId[$tagId]: Room 失败 (rawRemaining=$rawRemaining), 跳过 Level 1")
            return level234Fallback(context, tagId, RuntimeException("Room 查询失败"))
        }
    }

    private fun level234Fallback(context: Context, appWidgetId: Int, rootCause: Throwable): RemoteViews {
        // ---------- Level 2: buildViews(remaining=0) ----------
        return try {
            Log.d(TAG, "buildForId[$appWidgetId]: === Level 2 buildViews remaining=0 ===")
            val result = buildViews(context, appWidgetId, 0)
            Log.d(TAG, "buildForId[$appWidgetId]: ✅ Level 2 成功")
            result
        } catch (e2: Throwable) {
            Log.e(TAG, "buildForId[$appWidgetId]: ❌ Level 2 失败", e2)
            // ---------- Level 3: buildNoListView ----------
            return try {
                Log.d(TAG, "buildForId[$appWidgetId]: === Level 3 buildNoListView ===")
                val result = buildNoListView(context, appWidgetId, rootCause)
                Log.d(TAG, "buildForId[$appWidgetId]: ✅ Level 3 成功")
                result
            } catch (e3: Throwable) {
                Log.e(TAG, "buildForId[$appWidgetId]: ❌ Level 3 失败", e3)
                // ---------- Level 4: buildSafeFallback (最后的保障) ----------
                Log.w(TAG, "buildForId[$appWidgetId]: === Level 4 buildSafeFallback (终极白屏) ===")
                buildSafeFallback(context, e3)
            }
        }
    }

    private fun buildViews(context: Context, appWidgetId: Int, remaining: Int): RemoteViews {
        Log.d(TAG, "buildViews[$appWidgetId]: remaining=$remaining")
        val views = try {
            RemoteViews(context.packageName, R.layout.widget_content).also {
                Log.d(TAG, "buildViews[$appWidgetId]: widget_content inflate OK")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ widget_content inflate FAILED", e)
            throw e
        }
        val headerPi = try {
            PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ header PendingIntent FAILED", e)
            throw e
        }
        try {
            views.setOnClickPendingIntent(R.id.widget_header, headerPi)
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ R.id.widget_header 失败", e)
            throw e
        }
        try {
            views.setTextViewText(
                R.id.count_text,
                if (remaining > 0) context.getString(R.string.widget_remaining, remaining)
                else context.getString(R.string.widget_all_done)
            )
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ R.id.count_text 失败", e)
            throw e
        }
        val listIntent = Intent(context, TaskListRemoteViewsService::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            // Unique data URI per widget id — ensures Launcher creates separate
            // RemoteViewsService connections for each widget instance.
            data = Uri.parse("taskflow://widget/$appWidgetId")
        }
        try {
            views.setRemoteAdapter(R.id.task_list, listIntent)
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ R.id.task_list setRemoteAdapter FAILED", e)
            throw e
        }
        val template = Intent(context, MainActivity::class.java).apply {
            action = MainActivity.ACTION_OPEN_TASK
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val templatePi = try {
            PendingIntent.getActivity(
                context, appWidgetId, template,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ template PendingIntent FAILED", e)
            throw e
        }
        try {
            views.setPendingIntentTemplate(R.id.task_list, templatePi)
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ setPendingIntentTemplate FAILED", e)
            throw e
        }
        // 关键修复：ListView 永远保持 VISIBLE。当没有任务时，RemoteViewsFactory.getCount()
        // 会返回 1 且 getViewAt(0) 返回内联空态视图，因此 ListView 永远有内容可渲染，
        // Launcher 不会因为 count=0 或 Service 绑定失败把 ListView 折叠为空白。
        try {
            views.setViewVisibility(R.id.task_list, View.VISIBLE)
            views.setViewVisibility(R.id.empty_text, View.GONE)
            Log.d(TAG, "buildViews[$appWidgetId]: list_always_visible OK (remaining=$remaining)")
        } catch (e: Throwable) {
            Log.e(TAG, "buildViews[$appWidgetId]: ❌ list visibility FAILED", e)
            throw e
        }
        Log.d(TAG, "buildViews[$appWidgetId]: ✅ 完整版本构建成功")
        return views
    }

    /**
     * Level 3：去掉 ListView/RemoteViewsService，仅保留 widget_content 框架 +
     * 把根因打印到 empty_text，保证用户有东西看（并能点击打开 App），
     * 同时把具体错误类型留到 Logcat 里精确排查。
     */
    private fun buildNoListView(context: Context, appWidgetId: Int, rootCause: Throwable): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_content)
        Log.d(TAG, "buildNoListView[$appWidgetId]: widget_content inflate OK")
        val headerPi = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        views.setOnClickPendingIntent(R.id.widget_header, headerPi)
        views.setViewVisibility(R.id.task_list, View.GONE)
        views.setViewVisibility(R.id.empty_text, View.VISIBLE)
        // 在 count_text 显示标题，在 empty_text 显示根因（限长避免截断问题）
        views.setTextViewText(R.id.count_text, "TaskFlow")
        val cause = rootCause.javaClass.simpleName
        val msg = rootCause.message?.take(80) ?: "(null)"
        views.setTextViewText(R.id.empty_text, "加载失败($cause)\n$msg")
        views.setOnClickPendingIntent(R.id.empty_text, headerPi)
        Log.d(TAG, "buildNoListView[$appWidgetId]: ✅ 降级构建成功")
        return views
    }

    /**
     * Level 4：终极保障。只使用 widget_test.xml（纯白背景 + 1 个 TextView），
     * 100% 为 RemoteViews 原生支持，不引用任何 drawable/color 资源。
     * 这里不应该失败——如果这一层失败，基本就是 Launcher/打包资源层面的问题。
     */
    private fun buildSafeFallback(context: Context, rootCause: Throwable): RemoteViews {
        Log.w(TAG, "buildSafeFallback[$context.packageName]: rootCause=${rootCause.javaClass.name}")
        val cls = rootCause.javaClass.simpleName
        val msg = rootCause.message?.take(40) ?: "(null)"
        return try {
            RemoteViews(context.packageName, R.layout.widget_test).also { views ->
                try {
                    views.setTextViewText(R.id.test_text, "TaskFlow $cls $msg")
                } catch (e: Throwable) {
                    // setTextViewText 失败就放弃，但保证 RemoteViews 对象被返回
                    Log.e(TAG, "buildSafeFallback: setText failed (继续返回，不抛)", e)
                }
            }
        } catch (e: Throwable) {
            // widget_test.xml 也 inflate 失败 → 打印原因，然后重试 inflate 但不设置任何内容
            Log.e(TAG, "buildSafeFallback: widget_test inflate FAILED → 终极 fallback", e)
            RemoteViews(context.packageName, R.layout.widget_test)
        }
    }

    fun providerComponent(context: Context): ComponentName =
        ComponentName(context, TaskWidgetProvider::class.java)

    /**
     * 请求系统 Pin Widget。
     *
     * 返回值：
     * - true: 系统接受了请求（但不代表 Widget 已创建）
     * - false: 系统不支持或请求失败（详细原因见 [lastPinError] / [lastPinDiagnostic]）
     *
     * v7 修复：
     * - 不再 `catch(_: Throwable)` 静默吞掉异常
     * - 全部打印完整 stacktrace 到 Logcat
     * - 记录 API、Launcher 包名、isRequestPinAppWidgetSupported、accepted
     * - 异常存入 [lastPinError] 供 UI 读取展示
     *
     * 必须在主线程调用。
     */
    fun requestPinWidget(context: Context): Boolean {
        lastPinError = null
        val launcherPkg = getLauncherPackage(context)
        val apiLevel = Build.VERSION.SDK_INT

        Log.d(TAG, "================ requestPinWidget START ================")
        Log.d(TAG, "requestPinWidget: api=$apiLevel, launcher=$launcherPkg, " +
            "manufacturer=${Build.MANUFACTURER}, model=${Build.MODEL}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            lastPinDiagnostic = "API < 26 (current=$apiLevel), requestPinAppWidget 不可用"
            Log.w(TAG, "requestPinWidget: $lastPinDiagnostic")
            return false
        }

        val manager = AppWidgetManager.getInstance(context)
        val supported = try {
            manager.isRequestPinAppWidgetSupported
        } catch (e: Throwable) {
            lastPinError = e
            lastPinDiagnostic = "isRequestPinAppWidgetSupported 抛异常: ${e.javaClass.name}: ${e.message}"
            Log.e(TAG, "requestPinWidget: $lastPinDiagnostic", e)
            return false
        }

        val provider = providerComponent(context)
        val currentIds = try {
            manager.getAppWidgetIds(provider).toList()
        } catch (e: Throwable) {
            Log.w(TAG, "requestPinWidget: getAppWidgetIds failed", e)
            emptyList()
        }

        Log.d(TAG, "requestPinWidget: isRequestPinAppWidgetSupported=$supported, " +
            "provider=${provider.flattenToShortString()}, existingIds=$currentIds")

        lastPinDiagnostic = "api=$apiLevel, launcher=$launcherPkg, " +
            "isRequestPinAppWidgetSupported=$supported, existingWidgetCount=${currentIds.size}"

        if (!supported) {
            Log.w(TAG, "requestPinWidget: 当前 Launcher 不支持 requestPinAppWidget，" +
                "需引导用户手动添加")
            return false
        }

        // Launcher 通过 resultExtras 回传 widget id，必须显式 setResult。
        // 回调 PendingIntent 必须可变（FLAG_MUTABLE），系统会向其 extra 写入新 widget id。
        val callbackIntent = Intent(context, WidgetPinResultReceiver::class.java).apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val callback = try {
            PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                callbackIntent,
                flags
            )
        } catch (e: Throwable) {
            lastPinError = e
            lastPinDiagnostic = "PendingIntent.getBroadcast 抛异常: ${e.javaClass.name}: ${e.message}"
            Log.e(TAG, "requestPinWidget: $lastPinDiagnostic", e)
            return false
        }
        Log.d(TAG, "requestPinWidget: callback PendingIntent 创建成功, flags=$flags")

        return try {
            val accepted = manager.requestPinAppWidget(provider, null, callback)
            Log.d(TAG, "requestPinWidget: requestPinAppWidget returned accepted=$accepted")
            Log.d(TAG, "================ requestPinWidget END (accepted=$accepted) ================")
            lastPinDiagnostic += ", accepted=$accepted"
            if (!accepted) {
                // 系统接受了请求但返回 false：常见于厂商 ROM 拦截
                Log.w(TAG, "requestPinWidget: 系统返回 false（厂商 ROM 可能拦截了请求）")
            }
            accepted
        } catch (e: Throwable) {
            // v7 关键修复：不再 `catch(_: Throwable)` 静默吞掉
            // 打印完整 stacktrace，存入 lastPinError 供 UI 展示
            lastPinError = e
            lastPinDiagnostic = "requestPinAppWidget 抛异常: ${e.javaClass.name}: ${e.message}"
            Log.e(TAG, "requestPinWidget: $lastPinDiagnostic", e)
            Log.e(TAG, "requestPinWidget: 完整 stacktrace:", e)
            Log.d(TAG, "================ requestPinWidget END (exception) ================")
            false
        }
    }

    /**
     * 获取当前默认 Launcher 包名（用于诊断日志）。
     * 通过 ACTION_HOME Intent 的 resolveActivity 推断。
     */
    private fun getLauncherPackage(context: Context): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
            }
            val resolveInfo = context.packageManager.resolveActivity(intent, 0)
            resolveInfo?.activityInfo?.packageName ?: "unknown"
        } catch (e: Throwable) {
            "unknown(${e.javaClass.simpleName})"
        }
    }

    /**
     * 实时检测：Widget 是否存在于桌面。
     * 只信任系统 API，不信任任何本地缓存。
     */
    fun isWidgetPlaced(context: Context): Boolean {
        return try {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(providerComponent(context))
                .isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 同 [isWidgetPlaced]，兼容调用方命名。
     */
    fun isAnyWidgetPlaced(context: Context): Boolean = isWidgetPlaced(context)
}
