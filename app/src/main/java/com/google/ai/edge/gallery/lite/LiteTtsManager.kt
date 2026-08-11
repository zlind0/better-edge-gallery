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
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Wraps the Android [TextToSpeech] engine for streaming read-aloud of the model's answers.
 *
 * Token-by-token text is accumulated, and each segment ending with a punctuation mark is spoken.
 * Per the app spec, reading starts automatically the moment the first punctuation mark arrives.
 * The read-aloud language is set to match the configured model answer language.
 */
@Singleton
class LiteTtsManager
@Inject
constructor(@ApplicationContext private val context: Context) {

  /** Characters that terminate a spoken segment. */
  private val PUNCTUATION = "，。！？、；,.!?;:…"

  @Volatile var enabled: Boolean = false
    private set

  private val mainHandler = Handler(Looper.getMainLooper())
  private var tts: TextToSpeech? = null
  private var ready = false

  private val _engineReady = MutableStateFlow(false)
  /** True once a TTS engine has initialized successfully (or false if none did). */
  val engineReady: StateFlow<Boolean> = _engineReady.asStateFlow()

  /** The package name of the engine currently in use, or null while initializing / after failure. */
  @Volatile var activeEngineName: String? = null
    private set

  private var languageTag: String? = null
  private var rate = 1.0f
  private var pitch = 1.0f
  private var voiceName: String? = null

  private val stripper = StreamingMarkdownStripper()
  /** The cleaned (markdown-free) text emitted by [stripper], still awaiting read-aloud. */
  private val cleanBuffer = StringBuilder()
  private var readingStarted = false

  /** True while an init attempt is still in flight (prevents double init). */
  private var initializing = false

  /** Bumped on every init attempt; a stale callback (superseded or shutdown) ignores itself. */
  private var initAttemptSerial = 0

  private companion object {
    const val TAG = "LiteTtsManager"
    const val ENGINE_INIT_TIMEOUT_MS = 4000L
  }

  /**
   * Creates the TTS engine. The system default engine is tried first; if it fails to initialize
   * (or has no voices), every other installed engine is tried in turn. The "default" is only a
   * secure setting — it can point at a broken engine while other engines work, so read-aloud must
   * not give up when the first one errors. Safe to call multiple times.
   */
  fun init() {
    if (tts != null || initializing) return
    val defaultEngine = defaultTtsEngine()
    val candidates =
      buildList {
        if (defaultEngine != null) add(defaultEngine)
        installedTtsEngines().forEach { if (it != defaultEngine) add(it) }
      }
    if (candidates.isEmpty()) {
      Log.w(TAG, "No TTS engines installed")
      _engineReady.value = false
      return
    }
    initializing = true
    tryInitEngine(candidates, 0)
  }

  /**
   * The engine set in the system settings ("default"). TextToSpeech.getDefaultEngine() is a hidden
   * API, so the same underlying secure setting is read directly.
   */
  private fun defaultTtsEngine(): String? =
    Settings.Secure.getString(context.contentResolver, Settings.Secure.TTS_DEFAULT_SYNTH)

  /**
   * Every installed engine, in system-provided order. TextToSpeech.getEngines() is a hidden API,
   * so the engine list is resolved through the package manager's TTS service query instead.
   */
  private fun installedTtsEngines(): List<String> {
    val intent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
    return context.packageManager
      .queryIntentServices(intent, PackageManager.MATCH_ALL)
      .mapNotNull { it.serviceInfo?.packageName }
      .distinct()
  }

  /** Tries the engine at [index]; recurses to the next one when it fails or times out. */
  private fun tryInitEngine(candidates: List<String>, index: Int) {
    if (index >= candidates.size) {
      mainHandler.post {
        initializing = false
        ready = false
        _engineReady.value = false
        Log.w(TAG, "All ${candidates.size} TTS engines failed to initialize")
      }
      return
    }
    val engineName = candidates[index]
    val attempt = ++initAttemptSerial
    var instance: TextToSpeech? = null
    val listener =
      TextToSpeech.OnInitListener { status ->
        mainHandler.post {
          // A later attempt superseded this one (e.g. the timeout fired first): ignore it.
          if (attempt != initAttemptSerial) return@post
          val engine = instance ?: return@post
          if (status == TextToSpeech.SUCCESS) {
            if (engine.voices.isNullOrEmpty()) {
              // An engine with no voice data cannot read aloud; keep looking.
              Log.w(TAG, "TTS engine $engineName initialized but has no voices, trying next")
              abandonEngine()
              tryInitEngine(candidates, index + 1)
            } else {
              ready = true
              initializing = false
              activeEngineName = engineName
              _engineReady.value = true
              Log.i(TAG, "TTS engine ready: $engineName (${engine.voices.size} voices)")
              applySettings()
              // Speak anything that streamed in while the engine was still initializing.
              tts?.let { speakCompletedSegments(it) }
            }
          } else {
            Log.w(TAG, "TTS engine $engineName init failed with status $status")
            abandonEngine()
            tryInitEngine(candidates, index + 1)
          }
        }
      }
    instance =
      try {
        TextToSpeech(context.applicationContext, listener, engineName)
      } catch (e: IllegalArgumentException) {
        Log.w(TAG, "Cannot use TTS engine $engineName", e)
        mainHandler.post { tryInitEngine(candidates, index + 1) }
        return
      }
    tts = instance
    activeEngineName = engineName
    // A broken engine service may never deliver onInit; move on after a while.
    mainHandler.postDelayed(
      {
        if (attempt == initAttemptSerial && !ready) {
          Log.w(TAG, "TTS engine $engineName init timed out, trying next")
          abandonEngine()
          tryInitEngine(candidates, index + 1)
        }
      },
      ENGINE_INIT_TIMEOUT_MS,
    )
  }

  /** Shuts down and drops the current engine instance (called before trying the next one). */
  private fun abandonEngine() {
    tts?.shutdown()
    tts = null
    activeEngineName = null
  }

  fun setEnabled(enabled: Boolean) {
    this.enabled = enabled
    if (!enabled) {
      reset()
    }
  }

  /** Sets the BCP-47 language tag used for read-aloud. */
  fun setLanguage(tag: String) {
    languageTag = tag
    if (ready) {
      applySettings()
    }
  }

  fun setRate(value: Float) {
    rate = value
    if (ready) {
      applySettings()
    }
  }

  fun setPitch(value: Float) {
    pitch = value
    if (ready) {
      applySettings()
    }
  }

  /** Selects a voice by name, or `null` to use the engine default. */
  fun setVoice(name: String?) {
    voiceName = name
    if (ready) {
      applySettings()
    }
  }

  fun currentVoiceName(): String? = voiceName

  /** The localized label of the engine in use (falls back to the package name). */
  fun activeEngineLabel(): String? {
    val name = activeEngineName ?: return null
    return runCatching {
      val pm = context.packageManager
      pm.getApplicationLabel(pm.getApplicationInfo(name, 0)).toString()
    }.getOrNull() ?: name
  }

  /**
   * The voices exposed by the current engine. Voices matching the configured answer language are
   * listed first; if none match, every available voice is returned so the user can still choose.
   */
  fun availableVoices(): List<Voice> {
    val engine = tts ?: return emptyList()
    val all = engine.voices?.toList() ?: emptyList()
    val lang = languageTag?.substringBefore("-")
    val matching = all.filter { it.locale?.language == lang }
    val sorted: (Voice) -> String = { it.name }
    return if (matching.isNotEmpty()) matching.sortedBy(sorted) else all.sortedBy(sorted)
  }

  /** Speaks a sample sentence with the current settings, so the user can test the chosen voice. */
  fun speakTest() {
    mainHandler.post {
      val engine = tts ?: return@post
      if (!ready) return@post
      val sentence =
        when (languageTag?.substringBefore("-")) {
          "zh" -> "你好，这是一次朗读测试。"
          "ja" -> "こんにちは。これは音声読み上げのテストです。"
          "ko" -> "안녕하세요. 음성 읽어주기 테스트입니다."
          "es" -> "Hola. Esta es una prueba de lectura en voz alta."
          "fr" -> "Bonjour. Ceci est un test de lecture à voix haute."
          "de" -> "Hallo. Dies ist ein Vorlesetest."
          else -> "Hello. This is a read-aloud test."
        }
      val result = engine.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "lite_tts_test")
      if (result != TextToSpeech.SUCCESS) {
        Log.w(TAG, "Test speak returned error code $result")
      }
    }
  }

  /**
   * Feeds one streamed token from the model. Complete segments (ending in punctuation) are spoken;
   * the first segment triggers read-aloud automatically.
   */
  fun onToken(token: String) {
    if (!enabled || token.isEmpty()) return
    mainHandler.post {
      val engine = tts ?: return@post
      cleanBuffer.append(stripper.consume(token))
      if (ready) {
        speakCompletedSegments(engine)
      }
      // While the engine is still initializing, the cleaned text stays buffered; the init callback
      // speaks it once the engine becomes ready.
    }
  }

  /** Speaks whatever text is still buffered (e.g., when the response finishes without punctuation). */
  fun flushRemaining() {
    if (!enabled) return
    mainHandler.post {
      val engine = tts ?: return@post
      if (!ready) return@post
      cleanBuffer.append(stripper.flush())
      if (cleanBuffer.isEmpty()) return@post
      speakRemainingStripped(engine)
    }
  }

  /** Clears pending text and stops any ongoing speech. */
  fun reset() {
    mainHandler.post {
      cleanBuffer.setLength(0)
      stripper.reset()
      readingStarted = false
      tts?.stop()
    }
  }

  fun shutdown() {
    mainHandler.post {
      initAttemptSerial++
      initializing = false
      tts?.stop()
      tts?.shutdown()
      tts = null
      activeEngineName = null
      ready = false
      _engineReady.value = false
    }
  }

  /**
   * Speaks the newly completed sentences (ending in punctuation) of the cleaned text. Markdown was
   * already stripped by [stripper] as the tokens streamed in, so only visible text reaches here.
   */
  private fun speakCompletedSegments(engine: TextToSpeech) {
    while (true) {
      val end = cleanBuffer.indexOfFirst { it in PUNCTUATION }
      if (end < 0) break
      val segment = cleanBuffer.substring(0, end + 1)
      cleanBuffer.delete(0, end + 1)
      val result =
        engine.speak(
          segment,
          if (readingStarted) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
          null,
          "lite_tts_${readingStarted}",
        )
      if (result != TextToSpeech.SUCCESS) {
        Log.w(TAG, "Speak returned error code $result")
      } else {
        readingStarted = true
      }
    }
  }

  /** Speaks whatever cleaned text is still pending (e.g., when the response ends without punctuation). */
  private fun speakRemainingStripped(engine: TextToSpeech) {
    val text = cleanBuffer.toString()
    cleanBuffer.setLength(0)
    val result =
      engine.speak(
        text,
        if (readingStarted) TextToSpeech.QUEUE_ADD else TextToSpeech.QUEUE_FLUSH,
        null,
        "lite_tts_flush",
      )
    if (result != TextToSpeech.SUCCESS) {
      Log.w(TAG, "Speak returned error code $result")
    } else {
      readingStarted = true
    }
  }

  private fun applySettings() {
    val engine = tts ?: return
    languageTag?.let { tag ->
      try {
        engine.setLanguage(Locale.forLanguageTag(tag))
      } catch (e: Exception) {
        // Unsupported locale: keep the previous one.
      }
    }
    voiceName?.let { name ->
      val voice = engine.voices?.firstOrNull { it.name == name }
      if (voice != null) {
        val result = engine.setVoice(voice)
        if (result != TextToSpeech.SUCCESS) {
          Log.w(TAG, "setVoice($name) returned error code $result")
        }
      }
    }
    engine.setSpeechRate(rate)
    engine.setPitch(pitch)
  }
}

