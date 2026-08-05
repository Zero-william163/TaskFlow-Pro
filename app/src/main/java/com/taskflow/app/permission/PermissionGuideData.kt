package com.taskflow.app.permission

import android.os.Build

/**
 * 多品牌操作路径数据（12 品牌 × 4 类专项权限）。
 *
 * 移植自 Countdown 项目 PermissionGuideData v4。
 * 当厂商 Intent 无法 resolveActivity 时，UI 层必须使用此文件中的分步文字引导，
 * 而不是回退到系统设置首页。
 *
 * App 名称占位为 "TaskFlow"。
 */
object PermissionGuideData {

    /** 单品牌操作路径包 */
    data class VendorGuide(
        val vendorLabel: String,            // 展示用名称
        val autoStartPath: String,          // 自启动
        val batteryManualPath: String,      // 电池手动管理
        val lockScreenWhiteListPath: String,// 锁屏清理白名单
        val widgetPath: String              // 桌面小组件
    )

    /* ============================================================
     * 12 个品牌数据（按国内市场占有率排序）
     * 每条都是"操作路径"，可直接显示在 UI 中换行展示
     * ============================================================ */

    private val HUAWEI = VendorGuide(
        vendorLabel = "华为",
        autoStartPath = """
            设置 → 应用和服务 → 应用启动管理
            找到「TaskFlow」→ 点击进入详情
            关闭「自动管理」开关
            在弹窗中勾选并开启三个开关：
              ✔ 允许自启动
              ✔ 允许关联启动
              ✔ 允许后台活动
            点击「确定」
        """.trimIndent(),
        batteryManualPath = """
            设置 → 应用和服务 → 应用启动管理
            找到「TaskFlow」→ 进入详情
            关闭「自动管理」
            手动管理模式下，确保「允许后台活动」已开启
            另外：设置 → 电池 → 耗电排行
            找到「TaskFlow」→ 关闭「高耗电提醒」
        """.trimIndent(),
        lockScreenWhiteListPath = """
            手机管家（或设置 → 电池）
            → 应用启动管理 → 找到「TaskFlow」
            → 手动管理 → 开启「允许后台活动」
            （华为最新 EMUI 已将锁屏清理合并到手动管理的后台活动中）
            另：设置 → 通知 → 锁屏通知
            → 找到「TaskFlow」→ 开启「允许通知」与「显示内容」
        """.trimIndent(),
        widgetPath = """
            长按桌面空白处 → 选择「服务卡片」或「小组件」
            → 找到「TaskFlow」→ 拖拽到桌面
            如果找不到，请先：
              设置 → 应用和服务 → 权限管理
              → 找到「TaskFlow」→ 开启「创建桌面快捷方式」
        """.trimIndent()
    )

    private val HONOR = VendorGuide(
        vendorLabel = "荣耀",
        autoStartPath = """
            设置 → 应用 → 应用启动管理
            找到「TaskFlow」→ 关闭「自动管理」
            手动开启：
              ✔ 允许自启动
              ✔ 允许关联启动
              ✔ 允许后台活动
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池 → 耗电排行
            → 找到「TaskFlow」
            → 关闭「高耗电提醒」
            → 进入「启动管理」关闭自动管理
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 通知 → 锁屏通知
            → 找到「TaskFlow」→ 开启全部通知与内容显示
            手机管家 → 启动管理 → 手动管理 → 允许后台活动
        """.trimIndent(),
        widgetPath = """
            长按桌面空白处 → 卡片
            → 搜索「TaskFlow」→ 拖到桌面
            若无结果：设置 → 应用 → 权限管理
            → TaskFlow → 开启「创建桌面快捷方式」
        """.trimIndent()
    )

    private val XIAOMI = VendorGuide(
        vendorLabel = "小米/红米/POCO",
        autoStartPath = """
            手机管家 → 应用权限管理（或「隐私保护」）
            → 权限 → 自启动
            → 找到「TaskFlow」→ 开启「允许自启动」
            同时开启：关联启动（如有此选项）
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池与性能 → 电池 → 应用智能省电
            → 找到「TaskFlow」
            → 设置为「无限制」
            另：关闭「锁屏后清理内存」中的 TaskFlow
        """.trimIndent(),
        lockScreenWhiteListPath = """
            手机管家 → 电量与性能 → 右上角 ⚙ 设置
            → 锁屏清理内存 → 设置为「从不」或排除 TaskFlow
            设置 → 锁屏 → 高级设置
            → 「锁屏后允许通知的应用」→ 添加 TaskFlow
        """.trimIndent(),
        widgetPath = """
            双指捏合桌面 → 小组件
            → 找到「TaskFlow」→ 拖到桌面
            若无结果：手机管家 → 隐私保护
            → 权限管理 → TaskFlow
            → 开启「桌面快捷方式」与「创建小组件」
        """.trimIndent()
    )

