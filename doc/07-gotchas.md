# 07 · 踩过的坑（改代码前必读）

这里是本项目开发过程中真实踩过并修复的坑，每一条都可能再次咬人。

## 1. remember 状态在导航返回后丢失

- **现象**：聊天中途进设置再返回，聊天被清空、变成了新对话。
- **根因**：`LiteChatScreen` 里用 `remember { mutableStateOf(...) }` 保存的 `lastResetKey`，在导航到设置页时该 destination 离开组合、`remember` 状态被丢弃；返回后 `lastResetKey` 重置为 `""`，触发"会话重置"effect，把历史 `clearHistory=true` 清了。
- **修复**：换成 `rememberSaveable { mutableStateOf(...) }`。`NavHost` 的 `SaveableStateHolder` 会在 push/pop 导航间保留 `rememberSaveable` 状态。
- **规则**：**聊天页/设置页凡是"跨导航不能丢"的状态（`lastResetKey`、`didAutoSelectSavedModel` 等）一律 `rememberSaveable`**；纯临时状态（比如单次点击的动画）用 `remember` 即可。

## 2. EMPTY_MODEL.name = "empty"（不是空串）

- `data/Model.kt` 里 `EMPTY_MODEL = Model(name = "empty", ...)`。
- 任何"判断是否还没选中模型"的代码如果写 `if (model.name.isEmpty())` 会**永远不成立**，导致启动时"自动恢复上次保存的模型"从不执行。
- 本项目的 `didAutoSelectSavedModel` 守卫 + `getModelByName(readSelectedModel()) ?: DEFAULT_MODEL_NAME` 专门绕开了这个陷阱。别改成 `isEmpty()` 判断。

## 3. SDK 37 隐藏了 TTS 的引擎 API

- `TextToSpeech.getEngines()`、`TextToSpeech.getDefaultEngine()` 在 compileSdk 37 的公开 `android.jar` 中被剥离，直接调用**编译不过**。
- 替代：默认引擎读 `Settings.Secure.getString(resolver, Settings.Secure.TTS_DEFAULT_SYNTH)`；引擎列表用 `PackageManager.queryIntentServices(Intent(Intent.ACTION_TTS_SERVICE), MATCH_ALL)` 再取 `serviceInfo.packageName` 去重。

## 4. Android 11+ 包可见性：漏了 `<queries>` 就查不到引擎

- manifest `<application>` 之前必须有：
  ```xml
  <queries>
    <intent><action android:name="android.intent.action.TTS_SERVICE" /></intent>
  </queries>
  ```
- 否则 `queryIntentServices` 返回空，`LiteTtsManager` 认为"没有引擎"，朗读彻底不可用。

## 5. 默认 TTS 引擎可能坏，必须有回退

- 系统"默认引擎"只是 secure setting，可能指向坏引擎（实测 `org.nobody.multitts` 不稳定），但其他引擎能用。
- `LiteTtsManager.tryInitEngine` 会：默认引擎 → 其余所有引擎，逐个试；`SUCCESS` 但空 voices / 失败 / 异常 / **4 秒超时**都换下一个。别把它"优化"成只试默认引擎。

## 6. 转换失败不要塞 String 进 typed config（设置页崩溃）

- 模型参数存在 DataStore 是 String（JSON）。按 `Config.valueType` 转回时可能失败（`convertValueToTargetType` 返回 `""`）。
- 直接把 `""` 塞进 `configValues`，设置页 Slider/Switch 读到 String 会抛 `ClassCastException`（历史 bug）。
- 修复：`applySavedConfigs` 对非 String 类型，转换失败就保留现有 typed 旧值（见 [03-settings-and-datastore.md](03-settings-and-datastore.md)）。

## 7. 流式 TTS 不能用"按剥离后长度增量"追读

- 第一版朗读剥 markdown 用"记录已读的剥离后长度，新 token 只读增量"，遇到 `1.标志性地标` 这种**标记改写前面内容**的输入会吞字（`标志` 被跳过）。
- 正确做法：`StreamingMarkdownStripper` 状态机，**吐出的字符永不回退**，所有可能被改写的部分（run、行首数字、待定点）先挂起再判定（见 [04-tts-read-aloud.md](04-tts-read-aloud.md)）。

## 8. OSS Licenses 插件与 Gradle 9.2.1 不兼容

- `oss-licenses-plugin` 的 `LicensesTask.addDebugLicense` 签名在 Gradle 9.2.1 下执行期报错。
- 修复：`app/build.gradle.kts` 末尾把 `*OssLicensesTask` 的任务全部 `enabled = false`（Lite 不用开源许可页）。

## 9. Firebase / google-services

- `app/build.gradle.kts` 里 `alias(libs.plugins.google.services) apply false`——**没有 `google-services.json`**，所以 Firebase 相关服务（Analytics/Messaging）都是可选初始化，缺配置也能构建运行。

## 10. 主线程/内存

- `MainActivity` 里 `window.addFlags(FLAG_KEEP_SCREEN_ON)`——演示时保持亮屏。
- `ExperimentalFlags.enableBenchmark = false`（Lite 不跑 benchmark）。
- `onCreate(null)` 故意丢弃 savedInstanceState，避免 OS 杀进程后自动恢复旧界面，强制干净地从聊天页启动。

## 11. 录音限制（复用层常量）

- `MAX_AUDIO_CLIP_COUNT = 1`、`MAX_AUDIO_CLIP_DURATION_SEC = 30`、`MAX_IMAGE_COUNT = 10`（`data/Consts.kt`）。改这些常量即可调整聊天附件上限。
