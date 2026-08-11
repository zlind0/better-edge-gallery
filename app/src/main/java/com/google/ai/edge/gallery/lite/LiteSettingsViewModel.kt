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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.gallery.data.BuiltInTaskId
import com.google.ai.edge.gallery.data.SystemPromptRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * View model for the Lite settings screen. Owns persistence that needs a coroutine scope: keeping
 * the answer-language directive in sync with the persisted LLM chat system prompt, so a model
 * re-initialization (e.g. after an app restart) uses the chosen language.
 */
@HiltViewModel
class LiteSettingsViewModel
@Inject
constructor(private val systemPromptRepository: SystemPromptRepository) : ViewModel() {

  /** Persists the language directive as the LLM chat system prompt. */
  fun applyAnswerLanguage(language: LiteAnswerLanguage) {
    viewModelScope.launch(Dispatchers.IO) {
      systemPromptRepository.updateSystemPrompt(
        taskId = BuiltInTaskId.LLM_CHAT,
        newPrompt = language.toSystemPrompt(),
      )
    }
  }
}
