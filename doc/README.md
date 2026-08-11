# Edge Gallery Lite —— 项目维护文档

本目录是 **Edge Gallery Lite**（`app/` 模块）的内部维护文档，把项目"为什么这样写、关键逻辑在哪里、改代码时要注意什么"讲清楚。

## 项目一句话概括

Edge Gallery Lite 复用了 Google AI Edge Gallery 的绝大部分代码（模型下载/初始化、LiteRT-LM 推理、聊天界面、录音/拍照输入），在外层包了一个极简的 `lite` 应用层：**一打开就是聊天页**，带聊天历史抽屉、新聊天/自动朗读/设置按钮，设置里可选模型、改模型参数、选回答语言、调 TTS，模型回答**自动朗读**（读到第一个标点就开始，且不读 markdown 符号）。

## 文档索引

| 文档 | 内容 |
| --- | --- |
| [01-architecture.md](01-architecture.md) | 总体架构：复用的 gallery 代码 vs 自写的 lite 层，技术栈，模块划分 |
| [02-screens-and-navigation.md](02-screens-and-navigation.md) | 两个界面（聊天页 / 设置页）+ 导航 + 聊天状态保持的坑 |
| [03-settings-and-datastore.md](03-settings-and-datastore.md) | LiteSettings 常量、DataStore 键、设置仓库、回答语言、模型参数持久化 |
| [04-tts-read-aloud.md](04-tts-read-aloud.md) | 朗读引擎回退逻辑 + 流式 markdown 剥离器（为什么这么设计、测试用例） |
| [05-model-management.md](05-model-management.md) | 模型：allowlist、下载、初始化、删除/更新、参数合并 |
| [06-chat-and-input.md](06-chat-and-input.md) | 聊天：生成/停止、会话与历史、图片/音频/按住录音输入 |
| [07-gotchas.md](07-gotchas.md) | 踩过的坑（SDK 37 隐藏 API、包可见性、EMPTY_MODEL、multitts 等） |
| [08-build-and-file-map.md](08-build-and-file-map.md) | 构建/部署命令 + 源码文件地图 |

## 快速上手：改代码后如何验证

1. 构建并安装：`./gradlew :app:installDebug`（详见 [08-build-and-file-map.md](08-build-and-file-map.md)）。

2. 看日志：`adb logcat -s LiteTtsManager AGMainActivity ModelManagerViewModel LlmChatViewModel`。