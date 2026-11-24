# WebDAV 补充音源包

MusicOne 的 WebDAV 补充音源（`com.musicone.source.webdav`，分发后缀 `.mscp`），随宿主预装，也可独立构建。

## 能力范围

- **协议与鉴权**：内置 OkHttp，支持 `PROPFIND`（Depth 0/1）、Basic 与 Digest（RFC 2617 / 7616）鉴权、重定向跟随与多服务器切换，支持匿名 WebDAV。
- **文件树浏览**：`HOME=CUSTOM` 自绘文件浏览，支持路径面包屑、搜索、排序、定位当前歌曲、书签子页；手机竖/横屏单栏，宽 ≥720dp 且高 ≥480dp 时双栏（左目录树、右列表）。
- **标签、封面与歌词**：按扩展名识别音频（含 FLAC/MP3/M4A/OGG/OPUS/WAV/APE/WV/DSF/DFF/ALAC），列表由文件名秒出后后台限并发补全 `MediaMetadataRetriever` 标签与内嵌封面（回退同目录 `cover.jpg`/`folder.jpg`），按 `路径 + ETag + 大小` 缓存；歌词自动读取同目录同名 `.lrc`。
- **播放、导入与订阅**：原始文件直连播放（携带 `Authorization` 头与精确 MIME 分流），支持“播放此文件夹”“加入本地歌单（可选含子文件夹）”，支持将文件夹加入书签并在公共收藏详情页订阅为本地歌单。

## 独立构建

```powershell
# Debug 调试包
..\..\gradlew.bat -p . -PsourceSdk=..\..\接入文档\source-sdk.aar testDebugUnitTest packageDebugSource

# Release 优化签名包
..\..\gradlew.bat -p . -PsourceSdk=..\..\接入文档\source-sdk.aar -PsourceSigningProperties=..\..\keystore\source-signing.properties packageReleaseSource
```

产物输出位于 `build/outputs/source-packs/debug/webdav-0.1.mscp` 与 `build/outputs/source-packs/release/webdav-0.1.mscp`。
