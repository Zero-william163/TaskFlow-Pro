# Changelog

All notable changes to this project are documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The GitHub Actions release workflow extracts the section matching the pushed tag
(e.g. `## v1.0.0`) and uses it as the GitHub / Gitee release notes, and embeds it
into `release.json` as the `log` field consumed by the in-app updater.

## v2.9.0

### 新增 — 番茄专注沉浸模式 + 已完成全选删除 + 表单默认值变更 + 任务卡片交互分流

#### 一、 紧急修复项与表单默认值变更 (Form & UI Fixes)

1. **【已完成】Tab 强制渲染「清空全部」工具栏**
   - 在 Completed Task 列表顶部 Header 行渲染：左侧 `已完成 (X项)`、右侧 `🗑️ 清空全部` 按钮。
   - 点击弹出 M3 确认弹窗，确认后执行 DAO `DELETE FROM task_table WHERE isCompleted = 1`。
   - 在【已完成】Tab 下点击卡片禁用编辑，弹窗提示「确定彻底删除该任务？」，确认后单条删除。

2. **DatePicker 数字裁剪 Bug**
   - 给 `DatePickerDialog` 显式添加 `properties = DialogProperties(usePlatformDefaultWidth = false)`，确保右侧网格数字（14/21/28）完全无遮挡展示。

3. **截止日期（DueDate）改为【可选项】**
   - `TaskEntity` 增加 `hasDueDate: Boolean = false`。
   - 表单中增加开关：**默认关闭（不设截止日期，从开始日期起无限期执行）**。开启后方可选择具体截止日期。

4. **提醒时间与模式默认值修正**
   - 提醒时间默认开关设为 `isReminderEnabled = true`（开启）。
   - 提醒频率默认选项修改为 **「每日」** (`ReminderMode.DAILY`)。

5. **自定义分类 & 颜色 (Custom Category)**
   - 分类 Chip 末尾增加 `＋ 自定义` 按钮，点击弹窗包含：名称输入框（限 6 字）+ 莫兰迪色系调色盘（10 色）。保存后存入 `CategoryEntity` 并自动选中。
   - 长按自定义分类 Chip 可删除（系统弹窗确认），内置分类不可删除。

6. **专注时长配置**
   - `TaskEntity` 增加 `focusDurationMinutes: Int = 25`。表单提供 FilterChips：`[10分]` `[25分(默认)]` `[35分]` `[⚙️自定义]`。

#### 二、 任务卡片交互分流 (Card Touch Zones)

1. **右侧 Checkbox**：仅触发任务完成/恢复状态切换。
2. **右上角编辑图标 (新增)**：渲染 `Icons.Outlined.Edit` 按钮，**仅点击此图标才唤起【编辑任务】弹窗**。
3. **卡片主体 (Card Body)**：点击卡片空白/文本区域，**直接带参 `taskId` 跳转进入 `PomodoroScreen` (番茄专注页)**。

#### 三、 番茄专注沉浸页面 (Pomodoro Focus Screen)

新建 `PomodoroScreen.kt` 及配套 ViewModel / AudioPlayerManager：

1. **屏幕常亮 (Keep Screen On)**
   - 底部提供 `[ ☀️ 开启屏幕常亮 ]` 切换胶囊按钮。
   - 状态开启时，通过 `DisposableEffect` 对当前 Window 动态设置 `WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`，关闭/退出页面时自动清除标记。

2. **核心 UI**
   - 沉浸式壁纸背景（深紫渐变 + 星点 Canvas）+ 顶部励志名言（8 句随机）+ 极简环形倒计时器（显示 `MM:SS`，初始值为任务设定的 `focusDurationMinutes`）。
   - 底部控制栏：`[ 🎵 背景音 ]`、`[ ▶/⏸ 播放/暂停 ]`（放大渐变主控按钮）、`[ ↺ 重置 ]`。

