# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The GitHub Actions release workflow extracts the section matching the pushed tag
(e.g. `## v1.0.0`) and uses it as the GitHub / Gitee release notes, and embeds it
into `release.json` as the `log` field consumed by the in-app updater.

## v2.0.0

### 重构 — 权限管理中心 / Widget 状态检测系统（大型商业 APP 标准）

1. **权限三级分类（A级 / B级 / 厂商专项）**
   - 新增 `PermissionLevel` 枚举：REQUIRED（A级必需）/ RECOMMENDED（B级推荐）/ VENDOR（厂商专项）
   - A 级：通知、闹钟通知渠道、精确闹钟、忽略电池优化 —— 未开启将导致提醒无法触发
   - B 级：悬浮窗 —— 全屏提醒降级方案
   - 厂商专项：自启动、后台运行、锁屏清理白名单 —— 国产 ROM 专属，需手动确认
   - 权限管理页按分级分组展示，每组带标题与说明

2. **移除不应展示给用户的权限**
   - 移除 FOREGROUND_SERVICE：`OPSTR_RUN_ANY_IN_BACKGROUND` 为 @SystemApi，非用户可见权限，展示只会造成困惑
   - 移除 WIDGET：Widget 状态独立管理，不再混入权限列表

3. **Widget 状态检测系统重构（修复"APP 显示已创建但桌面无组件"）**
   - 根因：`TaskWidgetProvider.onEnabled/onDisabled` 与 `WidgetPinResultReceiver` 写入 `UserPreferences.widgetAdded` 本地缓存，与系统真实状态产生双源不一致
   - 修复：彻底移除所有 `setWidgetAdded` 调用，Widget 放置状态唯一可信源为 `AppWidgetManager.getAppWidgetIds()`
   - 权限管理页新增独立的「桌面小组件」区块，状态实时读取系统 API，返回 APP 自动刷新
   - 禁止重复创建：点击添加前先通过系统 API 确认桌面是否已存在 Widget

4. **统一日志体系**
   - 新增 `PermissionLogger`：权限检测、点击跳转、Intent resolve、跳转成功/失败、降级引导、手动确认、刷新等全链路日志
   - logcat 统一 tag `TaskFlow-Perm`，便于排查权限跳转问题

5. **权限状态模型**
   - 新增 `PermissionState` / `PermissionStateItem` 模型，定义权限等级与状态枚举，为后续扩展提供统一数据结构

6. **权限精准跳转系统保持不变**
   - 多级降级跳转链：优先级 1 直达具体权限页（带 package + uid + channelId）→ 优先级 2 应用详情页 → 全部失败显示文字引导
   - 绝不跳转系统设置首页

### 升级须知
- Widget 状态不再依赖本地缓存，已添加的 Widget 会在下次进入权限页时通过系统 API 重新确认
- 厂商专项权限的"已确认"状态保持不变（SharedPreferences 持久化）

## v1.4.1

### 修复
1. **厂商专项权限（自启动/后台运行/锁屏白名单）不再回退到应用详情页**
   - 根因：buildVendorJumpChain 在厂商 Intent 全部失败后回退到 ACTION_APPLICATION_DETAILS_SETTINGS，
     该页面"看起来像主设置"，用户无法感知具体操作路径
   - 修复：移除应用详情页 fallback，厂商 Intent 全失败时 startIntent 返回 false，
     UI 层显示 PermissionGuideData 中的详细文字引导
2. **从权限管理页面移除"桌面组件"项**（按用户要求）
3. **清理 Widget 相关代码**：移除 handleItemClick 中的 WIDGET 分支、showWidgetGuideDialog 状态、
   WidgetCapability/WidgetHelper imports

## v1.4.0

### 重构
1. **完整移植 Countdown 项目 PermissionChecker v4 的精准跳转能力**
   - 解决 v1.3.x 仍然存在的"跳转不精准"问题：从仅 1 个候选 Intent 改为多级降级跳转链
   - 每项权限的候选链严格按优先级降级，**绝不跳转系统设置首页**
