# 自建播放器示例

将 [ExamplePlayerUi.kt](ExamplePlayerUi.kt) 加入主音源工程，在入口实现 `SourcePlayerUiProviderAccess`，返回 `ExamplePlayerUi()`。本目录不是独立 Gradle 工程。

示例支持手机横竖屏与平板，演示共享元素、宿主歌词及受控播放动作。完整接线见[播放器规范](../../接入文档/Agent音源接入完整规范.md#player-ui)。
