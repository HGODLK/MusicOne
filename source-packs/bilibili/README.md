# 哔哩哔哩补充音源

提供视频解析、分 P 导入、订阅与音频播放。

从仓库根构建：

```powershell
.\gradlew.bat :source-packs:bilibili:packageDebugSource :source-packs:bilibili:packageReleaseSource
```

产物位于模块的 `build/outputs/source-packs/`。正式包须使用固定签名；独立构建、共享依赖及安装规则见[接入规范](../../接入文档/Agent音源接入完整规范.md#build)。
