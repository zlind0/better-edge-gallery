# 05 · 模型：allowlist / 下载 / 初始化 / 删除 / 更新

本项目不自己实现模型逻辑，全部复用 gallery 的 `ModelManagerViewModel` + `DownloadRepository`/`DownloadWorker` + `LlmChatModelHelper`。这里把**关键链路**讲清楚，方便定位"为什么模型没初始化/没下载/参数没生效"。

## 模型从哪来：`loadModelAllowlist()`

`ModelManagerViewModel.loadModelAllowlist()`（IO 线程）按顺序找 allowlist：

1. 测试文件 `/data/local/tmp/model_allowlist_test.json`；
2. 硬编码的 `TEST_MODEL_ALLOW_LIST` 字符串（非空时）；
3. **打包进 assets 的 `model_allowlist.json`**（本项目实际走这条，所以秒开、无网络）；
4. GitHub `model_allowlists/<VERSION_NAME>.json`（网络，兜底更新）；
5. externalFilesDir 里的磁盘副本。

然后对每个 `AllowedModel` 做筛选，**只保留 `LiteSettings.SUPPORTED_MODEL_NAMES`（`Gemma-4-E2B-it` / `Gemma-4-E4B-it`）**（这个过滤是 Lite 加在复用逻辑外的），`disabled==true` 的跳过、AICore 不支持跳过、NPU-only 且 SOC 不匹配跳过。通过 `allowedModel.toModel()` 构建 `Model`，再 `processTasks()` 时对每个模型调 **`model.preProcess()`**（用 config 默认值填充 `configValues`、算 `totalBytes`）。

> **allowlist 里每个模型的 `defaultConfig`**（`topK=64、topP=0.95、temperature=1.0、maxContextLength=32000、maxTokens=4000、accelerators="gpu,cpu"` + `ENABLE_THINKING`/`ENABLE_SPECULATIVE_DECODING` 开关）在 `createLlmChatConfigs` 里变成可编辑的 `configs`。

## 状态如何暴露

`uiState: StateFlow<ModelManagerUiState>`，字段（本项目只用到几个）：
- `tasks: List<Task>`——LLM_CHAT 任务（以及 `getTaskById(BuiltInTaskId.LLM_CHAT)`）。
- `selectedModel: Model = EMPTY_MODEL`——当前选中模型。
- `modelDownloadStatus: Map<模型名, ModelDownloadStatus>`——下载状态。
- `modelInitializationStatus: Map<模型名, ModelInitializationStatus>`——初始化状态（`INITIALIZED` 即 `isModelInitialized`）。

## 下载链路

`downloadModel(task, model)`：
1. 置 `IN_PROGRESS`；
2. `deleteModel(model, removeImportedFromModelList=false)` 清旧文件（保证干净）；
3. `DownloadRepository.downloadModel(...)`：拼 WorkManager 输入 → `enqueueUniqueWork(model.name, REPLACE, DownloadWorker)`（**同一模型重复下载自动替换**）→ 观察 `WorkInfo`：
   - RUNNING：读进度（receivedBytes / 速率 / 剩余毫秒 / UNZIPPING），回调 `setDownloadStatus` → UI 进度条。
   - SUCCEEDED：置 `SUCCEEDED`；App 在后台时发通知；记分析事件。
   - FAILED / CANCELLED：置 `FAILED`（带 errorMessage）或 `NOT_DOWNLOADED`。

`DownloadWorker`（前台服务，`model_download_channel_foreground`）：
- 下载到 `{externalFilesDir}/{normalizedName}[/{version}]/{fileName}.gallerytmp`（临时后缀 `TMP_FILE_EXT`）。
- **断点续传**：`Range: bytes={已有大小}-` + `Accept-Encoding: identity`。
- 每 200ms 报一次进度（速率 = 最近 5 个样本的 bytes/ms 均值）。
- 完成后改名去掉 `.gallerytmp`；`isZip` 则解压到 `{modelDir}/{version}/{unzipDir}` 后删掉 zip。

**下载完成 ≠ 能聊**。聊天页的 effect：`modelDownloadStatus[selectedModel.name].status == SUCCEEDED` 时，先 `applySavedConfigs(selectedModel)`（把保存的参数合进去）再 `initializeModel(...)`。

## 初始化链路

`initializeModel(context, task, model, force, ...)`：
1. 跳过条件：非 force 且 `instance != null` 且已 `INITIALIZED`；或已在 `initializing`。
2. `model.markInitializationStarted()` + 置 `INITIALIZING`。
3. 取 system prompt = `SystemPromptHelper.getEffectiveSystemPrompt(...)`（本项目由回答语言写入，见 [03](03-settings-and-datastore.md)）。
4. IO 线程调任务的 `initializeModelFn`。LLM_CHAT 走 `DefaultAgentRuntimeExecutor` → `LlmChatModelHelper.initialize`：
   - 读 `configValues` 的 `MAX_TOKENS / TOPK / TOPP / TEMPERATURE / ACCELERATOR / VISION_ACCELERATOR`；
   - 组 `EngineConfig(modelPath = model.getPath(context), backend, visionBackend, audioBackend, maxNumTokens, cacheDir)`；
   - `Engine(engineConfig).initialize()`；
   - 建 `Conversation`（`SamplerConfig(topK, topP, temperature)`，NPU/TPU 用 null；systemInstruction；tools）；
   - `model.instance = LlmModelInstance(engine, conversation)`；`markInitialized()`。
5. 结果回 `onDoneFn`：成功 → `INITIALIZED`；失败 → `ERROR` + `markInitializationFailed(error)`。

**`Model.awaitInitialization()`**：`runAgain` 等场景在 `instance==null` 时等 `initDeferred`。用 `CompletableDeferred`（`ConcurrentHashMap` 按模型名存）实现，**并发初始化同一模型会去重**。

## 参数修改 → 需要重新初始化

`Config.needReinitialization` 默认为 true。设置页 `ModelSettingsCard` 点 OK 时：
- 对比新旧 `configValues`，有变化 → `model.configValues` 更新 + `saveModelConfigs`（持久化）+ `updateConfigValuesUpdateTrigger`（触发 UI 刷新）；
- 若变化的参数 `needReinitialization` → `initializeModel(force = true)` 重新初始化。

> 所以"改 max tokens 等参数后**旧会话**不会自动生效，需要重新初始化/新会话"。这是复用层既有行为。

## 删除 / 取消下载

- `deleteModel(model)`：先处理 `updatable`（回退到 `latestModelFile`），删除 `{externalFilesDir}/{normalizedName}` 目录，置 `NOT_DOWNLOADED`；imported 模型额外清 DataStore 记录。
- `cancelDownloadModel(model)`：`cancelAllWorkByTag("modelName:${model.name}")` 后 `deleteModel`。

## 更新（Update 按钮）

设置页里模型已下载且 `model.updatable` 时显示 Update：用 `latestModelFile`（commitHash + fileName）覆盖 `model.version / model.downloadFileName`，`updatable=false`，然后 `selectModel` + `downloadModel`。下载新版本会替换旧文件。

## 本项目在设置页的模型卡片（`ModelSettingsCard`）

每张卡展示：选中 radio（"Use for chat"，点击即 `selectModel` + `saveSelectedModel`）、下载状态/大小、下载中进度条+取消、失败原因、已下载则 Update/Delete 按钮、以及可编辑的模型参数（`ConfigEditorsPanel`）+ Reset/OK。

> 注意：`editableConfigs` 过滤掉了 `ConfigKeys.RESET_CONVERSATION_TURN_COUNT`（tiny garden 专用）。
