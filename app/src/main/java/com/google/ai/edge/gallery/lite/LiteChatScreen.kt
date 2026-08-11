/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ai.edge.gallery.lite

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddComment
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VolumeOff
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelCapability
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.ui.common.chat.ChatHistorySideSheetContent
import com.google.ai.edge.gallery.ui.common.chat.ChatMessage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageAudioClip
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageError
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageImage
import com.google.ai.edge.gallery.ui.common.chat.ChatMessageText
import com.google.ai.edge.gallery.ui.common.chat.ChatPanel
import com.google.ai.edge.gallery.ui.common.chat.convertToLitertMessage
import com.google.ai.edge.gallery.ui.common.chat.deserializeProtoMessages
import com.google.ai.edge.gallery.ui.llmchat.LlmChatViewModel
import com.google.ai.edge.gallery.ui.modelmanager.ModelInitializationStatusType
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.emptyStateContent
import com.google.ai.edge.gallery.ui.theme.emptyStateTitle
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The main chat screen of the Lite app. It reuses the gallery's [ChatPanel] and model management,
 * adding:
 * - a top bar with chat history (left) and new-chat / auto-read / settings (right),
 * - streaming TTS read-aloud of the model's answers ([LiteTtsManager]),
 * - an answer-language-driven system prompt that resets the session when changed,
 * - chat history persisted and restored via the drawer.
 */
