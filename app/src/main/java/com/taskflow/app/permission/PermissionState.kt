package com.taskflow.app.permission

import androidx.annotation.StringRes

/**
 * 权限等级。
 *
 * - [REQUIRED]：A级，必须开启，否则核心功能（提醒/闹钟）失效
 * - [RECOMMENDED]：B级，推荐开启，提升稳定性
 * - [VENDOR]：厂商专项，无法系统检测，需用户手动确认
 */
enum class PermissionLevel {
    REQUIRED,
    RECOMMENDED,
    VENDOR
}

/**
 * 权限状态。
 *
 * - [GRANTED]：已开启（系统 API 确认）
 * - [DENIED]：未开启（系统 API 确认）
 * - [NOT_APPLICABLE]：当前系统版本/设备不适用
 * - [NEEDS_MANUAL_CONFIRM]：厂商专项，需用户手动确认
 * - [MANUAL_CONFIRMED]：厂商专项，用户已确认开启
 */
enum class PermissionState {
    GRANTED,
    DENIED,
    NOT_APPLICABLE,
    NEEDS_MANUAL_CONFIRM,
    MANUAL_CONFIRMED
}

/**
 * 权限项展示模型。
 *
 * 统一的权限展示单元，包含检测状态、跳转能力、教程文案。
 */
data class PermissionStateItem(
    /** 权限类型标识 */
    val type: PermissionType,
    /** 权限等级 */
    val level: PermissionLevel,
    /** 标题 */
    @StringRes val titleRes: Int,
    /** 描述 */
    @StringRes val descRes: Int,
    /** 当前状态 */
    val state: PermissionState,
    /** 是否适用于当前设备/系统版本 */
    val applicable: Boolean,
    /** 角标（如"必需"、"推荐"、"华为"） */
    val badge: String? = null,
    /** 跳转按钮文案 */
    @StringRes val actionRes: Int = com.taskflow.app.R.string.permission_open_settings
) {
    /** 是否处于"正常"状态（已开启 / 已确认 / 不适用） */
    val isOk: Boolean
        get() = state == PermissionState.GRANTED ||
            state == PermissionState.MANUAL_CONFIRMED ||
            state == PermissionState.NOT_APPLICABLE

    /** 是否需要用户操作 */
    val needsAction: Boolean
        get() = state == PermissionState.DENIED || state == PermissionState.NEEDS_MANUAL_CONFIRM
}