3. **双音源背景音乐播放器 (AudioPlayerManager)**
   - 弹窗 BottomSheet 提供切换：`[💾 本地离线]`（读取 `res/raw` 雨声/滴答声等）与 `[🌐 在线流媒体]`（网络音频 URL）。
   - 预置分类 Tab：`[自然音]` `[氛围音乐]` `[轻音乐]`，本地资源缺失时友好 Toast 提示而不崩溃。

4. **专注统计打通**
   - 倒计时完成（100%）时，自动写入 `focus_history` 表（包含 `taskId`, `durationMinutes`, `timestamp`）。
   - `StatisticsScreen` 新增「番茄专注」卡片：累计专注时长 + 完成轮数 + 近 7 天专注分钟柱状图，数据实时联动。

#### 数据层
- `TaskEntity` 新增 `hasDueDate: Boolean` 与 `focusDurationMinutes: Int` 字段。
- 新建 `FocusHistoryEntity` / `FocusHistoryDao` / `FocusHistoryRepository`（专注历史记录仓库）。
- 数据库版本 v6 → v7 迁移：`ALTER TABLE tasks ADD COLUMN hasDueDate/focusDurationMinutes` + `CREATE TABLE focus_history`。
- `ServiceLocator` 注册 `FocusHistoryRepository`；`StatsViewModel` 注入并扩展为 12 路 combine 流。

#### 构建信息
- versionCode: 47
- versionName: 2.9.0

---

## v2.8.0

### 修复 — 已完成 Tab 清空按钮、主界面冻结、DatePicker 时区、检查更新安装、小组件点击

1. **已完成 Tab 清空按钮**：在【已完成】Tab 且列表非空时，于搜索框/分类 Tab 与任务列表之间显示 `已完成 (共 X 项)` + 红色 `🗑️ 清空全部` Header 行；点击弹出确认对话框清空所有已完成任务。
2. **主界面全局冻结**：`TaskCompletionDialogActivity` 添加 `setOnDismissListener` 确保对话框关闭时 Activity 调用 `finish()`，`onDestroy` 取消协程，避免透明 Activity 拦截触摸事件。
3. **DatePicker 时区 Bug**：Material3 DatePicker 内部按 UTC 解释时间戳，转换回 `LocalDate` 时统一使用 `ZoneId.of("UTC")`，修复默认选中昨天的时区偏移问题。
4. **检查更新下载后不弹安装界面**：Manifest 已声明 `REQUEST_INSTALL_PACKAGES`，配置 `FileProvider` + `res/xml/file_paths.xml`，下载完成用 `content://` URI 启动 `ACTION_VIEW` 安装；检查更新图标增加旋转 Loading 动效。
5. **小组件卡片点击无响应**：使用 `PendingIntentTemplate` 配合 `setOnClickFillInIntent`，确认后更新数据库并调用 `notifyAppWidgetViewDataChanged` 刷新。
6. **跨应用/锁屏全屏闹钟**：Manifest 声明 `USE_FULL_SCREEN_INTENT` / `SCHEDULE_EXACT_ALARM` / `WAKE_LOCK` / `VIBRATE`，通过 `Notification.setFullScreenIntent` 实现跨应用与锁屏全屏强弹。

---

## v2.1.4

### 修复 — 应用内更新无法下载 APK

#### 真实根因
**`GitHubApiSource` 只返回 GitHub 直链**（`region: "international"`），不包含国内镜像 URL。当 `GitHubApiSource` 优先于 `release.json` raw 源成功获取到更新信息时，`release.json` 中配置的镜像 URL（GH Proxy / GH Fast）被完全丢弃。国内用户唯一的下载 URL 是 GitHub 直链 → 网络不可达 → 下载失败。

#### 修复项
1. **`UpdateSource.kt` GitHubApiSource**：返回的 `downloadUrls` 从仅 GitHub 直链改为包含 3 个镜像：GH Proxy（domestic）、GH Fast（cdn）、GitHub（international），确保国内用户有可用的下载源
2. **`release.json`**：移除无效的 Gitee 下载 URL（Gitee 未配置 release），避免国内用户先尝试 Gitee 下载 → 404 失败

