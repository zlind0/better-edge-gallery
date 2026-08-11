/*
 * Copyright 2025 Google LLC
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

package com.google.ai.edge.gallery

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.core.os.bundleOf
import com.google.ai.edge.gallery.lite.LiteSettingsRepository
import com.google.ai.edge.gallery.lite.LiteTtsManager
import com.google.ai.edge.gallery.ui.modelmanager.ModelManagerViewModel
import com.google.ai.edge.gallery.ui.theme.GalleryTheme
import com.google.ai.edge.litertlm.ExperimentalApi
import com.google.ai.edge.litertlm.ExperimentalFlags
import com.google.firebase.analytics.FirebaseAnalytics
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

  private val modelManagerViewModel: ModelManagerViewModel by viewModels()

  @Inject lateinit var liteSettingsRepository: LiteSettingsRepository
  @Inject lateinit var liteTtsManager: LiteTtsManager

  override fun onCreate(savedInstanceState: Bundle?) {
    // We intentionally pass null to discard the saved instance state bundle.
    // This prevents Jetpack Compose from automatically restoring the previous screen
    // and forces the app to start cleanly on the chat screen after an OS kill.
    super.onCreate(null)

    // Debug: Dump all intent extras to see what FCM unloads
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        Log.d(TAG, "onCreate Extra -> Key: $key, Value: ${extras.get(key)}")
      }
    }

    // Convert FCM Console data extras to intent data for GalleryNavGraph to pick up
    intent.getStringExtra("deeplink")?.let { link ->
      Log.d(TAG, "onCreate: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }

    // Load the model allowlist (from the bundled asset, so it is nearly instant) and render the
    // chat UI immediately. No splash screen.
    modelManagerViewModel.loadModelAllowlist()

    setContent {
      GalleryTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
          GalleryApp(
            modelManagerViewModel = modelManagerViewModel,
            liteSettingsRepository = liteSettingsRepository,
            liteTtsManager = liteTtsManager,
          )
        }
      }
    }

    @OptIn(ExperimentalApi::class)
    ExperimentalFlags.enableBenchmark = false

    enableEdgeToEdge()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      // Fix for three-button nav not properly going edge-to-edge.
      // See: https://issuetracker.google.com/issues/298296168
      window.isNavigationBarContrastEnforced = false
    }
    // Keep the screen on while the app is running for better demo experience.
    window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)

    // Debug: Dump all intent extras to see what FCM unloads
    intent.extras?.let { extras ->
      for (key in extras.keySet()) {
        Log.d(TAG, "onNewIntent Extra -> Key: $key, Value: ${extras.get(key)}")
      }
    }

    intent.getStringExtra("deeplink")?.let { link ->
      Log.d(TAG, "onNewIntent: Found deeplink extra: $link")
      if (link.startsWith("http://") || link.startsWith("https://")) {
        val browserIntent = Intent(Intent.ACTION_VIEW, link.toUri())
        startActivity(browserIntent)
      } else {
        intent.data = link.toUri()
      }
    }
  }

  override fun onResume() {
    super.onResume()

    firebaseAnalytics?.logEvent(
      FirebaseAnalytics.Event.APP_OPEN,
      bundleOf(
        "app_version" to BuildConfig.VERSION_NAME,
        "os_version" to Build.VERSION.SDK_INT.toString(),
        "device_model" to Build.MODEL,
      ),
    )
  }

  companion object {
    private const val TAG = "AGMainActivity"
  }
}
