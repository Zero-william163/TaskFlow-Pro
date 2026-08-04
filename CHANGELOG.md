# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The GitHub Actions release workflow extracts the section matching the pushed tag
(e.g. `## v1.0.0`) and uses it as the GitHub / Gitee release notes, and embeds it
into `release.json` as the `log` field consumed by the in-app updater.

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