---

## v2.1.3

### 修复 — 小组件「Problem loading widget」真正根因

#### 真实根因
**所有 Widget 布局中使用了 `<View>` 元素，而 `<View>` 不在 RemoteViews 支持的 View 列表中。**

Android 官方文档明确列出了 RemoteViews 支持的布局和控件类：
- 布局：FrameLayout、LinearLayout、RelativeLayout、GridLayout
- 控件：AnalogClock、Button、Chronometer、ImageButton、ImageView、ProgressBar、TextView、ViewFlipper、ListView、GridView、StackView、AdapterViewFlipper

**`View` 不在其中。** `widget_loading.xml`、`widget_content.xml`、`widget_preview.xml` 三个布局文件都使用了 `<View>` 作为分隔线/装饰点，导致 Launcher 进程 inflate 失败。

#### 为什么 v2.1.2 的 try/catch fallback 无效
**关键机制：`RemoteViews(packageName, layoutId)` 构造函数不会 inflate 布局** — 它只存储 layout resource ID。真正的 inflate 发生在 **Launcher 进程** 中（当 Launcher 收到 `updateAppWidget` 调用时）。

这意味着：
1. `RemoteViews(context.packageName, R.layout.widget_loading)` → 成功（只是存储 ID）
2. `appWidgetManager.updateAppWidget(id, views)` → 成功（只是发送序列化数据到 Launcher）
3. Launcher 尝试 inflate `widget_loading` → 失败（`<View>` 不被支持）→ 异常在 **Launcher 进程** 中抛出
4. 我们的 `try/catch` **永远不会捕获这个异常**，因为异常不在我们的进程中
5. Launcher 显示 "Problem loading widget"，我们的代码继续执行以为一切正常

#### 修复项
1. **`widget_loading.xml`**：`<View>` → `<ImageView>`（添加 `android:contentDescription="@null"`）
2. **`widget_content.xml`**：两处 `<View>` → `<ImageView>`（logo dot + 分隔线）
3. **`widget_preview.xml`**：两处 `<View>` → `<ImageView>`（logo dot + 分隔线）
4. **`task_widget_info.xml`**：`initialLayout` 从 `widget_loading` 改为 `widget_test`（纯 `LinearLayout + TextView` + 硬编码颜色，零资源引用，绝对安全）
5. **`TaskWidgetProvider.onUpdate()`**：先推 `widget_test`（最安全），再异步加载实际内容
6. **`TaskListRemoteViewsService.loadingView()`**：从 `widget_loading` 改为 `widget_test`

---

## v2.1.2

### 修复 — 小组件已成功添加到桌面但显示「Problem loading widget」

#### 真实根因
1. **`onUpdate()` 异步加载 RemoteViews，中间无任何即时占位**：Launcher 拿到 Provider 回调后要求在 `onUpdate()` 同步返回合法 RemoteViews，而我们只在 IO 线程完成后才 `updateAppWidget`，Launcher 在此空档期直接显示「Problem loading widget」
2. **`WidgetHelper.buildViews()` 任何步骤抛异常都未捕获**：Room 查询、`describeTime` 时区、`setRemoteAdapter`、PendingIntent flag 等任何一个异常 → Launcher 判定 Widget 构建失败 →「Problem loading widget」
3. **`TaskListRemoteViewsService.Factory.getViewAt()` 没兜底**：`setTextViewText(R.id.item_title,...)`、`describeTime`、`PendingIntent.getBroadcast` 任一异常 → 工厂返回异常 → ListView 整列失败
4. **TaskWidgetProvider 未处理「第一次创建还没完成数据加载」**：创建 Widget 的几十毫秒窗口无法读到 Room，直接抛错

