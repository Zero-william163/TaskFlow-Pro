package com.taskflow.app.permission

import android.content.Intent
import android.util.Log

/**
 * 统一权限日志工具。
 *
 * 所有权限检测、跳转、状态变化均通过此对象记录，
 * 便于在 logcat 中通过 tag `TaskFlow-Perm` 统一过滤排查。
 */
object PermissionLogger {

    private const val TAG = "TaskFlow-Perm"

    /** 记录权限检测结果 */
    fun logCheck(type: PermissionType, granted: Boolean, extra: String? = null) {
        val status = if (granted) "GRANTED" else "DENIED"
        Log.d(TAG, "check[$type] → $status${extra?.let { " | $it" } ?: ""}")
    }

    /** 记录用户点击权限项 */
    fun logClick(type: PermissionType) {
        Log.d(TAG, "click[$type] → 开始跳转")
    }

    /** 记录某个 Intent 候选的 resolve 结果 */
    fun logResolve(type: PermissionType, intent: Intent?, resolved: Boolean) {
        val action = intent?.action ?: intent?.component?.flattenToShortString() ?: "null"
        Log.d(TAG, "resolve[$type] action=$action → ${if (resolved) "OK" else "FAIL"}")
    }

    /** 记录成功启动了某个 Intent */
    fun logJumpSuccess(type: PermissionType, intent: Intent) {
        val action = intent.action ?: intent.component?.flattenToShortString() ?: "unknown"
        Log.d(TAG, "jump[$type] → 成功 via $action")
    }

    /** 记录某个候选 Intent 启动失败（抛异常） */
    fun logJumpFail(type: PermissionType, intent: Intent, error: Throwable) {
        val action = intent.action ?: intent.component?.flattenToShortString() ?: "unknown"
        Log.w(TAG, "jump[$type] → 失败 via $action: ${error.message}")
    }

    /** 记录所有候选都失败，降级到文字引导 */
    fun logFallbackToGuide(type: PermissionType, candidateCount: Int) {
        Log.w(TAG, "jump[$type] → $candidateCount 个候选全部失败，降级到文字引导")
    }

    /** 记录厂商专项权限用户手动确认 */
    fun logManualConfirm(type: PermissionType, confirmed: Boolean) {
        Log.d(TAG, "manualConfirm[$type] → ${if (confirmed) "已确认" else "已撤销"}")
    }

    /** 记录 onResume 刷新权限状态 */
    fun logRefresh(trigger: String) {
        Log.d(TAG, "refresh ← $trigger")
    }
}
