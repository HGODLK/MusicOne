# 音源示例

- [source-main](source-main/README.md)：主音源与公共页面复用。
- [source-supplemental-simple](source-supplemental-simple/README.md)：最小链接转音频。
- `source-supplemental`：资源隔离、生命周期与故障注入原型。
- [player-ui](player-ui/README.md)：自建播放器接线片段。

从仓库根构建独立示例（替换工程名及 SDK 路径）：

```powershell
gradle -p examples/source-main -PsourceSdk=C:/sdk/source-sdk-debug.aar assembleDebug assembleRelease
```

主音源已签 APK 改后缀为 `.mscm`，补充音源为 `.mscp`，不解压重打包。示例使用调试签名；正式分发须配置固定签名并启用 v1。SDK 与共享运行时使用 `compileOnly`。

兼容、签名及安装规则见[接入规范](../接入文档/Agent音源接入完整规范.md)。
