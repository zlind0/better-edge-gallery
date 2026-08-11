# 02 · 界面与导航

## LiteNavHost（`lite/LiteNavHost.kt`）

整个 App 只有两个路由，起始页是聊天页：

```kotlin
object LiteRoute {
  const val CHAT = "lite_chat"
  const val SETTINGS = "lite_settings"
}
```

```kotlin
NavHost(navController, startDestination = LiteRoute.CHAT) {
  composable(LiteRoute.CHAT)    { LiteChatScreen(..., onNavigateToSettings = { navController.navigate(LiteRoute.SETTINGS) }) }
  composable(LiteRoute.SETTINGS){ LiteSettingsScreen(..., onNavigateBack = { navController.popBackStack() }) }
}
```

设置页是**压在聊天页之上**的，返回就是 `popBackStack()`。聊天页在返回时**不会重新组合成新的**，因为它的关键状态用了 `rememberSaveable`（见下）。

## LiteChatScreen（`lite/LiteChatScreen.kt`）——聊天主界面

这是本项目最核心的编排文件。它**不自己渲染消息气泡**，而是把复用的 `ChatPanel` 装进一个带自定义顶栏的 `ModalNavigationDrawer`（聊天历史抽屉）里。

### 顶栏（`LiteChatTopBar`）

- **左侧**：历史抽屉按钮（`Icons.Rounded.History`）。
- **中间**：当前模型名（`selectedModel.displayName`）。
- **右侧**（从左到右）：新聊天（`AddComment`）、自动朗读开关（`VolumeUp/VolumeOff`）、设置（`Settings`）。

自动朗读按钮点击 → `liteSettingsRepository.saveAutoRead(!autoRead)`，用 `LaunchedEffect(autoRead)` 同步到 `liteTtsManager.setEnabled(...)`。

### 顶栏之下：`ChatPanel`（复用组件）

```kotlin
ChatPanel(
  modelManagerViewModel, task, selectedModel, viewModel,
  innerPadding, navigateUp = {},           // 不向上导航
  onSendMessage = { model, messages -> onSendMessage(model, messages) },
  onRunAgainClicked = { model, message -> onRunAgainClicked(model, message) },
  onBenchmarkClicked = { _, _, _, _ -> },   // 禁用 benchmark
  onStopButtonClicked = { liteTtsManager.reset(); viewModel.stopResponse(selectedModel) },
  showStopButtonInInputWhenInProgress = true,
  showImagePicker = true,
  showAudioPicker = true,
  liteInputLayout = true,                    // 本项目加的：紧凑输入布局
  emptyStateComposable = { model -> ... },   // 空态：提示去设置页下载模型
)
```

`onSendMessage` 是本项目的核心钩子，逻辑见 [06-chat-and-input.md](06-chat-and-input.md)。

### 聊天页里的 `LaunchedEffect` 时序（重要）

这些 effect 的执行顺序决定了功能是否正确：

1. **TTS 初始化**（`LaunchedEffect(Unit)`）：`liteTtsManager.init()` + 应用 rate/pitch/voice。
2. **自动朗读开关**（`LaunchedEffect(autoRead)`）：`setEnabled(autoRead)`。
3. **朗读语言**（`LaunchedEffect(answerLanguageTag)`）：`setLanguage(tag)`，跟随设置的回答语言。
4. **自动选模型**（`LaunchedEffect(modelManagerUiState.tasks)`，`didAutoSelectSavedModel` 守卫，只跑一次）：allowlist 就绪后，读取保存的模型名，找不到就回退 `LiteSettings.DEFAULT_MODEL_NAME`（`Gemma-4-E2B-it`），`selectModel(model)`。
5. **合并保存的参数**（`LaunchedEffect(modelManagerUiState.tasks)`）：对两个支持的模型各调一次 `applySavedConfigs`，让默认值带上用户保存的参数（幂等）。
6. **初始化模型**（`LaunchedEffect(curDownloadStatus, selectedModel.name)`）：下载状态为 `SUCCEEDED` 时 `initializeModel(...)`。
7. **会话重置**（`LaunchedEffect(isModelInitialized, resetKey)`）：见下。

### 会话重置机制（容易踩坑，务必读）

```kotlin
val resetKey = "${selectedModel.name}|$answerLanguage"
var lastResetKey by rememberSaveable { mutableStateOf("") }
LaunchedEffect(isModelInitialized, resetKey) {
  if (isModelInitialized && lastResetKey != resetKey) {
    lastResetKey = resetKey
    liteTtsManager.reset()
    viewModel.resetSession(task, model, systemInstruction = currentSystemPrompt(), ...)
  }
}
```

作用：
- **模型变了**或**回答语言变了** → 用新的 system prompt（含语言指令）重启一个会话，历史清空。这是"改语言 = 新对话"的实现。
- `rememberSaveable` 保证从设置页返回后 `lastResetKey` 不丢，不会误触发清空（见 [07-gotchas.md](07-gotchas.md#remember-状态在导航返回后丢失)）。
- 从历史抽屉恢复会话时，恢复逻辑里会把 `lastResetKey = resetKey` 先置位，防止模型重新初始化时这个 effect 把刚恢复的对话清掉。

### 聊天历史抽屉

- `ModalNavigationDrawer`，`drawerContent` 用复用的 `ChatHistorySideSheetContent`。
- 历史列表来自 `viewModel.historySessions`，按 `task.id == LLM_CHAT` 过滤。
- 点击某条历史：IO 线程 `deserializeProtoMessages(session.messagesList)` → `clearAllMessages` → 逐条 `addMessage` → `onResetSessionClicked(..., clearHistory=false)`（带 `initialMessages` 恢复上下文）→ 恢复 `currentSessionId`。
- 删除某条 / 清空全部 / 新聊天：都会 `onResetSessionClicked(..., clearHistory=true)` 并换一个新的 `currentSessionId`（UUID）。

## LiteSettingsScreen（`lite/LiteSettingsScreen.kt`）——设置界面

一个 `LazyColumn`，四个卡片区：

1. **回答语言（Answer language）**：`LITE_ANSWER_LANGUAGES` 七种语言单选。选中时：
   - `liteSettingsRepository.saveAnswerLanguage(displayName)`
   - `viewModel.applyAnswerLanguage(language)`（更新 LLM_CHAT 的 system prompt，见 [03-settings-and-datastore.md](03-settings-and-datastore.md)）
   - `liteTtsManager.setLanguage(bcp47Tag)`
2. **模型管理（每模型一张 `ModelSettingsCard`）**：两个模型各一张卡，见 [05-model-management.md](05-model-management.md)。
3. **朗读语速/语调（Read-aloud voice）**：`Slider`，语速 `0.5f..8f`、语调 `0.5f..2f`，实时 `setRate/setPitch` + 持久化。
4. **朗读音色（Voice）**：显示当前引擎（`Engine: %1$s`），列出 `availableVoices()`（匹配回答语言的在前面），单选或"跟随系统默认"；底部有"Test read-aloud"按钮调 `speakTest()`。

**注意**：设置页里任何语言/参数改动，返回聊天页后由 `LiteChatScreen` 的 effect 生效；模型在设置页**被选中**时 `selectModel` + `saveSelectedModel` 已同步。

## 顶栏字符串

新增字符串都在 `values/strings.xml` 的 `lite_*` 前缀下（`lite_settings`、`lite_auto_read`、`lite_answer_language`、`lite_model_parameters`、`lite_model_not_downloaded`、`lite_tts*`、`lite_recording_in_progress`、`lite_use_for_chat`、`lite_downloaded`、`lite_download_progress/rate/remaining/unzipping`、`lite_reset` 等）。
