# 网易云音乐主音源

提供登录、推荐、搜索、歌单、播放与歌词。

从仓库根构建：

```powershell
.\gradlew.bat :source-packs:netease:packageDebugSource :source-packs:netease:packageReleaseSource
```

产物位于模块的 `build/outputs/source-packs/`。正式包须使用固定签名；独立构建、共享依赖及安装规则见[接入规范](../../接入文档/Agent音源接入完整规范.md#build)。
