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

import com.google.ai.edge.gallery.data.DataStoreRepository
import com.google.ai.edge.gallery.data.Model
import com.google.ai.edge.gallery.data.ValueType
import com.google.ai.edge.gallery.data.convertValueToTargetType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Constants shared by the Lite app screens. */
object LiteSettings {
  // DataStore secret keys.
  const val KEY_ANSWER_LANGUAGE = "lite_answer_language"
  const val KEY_AUTO_READ = "lite_auto_read"
  const val KEY_TTS_RATE = "lite_tts_rate"
  const val KEY_TTS_PITCH = "lite_tts_pitch"
  const val KEY_TTS_VOICE = "lite_tts_voice"
  const val KEY_MODEL_CONFIG_PREFIX = "lite_model_configs_"
  const val KEY_SELECTED_MODEL = "lite_selected_model"

  const val DEFAULT_ANSWER_LANGUAGE = "English"
  const val DEFAULT_TTS_RATE = 1.0f
  const val DEFAULT_TTS_PITCH = 1.0f

  /** The only models supported by this app. */
  val SUPPORTED_MODEL_NAMES = setOf("Gemma-4-E2B-it", "Gemma-4-E4B-it")

  /** The model that is auto-selected (and auto-loaded) on startup when possible. */
  const val DEFAULT_MODEL_NAME = "Gemma-4-E2B-it"
}

/** An answer language option: the display name shown in the UI and the BCP-47 tag used by TTS. */
data class LiteAnswerLanguage(val displayName: String, val bcp47Tag: String) {
  /** The system prompt directive that makes the model answer in this language. */
  fun toSystemPrompt(): String =
    "Please always respond in $displayName ($bcp47Tag). Use this language for all your replies."
}

/** The languages the user can choose for the model's answers. */
val LITE_ANSWER_LANGUAGES =
  listOf(
    LiteAnswerLanguage("English", "en-US"),
    LiteAnswerLanguage("简体中文", "zh-CN"),
    LiteAnswerLanguage("日本語", "ja-JP"),
    LiteAnswerLanguage("한국어", "ko-KR"),
    LiteAnswerLanguage("Español", "es-ES"),
    LiteAnswerLanguage("Français", "fr-FR"),
    LiteAnswerLanguage("Deutsch", "de-DE"),
  )

fun displayNameOf(name: String): LiteAnswerLanguage? =
  LITE_ANSWER_LANGUAGES.find { it.displayName == name }

/**
 * Persists the Lite app settings (model params, answer language, TTS params, auto read) using the
 * existing DataStore secret store. All methods are synchronous, mirroring the gallery's own
 * repository style.
 */