@Composable
fun LiteChatScreen(
  modelManagerViewModel: ModelManagerViewModel,
  liteSettingsRepository: LiteSettingsRepository,
  liteTtsManager: LiteTtsManager,
  onNavigateToSettings: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LlmChatViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val uiState by viewModel.uiState.collectAsState()
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val answerLanguage by liteSettingsRepository.answerLanguage.collectAsState()
  val autoRead by liteSettingsRepository.autoRead.collectAsState()

  // The generic LLM chat task, provided by the DI-injected LlmChatTask.
  val task = modelManagerViewModel.getTaskById(BuiltInTaskId.LLM_CHAT)

  // TTS: create the engine once and apply the saved settings.
  LaunchedEffect(Unit) {
    liteTtsManager.init()
    liteTtsManager.setRate(liteSettingsRepository.readTtsRate())
    liteTtsManager.setPitch(liteSettingsRepository.readTtsPitch())
    liteTtsManager.setVoice(liteSettingsRepository.readTtsVoice())
  }
  LaunchedEffect(autoRead) { liteTtsManager.setEnabled(autoRead) }
  val answerLanguageTag = displayNameOf(answerLanguage)?.bcp47Tag ?: "en-US"
  LaunchedEffect(answerLanguageTag) { liteTtsManager.setLanguage(answerLanguageTag) }

  // Render the chat shell immediately. The allowlist is bundled in assets, so the task and model
  // become available in milliseconds — there is no spinner and no splash screen. In the unlikely
  // case the task is not ready yet, show the chat UI with an empty content area.
  if (task == null) {
    Scaffold(
      modifier = modifier,
      topBar = {
        LiteChatTopBar(
          modelDisplayName = "",
          autoRead = autoRead,
          onHistoryClicked = {},
          onNewChatClicked = {},
          onAutoReadClicked = { liteSettingsRepository.saveAutoRead(!autoRead) },
          onSettingsClicked = onNavigateToSettings,
        )
      },
    ) { innerPadding ->
      Box(modifier = Modifier.fillMaxSize().padding(innerPadding))
    }
    return
  }

  val selectedModel = modelManagerUiState.selectedModel
  val currentMessages = uiState.messagesByModel[selectedModel.name] ?: emptyList()
  val curDownloadStatus = modelManagerUiState.modelDownloadStatus[selectedModel.name]
  val modelInitializationStatus = modelManagerUiState.modelInitializationStatus[selectedModel.name]
  val isModelInitialized =
    modelInitializationStatus?.status == ModelInitializationStatusType.INITIALIZED

  // Auto-select the model chosen on the settings page (persisted) once the allowlist is ready.
  // Runs exactly once: the initial selectedModel is EMPTY_MODEL whose name is "empty" (not ""), so
  // checking isEmpty() never fired and the saved model was never restored on restart.
  var didAutoSelectSavedModel by remember { mutableStateOf(false) }
  LaunchedEffect(modelManagerUiState.tasks) {
    if (!didAutoSelectSavedModel && modelManagerUiState.tasks.isNotEmpty()) {
      didAutoSelectSavedModel = true
      val model =
        modelManagerViewModel.getModelByName(liteSettingsRepository.readSelectedModel())
          ?: modelManagerViewModel.getModelByName(LiteSettings.DEFAULT_MODEL_NAME)
      if (model != null) modelManagerViewModel.selectModel(model)
    }
  }

  // Merge the saved parameter values into every supported model once the allowlist has seeded
  // their defaults, so the first initialization uses them. Idempotent.
  LaunchedEffect(modelManagerUiState.tasks) {
    for (name in LiteSettings.SUPPORTED_MODEL_NAMES) {
      modelManagerViewModel.getModelByName(name)?.let { liteSettingsRepository.applySavedConfigs(it) }
    }
  }

  // Initialize the model as soon as its download succeeds.
  LaunchedEffect(curDownloadStatus, selectedModel.name) {
    if (curDownloadStatus?.status == ModelDownloadStatusType.SUCCEEDED) {
      liteSettingsRepository.applySavedConfigs(selectedModel)
      modelManagerViewModel.initializeModel(context = context, task = task, model = selectedModel)
    }
  }

  // Reset the executor session with the effective (answer-language) system prompt once per
  // (model, language) pair. Changing the answer language in settings therefore restarts the
  // conversation with the new language directive.
  var lastResetKey by remember { mutableStateOf("") }
  val resetKey = "${selectedModel.name}|$answerLanguage"
  LaunchedEffect(isModelInitialized, resetKey) {
    if (isModelInitialized && lastResetKey != resetKey) {
      lastResetKey = resetKey
      liteTtsManager.reset()
      viewModel.resetSession(
        task = task,
        model = selectedModel,
        systemInstruction = liteSettingsRepository.currentSystemPrompt(),
        supportImage = true,
        supportAudio = true,
        clearHistory = true,
        onDone = {},
      )
    }
  }

  // Auto-save the conversation to chat history whenever it stops streaming.
  LaunchedEffect(uiState.inProgress) {
    if (!uiState.inProgress && currentMessages.isNotEmpty()) {
      viewModel.saveSession(
        sessionId = viewModel.currentSessionId,
        messages = currentMessages,
        originalModel = selectedModel.name,
        taskId = task.id,
        context = context,
      )
    }
  }

  val onResetSessionClicked:
    (Model, List<ChatMessage>, clearHistory: Boolean, onDone: () -> Unit) -> Unit =
    { model, chatMessages, clearHistory, onDone ->
      val litertMessages = chatMessages.mapNotNull { convertToLitertMessage(it) }
      liteTtsManager.reset()
      viewModel.resetSession(
        task = task,
        model = model,
        systemInstruction = liteSettingsRepository.currentSystemPrompt(),
        supportImage = true,
        supportAudio = true,
        initialMessages = litertMessages,
        onDone = onDone,
        clearHistory = clearHistory,
      )
    }

  val onSendMessage: (Model, List<ChatMessage>) -> Unit = { model, messages ->
    // Do not generate a response until the selected model has been downloaded (from Settings).
    if (curDownloadStatus?.status != ModelDownloadStatusType.SUCCEEDED) {
      viewModel.addMessage(
        model = model,
        message = ChatMessageError(content = context.getString(R.string.lite_model_not_downloaded)),
      )
    } else {
      for (message in messages) {
        viewModel.addMessage(model = model, message = message)
      }
      var text = ""
      val images: MutableList<Bitmap> = mutableListOf()
      val audioMessages: MutableList<ChatMessageAudioClip> = mutableListOf()
      for (message in messages) {
        when (message) {
          is ChatMessageText -> text = message.content
          is ChatMessageImage -> images.addAll(message.bitmaps)
          is ChatMessageAudioClip -> audioMessages.add(message)
          else -> {}
        }
      }
      if (text.isNotEmpty() || audioMessages.isNotEmpty()) {
        if (text.isNotEmpty()) {
          modelManagerViewModel.addTextInputHistory(text)
        }
        // A new response starts a fresh read-aloud pass.
        liteTtsManager.reset()
        viewModel.generateResponse(
          model = model,
          input = text,
          images = images,
          audioMessages = audioMessages,
          onFirstToken = {},
          onDone = { liteTtsManager.flushRemaining() },
          onError = { errorMessage ->
            viewModel.handleError(
              context = context,
              task = task,
              model = model,
              modelManagerViewModel = modelManagerViewModel,
              errorMessage = errorMessage,
            )
          },
          allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
          onToken = { token -> liteTtsManager.onToken(token) },
        )
      }
    }
  }

  val onRunAgainClicked: (Model, ChatMessage) -> Unit = { model, message ->
    if (message is ChatMessageText) {
      liteTtsManager.reset()
      viewModel.runAgain(
        model = model,
        message = message,
        onError = { errorMessage ->
          viewModel.handleError(
            context = context,
            task = task,
            model = model,
            modelManagerViewModel = modelManagerViewModel,
            errorMessage = errorMessage,
          )
        },
        allowThinking = task.allowCapability(ModelCapability.LLM_THINKING, model),
      )
    }
  }

  // Chat history drawer.
  val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
  val allHistorySessions by viewModel.historySessions.collectAsState()
  val historySessions =
    remember(allHistorySessions, task.id) { allHistorySessions.filter { it.taskId == task.id } }

  BackHandler { if (drawerState.isOpen) { scope.launch { drawerState.close() } } }

  ModalNavigationDrawer(
    drawerState = drawerState,
    drawerContent = {
      ModalDrawerSheet {
        ChatHistorySideSheetContent(
          history = historySessions,
          onHistoryItemClicked = { sessionId ->
            val session = historySessions.firstOrNull { it.sessionId == sessionId }
            if (session != null) {
              scope.launch {
                viewModel.setIsResettingSession(true)
                val messages =
                  withContext(Dispatchers.IO) { deserializeProtoMessages(session.messagesList) }
                viewModel.clearAllMessages(selectedModel)
                for (msg in messages) {
                  viewModel.addMessage(selectedModel, msg)
                }
                // Mark the current reset key as handled so the auto-reset effect doesn't wipe the
                // restored conversation once the model finishes initializing.
                lastResetKey = resetKey
                onResetSessionClicked(selectedModel, messages, /* clearHistory= */ false) {
                  viewModel.setIsResettingSession(false)
                }
                viewModel.currentSessionId = session.sessionId
              }
            }
            scope.launch { drawerState.close() }
          },
          onHistoryItemDeleted = { sessionId ->
            viewModel.deleteSession(sessionId, context)
            if (sessionId == viewModel.currentSessionId) {
              onResetSessionClicked(selectedModel, emptyList(), /* clearHistory= */ true) {}
              viewModel.currentSessionId = UUID.randomUUID().toString()
            }
          },
          onHistoryItemsDeleteAll = {
            viewModel.clearAllSessions(context)
            onResetSessionClicked(selectedModel, emptyList(), /* clearHistory= */ true) {}
            viewModel.currentSessionId = UUID.randomUUID().toString()
            scope.launch { drawerState.close() }
          },
          onNewChatClicked = {
            onResetSessionClicked(selectedModel, emptyList(), /* clearHistory= */ true) {}
            viewModel.currentSessionId = UUID.randomUUID().toString()
            scope.launch { drawerState.close() }
          },
          onDismissed = { scope.launch { drawerState.close() } },
        )
      }
    },
    gesturesEnabled = drawerState.isOpen,
  ) {
    Scaffold(
      modifier = modifier,
      topBar = {
        LiteChatTopBar(
          modelDisplayName = selectedModel.displayName.ifEmpty { selectedModel.name },
          autoRead = autoRead,
          onHistoryClicked = { scope.launch { drawerState.open() } },
          onNewChatClicked = {
            onResetSessionClicked(selectedModel, emptyList(), /* clearHistory= */ true) {}
            viewModel.currentSessionId = UUID.randomUUID().toString()
          },
          onAutoReadClicked = { liteSettingsRepository.saveAutoRead(!autoRead) },
          onSettingsClicked = onNavigateToSettings,
        )
      },
    ) { innerPadding ->
      Box {
        // The chat panel is always shown. Model downloads live exclusively on the settings page.
        ChatPanel(
          modelManagerViewModel = modelManagerViewModel,
          task = task,
          selectedModel = selectedModel,
          viewModel = viewModel,
          innerPadding = innerPadding,
          navigateUp = {},
          onSendMessage = { model, messages -> onSendMessage(model, messages) },
          onRunAgainClicked = { model, message -> onRunAgainClicked(model, message) },
          onBenchmarkClicked = { _, _, _, _ -> },
          onStopButtonClicked = {
            liteTtsManager.reset()
            viewModel.stopResponse(selectedModel)
          },
          modifier = Modifier.fillMaxSize(),
          showStopButtonInInputWhenInProgress = true,
          showImagePicker = true,
          showAudioPicker = true,
          liteInputLayout = true,
          emptyStateComposable = { model ->
            Box(modifier = Modifier.fillMaxSize()) {
              Column(
                modifier =
                  Modifier.align(Alignment.Center)
                    .padding(horizontal = 48.dp)
                    .padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
              ) {
                Text(
                  stringResource(R.string.aichat_emptystate_title),
                  style = emptyStateTitle,
                )
                Text(
                  stringResource(R.string.aichat_emptystate_content),
                  style = emptyStateContent,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                )
                Text(
                  model.displayName.ifEmpty { model.name },
                  style = emptyStateContent,
                  color = MaterialTheme.colorScheme.primary,
                  textAlign = TextAlign.Center,
                )
                // The selected model is not downloaded yet — point the user to the settings page.
                if (curDownloadStatus?.status != ModelDownloadStatusType.SUCCEEDED) {
                  Text(
                    stringResource(R.string.lite_model_not_downloaded),
                    style = emptyStateContent,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center,
                  )
                }
              }
            }
          },
        )
      }
    }
  }
}

