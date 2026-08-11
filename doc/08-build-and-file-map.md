# 08 · 构建 / 部署 / 文件地图

## 环境

| 项 | 值 |
| --- | --- |
| Gradle 工程根 | `edge-gallery-lite/app` |
| Android SDK | `/Users/lin/Library/Android/sdk` |
| adb | `/Users/lin/Library/Android/sdk/platform-tools/adb` |
| 测试机 | OPPO/OnePlus（ColorOS），Android 13+，分辨率 1080x2376 / 420dpi |
| 安装包 id | `com.example.edgegallerylite` |

## 构建与安装

```bash
cd edge-gallery-lite/app

# 只构建 debug APK（不装）
./gradlew :app:assembleDebug

# 构建并安装到已连接的设备
./gradlew :app:installDebug

# 或直接用 adb 装现成 APK
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 清缓存重编（改了 proto / 依赖出诡异问题时）
./gradlew clean :app:installDebug
```

## 启动与日志

```bash
# 启动应用（冷启动）
adb shell am start -n com.example.edgegallerylite/com.google.ai.edge.gallery.MainActivity

# 看本项目相关日志（按 Tag 过滤）
adb logcat -s LiteTtsManager AGMainActivity ModelManagerViewModel LlmChatViewModel

# 全部日志里搜本项目特征串
adb logcat | grep -E "LiteTtsManager|AGMainActivity"
```

关键日志串：
- 模型初始化：`Initializing model '<模型名>'...`（`AGMainActivity` 或 `ModelManagerViewModel` 标签下）。
- TTS 引擎就绪：`TTS engine ready: <engine> (<n> voices)`。
- TTS 引擎失败：`TTS engine <engine> init failed / timed out / has no voices`。

## 产品验证分工（重要约定）

**UI 效果（是否显示正确、交互是否流畅）不要用截图/OCR/像素分析来验证**。请把 App 装到真机后**让用户自己测试并观察**，再由用户反馈结果。可以放心用的自动化手段：logcat、单测。

## 源码文件地图

### 本项目自定义层（`java/.../lite/`）——维护重点

| 文件 | 职责 |
| --- | --- |
| `LiteNavHost.kt` | 两个路由（聊天/设置），起始页=聊天 |
| `LiteChatScreen.kt` | 聊天主界面：顶栏、历史抽屉、TTS 接线、自动选模型、会话重置 |
| `LiteSettingsScreen.kt` | 设置界面：回答语言、模型管理、TTS 语速/语调/音色 |
| `LiteSettings.kt` | 常量键、`LiteAnswerLanguage`、`LiteSettingsRepository`（持久化仓库） |
| `LiteSettingsViewModel.kt` | 设置页 ViewModel（回答语言 → LLM system prompt） |
| `LiteTtsManager.kt` | 朗读引擎（回退选择）+ `StreamingMarkdownStripper`（剥离 markdown） |

### 复用层——了解即可，改动要克制

| 目录/文件 | 职责 | 本项目用到 |
| --- | --- | --- |
| `MainActivity.kt` | 入口，加载 allowlist、渲染 GalleryApp | 改动：注入 lite 仓库/TTS |
| `GalleryApp.kt` | 顶层 composable → LiteNavHost | 新增（原为 gallery 导航） |
| `GalleryApplication.kt` | Application，主题/通知/Firebase 初始化 | 原样 |
| `data/Model.kt` | `Model` 数据类、EMPTY_MODEL、初始化标记 | 全用 |
| `data/Tasks.kt` | 任务定义（`BuiltInTaskId.LLM_CHAT` 等） | 全用 |
| `data/Config.kt` / `ConfigValue.kt` | 模型参数类型与转换 | 全用 |
| `data/Consts.kt` | 常量（token 默认、图片/音频上限等） | 全用 |
| `data/ModelAllowlist.kt` | 模型 allowlist（assets 加载） | 全用 |
| `data/DataStoreRepository.kt` | Proto DataStore 仓库（secrets 等） | 全用 |
| `data/DownloadRepository.kt` | 模型文件下载/解压/删除 | 全用 |
| `data/SystemPromptRepository.kt` | LLM system prompt 持久化 | 回答语言写入 |
| `di/AppModule.kt` / `CoroutinesModule.kt` | Hilt 注入 | 全用 |
| `worker/DownloadWorker.kt` | 后台下载模型（WorkManager） | 全用 |
| `ui/modelmanager/ModelManagerViewModel.kt` | 模型状态总管 | 核心 |
| `ui/modelmanager/ModelManager.kt` | 模型生命周期管理 | 核心 |
| `ui/llmchat/LlmChatViewModel.kt` | 聊天生成/会话/历史 | 核心 |
| `ui/llmchat/LlmChatTaskModule.kt` | `LLM_CHAT` 任务 Hilt module | 核心 |
| `ui/llmchat/LlmChatModelHelper.kt` | LiteRT-LM 生成调用 | 核心 |
| `ui/common/chat/ChatPanel.kt` | 聊天面板（气泡、输入、附件） | 复用+`liteInputLayout` |
| `ui/common/chat/ChatMessage.kt` | 消息类型（文本/图片/音频/错误） | 全用 |
| `ui/common/chat/ChatHistorySideSheet.kt` | 历史抽屉内容 | 全用 |
| `ui/common/chat/MessageInputText.kt` | 输入框 + 录音指示 | 复用+`HoldRecordingIndicator` |
| `ui/common/textandvoiceinput/HoldToDictate*.kt` | 按住说话 + 语音识别浮层 | 复用 |
| `ui/common/textandvoiceinput/TextAndVoiceInput.kt` | 文本/语音切换输入 | 复用 |
| `runtime/LlmModelHelper.kt` | LiteRT-LM 引擎封装 | 全用 |
| `ui/theme/*` | 主题 | 全用 |

### 未使用但保留的 gallery 代码

`agent/`、`customtasks/`（agentchat / examplecustomtask / mobileactions / tinygarden）、`skills/`、`tools/`、`mcp/`、`huggingface/`、`ui/home/`、`ui/llmsingleturn/`、`ui/benchmark/`、`ui/navigation/`、`notifications/` 等。它们是从 Google 原工程带来的，Lite 不进入这些页面，但**保留可保证依赖/编译完整**。删之前确认没有反射/manifest 引用。

## 资源

| 资源 | 说明 |
| --- | --- |
| `res/values/strings.xml` | 全部文案，`lite_*` 为本项目新增 |
| `res/values/themes.xml` | `Theme.Gallery` |
| `AndroidManifest.xml` | 权限、`<queries>`（TTS 可见性）、MainActivity |
| `assets/` | 模型 allowlist JSON（`ModelAllowlist` 加载） |

## 版本

`app/build.gradle.kts`：`versionCode = 40`、`versionName = "1.0.18"`。发版时同步递增。