    private val OPPO = VendorGuide(
        vendorLabel = "OPPO/realme/OnePlus",
        autoStartPath = """
            设置 → 应用管理 → 应用启动管理
            → 找到「TaskFlow」
            → 关闭「自动管理」→ 允许「自启动」与「后台运行」
            或：手机管家 → 权限隐私 → 自启动管理 → 开启 TaskFlow
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池 → 更多电池设置 → 应用耗电管理
            → 找到「TaskFlow」→ 开启：
              ✔ 允许应用自启动
              ✔ 允许应用前台服务
              ✔ 允许完全后台行为
            → 关闭「允许系统自动关闭」
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 电池 → 应用耗电管理 → TaskFlow
            → 开启「允许完全后台行为」与「允许前台服务」
            设置 → 通知与状态栏 → 通知管理
            → TaskFlow → 开启「锁屏显示」
        """.trimIndent(),
        widgetPath = """
            双指捏合桌面 → 插件 → 找到「TaskFlow」拖到桌面
            若无：手机管家 → 权限管理 → 桌面快捷方式
            → TaskFlow → 允许
        """.trimIndent()
    )

    private val VIVO = VendorGuide(
        vendorLabel = "VIVO/iQOO",
        autoStartPath = """
            i管家 → 应用管理 → 权限管理 → 自启动
            → 找到「TaskFlow」→ 打开开关
            或：设置 → 应用与权限 → 应用管理
            → 右上角权限 → 自启动 → TaskFlow → 允许
        """.trimIndent(),
        batteryManualPath = """
            i管家 → 电池管理 → 后台耗电管理
            → 找到「TaskFlow」
            → 设置为「允许后台高耗电」
            另：设置 → 电池 → 后台高耗电
            → 开启 TaskFlow
        """.trimIndent(),
        lockScreenWhiteListPath = """
            i管家 → 应用管理 → 权限管理 → 单项权限设置
            → 后台弹出界面 → 允许 TaskFlow
            设置 → 状态栏与通知 → 管理通知
            → TaskFlow → 开启「锁屏显示」与「显示预览」
        """.trimIndent(),
        widgetPath = """
            长按桌面空白 → 原子组件/桌面组件
            → 找到「TaskFlow」拖到桌面
            若无：设置 → 应用与权限 → 权限管理
            → 桌面快捷方式 → 允许 TaskFlow
        """.trimIndent()
    )

    private val MEIZU = VendorGuide(
        vendorLabel = "魅族",
        autoStartPath = """
            手机管家 → 权限管理 → 自启动管理
            → 找到「TaskFlow」→ 允许
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电量管理 → 应用电量管理
            → TaskFlow → 待机耗电优化 → 关闭
        """.trimIndent(),
        lockScreenWhiteListPath = """
            手机管家 → 权限管理 → 通知管理
            → TaskFlow → 允许锁屏显示
            设置 → 通知和状态栏 → 锁屏通知 → 显示 TaskFlow
        """.trimIndent(),
        widgetPath = """
            长按桌面 → 添加工具/插件 → TaskFlow 拖到桌面
            若无：设置 → 应用管理 → TaskFlow
            → 权限管理 → 桌面快捷方式 → 允许
        """.trimIndent()
    )

    private val SAMSUNG = VendorGuide(
        vendorLabel = "三星",
        autoStartPath = """
            设置 → 应用 → 选择「TaskFlow」
            → 电池和设备维护 → 电池 → 后台使用限制
            → 从不休眠的应用 → 添加「TaskFlow」
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池和设备维护 → 电池 → 后台使用限制
            → 休眠的应用 → 将 TaskFlow 移除
            → 从不休眠的应用 → 添加 TaskFlow
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 锁定屏幕 → 通知 → 显示内容
            → 找到「TaskFlow」→ 开启全部通知
            设置 → 应用 → TaskFlow → 通知 → 锁屏通知 → 显示
        """.trimIndent(),
        widgetPath = """
            长按桌面 → 小组件 → TaskFlow → 添加到桌面
            若无：设置 → 应用 → TaskFlow
            → 权限 → 安装未知应用/创建快捷方式 → 允许
        """.trimIndent()
    )