#### 修复项
1. **`TaskWidgetProvider.onUpdate()` 立即返回占位 RemoteViews**：在 forEach 循环内 `try { appWidgetManager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_loading)) } catch(...)`，确保 Launcher 第一时间收到合法 UI，再异步调用 `WidgetHelper.refreshAppWidget(id)` 加载实际数据
2. **新增 `widget_test.xml` 最小化兜底布局**：纯原生 `LinearLayout + TextView "TaskFlow Widget Test"`，无引用 drawable、无 ListView、无 constraint，作为最终 fallback
3. **`WidgetHelper.buildForId()` 四级 Fallback**：
   - Level 1：完整 Room + ListView 版本
   - Level 2：remaining=0 简化版（仍有 ListView，空数据）
   - Level 3：彻底移除 ListView，只显示标题栏 + 日期 + 「暂无任务」
   - Level 4：`widget_test.xml` 最小化
   每一级失败都记录 stacktrace，并打印 fallback 决策
4. **`TaskListRemoteViewsService.Factory.onDataSetChanged()` 加 try/catch**：Room 查出来后再按 `position < tasks.size` 再判一次，杜绝越界；`getLoadingView()` 返回 `widget_loading_view.xml`（单独 layout，不会依赖 task_list）
5. **`Factory.getViewAt()` 逐语句 try/catch + 顶层兜底**：`setTextViewText` / `describeTime` / `setImageViewResource` / `toggle PendingIntent` / `fillIn` 分别 try，最终外层再兜底，失败一律返回 `loadingView()`，绝不抛异常给 Launcher
6. **TaskWidgetProvider 缺失 import 修复**：补上 `android.widget.RemoteViews` 与 `com.taskflow.app.R`

---

## v2.1.1

### 修复 — Widget「点击添加无反应」+ 静默吞异常 + 元数据矛盾

#### 真实根因
1. **`WidgetHelper.requestPinWidget()` 静默吞掉所有异常**：`catch (_: Throwable) { false }` 用 `_` 丢弃异常对象，Logcat 完全看不到 `SecurityException` / `IllegalArgumentException`，叠加 `pinned==true` 时只弹 Toast 直接 return，用户感知为"点击没反应"
2. **`task_widget_info.xml` 元数据自相矛盾**：`android:configure=""`（空字符串非合法值）+ `widgetFeatures="reconfigurable"`（声明可重配置但 configure 指向空），部分国产 Launcher 行为异常
3. **`addWidget()` 全链路无日志**：无法从 Logcat 还原 `isRequestPinAppWidgetSupported` / `requestPinAppWidget` 返回值 / 当前 Launcher 包名等关键诊断信息
4. **`UserPreferences.widgetAdded` 死代码残留**：Widget 状态本应由 `AppWidgetManager.getAppWidgetIds()` 实时查询，但 `widgetAdded` Key/Flow/setter 未清理彻底

#### 修复项
1. **暴露异常 + 诊断字段**：`WidgetHelper.requestPinWidget()` 改为打印完整 stacktrace 到 Logcat，同时暴露 `lastPinError` / `lastPinDiagnostic` 两个 @Volatile 字段供 UI 读取
2. **新增 `getLauncherPackage()`**：诊断日志记录当前默认 Launcher 包名，便于区分「Launcher 不支持」还是「异常失败」
3. **`PermissionScreen.addWidget()` 优化**：`pinned==true` 不再只弹 Toast 直接 return，改为同时显示引导 Dialog；`pinned==false` 显示异常类型 + 诊断信息
4. **`task_widget_info.xml` 修复**：删除 `android:configure=""` 和 `widgetFeatures="reconfigurable|configuration_optional"`，元数据与代码（无配置 Activity）一致
5. **移除 `UserPreferences.widgetAdded` 死代码**：删除 Key / Flow / setWidgetAdded()，彻底消除双源不一致风险
6. **修正 Onboarding 误导文案**：不再提示"开启允许创建小组件权限"，改为说明 Android 无小组件权限，给出手动添加步骤
7. **全链路调试日志**：`TaskWidgetProvider` / `WidgetPinResultReceiver` / `TaskListRemoteViewsService` / `WidgetHelper.buildViews` / `refresh` 全部添加分隔线 + ✅/❌ 标记日志，Logcat 过滤 `WidgetHelper|WidgetProvider|WidgetPinCallback|WidgetListService|PermissionScreen` 即可还原完整链路
8. **国内镜像仓库前置**：`settings.gradle.kts` 把阿里云 / 腾讯云镜像放在 `google()` / `mavenCentral()` 之前，国内构建更稳定

