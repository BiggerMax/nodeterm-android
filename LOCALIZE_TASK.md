# 任务：为 nodeterm Android 客户端添加中文语言选项（完整 i18n）

> 本文件是一份自包含的任务规格。请在阅读后直接执行实现，不要回来询问——所有必要信息都在下面。
> 完成后请自行运行构建与测试验证，再报告结果。

## 背景

项目路径：`/Users/yuanjie/Documents/work/Nodeterm_android`，当前工作目录即此。
这是一个纯 Kotlin + Jetpack Compose 的 Android 客户端（`:app` 模块 + `:core` 纯 JVM 模块）。当前 UI 文本全部硬编码为英文字符串，散落在各 Composable 的 `Text("...")` / `label` / `placeholder` 等调用里。`app/src/main/res/values/strings.xml` 只有 3 个字符串（app_name, notification_channel_name, notification_channel_desc）。

用户要求"添加中文选项"——即：让整个 UI 支持中文，并在设置页提供语言切换（跟随系统 / English / 中文）。

## 架构事实（已调查确认，无需重复调研）

- `app/src/main/java/com/nodeterm/android/NodetermApp.kt`：`class NodetermApp : Application()`，`onCreate` 里调用 `NotificationHelper.ensureChannel(this)`。
- `app/src/main/java/com/nodeterm/android/MainActivity.kt`：`class MainActivity : ComponentActivity()`，用 `setContent { NodetermTheme { ... } }`。**没有 AppCompat**（依赖里无 `androidx.appcompat`）。导航用 `androidx.navigation.compose`。
- `app/src/main/java/com/nodeterm/android/data/UiPrefsStore.kt`：基于 `SharedPreferences("ui", MODE_PRIVATE)` 的偏好存储，已有 `dismissedNodes` / `dismissedInbox` / `nodeOrder` 三个属性。注释明确说它放在独立 prefs 文件，`SessionStore.clear()` 不会清掉它——这是 UI 偏好的正确归处。
- `:core` 是纯 Kotlin/JVM，无 Android 资源，不需要动。
- `app/build.gradle.kts` 依赖了 `androidx.activity.compose`、`material3`、`navigation.compose` 等，无 appcompat。

## 实现方案（你要完整实现，不留 TODO）

### 1. 字符串资源化
- 把下面"完整字符串清单"里所有硬编码英文字符串抽到 `app/src/main/res/values/strings.xml`（扩展现有文件），给每个起一个语义化命名（如 `settings_title`、`action_approve`、`state_connected`、`home_nodes_count` 等）。
- 对带参数的字符串用格式化占位符：如 `"Nodes (%1$d)"`、`"Version %1$s"`、`"%1$d project · %2$d node"`。
- 对 `node(s)`/`project(s)` 这两处复数用 `<plurals>`（英文区分单复数，中文不区分，plurals 正好处理）。
- 创建 `app/src/main/res/values-zh/strings.xml`，给出所有字符串的简体中文翻译。翻译要自然、贴合产品语境（这是一个"基于节点的终端管理器 + AI agent 状态流"的伴侣客户端）。下面清单里已给出中文翻译建议，可直接用或优化。

### 2. 语言偏好存储
- 在 `UiPrefsStore` 里新增一个 `language: String` 属性，取值：`"system"`（默认）、`"en"`、`"zh"`。用 `KEY_LANGUAGE` 常量。保持该类既有的 SharedPreferences 风格。

### 3. Locale 应用
由于没有 AppCompat，采用 `attachBaseContext` 包裹 Context 的方式：
- 新建 `app/src/main/java/com/nodeterm/android/LocaleManager.kt`，实现：
  - `wrap(context: Context): ContextWrapper` —— 读 `UiPrefsStore(context).language`，非 system 时返回带 locale 的 context。
  - `setLocale(context, lang)`、`currentLanguage(context)` 等辅助方法。
  - 解析："system" → 不强制（用系统默认）；"en" → `Locale("en")`；"zh" → `Locale.SIMPLIFIED_CHINESE`。
  - 用 `context.createConfigurationContext(configuration)`（API 17+，minSdk 应满足）。配合一个 `ContextWrapper` 子类让 `getApplicationContext` 等正确委托。同时 `Locale.setDefault(...)` 让后台通知也能拿到正确语言。
- 在 `MainActivity` 里 override `attachBaseContext(newBase: Context)`：调用 `LocaleManager.wrap(newBase)`。
- 在 `NodetermApp` 里 override `attachBaseContext(newBase: Context)`：调 `super` 后 `Locale.setDefault(...)` 设好默认 locale（供后台通知使用）。

