# 04 · 朗读（TTS）与 markdown 剥离

文件：`lite/LiteTtsManager.kt`

## 产品需求回顾

- 模型回答**自动朗读**，读到**第一个标点**就开始（不用等整句/整段）。
- 朗读语言跟随设置里的"回答语言"。
- **markdown 符号不读**（`#`、`*`、`` ` ``、`[`、`](url)`、列表标记等不可见内容一律不读）。
- 语速/语调/音色可在设置页调节并持久化。

## 架构：三段流水线

```
模型 stream 出的 token
   │  onToken(token)                  ← LiteChatScreen 的 generateResponse 回调
   ▼
StreamingMarkdownStripper.consume(token)   ← 逐字符剥离，只吐出"现在安全可读"的字符
   │  产出
   ▼
cleanBuffer（StringBuilder）                ← 已剥离、待朗读的文本
   │  speakCompletedSegments / speakRemainingStripped
   ▼
TextToSpeech.engine.speak(...)
```

- `onToken`：`if (!enabled) return` → `cleanBuffer.append(stripper.consume(token))` → 若 `ready` 则 `speakCompletedSegments`。
- `flushRemaining`：回答结束（`onDone`）时调用 → `cleanBuffer.append(stripper.flush())` → `speakRemainingStripped`（把没标点收尾的残余读掉）。
- `reset`：清空 `cleanBuffer`、重置 stripper、`readingStarted=false`、`tts?.stop()`。**发新消息、停止、换会话、关自动朗读**都会触发。

## 分句逻辑

- 段边界字符：`PUNCTUATION = "，。！？、；,.!?;:…"`（中英文都算）。
- `speakCompletedSegments` 用 while 循环把 `cleanBuffer` 里**以标点结尾**的段切出来：
  - 第一段用 `QUEUE_FLUSH`（从 0 开始，这就是"读到第一个标点就开始"），
  - 之后的段用 `QUEUE_ADD`（排队衔接）。
  - 每段的 utterance id 是 `"lite_tts_${readingStarted}"`（只用来区分首段与后续段）。

## 引擎选择与回退（为什么必须回退）

`init()` 做的事：
1. 默认引擎 = `Settings.Secure.TTS_DEFAULT_SYNTH`（系统设置里的"默认"引擎）。
2. 候选列表 = 默认引擎优先，然后 `installedTtsEngines()`（所有装了 TTS 服务的引擎）按序补齐、去重。
3. 依次 `tryInitEngine(candidates, i)`：
   - `SUCCESS` 但**没 voices** → 换下一个（没音色数据没法朗读）；
   - 失败/异常 → 换下一个；
   - **4 秒超时**没收到 `onInit`（坏引擎服务会这样）→ 换下一个；
   - 全部失败 → `engineReady=false`，设置页显示"Text-to-speech is not available"。
4. `initAttemptSerial` 每次尝试 +1，超时/新尝试使旧回调作废，避免"迟到的 onInit 把已放弃的引擎复活"。

**为什么要有回退**：系统"默认"只是一个 secure setting，可能指向坏的引擎（实测真机默认 `org.nobody.multitts` 就不稳定），但系统里同时还有其他能用的引擎。不能"默认引擎失败就放弃"。

> **SDK 37 隐藏 API 的坑**：`TextToSpeech.getEngines()` / `getDefaultEngine()` 在公开 android.jar 里被剥掉了。替代方案：
> - 默认引擎：直接读 `Settings.Secure.TTS_DEFAULT_SYNTH`；
> - 引擎列表：`PackageManager.queryIntentServices(Intent(TTS_SERVICE), MATCH_ALL)`。
> - 包可见性：Android 11+ 需要 manifest 里 `<queries><intent><action android:name="android.intent.action.TTS_SERVICE"/></intent></queries>`，否则 `queryIntentServices` 查不到引擎。

## 音色选择

- `availableVoices()`：取当前引擎 `engine.voices`，语言前缀（`tag.substringBefore("-")`）匹配的排前面；一个都不匹配就全量返回，让用户仍可手动选。
- `setVoice(name)`：保存到 `voiceName`，`applySettings()` 时 `engine.setVoice(voice)`；设置页里 `voiceName==null` 表示"跟随系统默认"。
- 设置页的 `LaunchedEffect(engineReady)` 会在引擎就绪后**重新应用一次已保存的音色**——因为聊天页可能比引擎初始化更早启动，首次 `setVoice` 时引擎还没好。

## StreamingMarkdownStripper（核心难点）

### 需求约束（为什么不能简单正则）

模型是**流式**吐字的：`onToken` 每次只给一小段，一段可能只含半个词。而且剥离动作可能**改写已经吐过的前缀**。先看两个翻车案例：

1. **列表标记吞字**：`"1.标志性地标"` 曾读成 `"一 性地标"`。因为 `1.` 的语义要等后面的字符才知道（是序号标记还是小数 `1.5`），当 `1.` 恰好在一个 token 的末尾、`标志` 在下一个 token 时，旧实现用"按剥离后长度增量"追读，位置被重写破坏，"标志"整段被跳过。
2. **markdown 符号被读出来**：`**加粗**`、`` `代码` ``、`[链接](url)` 里的 `*`/`` ` ``/`[`/`](` 不能读。

