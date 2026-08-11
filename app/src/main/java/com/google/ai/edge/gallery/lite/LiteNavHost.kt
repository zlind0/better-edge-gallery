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

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel

/** Destination routes of the Lite chat app. */
object LiteRoute {
  const val CHAT = "lite_chat"
  const val SETTINGS = "lite_settings"
}

/**
 * Navigation graph of the Lite chat app. The app opens directly on the chat screen; the settings
 * screen is pushed on top when the user taps the settings button in the top bar.
 */
@Composable
fun LiteNavHost(
  navController: NavHostController,
  modelManagerViewModel: ModelManagerViewModel,
  liteSettingsRepository: LiteSettingsRepository,
  liteTtsManager: LiteTtsManager,
  modifier: Modifier = Modifier,
) {
  NavHost(
    navController = navController,
    startDestination = LiteRoute.CHAT,
    modifier = modifier,
  ) {
    composable(LiteRoute.CHAT) {
      LiteChatScreen(
        modelManagerViewModel = modelManagerViewModel,
        liteSettingsRepository = liteSettingsRepository,
        liteTtsManager = liteTtsManager,
        onNavigateToSettings = { navController.navigate(LiteRoute.SETTINGS) },
      )
    }
    composable(LiteRoute.SETTINGS) {
      LiteSettingsScreen(
        modelManagerViewModel = modelManagerViewModel,
        liteSettingsRepository = liteSettingsRepository,
        liteTtsManager = liteTtsManager,
        onNavigateBack = { navController.popBackStack() },
      )
    }
  }
}
