# 音源 SDK

公开包名 `com.musicone.source.sdk`，API 1。构建 AAR：

```powershell
.\gradlew.bat :source-sdk:assembleRelease
```

产物位于 `build/outputs/aar/`。音源须使用目标宿主配套的 SDK，SDK 与共享运行时均声明为 `compileOnly`。

- [接入指南](../接入文档/README.md)
- [接口、兼容与安装规范](../接入文档/Agent音源接入完整规范.md)
- [示例](../examples/README.md)

许可证： [GPL-3.0-or-later](../LICENSE)。