**结论**：必须用一个**逐字符状态机**，只吐"语义已确定、绝不会被后面改写"的字符——吐出去的字符永不回退、永不重算。

### 状态机原理（"吐出即稳定"）

维护一组状态（`inFence`、`inInlineCode`、`escapePending`、`atLineStart`、`skipSpaces`、`runChar/runLen`、`lineDigits`、`heldDotOrParen`、`heldClosingBracket`、`inHtmlTag`、`inLinkUrl`），对每个字符：

- **标记字符（`` ` ` ` `` `~` `*` `_` `#` `-` `<` `!` `|`）**：进入 `startRun(c)` 累积成一个"run"，**先不吐**；等 run 结束（遇到别的字符 / flush）再根据长度和上下文判定语义，要么整段丢弃（`*` 强调、`` ` `` 代码、`|` 表格线），要么转义（`#` 标题后吞空格），要么原样吐（正文里的 `-`、孤立的 `<` 等）。
- **行首数字**：`lineDigits` 暂存，遇 `.`/`)` 再决定是列表标记（吞数字+吞点+吞后续空格）还是小数（`1.5` 原样吐）。`heldDotOrParen` 处理"点正好在 token 末尾"的情况——把它挂起，等下一个 token 的首字符来决定。
- **链接 `[label](url)`**：`[` 丢弃，`label` 按正文吐；`]` 后紧跟 `(` 则进入 `inLinkUrl`，URL 全丢直到 `)`；`]` 在 token 末尾则 `heldClosingBracket=true` 挂起，等下一个 token 首字符是否为 `(`。
- **代码块 `` ``` ``**：连续 3+ 反引号切换 `inFence`，fence 内全部丢弃（直到闭合 fence）。
- **行内代码**：内容按原文吐，只有反引号是标记。
- **HTML 标签**：`<` 后是字母或 `/` 进入 `inHtmlTag`，丢弃到 `>`。
- **转义 `\`**：下一个字符按原样吐（`escapePending`）。
- **空格处理**：`skipSpaces`（丢弃标记后的空格 / 行首缩进）或 `atLineStart`（丢弃行首空格）；正文空格保留。
- **换行**：输出 `\n`，重置 `atLineStart=true`、`skipSpaces=false`，清 `lineDigits`。

**关键保证**：一旦 `out.append(c)`，这个字符就进入 `cleanBuffer` 不会再被删改。所有"可能需要收回"的东西（run、行首数字、待定标记点）都**先不出现在输出里**，等语义确定。这就是"标志"不被吞的原因：`1.` 先被 `lineDigits + heldDotOrParen` 挂起，确认是列表标记后**连同后面的空格一起吞掉**，此时才吐 `标志性地标`。

### 验证用例（用临时 Java 移植版跑过，全部通过）

| 输入 | 期望输出 |
| --- | --- |
| `1. 标志性地标` | `标志性地标`（列表标记+空格被吞） |
| `5 * 3 = 15` | `5 * 3 = 15`（正文 `*` 保留） |
| `a ~~删除~~ b` | `a 删除 b` |
| `**加粗**文字` | `加粗文字` |
| `[谷歌](https://x.com)` | `谷歌` |
| `![图](https://x.com/a.png)` | `图` |
| `` `code` 和文字 `` | `code 和文字` |
| `# 标题` | `标题` |
| ``` ```python\nprint("hi")\n``` ``` | `\nprint("hi")\n`（fence 内容保留，fence 本身丢弃） |
| `3.5 是小数的数字` | `3.5 是小数的数字` |
| `- 项目` | `项目` |
| `> 引用` | `引用` |

（第 3 行说明：不能用"标记后一律吞空格"的粗暴做法，否则删除线闭合后的空格也会被吞，读成 `a 删除b`。这是多次调优后的取舍：接受可能出现的重复空格，TTS 会忽略连续空白。）

### 已知的小取舍

- 强调/删除线标记后的**多余空格**偶尔会留下（`a ~~删除~~ b` 中间会有两个空格），TTS 朗读无影响，可接受。
- stripper 处理的是**正文可读字符**，不负责分段；分段由 `PUNCTUATION` 完成。

## 日志标签

- `LiteTtsManager`（引擎初始化、speak 错误码、每个引擎就绪时 `TTS engine ready: <engine> (<n> voices)`）。

## 真机已知情况（供排查参考）

- 测试机默认引擎 `org.nobody.multitts` 不稳定（曾导致"朗读不可用"），依赖回退逻辑换到其他可用引擎。