### 4. 设置页语言切换 UI
- 在 `SettingsScreen.kt` 新增一个 "Language / 语言" 区块（放在 Session 区块之后或 About 之前），加一个语言选择器：
  - 三选一：跟随系统 (System) / English / 中文。
  - 用 material3 的 `SegmentedButton`（`SingleChoiceSegmentedButtonRow`），若当前 BOM 版本不可用则用三个 `FilterChip` 单选样式。不要硬塞依赖。
  - 选中后调用 `onLanguageChange(code)` 回调。
- `SettingsScreen` 签名新增 `language: String` 和 `onLanguageChange: (String) -> Unit` 两个参数。
- `MainActivity` 的 `composable("settings")` 处传入 `language = UiPrefsStore(this).language` 和 `onLanguageChange = { code -> UiPrefsStore(this).language = code; recreate() }`。语言切换后调 `recreate()` 让新 locale 生效。

### 5. 替换所有硬编码字符串
- 把清单里每一处 `Text("...")` → `Text(stringResource(R.string.xxx))`；`label = { Text("...") }` → `label = { Text(stringResource(R.string.xxx)) }`；`placeholder`、`supportingText` 同理。
- 对带占位的，用 `stringResource(R.string.xxx, arg1, ...)`。
- 注意：品牌名 "nodeterm" 中英文都用 "nodeterm"（不译）；URL 不译；`‹ Back` 的箭头保留 `‹` 前缀，中文译为 `‹ 返回`。

### 6. 通知字符串
- `notify/NodetermMessagingService.kt` 里的 `"Completed"` / `"Needs you"` / `"An agent on your host needs attention."` 也要资源化（`context.getString(R.string...)`）。通知在后台服务里生成，用 `Locale.getDefault()` 即可——app 进程的 locale 已由 `Locale.setDefault` 设好。

## 完整字符串清单（按文件，已附中文翻译建议）

### SettingsScreen.kt
- "‹ Back" → `‹ 返回`
- "Settings" → `设置`
- "Session" → `会话`
- "Connection" (InfoRow label) → `连接`
- "connected"/"disconnected" (InfoRow value) → `已连接`/`已断开`
- "Channel SAS" → `通道 SAS`
- "Endpoint" → `端点`
- "Host" (InfoRow label) → `主机`
- "%d project · %d node" (plurals) → `%d 个项目 · %d 个节点`
- "Direct connection (SSH)" → `直连 (SSH)`
- "Host address" → `主机地址`
- "e.g. 100.64.0.1 or host name" → `例如 100.64.0.1 或主机名`
- "Off-LAN access: enter the host's Tailscale IP to connect from anywhere — the saved SSH key keeps working, no re-pairing." → `局域网外访问：填入主机的 Tailscale IP 即可随处连接——已保存的 SSH 密钥仍然有效，无需重新配对。`
- "Save & reconnect" → `保存并重连`
- "Shortcuts & gestures" → `快捷键与手势`
- "Jump anywhere (⌘K)" → `随处跳转 (⌘K)`
- "Tap the search icon in the home header" → `点击首页顶部的搜索图标`
- "Node actions (right-click)" → `节点操作（右键）`
- "Long-press a node card" → `长按节点卡片`
- "Focus a canvas node" → `聚焦画布节点`
- "Double-tap it on the board" → `在看板上双击该节点`
- "Scroll terminal history" → `滚动终端历史`
- "Swipe up / down in the terminal" → `在终端中上下滑动`
- "Dictate (⌘⇧D)" → `语音输入 (⌘⇧D)`
- "Tap the mic in a terminal — review, then send" → `在终端中点击麦克风——确认后发送`
- "Notifications" → `通知`
- "Host event notifications" → `主机事件通知`
- "Needs-you / done pushes from the host (requires a Firebase project wired in — see README)." → `来自主机 needs-you / 完成事件的推送（需接入 Firebase 项目——见 README）。`
- "Connection" (section title) → `连接`
- "Disconnect (keep pairing)" → `断开连接（保留配对）`
- "Unpair and reset" → `取消配对并重置`
- "About" → `关于`
- "nodeterm Android companion" → `nodeterm Android 伴侣`
- "Version %s" → `版本 %s`
- "nodeterm.dev" → 不译
- "GitHub · eneskirca/nodeterm" → 不译
- "E2EE relay client — protocol docs in ANDROID_CLIENT_SPEC.md." → `E2EE 中继客户端——协议文档见 ANDROID_CLIENT_SPEC.md。`
- 新增（语言区块）：`语言` (Language section), `跟随系统` (System), `English`, `中文`