/**
 * The Lite chat top bar: chat history on the left, new-conversation / auto-read-aloud / settings
 * buttons on the right.
 */
@Composable
private fun LiteChatTopBar(
  modelDisplayName: String,
  autoRead: Boolean,
  onHistoryClicked: () -> Unit,
  onNewChatClicked: () -> Unit,
  onAutoReadClicked: () -> Unit,
  onSettingsClicked: () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .statusBarsPadding()
        .height(56.dp)
        .padding(horizontal = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = onHistoryClicked) {
      Icon(
        Icons.Rounded.History,
        contentDescription = stringResource(R.string.chat_history_title),
      )
    }
    Text(
      text = modelDisplayName,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
    )
    IconButton(onClick = onNewChatClicked) {
      Icon(
        Icons.Rounded.AddComment,
        contentDescription = stringResource(R.string.new_chat),
      )
    }
    IconButton(onClick = onAutoReadClicked) {
      Icon(
        imageVector = if (autoRead) Icons.Rounded.VolumeUp else Icons.Rounded.VolumeOff,
        contentDescription = stringResource(R.string.lite_auto_read),
      )
    }
    IconButton(onClick = onSettingsClicked) {
      Icon(
        Icons.Rounded.Settings,
        contentDescription = stringResource(R.string.lite_settings),
      )
    }
  }
}
