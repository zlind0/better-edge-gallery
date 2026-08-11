# 03 · 设置、DataStore 键与回答语言

## LiteSettings 常量（`lite/LiteSettings.kt`）

```kotlin
object LiteSettings {
  // DataStore secret 键（存在 UserData proto 的 secrets map 里）
  const val KEY_ANSWER_LANGUAGE    = "lite_answer_language"
  const val KEY_AUTO_READ          = "lite_auto_read"
  const val KEY_TTS_RATE           = "lite_tts_rate"
  const val KEY_TTS_PITCH          = "lite_tts_pitch"
  const val KEY_TTS_VOICE          = "lite_tts_voice"
  const val KEY_MODEL_CONFIG_PREFIX = "lite_model_configs_"   // + 模型名 = 一个键
  const val KEY_SELECTED_MODEL     = "lite_selected_model"

  const val DEFAULT_ANSWER_LANGUAGE = "English"
  const val DEFAULT_TTS_RATE        = 1.0f
  const val DEFAULT_TTS_PITCH       = 1.0f

  val SUPPORTED_MODEL_NAMES = setOf("Gemma-4-E2B-it", "Gemma-4-E4B-it")
  const val DEFAULT_MODEL_NAME = "Gemma-4-E2B-it"   // 启动时默认自动加载的模型
}
```

### 回答语言（`LiteAnswerLanguage`）

```kotlin
data class LiteAnswerLanguage(val displayName: String, val bcp47Tag: String) {
  fun toSystemPrompt(): String =
    "Please always respond in $displayName ($bcp47Tag). Use this language for all your replies."
}
```

- `LITE_ANSWER_LANGUAGES`：English/en-US、简体中文/zh-CN、日本語/ja-JP、한국어/ko-KR、Español/es-ES、Français/fr-FR、Deutsch/de-DE。
- **两种用法**：`displayName` 用于界面单选和持久化；`bcp47Tag` 用于 `LiteTtsManager.setLanguage(tag)`（朗读语言）。
- **回答语言 = system prompt 指令**。改语言会：
  1. `LiteSettingsRepository.saveAnswerLanguage(displayName)`（持久化，`lite_answer_language` 键）；
  2. `LiteSettingsViewModel.applyAnswerLanguage(language)` → `SystemPromptRepository.updateSystemPrompt(taskId = BuiltInTaskId.LLM_CHAT, newPrompt = toSystemPrompt())`（持久化为 LLM 的 system prompt，这样重启后模型初始化也用这个语言）；
  3. `LiteTtsManager.setLanguage(bcp47Tag)`。
  4. 回到聊天页后，`resetKey = "模型名|语言"` 变化触发会话重置（见 [02-screens-and-navigation.md](02-screens-and-navigation.md#会话重置机制容易踩坑务必读)）。

> **为什么同时存两份**（`lite_answer_language` + LLM system prompt）？
> `lite_answer_language` 是轻量的显示名，给设置 UI 和 TTS 用；LLM system prompt 是给模型推理用的，模型初始化/重置会话时以它为准。改一处必须同步另一处，否则会出现"设置里显示已改，但模型还是旧语言回答"。

## LiteSettingsRepository（`lite/LiteSettings.kt` 内）

`@Singleton`，注入复用的 `DataStoreRepository`。**所有方法同步**（`runBlocking`），沿用 gallery 的仓库风格，因为内部就是 `DataStoreRepository.saveSecret/readSecret/deleteSecret`（这些方法内部已经 `runBlocking`）。

对外暴露：
- `answerLanguage: StateFlow<String>`、`autoRead: StateFlow<Boolean>`——界面用 `collectAsState()` 订阅。
- `save/readAnswerLanguage`、`save/readAutoRead`、`save/readSelectedModel`、`save/readTtsRate`、`save/readTtsPitch`、`save/readTtsVoice`。
- `saveModelConfigs(modelName, configs)` / `readModelConfigs(modelName)`：把 `Map<String, Any>` 转成 `Map<String, String>`（`toString()`），用 Gson 序列化成 JSON 存到 `lite_model_configs_<模型名>` 键。
- `applySavedConfigs(model)`：把保存的参数合并回 `model.configValues`。

### `applySavedConfigs` 为什么"失败时保留旧值"

```kotlin
val converted = convertValueToTargetType(value, config.valueType)
merged[key] =
  if (converted == "" && config.valueType != ValueType.STRING) {
    model.configValues[key] ?: config.defaultValue   // 转换失败 → 用现有类型值
  } else {
    converted
  }
```

原因（对应历史 bug"设置页 String→Float ClassCastException"）：DataStore 里存的是字符串，读出来要按 `Config.valueType` 转回 Float/Int/Boolean。如果 JSON 里某个值格式不对，`convertValueToTargetType` 会返回空串 `""`；若直接把 `""` 塞进 `configValues`，设置页的 Slider/Switch 读到 String 就会崩。所以对非 String 类型，转换失败就**保留现有类型的旧值**。

`Config` 与 `convertValueToTargetType` 的定义在复用的 `data/Config.kt`（BOOLEAN 接受 `true/1/yes`；INT 用 `toFloatOrNull()?.toInt()`）。

## DataStore 存储实现（复用层）

- Proto 定义在 `app/src/main/proto/`（`UserData`、`Settings`、`CutoutCollection`、`BenchmarkResults`、`Skills`）。
- `di/AppModule.kt` 提供各 DataStore 实例（文件名形如 `user_data.pb`、`settings.pb` 等，按文件名区分）。
- **本项目的所有设置都走 `UserData.secrets`**（一个 `map<string, string>`），键见上文。

## LiteSettingsViewModel（`lite/LiteSettingsViewModel.kt`）

```kotlin
@HiltViewModel
class LiteSettingsViewModel @Inject constructor(
  private val systemPromptRepository: SystemPromptRepository,
) : ViewModel() {
  fun applyAnswerLanguage(language: LiteAnswerLanguage) {
    viewModelScope.launch(Dispatchers.IO) {
      systemPromptRepository.updateSystemPrompt(
        taskId = BuiltInTaskId.LLM_CHAT, newPrompt = language.toSystemPrompt())
    }
  }
}
```

`LiteChatScreen` 和 `LiteSettingsScreen` 都用 `hiltViewModel()` 拿到各自的 ViewModel（`LlmChatViewModel` / `LiteSettingsViewModel`）。
