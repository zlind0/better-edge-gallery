# 06 · 聊天：生成 / 会话 / 历史 / 图片音频 / 按住录音

## 消息从输入到回显的完整链路

1. 用户在 `MessageInputText` 输入文字 / 拍照 / 选图 / 按住录音，`performSend`（Enter 或发送键）或 `sendHoldRecording`（松手）调用 `createMessagesToSend(pickedImages, audioClips, text)` 生成 `List<ChatMessage>`（`ChatMessageImage` ≤ `MAX_IMAGE_COUNT`、`ChatMessageAudioClip` ≤ `MAX_AUDIO_CLIP_COUNT`、`ChatMessageText`）。
2. `ChatPanel.onSendMessage` → 交给 `LiteChatScreen.onSendMessage(model, messages)`：
   - **先检查模型是否已下载**：`curDownloadStatus?.status != SUCCEEDED` 时只加一条 `ChatMessageError("Model not downloaded. Open Settings to download it.")`，**不生成**。
   - 逐条 `viewModel.addMessage(...)` 把用户消息上屏。
   - 拆出 `text`（`ChatMessageText.content`）、`images`（`ChatMessageImage.bitmaps`）、`audioMessages`（`ChatMessageAudioClip`）。
   - 有文字时 `modelManagerViewModel.addTextInputHistory(text)`（输入历史）。
   - **`liteTtsManager.reset()`**（新一轮回答从零朗读）。
   - `viewModel.generateResponse(model, text, images, audioMessages, onFirstToken, onDone, onError, allowThinking, onToken)`：
     - `onToken = { token -> liteTtsManager.onToken(token) }`——流式喂给朗读。
     - `onDone = { liteTtsManager.flushRemaining() }`——把没标点收尾的残余读掉。

## generateResponse（`LlmChatViewModel`）内部

1. `inProgress=true, preparing=true`，加 `ChatMessageLoading`。
2. 组装 `attachments`：每图一个 `Attachment.ImageBitmap`、每段音频一个 `Attachment.AudioBytes(wav)`。
3. `AgentRequest(query=input, attachments, metadata)` → `runtimeExecutor.executeStream(...)`（`DefaultAgentRuntimeExecutor` → `LlmChatModelHelper.runInference` → `conversation.sendMessageAsync`）。
4. 事件循环：
   - `StreamToken`：首次去掉 loading、`onFirstToken`；thinking 通道走 `ChatMessageThinking`；普通 token 追加到当前 agent 消息（`updateLastTextMessageContentIncrementally`）并 **`onToken(event.token)` 喂给朗读**。
   - `LoopTerminated`：`inProgress=false`、`onDone()`。
   - `Error` → `onError`（→ `handleError`）。
   - `LoopCancelled` → 复位标志（用户按停止）。

## 停止 / 重新生成 / 处理错误

- **停止**：顶栏旁停止按钮 → `liteTtsManager.reset()` + `viewModel.stopResponse(selectedModel)` → `runtimeExecutor.interrupt()` → `conversation.cancelProcess()`。**注意停止同时清朗读缓冲**。
- **Run again**：`runAgain(model, message)`——`awaitInitialization()` 后把该消息 clone 进列表，再 `generateResponse(message.content)`；同时 `liteTtsManager.reset()`。
- **错误处理**：`handleError` 去掉 loading、加 `ChatMessageError`，然后 IO 线程 `cleanupModel` → `initializeModel` 重试，成功则加 `ChatMessageWarning("Session re-initialized")`。

## 会话与历史