/**
 * Streaming markdown stripper for TTS.
 *
 * Consumes the model's raw output one character at a time and returns only the characters that are
 * safe to read aloud right now. Markup is dropped only once it is resolved (its closing marker has
 * arrived), so text that has already been emitted is never rewritten. This is what keeps a list
 * marker such as "1. 標誌性地標" from swallowing its text: the "1." is held back until it is known to
 * be a marker, then dropped together with its following space, leaving "標誌性地標".
 */
private class StreamingMarkdownStripper {

  private var inFence = false
  private var fenceChar: Char = '`'
  private var inInlineCode = false
  private var escapePending = false
  private var atLineStart = true
  private var skipSpaces = false

  // A run of identical markdown characters whose meaning is not known until it ends (e.g. "*" could
  // be italic, "**" bold or "***" bold + italic; all of them are dropped).
  private var runChar: Char? = null
  private var runLen = 0

  // Digits seen at the start of a line, held until a list marker ("1. item") can be told apart from
  // a plain number ("1.5").
  private val lineDigits = StringBuilder()
  private var heldDotOrParen: Char? = null
  private var heldClosingBracket = false

  private var inHtmlTag = false
  private var inLinkUrl = false

  /** Feeds one token of raw output and returns the characters that are now safe to speak. */
  fun consume(token: CharSequence): String {
    val out = StringBuilder()

    // Decisions that had to wait for the first character of this token.
    if (heldDotOrParen != null) {
      val dot = heldDotOrParen!!
      heldDotOrParen = null
      if (token.isNotEmpty() && token[0].isDigit()) {
        atLineStart = false
        out.append(lineDigits).append(dot) // "1.5": a plain number
      } else {
        skipSpaces = true // "1. 標誌": a list marker, drop the digits and the dot
      }
      lineDigits.setLength(0)
    }
    if (heldClosingBracket) {
      heldClosingBracket = false
      if (token.isNotEmpty() && token[0] == '(') inLinkUrl = true
    }

    var i = 0
    while (i < token.length) {
      val c = token[i]
      val after: Char? = if (i + 1 < token.length) token[i + 1] else null

      // A pending run of identical markdown characters ends here.
      if (runChar != null && c != runChar) endRun(c, out)

      // Escaped character: spoken literally.
      if (escapePending) {
        escapePending = false
        if (c == '\n') atLineStart = true else atLineStart = false
        out.append(c)
        i++
        continue
      }

      // Fenced code block: drop everything until the closing fence.
      if (inFence) {
        if (c == fenceChar) startRun(fenceChar)
        if (c == '\n') atLineStart = true
        i++
        continue
      }

      // HTML tag: drop until '>'.
      if (inHtmlTag) {
        if (c == '>') inHtmlTag = false
        if (c == '\n') atLineStart = true
        i++
        continue
      }

      // Link URL: drop until ')'.
      if (inLinkUrl) {
        if (c == ')') inLinkUrl = false
        if (c == '\n') atLineStart = true
        i++
        continue
      }

      // Inline code: only backticks are markup, the content is spoken as-is.
      if (inInlineCode) {
        if (c == '`') {
          startRun('`')
        } else if (c == '\n') {
          atLineStart = true
          out.append('\n')
        } else {
          atLineStart = false
          out.append(c)
        }
        i++
        continue
      }

      // Leading digits: could be an ordered-list marker.
      if (lineDigits.isNotEmpty() || (atLineStart && c.isDigit())) {
        if (c.isDigit()) {
          lineDigits.append(c)
          i++
          continue
        }
        if (c == '.' || c == ')') {
          if (after == null) {
            heldDotOrParen = c
            i++
            continue
          }
          if (after.isDigit()) {
            atLineStart = false
            out.append(lineDigits).append(c) // "3.5": a plain number
          } else {
            skipSpaces = true // "1. item": a list marker
          }
          lineDigits.setLength(0)
          i++
          continue
        }
        // Not a list marker: the held digits are a plain number.
        atLineStart = false
        out.append(lineDigits)
        lineDigits.setLength(0)
      }

      if (c == '\n') {
        if (lineDigits.isNotEmpty()) out.append(lineDigits)
        lineDigits.setLength(0)
        atLineStart = true
        skipSpaces = false
        out.append('\n')
        i++
        continue
      }
      if (c == ' ') {
        if (skipSpaces || atLineStart) {
          i++ // marker-following space or indentation, dropped
          continue
        }
        atLineStart = false
        out.append(' ')
        i++
        continue
      }
      if (skipSpaces) skipSpaces = false

      when (c) {
        '\\' -> escapePending = true
        '`', '~', '*', '_', '#', '-', '<', '!', '|' -> startRun(c)
        '>' -> if (atLineStart) skipSpaces = true else { atLineStart = false; out.append(c) }
        '+' -> if (atLineStart && (after == null || after == ' ')) skipSpaces = true else { atLineStart = false; out.append(c) }
        '[' -> Unit // drop; the label that follows is spoken as normal text
        ']' ->
          if (after == '(') {
            inLinkUrl = true // drop "](url)"
            i++ // also skip the '('
          } else if (after == null) {
            heldClosingBracket = true
          }
        // A stray ']' is dropped (the label text is already spoken).
        else -> {
          atLineStart = false
          out.append(c)
        }
      }
      i++
    }
    return out.toString()
  }