@Singleton
class LiteSettingsRepository
@Inject
constructor(private val dataStoreRepository: DataStoreRepository) {
  private val gson = Gson()
  private val stringMapType = object : TypeToken<Map<String, String>>() {}.type

  private val _answerLanguage = MutableStateFlow(readAnswerLanguage())
  val answerLanguage: StateFlow<String> = _answerLanguage.asStateFlow()

  private val _autoRead = MutableStateFlow(readAutoRead())
  val autoRead: StateFlow<Boolean> = _autoRead.asStateFlow()

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Answer language.

  fun saveAnswerLanguage(displayName: String) {
    dataStoreRepository.saveSecret(LiteSettings.KEY_ANSWER_LANGUAGE, displayName)
    _answerLanguage.value = displayName
  }

  fun readAnswerLanguage(): String =
    dataStoreRepository.readSecret(LiteSettings.KEY_ANSWER_LANGUAGE)
      ?: LiteSettings.DEFAULT_ANSWER_LANGUAGE

  /** Builds the effective system prompt directive from the currently saved answer language. */
  fun currentSystemPrompt(): String {
    val lang =
      displayNameOf(readAnswerLanguage()) ?: LiteAnswerLanguage("English", "en-US")
    return lang.toSystemPrompt()
  }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Auto read-aloud.

  fun saveAutoRead(enabled: Boolean) {
    dataStoreRepository.saveSecret(LiteSettings.KEY_AUTO_READ, enabled.toString())
    _autoRead.value = enabled
  }

  fun readAutoRead(): Boolean =
    dataStoreRepository.readSecret(LiteSettings.KEY_AUTO_READ)?.toBooleanStrictOrNull() ?: false

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Selected (default) model for chat.

  fun saveSelectedModel(modelName: String) {
    dataStoreRepository.saveSecret(LiteSettings.KEY_SELECTED_MODEL, modelName)
  }

  fun readSelectedModel(): String =
    dataStoreRepository.readSecret(LiteSettings.KEY_SELECTED_MODEL)
      ?: LiteSettings.DEFAULT_MODEL_NAME

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // TTS parameters.

  fun saveTtsRate(rate: Float) {
    dataStoreRepository.saveSecret(LiteSettings.KEY_TTS_RATE, rate.toString())
  }

  fun readTtsRate(): Float =
    dataStoreRepository.readSecret(LiteSettings.KEY_TTS_RATE)?.toFloatOrNull()
      ?: LiteSettings.DEFAULT_TTS_RATE

  fun saveTtsPitch(pitch: Float) {
    dataStoreRepository.saveSecret(LiteSettings.KEY_TTS_PITCH, pitch.toString())
  }

  fun readTtsPitch(): Float =
    dataStoreRepository.readSecret(LiteSettings.KEY_TTS_PITCH)?.toFloatOrNull()
      ?: LiteSettings.DEFAULT_TTS_PITCH

  fun saveTtsVoice(voiceName: String?) {
    if (voiceName.isNullOrEmpty()) {
      dataStoreRepository.deleteSecret(LiteSettings.KEY_TTS_VOICE)
    } else {
      dataStoreRepository.saveSecret(LiteSettings.KEY_TTS_VOICE, voiceName)
    }
  }

  /** The name of the chosen TTS voice, or `null` to follow the engine default. */
  fun readTtsVoice(): String? =
    dataStoreRepository.readSecret(LiteSettings.KEY_TTS_VOICE)?.takeIf { it.isNotEmpty() }

  ////////////////////////////////////////////////////////////////////////////////////////////////
  // Model parameters.

  fun saveModelConfigs(modelName: String, configs: Map<String, Any>) {
    val stringConfigs = configs.mapValues { (_, value) -> value.toString() }
    dataStoreRepository.saveSecret(
      LiteSettings.KEY_MODEL_CONFIG_PREFIX + modelName,
      gson.toJson(stringConfigs),
    )
  }

  fun readModelConfigs(modelName: String): Map<String, String> {
    val json =
      dataStoreRepository.readSecret(LiteSettings.KEY_MODEL_CONFIG_PREFIX + modelName) ?: return emptyMap()
    return runCatching { gson.fromJson<Map<String, String>>(json, stringMapType) }
      .getOrNull()
      ?: emptyMap()
  }

  /**
   * Merges the saved parameter values into [model]'s current config values. This should be called
   * after the allowlist has seeded the model's defaults via [Model.preProcess].
   */
  fun applySavedConfigs(model: Model) {
    val saved = readModelConfigs(model.name)
    if (saved.isEmpty()) return
    val merged = model.configValues.toMutableMap()
    for ((key, value) in saved) {
      val config = model.configs.find { it.key.label == key }
      if (config == null) {
        merged[key] = value
      } else {
        val converted = convertValueToTargetType(value, config.valueType)
        // A failed conversion yields an empty string. For typed (non-string) configs keep the
        // existing typed value instead, so the settings editors never see a String where a number
        // or boolean is expected (which would crash the sliders/switches).
        merged[key] =
          if (converted == "" && config.valueType != ValueType.STRING) {
            model.configValues[key] ?: config.defaultValue
          } else {
            converted
          }
      }
    }
    model.configValues = merged
  }
}