- **会话 = 一组消息 + system prompt**。`resetSession(task, model, systemInstruction, supportImage, supportAudio, initialMessages, clearHistory, onDone)` 会清旧消息（`clearHistory=true` 时）、`stopResponse`、用新 `AgentRuntimeConfig` 重建 `Conversation`（可带 `initialMessages` 恢复上下文）。
- **自动保存**：`LiteChatScreen` 的 `LaunchedEffect(uiState.inProgress)`——**停止流式时**（`inProgress` 变 false）如果当前有消息就 `viewModel.saveSession(currentSessionId, messages, model.name, task.id, context)`。
- `saveSession` 序列化：图片/音频二进制写到 `context.cacheDir`（`img_<sessionId>_...png` / `audio_<sessionId>_....pcm`），消息序列化为 `ChatMessageProto`，组装 `ChatSessionProto`（标题 = 首条文本前 30 字、timestamp、originalModel、taskId、messages）存进 **`UserData.chatSessions`**（`user_data.pb`）。
- `historySessions`：`userDataDataStore.data.map { chatSessionsList 按时间倒序 }`，抽屉按 `taskId == LLM_CHAT` 过滤显示。
- **恢复历史**：点历史项 → IO 线程 `deserializeProtoMessages(session.messagesList)` → `clearAllMessages` → 逐条 `addMessage` → **先把 `lastResetKey = resetKey` 置位**（防自动重置 effect 清掉刚恢复的对话，见 [02](02-screens-and-navigation.md)）→ `onResetSessionClicked(..., clearHistory=false)`（带 `initialMessages`）→ `currentSessionId = session.sessionId`。
- 删除单条 / 清空全部 / 新聊天：`clearHistory=true` 重置并换新 UUID。
- 序列化辅助：`convertToLitertMessage`（`ui/common/chat/ChatMessage.kt`）和 `deserializeProtoMessages`（`ui/common/chat/ChatView.kt`）。

## 输入组件

### 聊天面板（`ChatPanel`）

复用组件，渲染消息列表（各类 `MessageBody*` 气泡、Run again、Benchmark 按钮）+ 底部 `MessageInputText` + 空态 + 首次初始化 loader。Lite 传入的钩子：
- `liteInputLayout = true` → 单行紧凑输入。
- `showImagePicker / showAudioPicker = true`，`showStopButtonInInputWhenInProgress = true`。
- `emptyStateComposable`：模型未下载时在空态里提示"去设置页下载"。

### 输入框（`MessageInputText`）

- **lite 布局**（单行）：相机按钮 → 权限弹窗 → CameraX `showCameraCaptureBottomSheet`（512×512 截图，前后摄像头切换，EXIF 旋转）；相册按钮 → Photo Picker（`PickVisualMediaRequest(ImageOnly)`）；`TextField`（Enter=发送，`ImeAction.Send`）；**按住说话** mic（用 `detectTapGestures(onPress = { startHoldRecording(); tryAwaitRelease(); stopHoldRecording() })`，因为 `IconButton` 会吞掉按压手势）；发送按钮。
- 录音指示：`HoldRecordingIndicator(elapsedMs)`——输入框上方红色横条 + 脉动红点 + `formatRecordingTime` 的 `m:ss` 计时（本项目新增，历史任务 #15）。
- 按住录音产出 `ChatMessageAudioClip(audioData=bytes, sampleRate=16000, side=USER)`；小于 800 字节的静音片段丢弃。
- 音频录制：`AudioRecorderPanel` 的 `startRecording`——PCM 16bit mono、`SAMPLE_RATE=16000`、到 `MAX_AUDIO_CLIP_DURATION_SEC=30` 自动停、`calculatePeakAmplitude` 反馈给波浪动画。

### 语音转写（`HoldToDictate`，本项目未启用）

`HoldToDictate` + `HoldToDictateViewModel` 走 Android `SpeechRecognizer`，产出的是**识别文本**而非音频片段。Lite 的输入用的是**按住录音产音频**（走 `MessageInputText`），两者不要混淆。`TextAndVoiceInput` 是它的键盘/语音切换外壳。

## 常见问题排查

- **没生成**：先看 `modelDownloadStatus` 是否 `SUCCEEDED`（`LiteChatScreen` 会拦）。logcat 找 `ModelManagerViewModel` 的下载/初始化日志。
- **答到一半停**：`LoopCancelled` 表示用户停止或引擎中断；看 `conversation.cancelProcess()` 是否被误触发。
- **历史不显示**：看 `user_data.pb` 的 `chatSessions` 是否写入、`taskId` 过滤是否匹配 `llm_chat`。
- **图片/音频上限**：改 `data/Consts.kt` 的 `MAX_IMAGE_COUNT` / `MAX_AUDIO_CLIP_COUNT` / `MAX_AUDIO_CLIP_DURATION_SEC`。