  /** Emits anything still held (end of a response) and resets the stripper for the next response. */
  fun flush(): String {
    val out = StringBuilder()
    if (runChar != null) endRun(' ', out)
    heldDotOrParen?.let { out.append(lineDigits).append(it) }
    heldDotOrParen = null
    lineDigits.setLength(0)
    heldClosingBracket = false
    reset()
    return out.toString()
  }

  fun reset() {
    inFence = false
    inInlineCode = false
    escapePending = false
    atLineStart = true
    skipSpaces = false
    runChar = null
    runLen = 0
    lineDigits.setLength(0)
    heldDotOrParen = null
    heldClosingBracket = false
    inHtmlTag = false
    inLinkUrl = false
  }

  private fun startRun(c: Char) {
    if (runChar == c) {
      runLen++
    } else {
      runChar = c
      runLen = 1
    }
  }

  /** Resolves a finished run; [after] is the character that ended it (used for disambiguation). */
  private fun endRun(after: Char, out: StringBuilder) {
    val c = runChar ?: return
    when (c) {
      '`' ->
        if (runLen >= 3) {
          inFence = !inFence
          fenceChar = c
        } else {
          inInlineCode = !inInlineCode
        }
      '~', '*', '_' -> Unit // emphasis / strikethrough markers are dropped; the content stays
      '#' -> skipSpaces = true // heading marker (or stray hash); the following space is dropped too
      '-' ->
        if (atLineStart) {
          if (runLen < 3 && after != ' ') out.append("-".repeat(runLen))
          else skipSpaces = true // list marker or horizontal rule
        } else {
          out.append("-".repeat(runLen))
        }
      '<' -> if (after.isLetter() || after == '/') inHtmlTag = true else out.append("<")
      '!' -> if (after != '[') out.append("!") // "![" opens an image; drop it
      '|' -> Unit // table pipe
      else -> out.append(c.toString().repeat(runLen))
    }
    runChar = null
    runLen = 0
  }
}