2. **新增 4 项权限检测与直达跳转**：
   - **闹钟通知渠道**（CHANNEL_ALARM）：使用 `ACTION_CHANNEL_NOTIFICATION_SETTINGS` + `EXTRA_CHANNEL_ID`，直达 reminders 渠道详情页（最精准），即使通知总开关已开启也能发现渠道被单独关闭
   - **悬浮窗权限**（OVERLAY）：使用 `ACTION_MANAGE_OVERLAY_PERMISSION` + `data=package URI`，直接弹出"允许悬浮窗"对话框定位到本应用
   - **前台服务 AppOps**（FOREGROUND_SERVICE）：检测 `OPSTR_RUN_ANY_IN_BACKGROUND`，解决闹钟响铃几秒后被系统中断
   - **锁屏清理白名单**（LOCK_SCREEN）：新增厂商专项，解决锁屏状态下闹钟完全不触发
3. **12 品牌 × 4 类权限的详细文字引导**（新增 PermissionGuideData.kt）
   - 华为/荣耀/小米/红米/POCO/OPPO/realme/一加/VIVO/iQOO/魅族/三星/Moto/索尼/Google
   - 每条都是分步操作路径，当厂商 Intent 无法 resolveActivity 时显示
   - 引导对话框新增「我已开启」按钮，用户确认后标记为已开启状态
4. **厂商专项权限持久化**：用户手动确认的自启动/后台运行/锁屏白名单状态保存到 SharedPreferences，避免重复提示
5. PermissionType 枚举稳定化，PermissionStatus 新增 MANUAL 状态（厂商专项已确认）
6. 厂商识别扩展：新增 blackshark、coloros、flyme、moto 等别名

### 跳转链明细（v1.4.0）
- 通知总开关：APP_NOTIFICATION_SETTINGS(+pkg+uid) → 应用详情页
- 闹钟渠道：CHANNEL_NOTIFICATION_SETTINGS(+channelId) → APP_NOTIFICATION_SETTINGS → 应用详情页
- 精确闹钟：REQUEST_SCHEDULE_EXACT_ALARM(+data=pkgUri) → 全局精确闹钟页 → 应用详情页
- 电池优化：REQUEST_IGNORE_BATTERY_OPTIMIZATIONS(+data=pkgUri) → 列表页(+pkgUri) → 列表页 → 应用详情页
- 悬浮窗：MANAGE_OVERLAY_PERMISSION(+data=pkgUri) → 全局悬浮窗页 → 应用详情页
- 前台服务：应用详情页（AppOps 无公开直达 action）
- 厂商自启动/电池/锁屏：厂商专属组件（多候选）→ 应用详情页

## v1.3.2

### 重构
1. 权限跳转系统重构为**多级降级跳转链**（移植自 Countdown 项目 PermissionChecker v4）
   - 旧版：`intentFor()` 只返回 1 个 Intent，resolveActivity 失败就直接放弃
   - 新版：`buildJumpChain()` 返回完整候选列表，逐个尝试，大幅提高跳转成功率
2. 每项权限的候选链严格按优先级降级，**绝不跳转系统设置首页**：
   - 通知权限：通知页（+package+uid）→ 应用详情页
   - 精确闹钟：精确闹钟请求页（+data=pkgUri）→ 全局精确闹钟页 → 应用详情页
   - 电池优化：直接弹忽略对话框 → 列表页（+pkgUri）→ 列表页 → 应用详情页
   - 厂商自启动/后台运行：厂商专属组件（多个候选）→ 应用详情页
3. 厂商候选链扩充：
   - 华为：ProtectActivity + StartupAppControlActivity 双候选
   - 小米：AutoStartManagementActivity + HiddenAppsConfigActivity 双候选
   - OPPO：coloros.safecenter + oplus.safecenter 双候选
   - 新增魅族、三星厂商适配
4. `startIntent()` 返回 false 时由 UI 层显示 `vendorGuideFor()` 文字引导
5. POCO/OnePlus 品牌识别修正

## v1.3.1

### 修复
1. 权限跳转精准度修复：所有权限按钮现在直达**具体权限页**，不再跳设置首页
   - 通知权限：同时传 EXTRA_APP_PACKAGE + EXTRA_APP_UID，确保国产 ROM 定位到本应用
   - 精确闹钟：设置 data = package URI，直接定位到本应用而非全局列表
   - 电池优化：优先使用 ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS（直接弹对话框）
2. 所有 Intent 添加 FLAG_ACTIVITY_NEW_TASK
3. 新增 startIntent() 方法：启动前先 resolveActivity 检查，失败时 Toast 提示
4. 国产 ROM 自启动/后台运行：不再回退到应用详情页，改为显示详细文字引导
5. OPPO/realme 自启动 Intent 修正为 coloros.safecenter 组件