### HomeScreen.kt
- "nodeterm" → 不译（品牌）
- "Connected to host" → `已连接到主机`
- "Connecting to host…" → `正在连接主机…`
- "Disconnected" → `已断开`
- "Nodes (%d)" → `节点 (%d)`
- "Re-pair" → `重新配对`
- "Files" → `文件`
- "Open terminal" → `打开终端`
- "Browse files" → `浏览文件`
- "Copy path" → `复制路径`
- "Clear all" → `全部清除`
- "Approve" → `批准`
- "Deny" → `拒绝`

### PairingScreen.kt
- "Try again" → `重试`
- "Open settings" → `打开设置`
- "nodeterm://pair?code=… or host QR text" → `nodeterm://pair?code=… 或主机二维码文本`
- "Connect" → `连接`
- "Connecting to host…" → `正在连接主机…`
- "Codes match — connect" → `校验码一致——连接`
- "Cancel" → `取消`

### Dictation.kt
- "Microphone permission needed" → `需要麦克风权限`
- "Cancel" → `取消`
- "Allow" → `允许`
- "Dictate" → `语音输入`
- "Stop"/"Restart" (if listening) → `停止`/`重启`
- "Spoken text appears here…" → `语音文本显示于此…`
- "Send" → `发送`

### FileBrowserScreen.kt
- "Files" → `文件`
- "Git" → `Git`
- "Up" → `上级`
- "Empty folder" → `空文件夹`
- "Git status" → `Git 状态`
- "Refresh" → `刷新`
- "No response" → `无响应`
- "No changes" → `无改动`
- "No diff" → `无差异`

### KanbanBoard.kt
- "Done" → `完成`
- "Clear priority" → `清除优先级`
- "Add member" → `添加成员`
- "Add" → `添加`
- "Clear" → `清除`
- "No due date" → `无截止日期`
- "No comments yet." → `暂无评论。`
- "Add a comment" → `添加评论`
- "Comment" → `评论`
- "Open terminal" → `打开终端`

### BoardScreen.kt
- "Board" → `看板`
- "Refresh" → `刷新`
- "Fit" → `适配`

### CommandPalette.kt
- "Jump to a node, project or action…" → `跳转到节点、项目或操作…`

### NodeDetailScreen.kt
- "Approve" → `批准`
- "Deny" → `拒绝`

### notify/NodetermMessagingService.kt
- "Completed" → `已完成`
- "Needs you" → `需要你`
- "An agent on your host needs attention." → `主机上的一个 agent 需要关注。`

### notify/NotificationHelper.kt / 现有 strings.xml
- "Host events" (notification_channel_name) → `主机事件`
- "Needs-you, running and done events pushed from your host" (notification_channel_desc) → `来自主机 needs-you、运行中和完成事件的推送`

## 工程要求
- **编译必须通过**。完成后运行 `./gradlew :app:assembleDebug` 验证构建成功；再运行 `./gradlew :core:test :app:testDebugUnitTest` 确保未破坏现有测试。若某测试因字符串改动失败（例如断言匹配旧字符串），更新测试以匹配新流程——但优先保持测试通过。
- 不留 TODO、不留占位符、不造假数据。
- 保持代码风格与周围一致（注释密度、命名习惯）。中文翻译文案要专业、自然。
- 不要动 `:core` 模块。
- 若 `SegmentedButton` 在当前 BOM 不可用，用 `FilterChip` 组合实现单选即可，不要硬塞依赖。
- `plurals` 用于 project/node 计数那两处；其余简单字符串用普通 `<string>`。
- 处理好 `MainActivity` 重建：语言切换后调 `recreate()`。冷启动时 `attachBaseContext` 读 `UiPrefsStore` 得到上次的语言选择——确保 `UiPrefsStore` 在 `attachBaseContext` 阶段可读（SharedPreferences 可在此时读）。
- `LocaleManager` 要处理：选 "system" 时恢复系统 locale（不强制覆盖），选 "en"/"zh" 时强制。

## 交付报告
完成所有改动、构建通过、测试通过后，报告：改了哪些文件、`strings.xml`/`values-zh/strings.xml` 新增了多少条字符串、语言切换如何工作（设置入口 + 生效方式）、构建与测试结果。