## v2.1.0

### 增强 — 桌面滑动小组件 + 受限设置精准引导

1. **桌面滑动小组件（AppWidget）与动态列表**
   - 使用 `RemoteViewsService` + `RemoteViewsFactory` + `ListView` 展示未完成任务，支持桌面上滑/下滑
   - 卡片项「勾选完成」按钮点击 → 发送广播 → Repository 更新 `isCompleted = true` → `notifyAppWidgetViewDataChanged` 刷新
   - 主 App 通过 Room 响应式监听同步任务状态

2. **免悬浮窗权限的桌面固定小组件（AppWidget Pinning）**
   - 使用 Android 8.0+ 原生 `requestPinAppWidget`，**不需要 SYSTEM_ALERT_WINDOW 权限**，避开悬浮窗变灰问题
   - 新建任务保存时智能判断：检测桌面是否已有 Widget（`getAppWidgetIds`），若无则弹窗询问是否创建
   - 国产 ROM 兼容兜底：`requestPinAppWidget` 返回 false 或抛异常时，弹出手把手引导 Dialog
   - 新增 [WidgetAndPermissionHelper.kt](app/src/main/java/com/taskflow/app/widget/WidgetAndPermissionHelper.kt) 统一工具类

3. **精准处理「受限制设置 (Restricted Settings) 变灰」引导**
   - **不直接跳转** `ACTION_MANAGE_OVERLAY_PERMISSION`（开关灰色用户无法点击）
   - 检测到悬浮窗受限时，先弹步骤引导 Dialog（4 步操作指引）
   - 确认后精确跳转应用详情页：`ACTION_APPLICATION_DETAILS_SETTINGS`
   - 引导用户：三点菜单 (⋮) → 允许受限制的设置 → 返回开启悬浮窗

4. **Manifest 与小组件配置**
   - 新增 `SYSTEM_ALERT_WINDOW` 权限声明
   - `task_widget_info.xml` 新增 `widgetFeatures="reconfigurable|configuration_optional"`

## v2.0.1

### 修复 — 悬浮窗权限变灰 + Widget 添加无反应

1. **悬浮窗权限变灰（Android 13+ Restricted Settings）**
   - 根因：单文件侧载安装的应用被系统标记为「受限设置」，`SYSTEM_ALERT_WINDOW` 开关显示为灰色
   - 修复：新增 `isOverlayRestricted()` 检测，通过 `AppOpsManager.checkOp()` 判断权限是否被限制
   - 修复：新增 `jumpToRestrictedSettings()` 跳转方法，直达「允许受限制的设置」页面
   - 修复：`buildJumpChain(OVERLAY)` 优先级 1 新增 `ACTION_MANAGE_APP_ROLE_PERMISSIONS` 跳转让链
   - 修复：PermissionScreen 悬浮窗点击时检测受限状态，自动跳转解封页面或显示图文引导

2. **Widget 添加无反应（华为等厂商设备）**
   - 根因：`WidgetCapability.report()` 中 `canAutoPin = apiSupported && launcherSupported && !vendorRestricted`，
     华为在 `restrictedVendors` 列表中导致 `canAutoPin=false`，直接跳过了 `requestPinAppWidget` 尝试
   - 修复：`canAutoPin` 不再因厂商限制而跳过，只要 `apiSupported && launcherSupported` 就尝试自动添加
   - 修复：`addWidget()` 流程优化——先尝试 `requestPinAppWidget`，成功则等待系统弹窗确认；失败再降级引导
   - 修复：即使 `isRequestPinAppWidgetSupported=false`，也会先尝试一次 `requestPinAppWidget` 让系统返回真实结果

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