## v1.3.0

### 重构
1. 权限引导系统全面重构：所有权限跳转直达具体权限页，不再跳转系统设置首页
   - 通知权限 → ACTION_APP_NOTIFICATION_SETTINGS（带包名）
   - 精确闹钟 → ACTION_REQUEST_SCHEDULE_EXACT_ALARM
   - 电池优化 → ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS（带包名）
2. Widget 真实状态检测系统：只信任 AppWidgetManager.getAppWidgetIds()，不信任本地缓存
   - 修复 Widget 假创建问题（APP 显示已创建但桌面无组件）
   - 禁止重复创建 Widget（已添加时按钮禁用 + Toast 提示）
   - requestPinAppWidget 回调正确传递 EXTRA_APPWIDGET_ID
3. 新增 PermissionManager 统一权限管理：
   - 精确检测通知、精确闹钟、电池优化、Widget 状态
   - Android 13+ POST_NOTIFICATIONS 运行时权限请求
   - 国产 ROM 自启动/后台运行权限检测与引导
4. 国产 ROM 适配：华为/荣耀/小米/红米/OPPO/VIVO/iQOO/realme
   - 优先直达厂商专属权限页（resolveActivity 探测）
   - 无法直达时显示详细文字教程（如「设置 → 应用和服务 → 应用启动管理 → ...」）
5. SettingsScreen/PermissionScreen/HomeScreen 统一使用系统 API 检测 Widget 状态
6. 新增字符串资源：permission_autostart、permission_background_run 等

## v1.2.9

### 修复
1. 重构小组件权限引导：移除无效的"打开小组件选择"按钮
2. 新增 openAppPermissionSettings：失败时引导前往系统设置开启「允许创建小组件」权限
3. 统一三处入口（PermissionScreen / SettingsScreen / OnboardingScreen）的小组件引导逻辑
4. 设置页移除"手动添加"按钮，仅保留"添加小组件"按钮
5. 升级 GitHub Actions 到 Node 24 兼容版本（修复 CI 构建失败）

## v1.2.8

### 修复
1. 重构小组件权限引导：移除无效的"打开小组件选择"按钮（openWidgetPicker 在真实设备上基本无效）
2. 新增 openAppPermissionSettings：失败时引导用户前往系统设置开启「允许创建小组件」权限
3. 统一三处入口（PermissionScreen / SettingsScreen / OnboardingScreen）的小组件引导逻辑
4. 设置页移除"手动添加"按钮，仅保留"添加小组件"按钮
5. 引导对话框文案改为权限引导，不再显示手动添加步骤

## v1.2.7

### 修复
1. 重构小组件权限引导：移除无效的"打开小组件选择"按钮（openWidgetPicker 在真实设备上基本无效）
2. 新增 openAppPermissionSettings：失败时引导用户前往系统设置开启「允许创建小组件」权限
3. 统一三处入口（PermissionScreen / SettingsScreen / OnboardingScreen）的小组件引导逻辑
4. 设置页移除"手动添加"按钮，仅保留"添加小组件"按钮
5. 引导对话框文案改为权限引导，不再显示手动添加步骤

## v1.0.0

首个正式版本。

- 首页任务列表：高级圆角卡片、复选框完成动画、删除线与状态过渡
- 添加/编辑任务底部弹窗：标题、描述、截止日期、提醒时间、优先级、分类
- 日历视图：月历布局、按日期查看任务、周/月切换
- 统计页：完成率、优先级分布、分类分布、趋势
- 设置页：主题模式、动态颜色、权限管理入口、检查更新、关于
- Material You 主题，支持浅色/深色模式与系统动态颜色
- 桌面 Widget：小/中/大三种尺寸，与 APP 视觉统一，任务变化自动刷新
- 首次添加任务后的智能 Widget 引导（requestPinAppWidget + 兜底引导）
- 完整权限管理页：通知、Widget、安装未知应用、电池优化，直达对应系统设置
- Room 数据库本地持久化，APP 关闭/重启数据不丢失
- WorkManager 调度任务提醒，适配 Android 13+ 通知权限
- 多源自动更新检测（GitHub / Gitee / jsDelivr），按网络区域智能选源