    private val ONEPLUS = VendorGuide(
        vendorLabel = "一加",
        autoStartPath = """
            设置 → 应用管理 → 应用列表 → TaskFlow
            → 电池管理 → 允许自动运行
            或：设置 → 电池 → 电池优化 → 不优化 → TaskFlow
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池 → 高级设置 → 电池优化
            → 所有应用 → TaskFlow → 不优化
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 密码与安全 → 锁定屏幕
            → 锁屏显示通知 → TaskFlow → 显示所有通知
        """.trimIndent(),
        widgetPath = """
            长按桌面 → 组件 → 找到 TaskFlow 拖到桌面
        """.trimIndent()
    )

    private val REALME = OPPO.copy(vendorLabel = "realme") // 与 OPPO 共享组件
    private val I_QOO = VIVO.copy(vendorLabel = "iQOO")

    private val MOTOROLA = VendorGuide(
        vendorLabel = "Moto/联想",
        autoStartPath = """
            设置 → 应用 → TaskFlow → 打开
            → 电池优化 → 不优化
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池 → 电池优化 → TaskFlow → 不优化
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 应用和通知 → 通知 → 锁屏通知
            → TaskFlow → 显示所有通知内容
        """.trimIndent(),
        widgetPath = "长按桌面 → 小组件 → TaskFlow 拖到桌面"
    )

    private val SONY = VendorGuide(
        vendorLabel = "索尼",
        autoStartPath = """
            设置 → 电池 → 电池优化 → 找到 TaskFlow → 不优化
            设置 → 应用和通知 → 应用信息 → TaskFlow
            → 电池 → 不优化
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池 → 右上角三点 → 电池优化
            → TaskFlow → 不优化
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 应用和通知 → 通知
            → 锁定屏幕 → 显示所有通知
            → TaskFlow → 开启
        """.trimIndent(),
        widgetPath = "长按桌面 → 小部件 → 拖到桌面"
    )

    private val GOOGLE = VendorGuide(
        vendorLabel = "Google/原生 Android",
        autoStartPath = """
            设置 → 应用 → 查看全部应用 → TaskFlow
            → 电池 → 不受限制（Allow full usage）
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池 → 电池使用率 → 电池优化
            → 所有应用 → TaskFlow → 不优化
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 应用 → TaskFlow → 通知
            → 锁屏通知 → 显示静默和提醒性通知
        """.trimIndent(),
        widgetPath = "长按桌面 → 长按 Widget 图标 → 拖到桌面"
    )

    /* ============================================================
     * 公共查询 API
     * ============================================================ */

    fun forCurrent(): VendorGuide =
        when (Build.MANUFACTURER?.lowercase()) {
            "huawei"                           -> HUAWEI
            "honor"                            -> HONOR
            "xiaomi","redmi","poco","blackshark" -> XIAOMI
            "oppo","realme","oneplus","coloros" -> OPPO
            "vivo","iqoo"                      -> VIVO
            "meizu","flyme"                    -> MEIZU
            "samsung"                          -> SAMSUNG
            "lenovo","motorola","moto"         -> MOTOROLA
            "sony"                             -> SONY
            "google","essential"               -> GOOGLE
            else                               -> GOOGLE
        }

    /** 通用兜底（所有厂商都可用） */
    fun genericGuide(): VendorGuide = VendorGuide(
        vendorLabel = "通用",
        autoStartPath = """
            设置 → 应用 → 所有应用 → 找到「TaskFlow」
            → 打开「允许自动启动」或设置电池为「不优化」
        """.trimIndent(),
        batteryManualPath = """
            设置 → 电池 → 电池优化 → 所有应用
            → TaskFlow → 不优化 / 不受限制
        """.trimIndent(),
        lockScreenWhiteListPath = """
            设置 → 通知 → TaskFlow
            → 开启「锁屏通知」或「显示内容」
        """.trimIndent(),
        widgetPath = """
            长按桌面空白处 → 小组件/小部件/Widgets
            → 找到「TaskFlow」→ 拖拽到桌面
        """.trimIndent()
    )
}
