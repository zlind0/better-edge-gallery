# 01 · 总体架构

## 一句话

Edge Gallery Lite 是一个 **"换壳"工程**：它不重新实现任何 AI 能力，而是把 Google AI Edge Gallery 的 `app` 模块整个拿过来，包上自己的一层 `lite` 界面和设置，做出"打开就是聊天、设置精简、自动朗读"的产品。

## 关键事实

- **applicationId / 包名**
  - 安装包：`com.example.edgegallerylite`（在 `app/build.gradle.kts` 的 `defaultConfig.applicationId`）
  - 源码包：`com.google.ai.edge.gallery`（继承自 Google 原工程）
  - 类包 `com.google.ai.edge.gallery.lite` 下的文件是本项目**新增/改动**的自定义层。
- **入口**：`MainActivity`（`com.google.ai.edge.gallery.MainActivity`），`@AndroidEntryPoint`，`onCreate` 里直接 `setContent { GalleryApp(...) }`，**无启动页、无欢迎屏**。
- **技术栈**：Kotlin 2.2.0 / AGP 8.13.0 / Gradle 9.2.1 / compileSdk 37 / minSdk 31 / targetSdk 37；Jetpack Compose + Material3；Hilt DI；Navigation Compose；Proto DataStore；LiteRT-LM（`com.google.ai.edge.litertlm:litertlm-android:0.11.0`）；WorkManager（模型下载后台任务）。

## 两层代码的边界（维护时最关键的分界）

```
com.google.ai.edge.gallery
├── lite/          ← 【本项目自定义层，改这里是"改产品行为"】
│   ├── LiteNavHost.kt            导航（只有聊天页 + 设置页）
│   ├── LiteChatScreen.kt         聊天主界面（组合/编排层）
│   ├── LiteSettingsScreen.kt     设置界面
│   ├── LiteSettings.kt           常量 + 回答语言 + LiteSettingsRepository（持久化仓库）
│   ├── LiteSettingsViewModel.kt  设置页的 ViewModel
│   └── LiteTtsManager.kt         朗读引擎 + markdown 剥离器
│
├── MainActivity / GalleryApp.kt / GalleryApplication.kt   ← 入口与注入（少量改动）
├── data/          ← 复用 gallery：Model/Task/Config/DataStore/Download/Allowlist 等
├── ui/            ← 复用 gallery：modelmanager/llmchat/common/chat/textandvoiceinput 等
│   ├── modelmanager/ModelManagerViewModel.kt   模型状态总管（下载/初始化/选择）
│   ├── llmchat/LlmChatViewModel.kt             聊天气话生成/会话/历史
│   ├── llmchat/LlmChatTaskModule.kt            LLM_CHAT 任务的定义（Hilt module）
│   └── common/chat/*                           聊天面板、气泡、输入框、录音等 UI
├── worker/DownloadWorker.kt     复用：模型文件后台下载
├── di/AppModule.kt              复用：DataStore/仓库注入
└── ...其余 gallery 原文件（agent/customtasks/skills 等）   ← 未使用，但保留
```

**原则**：`lite/` 只管"界面编排 + 设置持久化 + 朗读"，**不碰推理逻辑**；推理全部调用 `ModelManagerViewModel` / `LlmChatViewModel` / `LlmChatTask` 这些复用层。加功能时，先想清楚是改 `lite/` 还是动复用层——尽量只改 `lite/`，避免动 gallery 核心。

## 启动时序（一进 App 发生什么）

1. `GalleryApplication.onCreate`：Hilt 初始化，读主题、初始化通知调度。
2. `MainActivity.onCreate`：`modelManagerViewModel.loadModelAllowlist()`（从**打包进 assets 的 allowlist** 秒读，无闪屏），随后 `setContent` 渲染 `LiteNavHost`。
3. `LiteNavHost` 的起始路由是 `lite_chat` → `LiteChatScreen`。
4. `LiteChatScreen` 内部几个 `LaunchedEffect` 依次做（顺序有讲究，详见 [02-screens-and-navigation.md](02-screens-and-navigation.md)）：
   - 初始化 `LiteTtsManager` 并应用已保存的 rate/pitch/voice；
   - allowlist 就绪后**自动选中并加载上次保存的模型**（默认 `Gemma-4-E2B-it`）；
   - 把保存的模型参数合并进模型默认值（`applySavedConfigs`）；
   - 下载成功则 `initializeModel`；
   - 模型就绪后用"回答语言"拼出的 system prompt 重置会话。

## 两个页面的路由

```
LiteRoute.CHAT     = "lite_chat"      ← 起始页
LiteRoute.SETTINGS = "lite_settings"  ← 从聊天页右上角齿轮进入，popBackStack() 返回
```

返回后聊天不被清空的机制见 [02-screens-and-navigation.md](02-screens-and-navigation.md#聊天页状态为什么会丢) 和 [07-gotchas.md](07-gotchas.md#remember-状态在导航返回后丢失)。

## 数据存储全景

- **模型参数 / 回答语言 / TTS 参数 / 选中模型** → Proto DataStore 的 `UserData.secrets` map（String→String），键见 [03-settings-and-datastore.md](03-settings-and-datastore.md)。
- **聊天历史** → 序列化的 proto 消息存于 ViewModel 内存 + `LlmChatViewModel` 的保存/恢复接口（见 [06-chat-and-input.md](06-chat-and-input.md)）。
- **模型文件** → `context.getExternalFilesDir(null)/<normalizedName>/<version>/<fileName>`（见 [05-model-management.md](05-model-management.md)）。

## 关键入口文件速查

| 想看什么 | 打开哪个文件 |
| --- | --- |
| App 怎么启动、怎么切页面 | `MainActivity.kt` → `GalleryApp.kt` → `lite/LiteNavHost.kt` |
| 聊天页全部逻辑 | `lite/LiteChatScreen.kt` |
| 设置页全部逻辑 | `lite/LiteSettingsScreen.kt` |
| 所有设置键和仓库 | `lite/LiteSettings.kt` |
| 朗读 | `lite/LiteTtsManager.kt` |
| 模型下载/初始化/选择 | `ui/modelmanager/ModelManagerViewModel.kt` |
| 模型/任务/配置的数据结构 | `data/Model.kt`、`data/Tasks.kt`、`data/Config.kt` |
| 聊天生成/会话/历史 | `ui/llmchat/LlmChatViewModel.kt` |
| 聊天 UI 组件 | `ui/common/chat/ChatPanel.kt` 等 |
| 按住录音 | `ui/common/textandvoiceinput/HoldToDictate*.kt` |
