# Subsonic 主音源

兼容 Subsonic／OpenSubsonic 协议的自建服务器（Navidrome、Gonic、Airsonic、Ampache 等）。提供多服务器切换、首页、搜索、歌手、专辑、我喜欢、歌单编辑、播放与音质、歌词和随机电台。

已加入根工程并随宿主预装，随 `:app:assembleRelease` 一起打包。也可独立构建（`sourceSdk` 指向配套的 SDK AAR）：

```powershell
.\gradlew.bat -p source-packs/subsonic -PsourceSdk=<source-sdk-debug.aar> packageDebugSource
```

产物为 `build/outputs/source-packs/debug/subsonic-0.1.mscm`。正式包须使用固定签名，见[接入规范](../../接入文档/Agent音源接入完整规范.md#build)。

## 协议对应

| 功能 | 接口 | 说明 |
| --- | --- | --- |
| 登录 | `ping`、`getOpenSubsonicExtensions` | token＋salt；服务器返回 41 时改用编码密码 |
| 首页 | `getRandomSongs`、`getAlbumList2` | 随机推荐货架＋专辑推荐流（最新／常听／最近播放／收藏／随机），不提供相似推荐和收藏歌曲卡片 |
| 每日推荐 | `getRandomSongs` | 每台服务器每天抽一次并保存，当天不变 |
| 搜索 | `search3` | 歌曲、专辑、歌手 |
| 歌手、专辑 | `getArtist`、`getArtistInfo2`、`getAlbum` | 歌手歌曲按专辑逐页展开 |
| 我的 | `getStarred2`、`getPlaylists`、`getPlaylist` | 我喜欢＝星标歌曲，收藏＝星标专辑 |
| 收藏 | `star`、`unstar` | 歌曲和专辑 |
| 歌单编辑 | `createPlaylist`、`updatePlaylist`、`deletePlaylist` | 改序用 `createPlaylist` 带 `playlistId` 整体替换 |
| 播放 | `stream` | 原始（`format=raw`）、MP3 320k／128k（服务器转码） |
| 播放记录 | `scrobble` | 开始时报正在播放；听满一半（最多 4 分钟）或播完才计入次数 |
| 歌词 | `getLyricsBySongId`、`getLyrics` | 结构化歌词优先；旧接口只接受 LRC 文本 |
| 电台 | `getRandomSongs` | 全曲库随机，排除本轮已播 |
