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

import android.content.Context
import android.speech.tts.Voice
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RadioButtonChecked
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.ai.edge.gallery.R
import com.google.ai.edge.gallery.data.ConfigKeys
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ModelDownloadStatus
import com.google.ai.edge.gallery.data.ModelDownloadStatusType
import com.google.ai.edge.gallery.data.Task
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.ai.edge.gallery.ui.common.ConfigEditorsPanel
import com.google.ai.edge.gallery.ui.common.formatToHourMinSecond
import com.google.ai.edge.gallery.ui.common.humanReadableSize
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.labelSmallNarrow

/**
 * The Lite settings screen. Lets the user:
 * - change the model's answer language (persisted and applied to the model's system prompt),
 * - manage the two supported models (download / delete / update) and edit their parameters
 *   (max tokens, top-k, top-p, temperature, ...),
 * - adjust the Android TTS speech rate and pitch used for read-aloud.
 */
@Composable
fun LiteSettingsScreen(
  modelManagerViewModel: ModelManagerViewModel,
  liteSettingsRepository: LiteSettingsRepository,
  liteTtsManager: LiteTtsManager,
  onNavigateBack: () -> Unit,
  modifier: Modifier = Modifier,
  viewModel: LiteSettingsViewModel = hiltViewModel(),
) {
  val context = LocalContext.current
  val modelManagerUiState by modelManagerViewModel.uiState.collectAsState()
  val answerLanguage by liteSettingsRepository.answerLanguage.collectAsState()
  val task = modelManagerViewModel.getTaskById(com.google.ai.edge.gallery.data.BuiltInTaskId.LLM_CHAT)
  val selectedModel = modelManagerUiState.selectedModel

  val models =
    LiteSettings.SUPPORTED_MODEL_NAMES
      .mapNotNull { modelManagerViewModel.getModelByName(it) }

  Scaffold(
    modifier = modifier,
    topBar = {
      Row(
        modifier = Modifier.fillMaxWidth().statusBarsPadding().height(56.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onNavigateBack) {
          Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = stringResource(R.string.cd_navigate_back_icon),
          )
        }
        Text(
          text = stringResource(R.string.lite_settings),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    },
  ) { innerPadding ->
    LazyColumn(
      modifier = Modifier.fillMaxSize().padding(innerPadding),
      contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      // Answer language.
      item {
        SettingsSectionCard(title = stringResource(R.string.lite_answer_language)) {
          LITE_ANSWER_LANGUAGES.forEachIndexed { index, language ->
            val isSelected = language.displayName == answerLanguage
            Row(
              modifier =
                Modifier.fillMaxWidth()
                  .clickable {
                    if (!isSelected) {
                      liteSettingsRepository.saveAnswerLanguage(language.displayName)
                      viewModel.applyAnswerLanguage(language)
                      liteTtsManager.setLanguage(language.bcp47Tag)
                    }
                  }
                  .padding(vertical = 10.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              Icon(
                imageVector =
                  if (isSelected) Icons.Rounded.RadioButtonChecked
                  else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = null,
                tint =
                  if (isSelected) MaterialTheme.colorScheme.primary
                  else MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Spacer(modifier = Modifier.width(12.dp))
              Text(
                text = language.displayName,
                style = MaterialTheme.typography.bodyLarge,
              )
            }
            if (index != LITE_ANSWER_LANGUAGES.lastIndex) {
              HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
          }
        }
      }

      // Model management: download / delete / update + parameters.
      items(models, key = { it.name }) { model ->
        ModelSettingsCard(
          model = model,
          task = task,
          selected = model.name == selectedModel.name,
          downloadStatus = modelManagerUiState.modelDownloadStatus[model.name],
          modelManagerViewModel = modelManagerViewModel,
          liteSettingsRepository = liteSettingsRepository,
          context = context,
          onSelectModel = {
            modelManagerViewModel.selectModel(model)
            liteSettingsRepository.saveSelectedModel(model.name)
          },
        )
      }

      // TTS parameters.
      item {
        SettingsSectionCard(title = stringResource(R.string.lite_tts)) {
          var ttsRate by remember { mutableFloatStateOf(liteSettingsRepository.readTtsRate()) }
          Text(
            stringResource(R.string.lite_tts_rate, ttsRate.toString()),
            style = MaterialTheme.typography.bodyMedium,
          )
          Slider(
            value = ttsRate,
            onValueChange = {
              ttsRate = it
              liteTtsManager.setRate(it)
              liteSettingsRepository.saveTtsRate(it)
            },
            valueRange = 0.5f..2f,
          )
          var ttsPitch by remember { mutableFloatStateOf(liteSettingsRepository.readTtsPitch()) }
          Text(
            stringResource(R.string.lite_tts_pitch, ttsPitch.toString()),
            style = MaterialTheme.typography.bodyMedium,
          )
          Slider(
            value = ttsPitch,
            onValueChange = {
              ttsPitch = it
              liteTtsManager.setPitch(it)
              liteSettingsRepository.saveTtsPitch(it)
            },
            valueRange = 0.5f..2f,
          )
        }
      }

      // TTS voice selection + read-aloud test.
      item {
        SettingsSectionCard(title = stringResource(R.string.lite_tts_voice)) {
          val engineReady by liteTtsManager.engineReady.collectAsState()
          var voices by remember { mutableStateOf<List<Voice>>(emptyList()) }
          var selectedVoice by remember { mutableStateOf(liteSettingsRepository.readTtsVoice()) }
          val engineLabel = remember(engineReady) { liteTtsManager.activeEngineLabel() }
          LaunchedEffect(engineReady, answerLanguage) {
            voices =
              if (engineReady) liteTtsManager.availableVoices() else emptyList()
          }
          // Re-apply the persisted voice once the engine becomes ready (the chat screen may have
          // started before the engine finished initializing).
          LaunchedEffect(engineReady) {
            if (engineReady) {
              liteTtsManager.setVoice(liteSettingsRepository.readTtsVoice())
            }
          }

          if (engineReady && engineLabel != null) {
            Text(
              text = stringResource(R.string.lite_tts_engine, engineLabel),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
          }

          if (!engineReady) {
            Text(
              stringResource(R.string.lite_tts_engine_unavailable),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.error,
            )
          } else if (voices.isEmpty()) {
            Text(
              stringResource(R.string.lite_tts_no_voices),
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            LiteVoiceRow(
              label = stringResource(R.string.lite_tts_voice_system_default),
              selected = selectedVoice == null,
              onSelected = {
                selectedVoice = null
                liteTtsManager.setVoice(null)
                liteSettingsRepository.saveTtsVoice(null)
              },
            )
            for (voice in voices) {
              LiteVoiceRow(
                label = voice.name,
                selected = selectedVoice == voice.name,
                onSelected = {
                  selectedVoice = voice.name
                  liteTtsManager.setVoice(voice.name)
                  liteSettingsRepository.saveTtsVoice(voice.name)
                },
              )
            }
          }

          Spacer(modifier = Modifier.height(8.dp))
          OutlinedButton(
            onClick = { liteTtsManager.speakTest() },
            contentPadding = PaddingValues(horizontal = 12.dp),
          ) {
            Icon(Icons.Rounded.VolumeUp, contentDescription = null, modifier = Modifier.width(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(stringResource(R.string.lite_tts_test))
          }
        }
      }
    }
  }
}

/** A single selectable voice row (radio + label) in the read-aloud voice picker. */
@Composable
private fun LiteVoiceRow(
  label: String,
  selected: Boolean,
  onSelected: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().clickable(onClick = onSelected).padding(vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(
      imageVector =
        if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
      contentDescription = null,
      tint =
        if (selected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(modifier = Modifier.width(12.dp))
    Text(
      text = label,
      style = MaterialTheme.typography.bodyMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** A titled card used for each settings section. */
@Composable
private fun SettingsSectionCard(
  title: String,
  content: @Composable ColumnScope.() -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
      )
      Spacer(modifier = Modifier.height(8.dp))
      content()
    }
  }
}

/** A card managing one model: selection, download/delete/update and editable parameters. */
@Composable
private fun ModelSettingsCard(
  model: Model,
  task: Task?,
  selected: Boolean,
  downloadStatus: ModelDownloadStatus?,
  modelManagerViewModel: ModelManagerViewModel,
  liteSettingsRepository: LiteSettingsRepository,
  context: Context,
  onSelectModel: () -> Unit,
) {
  // Editable configs (exclude the tiny-garden-only reset conversation turn count).
  val editableConfigs =
    model.configs.filter { it.key != ConfigKeys.RESET_CONVERSATION_TURN_COUNT }
  val configValues = remember(model.name) { mutableStateMapOf<String, Any>().apply { putAll(model.configValues) } }

  val curStatus = downloadStatus
  val isDownloaded = curStatus?.status == ModelDownloadStatusType.SUCCEEDED
  val isDownloading =
    curStatus?.status == ModelDownloadStatusType.IN_PROGRESS ||
      curStatus?.status == ModelDownloadStatusType.PARTIALLY_DOWNLOADED ||
      curStatus?.status == ModelDownloadStatusType.UNZIPPING
  val downloadFailed = curStatus?.status == ModelDownloadStatusType.FAILED

  // Download progress fraction (0f..1f).
  val totalBytes =
    if (curStatus != null && curStatus.totalBytes > 0) curStatus.totalBytes else model.sizeInBytes
  val receivedBytes = curStatus?.receivedBytes ?: 0
  val downloadProgress =
    if (totalBytes > 0) (receivedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
  val animatedProgress = remember { Animatable(0f) }
  LaunchedEffect(downloadProgress) {
    animatedProgress.animateTo(downloadProgress, animationSpec = tween(150))
  }

  Card(
    modifier = Modifier.fillMaxWidth().clickable { onSelectModel() },
    colors =
      CardDefaults.cardColors(
        containerColor =
          if (selected) MaterialTheme.colorScheme.primaryContainer
          else MaterialTheme.colorScheme.surfaceContainer,
      ),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      // Model name + "use for chat" radio + status badge.
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector =
            if (selected) Icons.Rounded.RadioButtonChecked else Icons.Rounded.RadioButtonUnchecked,
          contentDescription = stringResource(R.string.lite_use_for_chat),
          tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = model.displayName.ifEmpty { model.name },
            style = MaterialTheme.typography.titleMedium,
          )
          if (selected) {
            Text(
              text = stringResource(R.string.lite_use_for_chat),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
        Text(
          text = if (isDownloaded) stringResource(R.string.lite_downloaded) else formatSize(model.sizeInBytes),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Download in progress: percent + progress bar + stop button + size/speed/remaining line.
      if (isDownloading) {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "${(downloadProgress * 100).toInt()}%",
            // This stops numbers from "jumping around" when being updated.
            style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(44.dp),
          )
          LinearProgressIndicator(
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            progress = { animatedProgress.value },
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
          )
          IconButton(
            onClick = { modelManagerViewModel.cancelDownloadModel(model) },
            colors =
              IconButtonDefaults.iconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
              ),
          ) {
            Icon(
              Icons.Outlined.Close,
              contentDescription = stringResource(R.string.cd_stop_icon),
              tint = MaterialTheme.colorScheme.onSurface,
            )
          }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
          text = buildString {
            if (curStatus?.status == ModelDownloadStatusType.UNZIPPING) {
              append(stringResource(R.string.lite_download_unzipping))
            } else {
              append(
                stringResource(
                  R.string.lite_download_progress,
                  receivedBytes.humanReadableSize(extraDecimalForGbAndAbove = true),
                  totalBytes.humanReadableSize(),
                ),
              )
              val rate = curStatus?.bytesPerSecond ?: 0
              if (rate > 0) {
                append(" · ")
                append(stringResource(R.string.lite_download_rate, rate.humanReadableSize()))
                val remainingMs = curStatus?.remainingMs ?: -1
                if (remainingMs >= 0) {
                  append(" · ")
                  append(
                    stringResource(
                      R.string.lite_download_remaining,
                      remainingMs.formatToHourMinSecond(),
                    ),
                  )
                }
              }
            }
          },
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        // Actions: update / delete when downloaded, otherwise download.
        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          if (isDownloaded) {
            if (model.updatable) {
              OutlinedButton(
                onClick = {
                  model.latestModelFile?.let {
                    model.version = it.commitHash
                    model.downloadFileName = it.fileName
                  }
                  model.updatable = false
                  // Selecting the model being updated keeps the chat pinned to it.
                  modelManagerViewModel.selectModel(model)
                  liteSettingsRepository.saveSelectedModel(model.name)
                  if (task != null) modelManagerViewModel.downloadModel(task, model)
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
              ) {
                Icon(Icons.Outlined.Update, contentDescription = null, modifier = Modifier.width(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.update))
              }
              Spacer(modifier = Modifier.width(8.dp))
            }
            Button(
              onClick = { modelManagerViewModel.deleteModel(model) },
              colors =
                androidx.compose.material3.ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.errorContainer,
                  contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
              contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
              Icon(Icons.Rounded.Delete, contentDescription = null, modifier = Modifier.width(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.delete))
            }
          } else {
            Button(
              onClick = {
                // Downloading a model makes it the model used in chat (and persists that choice),
                // so "download -> chat" works without extra steps.
                modelManagerViewModel.selectModel(model)
                liteSettingsRepository.saveSelectedModel(model.name)
                if (task != null) modelManagerViewModel.downloadModel(task, model)
              },
              contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
              Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.width(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text(stringResource(R.string.download))
            }
          }
        }
        // Show the failure reason so the user can retry.
        if (downloadFailed && !curStatus?.errorMessage.isNullOrEmpty()) {
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            text = curStatus.errorMessage,
            style = labelSmallNarrow,
            color = MaterialTheme.colorScheme.error,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      // Model parameters.
      if (editableConfigs.isNotEmpty()) {
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
          text = stringResource(R.string.lite_model_parameters),
          style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        ConfigEditorsPanel(configs = editableConfigs, values = configValues)

        Row(
          modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
          horizontalArrangement = Arrangement.End,
        ) {
          OutlinedButton(
            onClick = {
              configValues.clear()
              configValues.putAll(model.configValues)
            },
            contentPadding = PaddingValues(horizontal = 12.dp),
          ) {
            Text(stringResource(R.string.lite_reset))
          }
          Spacer(modifier = Modifier.width(8.dp))
          Button(
            onClick = {
              // Detect changes and whether the model needs to be re-initialized.
              var same = true
              var needReinitialization = false
              for (config in editableConfigs) {
                val key = config.key.label
                val oldValue =
                  convertValueToTargetType(
                    model.configValues[key] ?: config.defaultValue,
                    config.valueType,
                  )
                val newValue =
                  convertValueToTargetType(
                    configValues[key] ?: config.defaultValue,
                    config.valueType,
                  )
                if (oldValue != newValue) {
                  same = false
                  if (config.needReinitialization) {
                    needReinitialization = true
                  }
                }
              }
              if (!same) {
                val oldConfigValues = model.configValues
                model.prevConfigValues = oldConfigValues
                model.configValues = configValues.toMap()
                modelManagerViewModel.updateConfigValuesUpdateTrigger()
                liteSettingsRepository.saveModelConfigs(model.name, model.configValues)
                if (needReinitialization) {
                  if (task != null) {
                    modelManagerViewModel.initializeModel(
                      context = context,
                      task = task,
                      model = model,
                      force = true,
                    )
                  }
                }
              }
            },
            contentPadding = PaddingValues(horizontal = 12.dp),
          ) {
            Text(stringResource(R.string.ok))
          }
        }
      }
    }
  }
}

private fun formatSize(bytes: Long): String {
  if (bytes <= 0) return ""
  val mb = bytes / (1024.0 * 1024.0)
  return if (mb >= 1024.0) "%.2f GB".format(mb / 1024.0) else "%.0f MB".format(mb)
}
