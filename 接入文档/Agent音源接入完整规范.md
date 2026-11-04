# Agent 音源接入完整规范

本文面向实现或维护 MusicOne 0.3.0 音源包的 AI Agent 和开发者，说明当前接口、流程、默认值、限制和示例。快速接入见 [README](README.md)，可自定义范围见 [可自定义功能](可自定义功能.md)。

第 17 章集中收录 SDK 完整声明与默认实现；前文说明宿主实际使用的字段与能力。不能仅凭模型中存在某个字段推断功能已接通。

本项目接入红线：只在音源包内实现功能，公开能力不足时报告缺口；修改公共框架须获得用户在当前请求中的明确授权：“允许修改公共框架”。

## 目录

1. [边界、工具链与兼容](#compatibility)
2. [主音源接入与页面组织](#main-source)
3. [补充音源接入与业务](#supplemental-source)，含 [简化接入与逐页接管](#simple-supplemental)
4. [独立工程与 SDK AAR](#build)
5. [清单、签名与版本](#manifest)
6. [入口、生命周期与资源](#lifecycle)
7. [能力、数据服务与公共模板限制](#capabilities)
8. [通用模型、身份、分页与失败处理](#models)
9. [认证、完整会话与迁移](#authentication)
10. [播放、音质、恢复与缓存策略](#playback)
11. [歌词、主题、背景与权限](#lyrics)
12. [Host 能力与公共 UI](#host-ui)
13. [安装、更新、回退与按类型删除](#installation)
14. [接入验收与源码导航](#acceptance)
15. [宿主与音源包的职责边界](#host-internals)
16. [兼容与交付边界](#documentation-gaps)
17. [SDK 完整声明与默认实现](#sdk-declarations)

## 接口族索引

| 接口或模型 | 查阅位置 |
| --- | --- |
| SourcePlayerUiProvider、SourcePlayerUiProviderAccess、SourcePlayerUiDeclaration、SourcePlayerInsets、SourcePlayerUiContext、SourcePlayerUiActions | [主音源完整播放器](#player-ui) |
| SourceEntry、SourceEnvironment、SourceRoute、SourcePageState | [主页面组织](#main-source)、[生命周期](#lifecycle) |
| SourceDiscovery、SourceSearch、SourceEntities、SourceCollectionEditing；collections、favorites、radio 服务 | [主业务对照](#main-source)、[能力与模板限制](#capabilities) |
| SourceDailyRecommendations、SourceHomeSectionPresentation、SourceHomePresentation、SourceMySectionPresentation、SourceEntityArtwork、SourceHomeContinuation、SourceRemoteCollectionArtwork | [能力与可选扩展](#capabilities) |
| SourceCollectionFavoritePolicy、SourceLibraryChanges、SourceCollectionDetailsEditing、SourceCollectionRemoteOrder、SourceEntityFollowing、SourceHotSearch、SourceCollectionDetailsEdit | [资料库可选扩展](#library-extensions) |
| SourceContents、SourceContent、SourceChildItems、SourceChildItem、SourceRemoteCollections、SourceImporting、SourceSubscriptions、SourceSubscriptionItem、SourceSubscriptionIdentity | [补充业务与订阅](#supplemental-source) |
| SimpleSupplementalSourceEntry、SimpleSupplementalSourceInfo、SourceSupplementalPageProvider、SourceSupplementalPagePolicy、SourceSupplementalPageContext、SourceSupplementalPageActions | [简化接入与逐页接管](#simple-supplemental) |
| TrackIdentity、SourceTrack／SourceTrackDetails、SourceArtwork、TrackAccessInfo、SourceAudioQuality、SourceCollection、SourceEntity／SourceEntityRef、SourcePayload、SourceCursorRequest、SourcePage、SourceOperationException | [通用模型](#models)、[音质与访问展示](#playback) |
| SourceAuthentication、SourceAccountState、SourceAccount、SourceAuthChallenge、SourceAuthResult、SourceSessionSnapshot、SourceLegacySession | [认证](#authentication) |
| SourceMembershipProvider、SourceMembershipDetector、membershipDetectorOrNull、currentMembership、detectMembership | [默认关闭的会员检测](#membership-detection) |
| SourceMediaPlayback、SourcePlaybackRequest、SourcePlaybackResource、PlaybackAddress、SourceMediaSegment、SourcePlaybackBehavior、SourcePlayerPresentation、SourceCollectionReturnBehavior、SourceCollectionCachePolicy | [播放与策略](#playback) |
| SourcePlaybackSessionProvider、SourcePlaybackSessionObserver、SourcePlaybackSessionEvent、SourcePlaybackSessionEventType、SourcePlaybackSessionEndReason | [默认关闭的可选播放会话](#playback-session) |
| SourceQualityCatalog、SourceQualityDefinition | [自定义音质目录](#playback) |
| SourceLyricProvider、SourceLyrics、SourceLyricLine、SourceLyricWord、SourceLyricTiming、SourceLyricBehavior、SourcePermissions、SourcePresentation、SourceTrackTheme、SourceVisuals、SourceVisualState | [歌词与视觉](#lyrics)、[行为扩展](#playback) |
| SourceHost、SourceTrackMetadataEdit、SourceCacheEntry、SourceUiRequest／SourceUiResult、SourcePlayerState | [Host接口](#host-ui) |
| SourceContentUi、SourcePageUi、SourcePageRequest、SourceSupplementalTarget、SourceComponentUi、SourceComponentScope、SourceTrackListState、SourceBackdropState、SourceSimilarRecommendation | [公共页面与组件](#host-ui) |
| SourceTrackComponents、SourceHomeComponents、SourceLoadedHomeComponents、SourceLibraryComponents、SourceBasicComponents、SourceSupplementalComponents | [组件方法表](#host-ui) |
| SourceRootPageUi、SourceRootScrollPosition、SourceHomeShelfComponents | [一级根页与货架](#root-pages-and-shelves) |
| SourceCachedArtworkComponents | [冷启动已有封面准备](#cached-artwork) |
| SourceRepository、SourceInstance、playback/catalog、SourceContentUi | [安装](#installation)、[生命周期](#lifecycle)、[公共 UI](#host-ui) |

<a id="compatibility"></a>

## 1. 边界、工具链与兼容

本文描述 MusicOne 0.3.0、公开 `com.musicone.source.sdk` API 1 的当前实现契约。接口存在、编译、优化构建、真实账号与双设备验收是不同证据，交付时分别记录。

包与宿主同进程运行，宿主通过 DexClassLoader 外载 APK 中的 DEX，并提供 Android／Kotlin／Compose 共享 ABI。包只依赖公开 SDK 和明确由宿主提供的共享库，不引用宿主 `com.musicone` 业务类，不读取宿主私有数据库。代码包不是 Android 应用安装流程，也不是安全沙箱。

| 项目 | 当前契约 |
| --- | --- |
| SDK | SourceSdk.API_VERSION=1；SourceSupplementalContract.API_VERSION=1；包名 com.musicone.source.sdk |
| 工具链 | Gradle 9.6.1；AGP 9.2.1；内置 Kotlin 2.3.21；Compose 编译插件 2.3.21 |
| Android | compileSdk/targetSdk 37；minSdk 至少23；Java字节码17，使用兼容JDK |
| 共享核心 | Kotlin stdlib 2.3.21；Compose Runtime/UI/Foundation/Animation 1.11.4；Material3 1.4.0 |
| 按实际需要声明 | Activity Compose 1.13.0；Lifecycle Runtime Compose 2.11.0；协程 Android 1.11.0；Material Icons 1.7.8 |
| 当前兼容判断 | apiVersion=1；minHostVersionCode 按宿主实际 versionCode 比较；Kotlin／Compose 编译版本须在宿主兼容名单中；minAndroid在23与设备API之间 |

清单以 `minHostVersionCode` 声明最低宿主编号，`minHostVersionName` 可选提供展示名称，只有编号参与比较。本轮宿主为 `0.3.0-麦辣鸡腿堡`／code 5；酷狗参考包因调用 `SourceHost.playRecommendation` 最低为 code 5，其余参考包和示例最低为 code 4。旧包可省略最低宿主字段，继续通过 API、Android 和共享运行时校验；新发布包应声明真实最低需求。同一展示版本新增 SDK 符号或行为时也必须递增宿主 code。`apiVersion=1` 代表接口大版本，不能单独确定可选符号与行为；二进制不兼容改动须提升 API 大版本。Kotlin／Compose 字段记录实际编译版本，不能改清单绕过检查；宿主在 `SourceHostCompatibility` 中维护经验证的兼容名单，当前为 Kotlin 2.3.21、Compose 1.11.4，不按主次版本无条件放行其他补丁。升级共享依赖时须验证旧编译产物后保留相应名单，不能将包使用新符号的情况当作双向兼容。分发说明仍须列出实际验证组合并提供匹配 SDK AAR。SDK 使用 `jvmDefault=DISABLE`；宿主、参考包和独立示例须使用配套 SDK，编译配置见第 4 章。

<a id="main-source"></a>

## 2. 主音源接入与页面组织

本文面向制作 `.mscm` 主音源的开发者。先完成独立示例加载，再按真实业务实现能力；QQ提供公共模板例子，酷狗/网易提供专属页面例子。使用公开SDK不代表可以依赖任何宿主内部平台类。

### 主音源快速接入

1. 生成SDK AAR，复制examples/source-main为独立工程，调整稳定ID、namespace、入口、显示名和自己的签名。
2. 配置签名清单kind=MAIN、分发后缀.mscm，保持descriptor完整一致；先验证示例资源、返回和持久化。
3. 选择useCustomPages=false的公共主模板或true的自定义入口（允许公共复用），按下方能力接口实现真实业务。
4. 保持曲目 stableId 稳定；有旧数据兼容需求时保留原 ID，并按第9章适用范围处理完整会话迁移。实现播放/真实音质/歌词/资料库，不用空服务冒充支持。
5. 构建普通和优化包，随后分开验证认证、网络失败、手机/平板与更新删除行为。

```powershell
gradle -p examples/source-main -PsourceSdk=C:/sdk/source-sdk-debug.aar assembleDebug assembleRelease
gradle -p examples/source-main -PsourceSdk=C:/sdk/source-sdk-debug.aar -PsourceVersion=2 assembleDebug
# 示例产物是已签APK，复制并改后缀，不能把源码目录压缩成包。
$apk = Get-ChildItem examples/source-main/build/outputs/apk/debug/*.apk | Select-Object -First 1
Copy-Item -LiteralPath $apk.FullName -Destination C:/packages/example-main-v2.mscm
# 真实业务包独立构建示例，任务会自动复制并命名分发文件：
gradle -p source-packs/kugou -PsourceSdk=C:/sdk/source-sdk-debug.aar packageDebugSource
# release 另传固定签名配置（见签名章节）：
gradle -p source-packs/kugou -PsourceSdk=C:/sdk/source-sdk-release.aar -PsourceSigningProperties=C:/private/musicone/source-signing.properties packageReleaseSource
```

示例输出名应以其Gradle工程名和实际outputs/apk目录为准；不要把自建工程改名后仍照抄旧产物名。

### 主音源清单例子

以下对应 `examples/source-main` 当前默认 `sourceVersion=2` 的公共 UI 演示声明。示例提供内存虚拟歌曲、歌单、歌手、专辑、搜索及分页，不提供真实播放、账号或远端收藏。改变 `-PsourceVersion` 时，示例会同步生成清单及 BuildConfig，不能把本段 JSON 固定覆盖到另一版本。入口见 `examples/source-main/src/main/java/org/example/source/ExampleSource.kt`；业务源按真实实现调整能力与服务。

```json
{
  "id":"org.example.main", "name":"外部音源示例 main",
  "kind":"MAIN", "versionCode":2, "versionName":"1.0.2",
  "entryClass":"org.example.source.ExampleSource",
  "apiVersion":1, "minHostVersionCode":4, "minHostVersionName":"0.3.0-麦辣鸡腿堡", "minAndroid":23,
  "kotlinVersion":"2.3.21", "composeVersion":"1.11.4",
  "capabilities":["HOME","SEARCH","PLAYLIST","ARTIST","ALBUM"], "useCustomPages":true,
  "presentation":{"pageColorArgb":4281559415}
}
```

### 主音源业务服务对照

| 业务入口 | 公开契约与实际处理 |
| --- | --- |
| 首页/推荐 | discovery.home(SourceCursorRequest)→SourcePage<SourceHomeSection>；section可含tracks/collections/entities/payload，保留服务端顺序 |
| 相似/最近/上报 | supportsSimilar/similar/similarSection；supportsRecent/recent/recentSnapshot；reportPlayed(track,playedMs)；SourceRecentSnapshot区分歌曲/歌单/专辑总数和电台 |
| 真实收听会话 | `mediaPlayback` 可选实现 `SourcePlaybackSessionProvider`，显式提供观察者后接收真实播放事件和累计收听时长；默认关闭，详见 [可选播放会话](#playback-session) |
| 搜索 | search.kinds、search(query,kind,request)→SourceSearchResult；公共模板仅消费TRACK/COLLECTION/ARTIST/ALBUM，suggestions仅supportsSuggestions=true时调用 |
| 歌手/专辑 | entities.kinds含artist/album；entity、tracks、collections；歌手内搜索由supportsTrackSearch声明；提供非空trackSortOptions时也须实现空查询的searchTracks，详见第7章 |
| 我的/歌单 | collections.librarySections列出FAVORITE_TRACKS/CREATED_COLLECTIONS/SAVED_COLLECTIONS；library/detail/tracks；sortOptions/selectedSort/selectSort只在真实支持时声明 |
| 远端收藏 | favoriteTrackIds返回包内stableId全量集合；SDK允许null，公共刷新此时保留已有索引，不调用isTrackFavorite逐曲补查；公共远端读写需要当前账号，详见第7章；editTrackMetadata对应的本地收藏分支见第11章；批量setTracksFavorite应override保留节流；收藏歌单须supportsCollections=true |
| 编辑 | collectionEditing.supportedOperations为CREATE/EDIT/DELETE/CHANGE_TRACKS/REORDER的真实子集；writableCollections与create/edit/delete/changeTracks/reorder；未接入 SourceCollectionRemoteOrder 扩展时没有公共远端改序入口；页面排序与远端改序的区别见能力章节 |
| 电台 | radio.next(previousPayload)→SourceRadioBatch(tracks,continuation)；续页参数由包解释且只在同一会话、同一音源内传递，队列执行归宿主；feedback(track,action)是SDK必需方法，但当前宿主没有调用 |
| 认证/设置 | authentication、authenticationPage、settings；公共模板不替代平台特有验证协议 |
| 音频/歌词/主题 | mediaPlayback、lyricProvider、trackTheme；字段和边界见本文对应通用章节 |

SourceSearchResult把tracks/collections/entities/contents和nextCursor分开；不能把每种结果都伪装成歌曲。当前公共搜索不请求CONTENT或展示contents，仅声明CONTENT不会获得可用公共搜索分类。实体kind与搜索enum是两个不同类型，不把它们合并为同一个kinds属性实现。集合编辑的默认方法是Unsupported，不声明空实现。各包按真实实现声明编辑操作与实体、电台能力，不按参考平台名称推定支持范围。SDK能力校验与公共模板可用范围分别见第7章，不能把二者等同。

### 公共模板与自定义入口（允许公共复用）

useCustomPages=false时，宿主按能力消费公开服务，呈现通用首页、我的、搜索、歌曲/歌单/歌手/专辑详情、选择菜单和电台。源只提供协议、数据、声明、认证与需要的设置；不能要求宿主理解包私有payload。QQ包是实际参考。入口Content仍是SourceEntry必需方法，但公共模板不会因此自动把未提供的数据服务补齐。

useCustomPages=true时，包提供自定义入口（允许公共复用），负责已声明内容页面的装配和内部导航：首页、我的、搜索与历史、歌单/专辑/歌手详情、推荐/最近/相似、选择和批量操作、账号/验证/平台设置及自己声明的其他业务。不支持的业务隐藏入口；声明支持的页面必须可用。允许主动调用 contentUi.pages 的全部完整公共页面和 contentUi.components 的功能／基础组件来组织自己的页面，不得借用其他平台的私有业务。宿主仍持有通用播放器、歌词、队列、本地资料库和缓存。酷狗/网易是实际参考，认证页可单独通过authenticationPage打开。

宿主当前根页以原HorizontalPager预组合home/my，并通过SourceRoute.arguments传embedded=true、active=true/false、bottomInsetDp和requestRevision，值均为字符串；requestRevision在每次打开搜索时递增，用于区分重复打开请求。active=false只绘制route指定根页，不能执行导航或覆盖共享controller；active=true才响应根路由；激活首帧直接以目标根路由决定画面，不能先绘制共享controller的旧根页再等待LaunchedEffect纠正。根页保留宿主PrimaryHeader/BottomBar，包隐藏重复顶栏/tabs，列表仍保留宿主覆盖所需顶部留白并使用bottomInsetDp留底。不要把预组合的我的页误当当前页面。

SourceEntry.pageState提供StateFlow<SourcePageState(page,title,atRoot)>，内部搜索/详情/登录等atRoot=false，使宿主隐藏根页顶栏；回到首页/我的atRoot=true。当前主音源根页只消费其中的 atRoot，page/title 不会自动导航或生成标题；未提供 pageState 时按 SourcePageState() 的 atRoot=true 处理。内部页保留包自己的返回/标题，onBack先退出选择或搜索再退详情。当前根页面使用home/my/search；专属playlist/collection/album/authentication/settings等路由以包声明处理器为准，未约定的route不是SDK保证。

<a id="player-ui"></a>

### 主音源自建完整播放器

播放器页面是主音源可选能力。接口和宿主职责按本节契约执行；编译或单元测试通过不等于真实设备、播放业务、共享动画或三种设备形态已完成验收，接入者应按第 14 章清单分别验证。

主音源入口可实现 `SourcePlayerUiProvider`，也可实现 `SourcePlayerUiProviderAccess` 并由 `playerUiProvider` 返回独立对象；`playerUiProviderOrNull()` 优先读取独立对象，再检查入口本身。宿主依据当前播放歌曲所属音源挂载页面，不随正在浏览的首页音源变化。该能力与 `useCustomPages` 无关，且不增加 `SourceEntry` 的抽象成员。入口未提供播放器 UI 时使用宿主播放器；提供后须使用包含该接口的配套 SDK 和宿主版本。

`playerUiDeclaration` 必须完整声明以下内容：

| 字段 | 约定 |
| --- | --- |
| `supportsPhone` | 手机竖屏支持情况。 |
| `supportsTablet` | 平板支持情况，包自行按可用空间排布。 |
| `supportsPhoneLandscape` | 手机横屏支持情况，不能把支持手机竖屏视为同时支持横屏。 |
| `opening.durationMillis` | 打开动画时长，由宿主统一推进并通过上下文提供进度。 |
| `opening.barParticipates` | 底部 bar 是否参与打开过渡，必须显式选择；不开放 bar 外观。 |
| `opening.sharedElements` | 共享元素集合，显式为空表示不使用；可选 `ARTWORK/TITLE/ARTIST/PLAY_PAUSE`。 |

声明校验要求 `supportsPhone` 与 `supportsTablet` 至少一项为 true，时长非负，`supportsPhoneLandscape=true` 时 `supportsPhone` 也须为 true；`barParticipates=false` 时共享集合必须为空。声明不合法会拒绝该能力接入，不将错误声明当作“未支持形态”。

未声明支持当前形态时使用 MusicOne 原生播放器。声明支持后，由包提供完整 `Player(context)` UI；页面失败仅显示错误，不自动或通过错误页选项回退到原生播放器。此规则覆盖通用外部页面的公共页回退规则。收起、系统返回及关闭手势仍归宿主，包通过 `dismiss()` 请求收起。宿主“关闭横屏播放页”设置仅将内置手机播放页固定为竖屏，不影响平板或音源自定义播放页；自定义播放页仍按声明选择支持的形态。

包用 `context.SharedElement(element, modifier) { 真实UI }` 注册实际目标元素及边界。宿主从当前底部栏源位置向包目标运行共享过渡；应使用真实封面、文字和按钮，不能另放一套假元素或仅用整页淡入冒充共享动画。同一元素只注册一个可见目标，集合声明与实际目标保持一致。包可依 `transition.progress`、`opening`、`interactive`、`reduceMotion` 安排其余内容入场，不自行创建第二套开页或收起时钟。底部胶囊、bar 的布局和外观均不开放。

宿主先等待包页面真实布局就绪，再开始共享过渡；声明的全部共享目标须在 160ms 布局预算内注册有效边界。缺失目标进入错误页，不回退原生。不要把 `progress>0` 作为创建共享目标的前提，否则目标无法在开页前准备就绪。

曲目允许缺少歌手等展示资料。已声明共享文字目标时，空或纯空白文字须显示真实占位或为目标保留非零布局，不能直接绘制零宽文字而令共享目标失效。[简洁示例](../examples/player-ui/README.md) 对标题及歌手均提供缺失占位；这只是显示处理，不改写曲目资料。

`snapshot` 提供只读歌曲、播放模式、收藏及音质展示；`timeline` 提供歌曲 ID、播放代际、位置、时长及是否推进。拖动进度调用 `actions.seekTo(trackId, generation, positionMs)`，使用同一次手势捕获的歌曲和代际，避免切歌后误跳新歌。动作必须在主线程、有效实例且 `transition.interactive=true` 时调用；旧上下文、过期进度和不在当前目录中的音质会被拒绝。`actions` 只发起受宿主校验的播放暂停、前后曲、模式、音质、队列、收藏及歌词重试动作；不能直接接管播放器、队列或解析流程。

歌词通过 `context.Lyrics(modifier)` 使用宿主歌词视图和既有时钟，包可决定区域排布；不自行轮询构建歌词时钟。`onFailure(message)` 报告包内可识别的页面故障；组合／布局／绘制故障交给宿主外部页面边界。快照不是可写核心状态，包不应把上下文保存在页面生命周期之外。

同音源切歌保留页面挂载与 `remember` 状态，包应按歌曲身份更新歌曲专属临时状态。每次读取 `context.actions` 得到绑定当前歌曲／播放代际的动作对象，不长期缓存旧 `actions` 或通过 `remember` 固定旧方法引用；按钮应在点击时读取当前 `context.actions`。进度操作的 ID 必须取 `timeline.trackId`，不能使用 `snapshot.track.stableId` 代替宿主时间线身份。

`context.safeDrawingInsets: SourcePlayerInsets` 给出当前播放器页面坐标中的绝对 `left/top/right/bottom: Dp` 安全边距，合并 `safeDrawing/displayCutout/waterfall`，并由宿主完成手机左右横屏转换。手机、平板均直接使用，无需包自行转向。内容区域使用 `Modifier.absolutePadding(insets.left, insets.top, insets.right, insets.bottom)`，背景仍可满幅绘制。不要叠加 `.safeDrawingPadding()`：自建手机横屏运行在旋转页面画布中，该原生 Modifier 仍反映竖屏 Activity 原始边距，不能代替页面坐标的安全区。

播放页挂载区域还隔离同步触摸、键盘／DPAD、通用指针及无障碍节点动作中的异常，失败显示原有错误页，不回退原生。该边界不覆盖包自行创建的独立窗口或任意后台任务，不是进程级安全沙箱。屏幕常亮沿用宿主“播放页播放时屏幕常亮”设置，音源页不需要自行持有常亮状态。

可复制 [简洁示例](../examples/player-ui/README.md) 到主音源工程；它演示声明、共享标题／歌手／播放暂停、统一进度、歌词区域和受控操作，不提供完整 Gradle 工程。验收分别覆盖三种形态、未支持形态、页面故障不回退、打开来源元素连续性、收起、切歌中拖动进度和歌词时钟；源码存在不等于设备验收通过。

<a id="supplemental-source"></a>

## 3. 补充音源接入与业务

本文面向制作 `.mscp` 补充音源的开发者。补充源可把远端视频/节目等内容解析成音频，接入子条目选择、远端集合和订阅；不把源名称、视频ID或分P字段写死到公开SDK。B站是当前真实业务例子，不代表补充源只能实现该平台或只能返回DASH。

### 补充音源快速接入

1. 生成SDK AAR，复制examples/source-supplemental验证独立外载资源、返回、存储与生命周期。
2. 使用自己的稳定ID/namespace/入口/签名，声明kind=SUPPLEMENTAL并分发.mscp；按实际能力选择公共补充模板或自定义入口（允许公共复用）。
3. 实现内容解析、子条目和导入，再接远端集合及需要的全量订阅；可选认证不应阻止访客进入允许公开解析的页面。
4. 把完整曲目、稳定子条目身份、媒体头/备用地址和缓存身份交宿主；未知或失败页不能清空本地内容。
5. 独立普通/优化构建后，验证指定子条目、选择、分页失败保旧、订阅原子提交、真实播放及来源移除。

```powershell
gradle -p examples/source-supplemental -PsourceSdk=C:/sdk/source-sdk-debug.aar assembleDebug assembleRelease
# 实际业务补充包，独立于宿主源码构建：
gradle -p source-packs/bilibili -PsourceSdk=C:/sdk/source-sdk-debug.aar packageDebugSource
# release 另传固定签名配置（见签名章节）：
gradle -p source-packs/bilibili -PsourceSdk=C:/sdk/source-sdk-release.aar -PsourceSigningProperties=C:/private/musicone/source-signing.properties packageReleaseSource
# 根工程已include时也可执行：
.\gradlew.bat :source-packs:bilibili:packageDebugSource :source-packs:bilibili:packageReleaseSource
# 业务包输出：source-packs/bilibili/build/outputs/source-packs/debug/bilibili-1.0.mscp
```

### 补充音源清单例子

下面列出B站参考包的当前实质配置。接入其他音源时须使用自己的身份、名称、入口和真实实现的能力，不能沿用他人ID/签名。完整JSON见包的src/main/assets/musicone-source.json，必须与descriptor一致。

```json
{
  "id":"com.musicone.source.bilibili", "name":"哔哩哔哩",
  "kind":"SUPPLEMENTAL", "versionCode":1, "versionName":"1.0",
  "entryClass":"com.musicone.source.bilibili.BilibiliSourceEntry",
  "apiVersion":1, "minHostVersionCode":4, "minHostVersionName":"0.3.0-麦辣鸡腿堡", "minAndroid":23,
  "kotlinVersion":"2.3.21", "composeVersion":"1.11.4",
  "capabilities":["HOME","ACCOUNT","PLAYBACK","CONTENT","CHILD_ITEMS","REMOTE_COLLECTIONS","IMPORT","SUBSCRIPTION"],
  "useCustomPages":false, "requiresLogin":false,
  "permissions":{"editTrackMetadata":true,"replaceManualLyrics":true,"adjustLineOffset":true},
  "presentation":{"pageColorArgb":4294668953,"lineLeadMs":0,"wordLeadMs":0}
}
```

### 内容、子条目和远端集合

| 能力接口 | 必需方法与数据 |
| --- | --- |
| SourceContents | resolve(input)与detail(content)→SourceContent；短链和输入格式由包解析，宿主不假定BV/URL格式 |
| SourceContent | identity/title/description/author/artwork/durationMs/access/payload；playableTrack可选，提供默认可直接播放的完整曲目 |
| SourceChildItems | children(content,request)→SourcePage<SourceChildItem>；每项identity/index/title/durationMs/payload/initiallySelected |
| SourceRemoteCollections | collections(request)→远端集合页；contents(collection,query,request)→内容页；集合含identity/title/count/artwork/payload |
| SourceImporting | tracks(content,selectedChildren)→List<SourceTrackDetails>；按该次调用传入的选中项转换，保留稳定旧ID和子条目资料，不擅自补选子条目 |
| SourceSubscriptions | page(collection,request)→SourcePage<SourceSubscriptionItem>；成员remoteMemberId/tracks/position |

SourceContent是远端内容条目，SourceChildItem是其可选子条目，导入后才成为完整SourceTrackDetails；不要把可播放曲目与内容列表身份强制混成一个ID。playableTrack用于列表默认播放入口，有明确默认子条目时填入；没有可直接消费曲目可以null，宿主再走详情/导入选择。稳定身份不能依赖列表序号或当前分页位置。

当前公共补充模板每次成功解析输入或重新打开内容详情，都会先收齐 children 的全部分页，再用 initiallySelected 重建选择集合；不会继承该内容上一次的手动选择。当前详情内的全选／清空／单项选择在下一次重新解析或打开前有效。children 列表按页直接拼接，不按 identity 去重；只有选择集合按 identity 存储，重复身份会影响多行选择及导入结果。因此包应在跨页结果中保证子条目身份唯一，不能依赖宿主清除重复项。链接指定某个子条目时，包可只将该项设为 initiallySelected；无指定项时按真实业务声明默认选择。服务端position和显示index不能替代稳定远端ID。

远端集合可局部要求登录，未登录仍可进入补充首页、查看说明或解析允许的公开链接。查询变化取消旧分页，首屏/下一页/刷新失败保留旧数据。count允许null表示未知，不以错误结果0覆盖已有集合计数和封面。认证页面可以独立自有，不要求整个内容页专属。

### 导入与订阅必须分开

导入是选定内容/子条目转换为完整曲目，再经host.library.destinations/importTracks进入允许手动添加的宿主歌单。不能偷偷导入未选择子条目，也不能把不接收手动成员的目标作为普通导入目标。允许时可先调用library.createCollection创建本地目标。

未实现 `SourceSupplementalPageProvider` 的入口，公共补充集合列表的多选“添加到／播放所选”按该入口的服务执行：对每个选中内容优先采用非空 `playableTrack`；为 null 时才收齐 children 的全部分页，选取 `initiallySelected=true` 的子条目并调用 `importing.tracks`。存在子条目但没有默认选中项时失败，不继承之前内容详情中的临时手动选择；没有子条目时以空选择列表调用 importing。转换完成后按 stableId 去重，整批成功且结果非空才导入或播放。实现 `SourceSupplementalPageProvider` 的入口按下节统一转换规则处理。

订阅身份为SourceSubscriptionIdentity(sourceId,accountId,remoteCollectionId,localCollectionId)，四项完整匹配；同远端ID跨来源/跨账号不能共用关联。包SourceSubscriptions只提供成功分页。已有订阅在开始拉取第一页之前读取 subscriptionRevision 并保存为 expectedRevision，收齐所有成功页后带同一个值调用 commitSubscription 一次原子合并；不能拉完再读取最新修订号来掩盖期间发生的修改。新订阅先收齐，再创建本地目标和完整订阅身份，读取这个新身份的初始修订号后提交。失败、取消、换号或修订冲突保留旧列表，不提交部分成功页。

宿主本地歌单的“刷新订阅”要求音源已激活、当前订阅身份与订阅账号相同；入口使用真实账号，或在未登录时显式提供公开身份，并同时提供 subscriptions 和 remoteCollections。宿主先收齐 `remoteCollections.collections` 目录，按 remoteCollectionId 重新定位集合，再读取修订号及全部 `subscriptions.page`；缺少目录服务、目录读取失败或查不到集合均失败保留原内容，不能仅实现 subscriptions 就保证这个刷新入口可用。当前目录与订阅分页都使用默认 `SourceCursorRequest`（CACHE_FIRST），提交前再次确认账号状态未变；包应按真实协议提供完整成功结果，不能把缺失目录或请求失败伪装成成功空订阅。

SourceSubscriptionItem.remoteMemberId识别远端内容成员，一个成员可有多个tracks（例如用户明确导入的多个子条目）。成功刷新按 position 恢复远端成员顺序，清除本地手动排序标记；手动添加的成员保留原相对顺序并排在远端成员之后。刷新不会保留此前对远端成员所做的自定义排序；失败或取消则保留旧列表和旧顺序。

匹配到已有 remoteMemberId 时，宿主直接保留其已选子条目的旧曲目对象、已有资料和隐藏状态，不以每页兜底单曲替换原多子条目集合；这一步也不会合入远端新返回的 access 或失效原因，不能承诺订阅刷新即更新已有成员的可播放状态。新成员采用包返回的 tracks。完整成功结果中已不再出现的远端成员会删除其未隐藏的本地成员关系，隐藏记录保留；包应区分真实移除、仍存在但不可用和请求失败，不能用失败页冒充成功空列表。

取消订阅的 SDK 入口为 removeSubscription(identity,keepTracks)：true 保留当前可见歌曲成员并解除关联；false 删除该订阅歌单中的远端成员关系，只保留可见的手动添加成员。两种方式都会清理该歌单已隐藏的成员记录，不会删除其他歌单里的歌曲或清除离线缓存。当前公共补充模板只提供“取消订阅（保留歌曲）”，固定传 true，没有同时删除远端成员的选项；自定义页面若提供 false 分支，应明确说明该操作的含义并由用户选择。取消关联与删除源包是不同操作。包不直接写宿主数据库，也不凭页内旧账号摘要强行提交。

提交订阅不能使用统一音乐、手机存储、全部本地这几个系统歌单，应使用独立本地目标。宿主按 position 排序后按 remoteMemberId 保留首次成员，重复远端成员不会拼接成多个订阅成员。Host 检查 sourceId、曲目来源及调用期间的账号租约；音源仍须确保传入 accountId／remoteCollectionId 确实属于用户当前选择，不能依赖 Host 校验平台身份语义。

### 公共补充模板与自定义入口（允许公共复用）

以下说明按补充入口是否实现 `SourceSupplementalPageProvider` 区分当前页面组织规则；该接口同时决定账号、能力按钮及内容转换所使用的契约。

`useCustomPages=false` 时，宿主呈现公开补充入口：链接／关键词输入、解析内容、远端集合／内容分页、子条目多选／全选／清空、播放、导入目标和订阅操作；B站是参考实现。认证与设置可由包单独提供。模板消费 `contents`／`childItems`／`remoteCollections`／`importing`／`subscriptions` 等真实服务，不解析私有 `payload`，也不能把其他平台专属页面当通用模板。当前内容详情的导入／播放按钮和集合“播放全部”没有完整按能力隐藏，订阅要求有效账号；未实现 `SourceSupplementalPageProvider` 的入口在缓存目录完整读取时仅收集 `playableTrack`，实现该接口的入口按统一默认选择转换，预览不展开全部子条目。具体条件及缺少服务时的行为见第 7 章“公共模板当前的能力组合限制”。

useCustomPages=true时，包提供自定义入口（允许公共复用），负责已声明补充内容页面的装配，包括首页、内容解析/详情、远端集合及搜索/分页、子条目选择、导入/订阅交互和已声明的账号/设置；公共播放器、歌词、队列、库、缓存仍归宿主。专属页可自由调用 contentUi.pages 完整页面与 contentUi.components 全部功能／基础组件，组合使用或完全自行实现；三层只是组织方式，范围见第12章及统一目标。

当前补充自定义内容入口调用 Content(SourceRoute("home", arguments))，arguments 只有 active（"true"／"false"）和 bottomInsetDp（dp数值字符串），不传主音源根页的 embedded／requestRevision。内部路由与返回栈由包维护；宿主返回时调用 onBack()，true 表示包已消费返回，false 则退出到补充音源列表。当前该入口不订阅 pageState，也不根据 atRoot 调整外层导航；pageState.atRoot 的宿主消费属于主音源根页，不能当作补充页退出条件。独立认证／设置入口的路由见第12章。

<a id="simple-supplemental"></a>

### 简化补充接入与逐页页面策略（API 1）

补充音源逐页策略通过 `SourceSupplementalPageProvider` 显式声明，清单使用 `apiVersion=1`、`kind=SUPPLEMENTAL`，并将 `useCustomPages` 设为 `false`。运行时仅对实现该接口的入口应用逐页策略；未实现该接口时，按 `useCustomPages` 及公开业务服务组织页面。使用该接口的分发包必须配套包含对应符号和行为的 SDK AAR 与宿主，并在发布说明列出已验证的宿主版本或构建标识。

最小独立工程见 [source-supplemental-simple](../examples/source-supplemental-simple/README.md)。最小接入只需实现真实业务，不要求填空账号、空远端集合或空订阅。统一转换仅在本次选中子条目为空且内容已有完整 `playableTrack` 时直接取该曲目，其余情况调用 `importing.tracks`；不能把“有 playableTrack”理解为总能省略 importing。详情使用当前手动选择，存在子条目却未选择时拒绝操作。单项播放、集合多选、播放全部与缓存完整读取采用默认选择：只要有 contents 就先调用权威 `contents.detail`（即使原内容已有 playableTrack），没有 contents 时保留原内容，然后仍收齐 children 的全部分页，只取 `initiallySelected=true` 的项，再按上述规则转换；有子条目却无默认选择时失败，不静默全选，也不继承详情中的临时选择。未实现 `SourceSupplementalPageProvider` 的入口按本规范定义的页面组织和转换规则运行。

HOME 关闭时隐藏音源入口；公共首页只显示真实能力及页面策略允许的子入口。需要更多能力时，仍可逐项实现原有 `contents/childItems/remoteCollections/importing/subscriptions/mediaPlayback` 等完整服务；简化接口不移除任何高级能力。

`SimpleSupplementalSourceEntry(SimpleSupplementalSourceInfo(...))` 只要求覆写两个 `protected suspend` 方法：`parseInput(input: String): List<SourceTrackDetails>` 和 `resolvePlayback(request: SourcePlaybackRequest): SourcePlaybackResource`。`info` 默认声明 HOME、CONTENT、CHILD_ITEMS、IMPORT、PLAYBACK，清单必须包含同一集合且 `useCustomPages=false`；示例从 `source.properties` 同时生成身份常量和清单，避免重复维护 ID／名称／版本。

解析返回一个曲目时直接作为可播放内容；多曲目转成公共子条目，默认全部选中，导入仅转换本次选中身份。解析结果必须非空、stableId 非空、身份及 stableId 各不重复且属于本源。基类缓存最近最多32次解析，淘汰后按保存的输入重新解析；重解析后内容身份改变会报错，失败不变成空成功。缓存命中不会重新检查账号或自动重解析；宿主换号会清理公共模板当前内容并拒绝旧事件，但包仍须弃用自行保留的旧账号 content，按新账号重新解析，不能通过新 context 重用旧内容。临时签名地址不得作为真实平台长期身份。

缓存淘汰后，`detail` 用本次解析的歌曲更新内容标题、封面、时长及 `playableTrack`；`children/tracks` 同样消费当前快照，不因旧内容曾是单曲而跳过新结果。单曲变成多个子项时重新生成默认选择，多项变单曲时更新直接播放入口；原先选中的身份已不存在则拒绝转换，不能默默导入另一首。缓存命中仍使用已有快照，不额外联网刷新。

手动选择入口对未选子项的旧单曲也先通过 `contents.detail` 更新一次；默认转换已经取得详情时不重复调用。若旧单曲更新后变为多项，当前手势未选择子项就不自动全选，需重新打开并选择后操作；自动默认转换仍按新详情的默认选择处理。

默认提供一档 `default`／“默认音质”，不猜测编码或无损规格。多档可覆写 `qualityDefinitions`、`defaultQualityId` 和 `fallbackQualityIds`；恢复默认重新调用解析资源，可覆写 `recoverPlayback`。需要整套特殊策略时可直接覆写 `mediaPlayback`。默认取址的请求用途、设备能力和原始音质 ID 均沿用公开 `SourcePlaybackRequest`。

`onInitialize(environment)`、`onClose()` 可选；公共 `initialize/close` 由基类保持，包私有后台任务仍需登记取消。认证、远端目录、订阅、歌词和设置等可以覆写对应业务 getter，并通过 `info.extraCapabilities` 仅声明真实能力；基类不补空账号或假订阅。`Content` 默认空实现用于满足 `SourceEntry` 接口；逐页策略入口的公共页面由宿主根据 API 1 可选接口组织，不应依赖宿主调用该空实现提供业务页面。

任何实现 `SourceSupplementalPageProvider` 的入口都必须声明 `kind=SUPPLEMENTAL`、`useCustomPages=false`，不限于简化基类；否则初始化校验失败。`supplementalPagePolicy(page)` 按 `HOME/LINK/CONTENT/COLLECTIONS/COLLECTION/ACCOUNT/SETTINGS` 单独返回策略：

| mode | 含义 |
| --- | --- |
| DEFAULT | 使用对应公共页；未覆写策略默认此值，仍须满足下表的服务条件。 |
| CUSTOM | 调用包的 `SupplementalPage(context)`；包可组合任意公开页面和组件。 |
| DISABLED | 显式关闭该页面入口，不用缺数据或异常来代替关闭声明。 |

公共页的可用条件如下；CUSTOM 页面自身不要求这些服务，但显式回退、`openPublic` 和 `PublicContent` 仍须具备对应公共能力，不能绕过 DISABLED。

`COLLECTION=DISABLED` 时，公共收藏卡片不能打开集合详情，模型选择入口也会拒绝；独立播放按钮仍按播放能力保留，不把禁详情扩大为禁播放。

| 页面 | 公共页所需服务 |
| --- | --- |
| HOME | 无额外服务条件；入口仍受页面策略控制 |
| LINK | contents |
| CONTENT | contents、remoteCollections、importing 至少一项 |
| COLLECTIONS / COLLECTION | remoteCollections |
| ACCOUNT | authentication；仅有 authenticationPage 不够 |
| SETTINGS | settings |

`SourceSupplementalPagePolicy.allowPublicFallback` 默认 false，只有 CUSTOM 允许设为 true，其他 mode 设为 true 会导致初始化校验失败。自定义页失败通过 `context.onFailure(message)` 报告；会显示明确错误，只有显式允许 fallback 且公共页可用才显示“使用公共页面”按钮，由用户选择切换展示；不会自动重做导入或订阅等写操作，不能悄悄改变页面组织。Compose 异常隔离依然受第6章独立错误边界限制，不能把普通 `try/catch` 当安全沙箱。

`context` 提供页面及 `SourceSupplementalTarget`、底部边距、返回、明确失败报告、打开或嵌入公共页；`actions` 提供 `navigate/resolve/openContent/play/importContents/subscribe/refreshSubscription/removeSubscription`，用于导航、解析、内容打开、播放、导入、订阅、刷新及取消订阅，沿用公共业务校验。不要另写宿主数据库或旁路账号／修订检查。包自己的自绘布局仍须兼顾手机和平板、明暗主题及降低动效偏好；自定义页只接管所声明的页面，不替换公共播放器和音频输出。

`actions.navigate` 与 `context.openPublic` 是普通事件方法，target 默认 Home；导航到 CONTENT／COLLECTION 时必须显式传入同源的 `SourceSupplementalTarget.Content`／`Collection`，否则失败。`context.PublicContent` 才是 Composable，默认使用当前 page／target，可直接嵌入当前公共页。context 及其 actions 绑定当前音源实例、账号／公开身份和页面挂载；换页、卸载、换号或实例失效后不得缓存并继续使用旧对象，应使用新页面收到的 context。来源与租约检查不替代包对内容所属账号的维护。

`PublicContent` 只嵌入公共正文，不绘制第二套标题／返回栏；内层当前页标题由宿主外层标题栏展示，返回先处理内层导航栈，再交外层。实现 `SourceSupplementalPageProvider` 的入口在任意前台页面每 500ms 检查认证状态、账号 ID 与 revision；任一变化均取消会话任务、清理导航及内容、重建有效 context，同账号 revision 变化也会失效旧对象。未实现该接口的入口，其公共模板账号页沿用模板现有的认证检查范围。

会话清理按世代隔离：旧任务的挂起调用返回和 `finally` 清理都不能再写入新会话状态，不能将取消旧请求等同于已完成写入隔离。

公开订阅只对 API 1 显式声明的稳定 `publicSubscriptionIdentity()` 开放。真实登录账号优先使用；仅在没有真实账号时才使用公开身份。它不是登录账号，不创建伪账号；身份须非空、稳定，换值会形成不同订阅关联。宿主将公开身份按 UTF-8 URLEncoder 编码并加 `@musicone-public/v1/` 前缀存储，不把它冒充真实账号；真实账号 ID 维持原值，占用该保留前缀时拒绝，防止两类订阅关联混合。未声明时沿用账号要求。订阅仍必须完整读取全部成功分页、校验身份／修订并原子提交；取消、失败和身份变化保留旧成员，不能提交半页或失败空列表。公开集合能否定位、是否允许公开读取由包真实协议决定。

逐页策略下的 `subscribe` 会复用同源、当前订阅身份及同远端集合的现有关联；`refreshSubscription` 刷新该关联，`removeSubscription` 取消关联并保留歌曲。宿主本地歌单刷新同样使用真实账号优先／显式公开身份规则，提交前再次核对身份，身份改变时失败保旧。

DEFAULT 账号页先要求 `authentication` 非空；在此基础上，有 `authenticationPage` 时显示包提供的隔离认证页，没有认证页时只显示真实账号摘要及退出，未登录则明确提示未提供登录页，不猜用户名、密码、Cookie 或平台验证码字段，不回落到可能为空的 `Content`。需要独立登录流程时由包实现公开认证页。

### B站参考的私有边界

B站参考包的定位载荷、Cookie 和设备身份由包内协议处理，不是公开 SDK 的通用格式。其他音源使用自己的 payload，并保持曲目身份稳定。

BiliSourcePlayback返回纯音频DASH清单、真实音轨及全部CDN/备用URL/headers，稳定key含账号/视频/子条目/音轨/codec。宿主对请求地址登记稳定key，播放器以DASH类型解释清单；缓存下载音轨字节，离线保留/重写清单指向已缓存数据。第三方补充包也可返回普通音频URL，不能仅因kind=SUPPLEMENTAL就强制认为每包都是B站DASH。

B站没有在线歌词服务时不声明LYRICS；仍可独立允许手动歌词和用户整行偏移。未声明的提前量为0/0，与是否支持资料编辑无关。更多播放和权限细节见本文播放及歌词权限章节。

<a id="build"></a>

## 4. 独立工程与 SDK AAR

从公开 SDK 构建接口产物，交给独立开发者。源码根执行下列命令：

```powershell
.\gradlew.bat :source-sdk:assembleDebug :source-sdk:assembleRelease
# 接口产物：source-sdk/build/outputs/aar/source-sdk-debug.aar
# 优化对应接口：source-sdk/build/outputs/aar/source-sdk-release.aar
```

独立工程保留 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`src/main/AndroidManifest.xml`、入口 Kotlin 和签名清单。直接复制对应 `examples/source-*` 或 `source-packs/*` 的独立构建配置，然后替换自己的稳定 namespace、入口和业务。独立 Gradle 可使用已安装 Gradle 9.6.1，或以原工程 wrapper 绝对路径配合 `-p` 启动；不要假定独立目录已附有 wrapper。

最小 examples/source-main 与 examples/source-supplemental 可分别复制为独立目录。真实参考包的独立构建指不依赖宿主业务源码，并非每个包目录都完全自包含：酷狗和B站通过 sourceSets 引入相邻 ../login-ui/src/main/java；复制这两个业务工程时须一并保留 source-packs/login-ui 与相对目录，或替换其登录展示实现及构建路径。不要把 login-ui 当作宿主共享 ABI 或 SDK AAR 的组成部分。

```kotlin
plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}
// android 必须设置自己的 namespace、applicationId、SDK与Java17，开启 compose。
dependencies {
    compileOnly(files(providers.gradleProperty("sourceSdk").get()))
    compileOnly("org.jetbrains.kotlin:kotlin-stdlib:2.3.21")
    compileOnly("androidx.compose.runtime:runtime:1.11.4")
    compileOnly("androidx.compose.ui:ui:1.11.4")
    compileOnly("androidx.compose.foundation:foundation:1.11.4")
    compileOnly("androidx.compose.material3:material3:1.4.0")
    // 仅加入实际使用且宿主提供的共享库；包私有第三方库才用 implementation。
}
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
```

`gradle.properties` 设置 `kotlin.stdlib.default.dependency=false`。仅在子模块目录写此属性不能保证根工程构建生效，因此业务包同时排除 RuntimeClasspath 中的自动 stdlib。SDK、Compose、协程和共享 AndroidX 不应使用 implementation 重打包；同名共享类型被第二份 DEX 定义会破坏类型身份。私有模型应 internal/private，不能冒充公开 SDK 类型。

release 开启 R8，保留入口无参构造及公开实现：`-keep class 自己的命名空间.入口类 { *; }`。反射、序列化和 WebView JavaScript 桥按实际成员保留；桥示例为 `-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }`。使用专属 `-repackageclasses 自己的命名空间.privateimpl`，避免多个包把实现都混淆为同一顶层名字。宿主消费 SDK/runtime 的 consumer rules，保留共享 SDK 与运行库 ABI；包不能替代宿主这一步。

<a id="manifest"></a>

## 5. 清单、签名与版本

签名清单位于 APK 内 `assets/musicone-source.json`。入口 `descriptor` 必须与清单完整一致，包括能力、登录要求、权限和 presentation，不能只保证 ID 相同。`entryClass` 是公开 SourceEntry 实现的完整类名，须有无参构造；宿主要求入口确实由外部 ClassLoader 定义。

| 清单字段 | 必填及默认规则 |
| --- | --- |
| `id`、`name`、`kind`、`versionCode`、`versionName` | 必填；`kind` 为区分大小写的 `MAIN` 或 `SUPPLEMENTAL`，ID 和版本约束见下文。 |
| `entryClass`、`apiVersion`、`minAndroid`、`kotlinVersion`、`composeVersion` | 必填；API 大版本、共享运行时兼容名单及 Android 范围见第 1 章。 |
| `minHostVersionCode`、`minHostVersionName` | 旧包可省略；新包应声明最低宿主正整数编号，名称可选且必须与编号一起出现，仅用于提示。编号不接受字符串、浮点数或 null。 |
| `capabilities` | 必填数组，元素为 `SourceCapability` 的枚举名；服务 getter 和 `implementedCapabilities` 还须通过真实能力校验。 |
| `useCustomPages`、`requiresLogin` | 可省略，均默认 `false`。 |
| `permissions` | 可省略；`editTrackMetadata`、`replaceManualLyrics`、`adjustLineOffset` 分别默认 `false`。 |
| `presentation` | 可省略；`pageColorArgb`/`radioColorArgb` 默认 `null`，`radioLabel` 默认“猜你喜欢”，两个提前量默认 `0`，两套电台调色板默认空列表。未声明页面色或电台色时省略对应键；这两个颜色字段不支持显式 JSON `null`。 |

稳定身份由 `id + 签名证书SHA-256` 约束，ID 使用小写字母开头的3至128字符 `[a-z][a-z0-9_.-]{2,127}`。同 ID 换证书会拒绝，即使已删除后重导入也如此。显示名称可改，ID、账号标识和旧曲目 stableId 不应随改名、版本或安装位置变化。

`versionCode` 是大于0的 Long，按数值比较；`versionName` 是非空展示文字。音源管理页只显示 `versionName`，不显示 `versionCode`；同版本不同内容的覆盖在页面上版本号相同，靠“重启应用后生效”提示区分。业务包当前静态 Gradle/JSON/descriptor 需要同步维护；只有 examples 已用生成资产与 BuildConfig 支持 `-PsourceVersion=N`。不要误以为四包也自动识别该参数。生产工程建议从一个版本源生成三处声明。

重复导入先完成验签、宿主兼容及签名身份检查，再与当前选定安装比较（有 pending 时优先 pending，否则 active）。只有版本和完整包文件内容相同才返回 ALREADY_INSTALLED；此分支不重新加载入口或初始化。同版本不同内容允许覆盖安装，继续执行 descriptor、能力和隔离初始化校验。

主动选择 rollback 安装，或已有 pending 时重新选择 active 安装，均重新登记待生效选择。每份不同内容的包使用宿主内部独立安装标识，active／pending／rollback 可以具有相同 versionCode；同内容的保留包复用原文件，同版本不同内容另存，不覆盖运行中或回退文件。旧注册表缺少安装标识时按原数字版本文件读取，无须移动包或重建账号数据。发布仍建议递增版本便于识别，但安装器允许用户主动同版本覆盖及降级。

APK 必须启用 v1/JAR 签名；可同时使用 v2。仅 v2 的 APK 当前不满足验证器。

四个业务参考包的 release 必须使用固定签名身份；Gradle 从 `-PsourceSigningProperties=<私有 properties 文件>` 读取，未传时使用当前根工程的 `keystore/source-signing.properties`。配置项为 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`，其中 storeFile 相对此 properties 文件的目录解析。迁移到其他机器时安全保留并复用同一 keystore 和别名；缺少配置时 release 构建失败，不能自动改用新生成的 debug key。debug 仍使用机器自己的调试密钥，不能承诺不同机器的 debug 包互相覆盖，也不能承诺 debug 与固定签名 release 相互更新。examples 的 release 仍是调试签名的验证样例，正式第三方分发必须自行改为固定私钥。私钥、密码、Cookie、Token及响应正文不得进入仓库或日志。

```properties
# 私有配置示意；使用实际路径与密码，不把配置和 keystore 提交 Git。
storeFile=source-release.jks
storePassword=<本机私有值>
keyAlias=<固定别名>
keyPassword=<本机私有值>
```

```powershell
gradle -p source-packs/kugou -PsourceSdk=C:/sdk/source-sdk-release.aar -PsourceSigningProperties=C:/private/musicone/source-signing.properties packageReleaseSource
```

当前参考工程的清单版本如下（宿主应用 0.3.0 与音源包版本分别维护；更新参考包时以其清单为准）：

| 包 | 展示版本 | versionCode | apiVersion | 类型及后缀 | requiresLogin |
| --- | --- | ---: | ---: | --- | --- |
| QQ音乐 | 1.0 | 1 | 1 | MAIN / .mscm | true |
| 酷狗音乐 | 1.0 | 1 | 1 | MAIN / .mscm | true |
| 网易云音乐 | 0.1 | 1 | 1 | MAIN / .mscm | true |
| 哔哩哔哩 | 1.0 | 1 | 1 | SUPPLEMENTAL / .mscp | false |

`requiresLogin` 的清单缺省值仍为 false，不等于所有参考包都允许访客使用。当前酷狗自定义根页在未认证、会话过期或缺少账号时展示原认证页，非活动预组合根页不启动第二份认证任务；媒体请求还核对真实账号及凭据，不能只改清单绕过包内登录要求。

当前 QQ／酷狗的 debug 和 release 分发任务分别输出 `qq-1.0.mscm`、`kugou-1.0.mscm`，位于各工程 `build/outputs/source-packs/debug` 或 `release`。这些是当前源码配置，不是自动递增规则；正式发布更新仍须遵守前述版本身份规则。

```powershell
# 使用自己的Android Build Tools实际路径；不要把真实密码写进命令历史。
apksigner verify --verbose --print-certs package.apk
# 必须先完成所有构建/对齐/签名，再仅复制并改分发后缀；不要解压重打包。
```

宿主完整读取每个非 META-INF 文件，触发 v1 摘要与证书验证；拒绝未签载荷、载荷签名不一致、重复条目、目录穿越、异常长度、缺 DEX/清单和类型后缀不匹配。当前限制单条载荷不超过128MiB、全部展开载荷不超过256MiB。证书来自验证结果，清单里自报签名不能作为可信身份。宿主在加载前将代码文件设为只读以满足 Android 动态代码加载约束。

<a id="lifecycle"></a>

## 6. 入口、生命周期与资源

#### 首屏与后台初始化

宿主先显示首屏并在应用进程后台准备预装包、迁移和激活，页面通过初始化状态显示等待／错误；等待页面退出或重建不会取消安装事务。初始化尚未结束不能把临时空列表当成“未安装音源”，也不能据此把已保存的主音源重设为本地。播放与下一首预取等待初始化完成，再确认曲目所属源是否实际可用；队列中的其他来源不因当前浏览来源变化而改归属。

包的 initialize 必须轻量，独立后台任务通过 `host.registerTask` 登记取消；不在 Composable 首次绘制同步做网络或大批磁盘操作。激活成功后由状态更新页面；初始化失败保留明确错误和可继续使用的本地入口，不能用覆盖全屏白页的方式阻止首屏。设备计时和视觉体验仍须按第14章分别验收。



`SourceEntry` 必须提供 descriptor、implementedCapabilities、initialize(environment)、close() 和 `@Composable Content(route)`；onBack 默认 false，各业务 getter 默认 null。播放实现 `mediaPlayback`，公共主首页实现 `discovery`。`playback/catalog` 仅参与兼容加载校验，不提供这两项宿主业务；校验条件见第 7 章。

入口构造后先读取 descriptor 并与签名清单比较；业务服务的 getter、implementedCapabilities、能力检查及新可选契约校验在 initialize 正常返回之后执行。可选校验包含第3章逐页接口的类型／开关／回退／公开身份约束，以及第10章音质目录、默认 ID 和每档回退 ID 的有效性。因此 descriptor 必须不依赖 initialize，业务服务可以在 initialize 中建立，但隔离验证初始化也必须建立供校验读取的服务对象，不能因 validationOnly=true 留下未初始化 lateinit。真实启动随后读取 authentication.currentAccount()，再将实例交给宿主页面。initialize 收到 `SourceEnvironment(context,dataDirectory,cacheDirectory,host,validationOnly)`；真实业务应在非验证初始化后启动。初始化验证使用独立临时文件/缓存/偏好目录，禁止播放、资料库写入和宿主导航，不调用真实迁移 hook。验证不是沙箱，不能限制包绕过 Context 自建线程或读取任意路径。

包私有存储使用 environment.context 的 filesDir/getSharedPreferences 或提供的数据目录。它们按稳定 sourceId 隔离，更新/降级/回退共享同一份数据。不要把账号目录按包 versionCode 分开；持久化格式要向后兼容，不能靠回退旧用户快照回退应用版本。公共账号摘要通过 authentication 暴露，曲库、资料覆盖和订阅等通用资料通过 Host 写入。

`environment.context` 是包专用的 ContextWrapper，`applicationContext` 仍返回该包装对象，不能强转为 Activity 或 Application。`packageName` 是音源 descriptor.id，不代表音源已作为 Android 应用安装。当前包装器的存储边界如下：

| Context 调用 | 当前实现与限制 |
| --- | --- |
| filesDir、noBackupFilesDir、getDir、文件输入／输出／删除 | 定位到提供的包数据目录；cacheDir 定位到独立包缓存目录。getDir、getFileStreamPath、openFileInput／openFileOutput、deleteFile 的 name 只允许 `[a-zA-Z0-9_.-]+`，且不能为 `.` 或 `..`；不能在 name 中传子路径或中文，子目录使用提供目录下的显式 File 路径 |
| getSharedPreferences | 名称使用同一限制；底层为包数据目录内的 JSON 与 AtomicFile，并非 Android 的 shared_prefs XML。apply() 同步执行 commit()，保存失败可能抛异常，不能据方法名假定异步落盘；监听器在提交调用线程执行，不保证主线程，需要主线程的 UI 更新须自行切换 |
| 数据库、外部目录及其他未覆写的 Context 存储方法 | 仍委托宿主，未由这个包装器按音源隔离。需要包私有数据库或文件时显式放入提供的数据／缓存目录，不要把普通 Context 数据库默认路径当作已隔离的包路径 |

外部包不会执行 Android 应用安装流程：加载器只反射创建 SourceEntry，不创建包在 AndroidManifest 中声明的 Application，也不会注册其中的 Activity／Service／Provider／静态 Receiver 或为其新增 Android 权限。SDK 的 SourcePermissions 仅控制宿主提供的资料编辑能力，不授予系统权限。加载器未提供包私有 nativeLibrarySearchPath，也不自动提取／配置 APK 内 JNI 库；引入依赖这些初始化、组件或本地库的第三方 SDK 前须确认其能在现有公开环境中工作，不能只打进 APK 就假定生效。

独立任务用 `environment.host.registerTask { job.cancel() }` 登记，持有并释放返回的 AutoCloseable；close 中停止轮询、请求、协程、WebView和监听。不要取消宿主传入的父 Job。运行时为挂起操作创建子任务，移除及实例失效会取消并阻止晚到结果；账号 revision/status/accountId 的变化也用于拒绝旧账号结果，认证操作保留自己的代次管理。

宿主通过 `SourceInstance.Content`、AuthenticationContent、SettingsContent 或 `SourceSupplementalPageProvider` 的 SupplementalPage 进入页面资源作用域。作用域提供独立 LocalContext、LocalResources，以及按包隔离的 ResourceIdCache/ImageVectorCache。每个包的Resources同时包含宿主base APK、原有splits与该包私有APK，以保留共享AndroidX的宿主资源引用；不会把所有音源挂到宿主全局Resources。当前缓存适配使用Compose内部ABI，并锁定1.11.4；升级宿主Compose前必须重新验证。

宿主保留资源包 ID `0x7f`；运行时接受 `0x02..0x7e` 且不得与宿主及其 split 的资源包 ID 冲突，示例采用 reserved 范围 `0x70..0x7e`。当前 QQ/酷狗/网易云/B站分别为 0x70/0x71/0x72/0x73，旧主／补充示例分别为 0x74/0x75，最小补充示例为 0x76。单个资源作用域只挂一个外部包，独立第三方可复用示例资源 ID；没有私有 Android 资源时允许没有 `resources.arsc`。采用示例 reserved ID 时 Gradle 配置如下，不可省略 allow-reserved 参数：

```kotlin
android {
    androidResources {
        additionalParameters += listOf(
            "--package-id", "0x74", "--allow-reserved-package-id",
        )
    }
}
```

包R ID只能交给包Context/Resources，不能让宿主Context解码；在传入的组合中使用stringResource/painterResource，不自行缓存宿主Resources。使用包自己的XML theme时也须从包Resources取值。加载器会拒绝与宿主资源ID冲突的包，需要重新构建并提高版本；这不是可由清理Compose缓存解决的冲突。旧主／补充示例的 sourceVersion 默认2，最小补充示例由 source.properties 默认设为1。

包可在 Content 内提供局部 MaterialTheme，限制作用范围，继承宿主明暗模式和 reduceMotion 偏好。布局须适配手机/平板，不假定固定横屏或固定像素。onBack只处理内部导航、选择和搜索，处理后true；根页返回false，让宿主决定退出。不要用包返回操作退出整个宿主进程。

当前主音源自定义根页、补充源自定义内容页，以及通过 source-authentication/source-settings 打开的页面使用 SourceExternalSurface：独立 ComposeView／Recomposer，捕获首次组合、其重组协程及 measure/layout/draw 中的异常，失败时替换该区域并保留返回入口。未实现 `SourceSupplementalPageProvider` 时，公共补充模板内部账号页直接调用 AuthenticationContent，不经过该边界；实现该接口时，CUSTOM 页面与 DEFAULT 账号页内的认证页经过该隔离边界。SourceInstance.Content 本身只提供资源作用域；包自行创建的线程、独立协程或系统回调也不属于页面隔离保证。业务失败仍应转为页面错误状态，不能将局部隔离表述为进程级安全沙箱。

<a id="capabilities"></a>

## 7. 能力、数据服务与公共模板限制

清单 capabilities 与 implementedCapabilities 都要声明实际能力。运行时还检查下表中的真实非null接口；自报一个set不能代替实现。HOME 支持自定义 Content 作为页面入口，详情如下表。下表是加载校验条件，不表示当前公共模板已消费全部接口或按每种能力组合隐藏所有入口；具体限制见本节后表。包不返回空数据或虚假成功冒充支持。requiresLogin=true还要求authentication非null；需要账号的某个远端集合操作可以局部要求登录，不必把整个包首页锁住。

| 能力 | 运行时服务及声明条件 |
| --- | --- |
| PLAYBACK | mediaPlayback 非null；未实现 SourceSupplementalPageProvider 的入口也可由 playback 通过加载校验，但宿主播放适配实际要求 mediaPlayback |
| HOME | discovery/catalog；或useCustomPages=true的Content；补充包也可 remoteCollections 或 SourceSupplementalPageProvider；当前公共主推荐页实际要求discovery |
| SEARCH | search非null且kinds非空；可选TRACK/COLLECTION/ARTIST/ALBUM/CONTENT |
| ACCOUNT | authentication非null；authenticationPage为独立可选认证页 |
| PLAYLIST | collections非null |
| ARTIST / ALBUM | entities.kinds含精确的artist / album |
| RADIO | radio非null |
| CONTENT | contents非null |
| CHILD_ITEMS | childItems非null |
| REMOTE_COLLECTIONS | remoteCollections非null |
| IMPORT | importing 非null；或实现 SourceSupplementalPageProvider 且 contents／remoteCollections 至少一项非null。通过校验不等于所有内容均可转换，仍遵循第3章的子条目与 playableTrack 条件 |
| SUBSCRIPTION | subscriptions非null |
| LYRICS | lyricProvider非null |
| SETTINGS | settings非null |

favorites、collectionEditing、trackTheme、visuals和pageState没有额外能力枚举，通过非null服务及其独立声明使用。SourceDiscovery.supportsSimilar/supportsRecent、SourceSearch.supportsSuggestions、SourceEntities.supportsTrackSearch(reference)默认false；librarySections、sortOptions和supportedOperations默认空。未实现的可选业务操作通常抛异常，但默认行为不统一：例如 collections.selectSort 默认抛 IllegalStateException，favoriteTrackIds 默认返回 null；具体以 SDK 方法声明为准。包的自定义页面应按实际声明显示操作；公共模板当前的消费范围如下，不能仅凭通过能力校验推断页面可用。

#### 公共模板当前的能力组合限制

| 入口 | 当前宿主实际行为 |
| --- | --- |
| 主音源搜索 | 仅创建 TRACK／COLLECTION／ARTIST／ALBUM 分类及其“全部”聚合；不创建 CONTENT 分类，也不展示 SourceSearchResult.contents。只声明 CONTENT 可以通过 SEARCH 校验，但公共搜索没有可用分类；需要内容搜索时由包的自定义页面处理 |
| 公共“我的→我喜欢的音乐” | 先检查 collections.librarySections 是否声明 FAVORITE_TRACKS；未声明时显示不支持提示，不要求登录、不读取该目录。声明后，有认证服务或 requiresLogin=true 的音源仍要求已登录；requiresLogin=false 且无认证服务时可读取真实游客收藏目录，游客缓存与账号缓存隔离，不伪造已登录状态，也不放宽远端收藏写入的账号要求。只取该目录首个返回页的首项集合，再调用 detail 和首批 tracks 取得预览，忽略目录后续集合及 nextCursor，不聚合多个收藏歌曲集合；收藏歌曲集合须在首屏首项，空首屏按读取失败处理并保留已有预览，完整歌曲仍经集合详情分页读取。登录、同步和不支持提示不启动卡片进入转场，未实际打开歌单时恢复卡片交互 |
| 公共歌手页排序 | trackSortOptions 非空时默认选中首项；普通歌曲列表改走 searchTracks(reference,"",sortId,request)，不受 supportsTrackSearch 限制。supportsTrackSearch 只控制歌手内搜索入口；提供排序选项时必须同时实现空查询的 searchTracks，不能只实现 tracks |
| 公共歌曲收藏 | 远端收藏链路按歌曲真实来源和当前账号读取 favoriteTrackIds 全量包内stableId，并转换为宿主ID；没有当前账号时该源返回空集合，返回null或读取失败时刷新保留已有索引，不逐曲补齐全量快照。远端单曲切换按当前本地收藏 ID 索引取反，先更新界面，再调用 setTracksFavorite；索引中没有该曲时按未收藏处理，不会先调用 isTrackFavorite 确认。远端写入要求当前账号，不因 requiresLogin=false 放宽身份要求。另有本地分支：来源开启 editTrackMetadata 时，宿主内置收藏动作改为增删本地综合歌单，不走上述远端切换，见第11章 |
| 公共歌单排序 | 排序选项走 collections.sortOptions／selectedSort／selectSort；未接入 SourceCollectionRemoteOrder 扩展时没有公共远端改序入口；接入后由 supportsRemoteOrder 按具体歌单提供独立对话框，完整读取 orderedTracks 并通过 collectionEditing.reorder 保存。只声明 REORDER 不自动增加入口；选排序条件或宿主本地排序不等于提交远端成员顺序 |
| 主音源电台 | 队列通过 radio.next 拉取；SDK 要求实现 feedback(track,action)，但当前宿主电台链路没有调用它，也没有约定宿主发送的 action 值，不能依赖播放、跳曲或收藏自动触发反馈 |
| 未实现逐页接口的补充内容详情 | 详情显示导入目标、“加入歌单”和“播放所选”；操作不按 IMPORT／PLAYBACK 自动隐藏。两个操作先调用 importing.tracks，缺少 importing 时报告“此音源未提供导入”；即使内容有 playableTrack，也不走列表播放捷径 |
| 未实现逐页接口的补充远端集合 | “播放全部”不按 PLAYBACK 隐藏；只收集各内容的非空 playableTrack，不自动选择子条目。内容列表的单项播放优先使用 playableTrack，为 null 时进入详情 |
| 未实现逐页接口的补充集合多选 | “添加到／播放所选”逐内容优先使用 playableTrack，否则取默认选中的子条目并调用 importing；整批按 stableId 去重。转换及失败条件见第 3 章，不沿用内容详情的临时子条目选择 |
| 实现逐页接口的播放／导入 | 播放要求声明 PLAYBACK 且提供 mediaPlayback，导入要求声明 IMPORT 并具备可转换条件；公共详情还按 importing 或“无子条目且有 playableTrack”判断能否转换。详情使用当前选择，单项／集合播放与多选使用统一默认选择；批量按 stableId 去重，转换规则见第 3 章 |
| 补充缓存目录 | 未实现逐页接口时，完整读取收齐 contents 并取非空 playableTrack。实现该接口时，完整读取按详情／列表／播放的统一规则处理：必要时读取 detail、收齐 children、只选 initiallySelected 项，再统一转换；存在子条目却无默认选择时失败，不静默全选。预览只读单内容页 playableTrack 或独立封面能力，不为预览展开全部子条目 |
| 补充订阅 | 未实现逐页接口时，subscriptions 非 null 才显示订阅操作，执行时还要求 authentication.currentAccount().account 非 null；缺少账号时报“请先登录后订阅”，SUBSCRIPTION 校验不强制同时声明 ACCOUNT。实现逐页接口时，可在无真实账号时使用显式 publicSubscriptionIdentity，并按可用订阅身份显示操作；未声明公开身份时仍要求真实账号。创建后的本地歌单刷新还要求 remoteCollections 目录能重新定位该集合，见第 3 章 |

音源包应按实际使用的公共入口提供对应服务；采用自定义页面时仍按真实能力组合装配，不需要为满足模板限制而伪造账号、播放或导入能力。

`SourceDiscovery.reportPlayed(track,playedMs)` 当前按播放请求代次去重，在累计实际推进时长首次大于零时上报一次；playedMs 是该代次实际播放推进的墙钟累计时长，不是 seek 位置、持续进度回调或曲终总时长。暂停时间不计入，不能等待宿主再发送同代次的更大时长来满足平台上报阈值。上报抛异常时同代次不自动重试；尚无 discovery 服务时会撤销本次去重标记，后续播放检查仍可再尝试。

`SourceRadio.next(previous)` 的公共队列消费有窗口限制：一次加载最多调用四次，按转换后的宿主曲目 ID 排除已有歌曲并在本次结果中去重，达到宿主目标数量即返回；超出目标的歌曲不保留到下一次，但本页 continuation 已保存。每次新电台会话的第一次请求 previous 都是 null，包括从歌曲开始的电台（此时已有 ID 集合含起点歌曲，不为空）和切换音源；续页只在同一会话、同一音源内传递，宿主不会把其他音源或上一会话的载荷交给包。应用重启后恢复的电台同样从 null 开始。返回的 continuation 是非空类型 SourcePayload，默认空载荷；空歌曲批次或空载荷都不是停止标记，未收够歌曲时仍会继续请求至本次上限。不能依赖大批次全部入队或返回空批次永久终止续接。

公共搜索“全部”只聚合各支持分类的首批结果，不继续聚合分页；继续加载须进入具体分类。热搜过滤空白与重复词，保留首次出现顺序。公共相似推荐只使用 `similarSection` 返回的标题及歌曲，不继续分页或展示其他分区内容；默认实现包装一次 `similar(...).items`，舍弃该 SourcePage 的下一页游标，返回的 SourceHomeSection 本身没有游标字段。默认 `recentSnapshot()` 同样只包装 `recent(SourceCursorRequest())` 一页，总数等于这页列表大小，不自动收齐分页或查询真实总数。

`SourceRecentSnapshot` 虽分别声明 collections、albums、radios 及集合／专辑总数，当前公共最近播放模板和 `SourceHomeComponents.RecentPlay` 都把这三份集合列表按此顺序拼接；tracks 非空时，另用 tracks 与 totalTracks 生成“已播歌曲”入口，显示数量为 `max(totalTracks, tracks.size)`，仅有 totalTracks>0 不会生成该入口。totalCollections／totalAlbums 不被读取，也不会据此生成独立分类或补齐内容；需要分别展示这些总数时由包自行组织页面。

#### 独立每日推荐、货架展示与轻量实体图片

这些是可选扩展接口，不新增能力枚举或服务接口的抽象成员。运行时通过服务对象的接口实现情况判断是否启用扩展能力；未实现扩展时继续使用对应默认行为。

- `SourceDailyRecommendations.dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails>` 由 discovery 服务按需实现；policy 为 CACHE_FIRST 或 REFRESH。每日推荐是独立的服务端数据集，不能用首页普通推荐的任意前几首冒充。公共主模板固定显示每日推荐卡片，不按此接口是否存在隐藏；返回空列表或请求失败时，已有推荐数据保留并仍可打开，无已有数据时卡片显示“轻触重试”，点击后重新请求。未实现此接口时的兼容取数规则见下方 layout 说明。 返回列表的数量由音源决定，公共每日推荐卡片按实际数量展示并打开完整列表，不额外截断为30首；QQ参考包在自身请求与缓存链路保持最多30首，其他音源不受QQ数量规则限制。
- `SourceHomeSectionPresentation.homePresentation(section): SourceHomePresentation` 由 discovery 服务按需实现。`SourceHomePresentation(stableId, mergePages=false, recommendation=false, groupId=null, groupLimit=null)` 声明公共容器的稳定身份、是否跨页合并、是否属于推荐区、可选分组及分组数量约束；编号和实际业务仍由包解释。不能把包私有货架键写死到宿主；同一货架的不同主题应有可区分的稳定身份。
- `SourceEntityArtwork.artwork(reference: SourceEntityRef): SourceArtwork` 由 entities 服务按需实现，只取得歌手／专辑等的轻量图片。公开扩展 `SourceEntities.artwork(reference)` 优先调用该接口，未实现时使用 `entity(reference).artwork`。

主音源可按需使用三类接口。补充源只有实际提供对应 discovery／entities 服务及该业务时才使用，不要求为了接入补齐主音源目录。

#### “我的”最近播放分区

`discovery` 可额外实现 `SourceMySectionPresentation`，通过 `showRecentInMy` 控制公共“我的”页最近播放卡片。未实现时默认显示，仅显式 false 隐藏；不影响首页最近播放，也不保证停止最近记录读取。当前公共最近播放读取直接调用 `recentSnapshot()`，没有以 `supportsRecent` 作为调用门槛，因此不能仅声明 false 就依赖宿主不调用该方法。

#### 公共主模板的首页布局载荷

SourceHomePresentation 声明稳定身份、合并和分组语义，当前公共渲染器只把这些声明用于 track-shelf；未提供该扩展时，货架按 mergePages=true、recommendation=true 处理，与显式返回 SourceHomePresentation 时的构造默认值 false／false 不同。卡片布局另由 SourceHomeSection.payload 的公开格式 `musicone.home-section`（当前版本 1）描述。此格式是宿主与包之间的公共展示约定，不是宿主解析平台私有业务协议；请保持 version=1，当前渲染器不按版本分支。

| payload.encoded 字段 | 当前消费规则 |
| --- | --- |
| layout | track-card、collection-card、未指定或其他值：tracks 中每首歌曲独立卡片，collections 另按集合卡展示；track-group：只展示 tracks 前三首的歌曲组；track-shelf：分页歌曲货架；daily/recent：整个 section 从普通推荐流排除，不会自动转交独立入口。每日推荐独立请求优先调用 SourceDailyRecommendations；仅当 discovery 未实现该接口时，才按同一请求策略读取 home 第一页，收集 payload.format=musicone.home-section 且 layout=daily 的所有 section.tracks，不读后续页或普通推荐。已实现该接口但返回空列表或抛异常时不会走此兼容路径。最近播放另走 discovery.recentSnapshot／recent，不读取 layout=recent 的 section |
| pageSizes | 仅 track-shelf 使用，按顺序从 section.tracks 切分各页；省略时全部歌曲在一页。提供时各项应为非负整数、总和等于 tracks.size，宿主不会补上未被切分的尾部 |
| recommendationTitles | 仅 track-shelf 且提供 pageSizes 时按 tracks 顺序用作逐曲推荐标题，缺项为空；未提供 pageSizes 的货架忽略该数组，全部使用 section.title。独立歌曲卡使用 section.title；track-group 不显示逐曲推荐标题，只显示 section.title 作为组标题，空白组标题显示“为你推荐的歌曲” |

除被排除的 daily／recent 外，collections 始终按数组顺序生成集合卡，放在同一 section 的歌曲卡之前；collection-card 不会屏蔽同时提供的 tracks。若需交错，拆成多个 section 并按目标顺序交付。track-group 的第四首及之后曲目不会自动另建卡片，应由包拆成多组或改用货架。当前推荐流只消费 tracks 和 collections，不渲染 section.entities；不要仅填 entities 就期待出现歌手／专辑卡。未使用这一公共格式时按普通集合／歌曲卡展示，包的其他私有首页 payload 不会被解读。

生成顺序不等于最终显示顺序：宿主统一把 recommendation=true 的歌曲货架放在推荐区，排在普通集合／歌曲卡、歌曲组和 recommendation=false 的货架之前，各区内保留剩余卡片的相对顺序。因此同一 section 同时包含集合和推荐货架时，最终货架在集合前；拆成多个 section 也不能跨越这一分区规则任意交错。宿主还会合并／去重：同 key 卡片仅保留一个；集合卡按来源、账号 和远端 ID 去重，同来源下标题与封面都非空且规范化后一致的卡片也可能被合并。需要显示不同实体时应提供可区分的真实资料，不能依赖数组位置作为卡片身份。

mergePages=true 的货架按稳定 key 追加非空页，相同曲目身份序列的整页去重；不是逐曲跨页去重。groupId／groupLimit 只约束这一合并路径中的不同货架数量，mergePages=false 时不使用这两个字段限制数量。缓存快照恢复还会对普通独立歌曲卡和集合卡分别随机排列并交错（两类都有时才执行），歌曲组和货架不参与此随机交错，之后仍按推荐区在前的规则排列。

```kotlin
SourceHomeSection(
    id = "recommended-tracks", title = "推荐歌曲", tracks = tracks,
    payload = SourcePayload("musicone.home-section", 1,
        """{"layout":"track-shelf","pageSizes":[2,2],"recommendationTitles":["推荐甲","推荐乙","推荐丙","推荐丁"]}"""),
) // 此例的 tracks 必须恰好包含四首歌曲。
```

#### 可选首页暂存续接

`discovery` 可额外实现 `SourceHomeContinuation.hasBufferedHomeItems(cursor: String): Boolean`。只查询 `home` 上次返回的当前游标是否有已获取、尚待准备或交付的卡池，不能发起网络请求、准备卡片或推进游标；未知、过期游标及只有远端下一页时返回 false。

公共主模板先发布首批并结束刷新，再在该提示为 true 时自动追加一次暂存批次。包使用 `home(SourceCursorRequest)` 交付它，仍按请求的视口数量取卡；后续远端分页继续由滚动触发。追加批次不进行随机交错，也不为了凑歌曲或歌单比例额外拉页，但仍执行前述推荐区优先、货架合并及卡片去重；缓存恢复另有前述随机交错规则，不能将批次处理理解为所有场景都原样保序。未实现包内暂存提示时，原分页仍然可用。

`SourceHomeContinuation` 是独立可选接口；运行时仅对实现该接口的服务转发暂存提示，并执行实例租约检查。QQ 参考包使用该能力在首批后交付延迟准备的歌曲。

#### 可选远端集合封面与可见刷新

`remoteCollections` 可额外实现 `SourceRemoteCollectionArtwork.artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork`。运行时仅在包实现该接口时转发，并检查实例、账号及请求有效性。

`collections` 先返回集合元数据和现有封面，不要为了目录展示逐个读取全部集合内容。公共补充模板和缓存目录只为已组合的可见卡片请求封面；刷新使用 `REFRESH`，公共补充模板在成功后更新图片版本，同 URL 也会重新加载。新图准备完成前继续显示旧图，再复用公共封面交叉渐变；失败、空图、离屏取消及账号切换不得清空旧封面或提交过期结果。封面刷新不代表已读取完整内容。需要全量的播放、缓存和清理操作仍须完成各自的分页读取；补充缓存目录在未实现逐页接口时完整读取仅收集 playableTrack，实现该接口时按统一默认选择转换，预览仍不展开全部子条目，详见本节能力组合限制。

未实现该接口时，缓存目录可按需用首内容页补封面。实现该接口的音源包须配套包含此接口的 SDK AAR 与宿主版本；API 版本相同不代表宿主提供相同的可选接口。B站参考包使用此能力，版本见第 5 章。

<a id="library-extensions"></a>

### 资料库编辑、关注与热搜扩展

以下为按需实现的独立可选扩展，签名见第 17 章。

#### 能力及宿主入口

| 接口 | 实现位置 | 契约与入口 |
| --- | --- | --- |
| `SourceCollectionFavoritePolicy` | `entry.favorites` | `canFavorite` 在宿主基本资格检查后进一步限制收藏，不能让账号我喜欢集合或 `templateEphemeral=true` 的临时集合获得公共收藏入口。`favoriteIdentityKeys` 返回非空有效别名，宿主在相同音源、当前账号目录中用交集比较公开歌单与收藏副本；不替换 stableId 或分页身份。 |
| `SourceLibraryChanges` | `entry.collections` | `libraryChanges` 发送账号及变化分区。后台核对完成后发送，未变化不发送；公共资料库以 CACHE_FIRST 重读对应分区，首次缓存恢复期间先合并读取意图。换号、实例卸载与页面 ViewModel 销毁会取消订阅。 |
| `SourceCollectionDetailsEditing` | `entry.collectionEditing` | `editableFields` 声明 DESCRIPTION/ARTWORK；公共歌单编辑窗口据此显示简介与系统选图。字段集为空时沿用旧名称编辑。 |
| `SourceCollectionRemoteOrder` | `entry.collectionEditing` | `supportsRemoteOrder` 按具体歌单决定入口。`orderedTracks` 按服务器顺序完整分页；独立对话框支持移动到指定位置。保存仍调用原 `SourceCollectionEditing.reorder`，不改变本地排序偏好、队列或播放。 |
| `SourceEntityFollowing` | `entry.entities` | `supportsFollowing` 控制歌手详情关注按钮及“我的 → 我关注的歌手”。实现读取状态、真实关注/取消及完整关注目录，不能返回假成功。 |
| `SourceHotSearch` | `entry.search` | `supportsHotSearch` 控制空查询菜单的热搜区；点击热词复用原搜索提交。返回有序热词，不混入搜索历史。 |

运行时包装器可能始终实现扩展接口，须同时检查 `editableFields`、`supportsRemoteOrder`、`supportsFollowing`、`supportsHotSearch`。未实现对应扩展时，收藏策略不额外限制，别名集合仅含 stableId，集合变化流为空，运行时关注／热搜支持标志为 false；关注／热搜扩展自身的支持标志默认 true。公共关注目录要求当前账号，全部分页使用 REFRESH，拒绝循环游标，按实体 reference.id 去重。

#### 编辑语义与失败处理

`SourceCollectionDetailsEdit(title, description, artworkUri)` 的 `description=null` 保留原简介，`description=""` 明确清空，非空字符串替换。`artworkUri=null` 保留封面，非空值是用户授权读取的本地图片 URI。上传、云端更新及结果确认属于音源职责，宿主不猜测平台地址或认证参数。旧 `SourceCollectionEdit` 的默认空简介语义保持不变。

创建请求发出后若不能确认新增歌单或后续元数据写入结果，音源可抛出 `SourceOperationException("COLLECTION_CREATE_UNCONFIRMED", 提示, false)`。宿主刷新资料库，并禁止在原窗口重复提交创建；用户确认已有歌单后可继续编辑。上传前的参数/文件读取失败仍可直接重试。取消必须继续抛出，不能伪装成功。

编辑和远端改序在打开窗口时捕获账号，写入前后核对；分页游标必须绑定账号、集合及对应顺序。宿主拒绝循环游标、重复歌曲和部分失败结果。音源保存顺序前仍需核对最新完整歌曲集合及版本，不能把“当前显示顺序”直接当成服务器顺序。

公共创建、编辑、删除要求当前账号，并检查 `supportedOperations` 的 CREATE／EDIT／DELETE。编辑或删除还会重新读取 `writableCollections()`，目标必须匹配且不是 `accountFavorites`；仅提供 `owned=true` 或操作声明不足以完成写入。`editableFields` 非空时调用 `createDetails/editDetails`，为空时退回旧名称编辑。

公共远端增删歌曲要求当前账号、`supportedOperations` 包含 `CHANGE_TRACKS`，真正写入时还会重新读取 `writableCollections()` 并匹配目标；歌曲必须与目标同源。只实现 `changeTracks` 或声明 `owned=true` 不足以完成写入，音源仍须核验真实远端归属与权限。这些远端条件不适用于宿主本地歌单导入。

公共远端改序入口由非本地集合及 `supportsRemoteOrder(collection)` 决定，当前没有额外读取 `supportedOperations.REORDER` 作为入口门槛。首分页用 REFRESH，后续用 CACHE_FIRST，收齐后拒绝重复 stableId 及循环游标；保存传包内 stableId 顺序。宿主不替音源重新核对服务器版本，音源须在 `reorder` 中确认最新完整集合及并发变化。

<a id="models"></a>

## 8. 通用模型、身份、分页与失败处理

`TrackIdentity(sourceId,accountId,remoteId)` 表示来源/账号/远端身份，`SourceTrackDetails.stableId` 表示包内稳定ID，既有四包沿用历史资料库ID，两者不互换。例：旧 `qq-*`、`kugou-*` 或含子条目的旧ID必须原样保留；不要为接入重新生成ID或加入版本。

包的stableId是包内稳定身份；宿主按sourceId与账号隔离第三方歌曲／集合ID，经曲目／集合模型转换回调包时还原包内stableId，接入包不需自行拼全局前缀。四个参考音源的既有ID按兼容规则保留，包版本不参与身份。播放器状态和背景路由不是上述模型转换回调，当前仍返回宿主ID，具体区别如下。

| 位置 | 当前 ID 含义 |
| --- | --- |
| SourceTrackDetails.stableId / SourceCollection.stableId | 包内稳定 ID；对应宿主曲目／集合模型转换回 SDK 时还原 |
| SourcePlayerState.stableTrackId | 当前 MusicTrack.id，即宿主曲目 ID；第三方源通常已按来源和账号隔离，不能直接与包内 stableId 做字符串相等比较，判断来源还须看 sourceId |
| SourceCacheEntry.stableTrackId | 当前 cache.lookup 原样返回调用参数中的 SourceTrackDetails.stableId，即包内 ID；与 playerState 的同名含义不同 |
| SourceLocalCollection.id / destinationId / SourceSubscriptionIdentity.localCollectionId | 宿主本地歌单 ID；使用 library.createCollection／destinations 返回的值，不替换成包内 SourceCollection.stableId 或远端集合 ID |
| visuals 的 playlist 路由 arguments["id"] | 当前宿主歌单 ID；observe 和设置背景回调使用这一标识，并非还原后的 SourceCollection.stableId，可作为该背景的原样存储键 |

当前 SDK 没有提供宿主 ID 与包内 stableId 的公开映射接口。不要把四个兼容音源的 ID 恰好相同当作第三方包保证，也不要把宿主内部哈希格式当成 SDK 身份协议。

| 模型 | 必须保留的资料 |
| --- | --- |
| SourceTrack / SourceTrackDetails | identity、stableId、标题、artists/实体引用和artistsText、album/albumRef、durationMs、artwork、access、qualities、lyrics、localUri、payload、previewUrl、identityAliases |
| SourceArtwork | url、startArgb/endArgb、mark；没有图不伪造平台占位成真实封面 |
| TrackAccessInfo | badge(MEMBERSHIP/PURCHASE)、trialAvailable、playable、reason、可选displayBadge（自定义文字及亮暗文字/背景色）；不能删访问事实 |
| SourceAudioQuality | id、label、mimeType、available、reason、payload；协议私有hash不充当跨来源公共字段 |
| SourceCollection | identity、stableId、title/subtitle/description/count/artwork、owned、accountFavorites、payload |
| SourceEntity / SourceEntityRef | ref(id/name/kind/payload)、description/artwork/attributes |
| SourcePayload | format、version、encoded；由包解释、版本化并向后兼容 |

上表列出 SDK 可表达的资料，不表示每个字段都被当前宿主显示或执行；歌词扩展字段和媒体分段字段的实际处理分别见第11章和第10章。

SDK 允许 `SourceCollection.count=null` 表示未知；转换为当前宿主歌单时改用同时传入的曲目数量，未提供曲目时为 0。因此未知数量不能保证经过宿主模型转换后仍以 null 无损往返。

公共实体模板读取以下 SourceEntity.attributes 字符串键。这是当前宿主展示约定，区别于包自己的不透明 payload；其他键不自动产生展示效果。

| 键 | 值格式与用途 | 缺省或解析失败 |
| --- | --- | --- |
| backgrounds | 歌手背景／介绍图片 URL 的 JSON 字符串数组，例如 `["https://example.org/artist-1.jpg"]` | 使用空图片列表 |
| trackCount | 可解析为 Int 的十进制数量字符串；用于歌手歌曲总数及实体专辑卡的曲目数 | 歌手页暂用已加载范围计数；实体专辑卡为 0 |
| collectionCount | 可解析为 Int 的十进制数量字符串；用于歌手专辑总数 | 暂用已加载范围计数 |

歌手总数取已取得的实体详情；详情尚未读取时也会使用上述计数后备。包应提供真实非负数量，不把未知数量伪装成确定的总数。
歌手歌曲的 `trackCount` 随详情晚到时会直接更新已打开页面；更早发起的歌曲页不能再用已加载数量覆盖该总数。歌曲仍按 `nextCursor` 分批加载，总数不决定续页是否结束；此行为使用既有 attributes，不增加 SDK 接口。

平台私有定位、协议类型和质量参数优先放在使用自有非空 format 的 payload 中，不能把平台专属字段加入公开 SDK。宿主按以下现有转换规则保留曲目／集合载荷，不能把“由包解释”理解为任意 format 或 opaqueData 键都不会被处理。

| 位置 | 当前保留规则与接入要求 |
| --- | --- |
| SourceTrackDetails.payload / SourceCollection.payload | 使用自有命名且非空白的 format 时，format／version／encoded 经对应宿主模型保存并还原。曲目 payload.format 为空白时不会保存该载荷；集合空 format 回读也会落入兼容格式，不能仅填写 encoded 就依赖原样往返 |
| musicone.provider-data | 宿主既有数据兼容格式；集合转换会把 encoded 当作 JSON 对象读取为字符串字段，回读可能重组为 version=1 的 JSON，不保证原始文本、值类型或版本不变。音源包私有载荷不要占用这一格式；musicone.home-section 另为第7章的公共首页格式 |
| SourceTrack.opaqueData | 普通自有键继续保留，但 catalogId、mediaId、providerType、qualityIds 是旧兼容字段，会按对应宿主字段读取／规范化并在回调时写回；sourcePayloadFormat、sourcePayloadVersion、sourcePayload 及 musicone.host.originalStableId 由宿主维护，宿主回调时不再随 opaqueData 下发，回传时也会被丢弃；不能存放无关私有值，也不要把整份宿主回调 opaqueData 当作自有业务格式 |

曲目中的 artists／albumRef 保存完整 SourceEntityRef（含 kind 和 payload）；qualities 保存完整列表（含 label、mimeType、available、reason、payload），经过宿主转换、曲目持久化和回调包后仍保留，歌手／专辑详情与菜单图片请求继续携带实体载荷。没有这些资料的记录只按可用身份及音质信息读取，不伪造缺失载荷。音源包不需填写 `opaqueData["qualityIds"]` 才能保存公开 qualities；未实现 `SourceQualityCatalog` 时使用默认音质策略，实现后可提供独立档位，支持范围见播放章节。公开字段已足以表达的标题、访问、时长、实体等仍须填入，不能全部藏进 JSON。

`SourceCursorRequest(cursor=null,limit=50,policy=CACHE_FIRST)`；REFRESH表示主动刷新。`SourcePage<T>(items,nextCursor)` 的 nextCursor=null表示正常结束，终止页可以为空。SDK 数据模型也能表达“空页且有下一游标”，但当前公共歌手页的歌曲、专辑和歌手内搜索路径会将它报为“分页没有前进”；这三条路径要求有下一游标时 items 非空，且游标不同于本次请求。接入公共歌手页时，包应在内部继续取得非空页或正常结束，不能直接交付空中间页；不能把这一模板限制扩大为 SDK 所有分页的统一规则。真实错误直接抛异常；CancellationException原样传播，绝不catch后返回空页/清空已有列表。游标是包的不透明协议，宿主保存游标与页面位置的映射，不解析token或自行构造包的分页参数。

公共主资料库初读及无变更返回使用 `CACHE_FIRST`，用户主动刷新或歌单返回确认变更时使用 `REFRESH`；目录分页和我喜欢的歌曲预览同样适用。`SourceLibraryChanges` 通知触发 `CACHE_FIRST` 重读，恢复期间合并该读取意图。音源按 policy 复用有效快照或重新核验；并发请求可合并，但接口不提供刷新批次编号，不能靠延时或 TTL 推断串行调用属于同一批。

列表首屏、刷新、下一页各自保留请求代次；新查询/换号/退出页面取消旧任务。刷新失败保留旧内容、选择、排序和用户编辑，下一页失败保留已加载页并提供重试，只有成功结果可更新游标。收齐所有页才提交全量替换；不能用一个失败页清空整个歌单。

跨ClassLoader业务失败优先抛公开 `SourceOperationException(code,message,retryable=false)`，code采用协作约定，不是SDK枚举。当前宿主失败策略不读取 retryable，设置 true 不会自动启用重试或改变跳曲行为。AUTH_REQUIRED、AUTH_EXPIRED、SECURITY_VERIFICATION_REQUIRED、REQUEST_REJECTED、RATE_LIMITED、PLAYBACK_RETRY_REQUIRED 会阻止继续尝试其他音质；SourceOperationException 中只有 TRACK_UNAVAILABLE 被标记为可跳过，最终是否跳曲仍受播放场景和音源行为策略控制。SOURCE_REQUEST_FAILED 没有这些专门分支。宿主不能用is包私有异常判断权限。message可供用户显示，不含凭据、签名URL或响应正文；网络故障不要冒充永久无版权或 TRACK_UNAVAILABLE。

<a id="authentication"></a>

## 9. 认证、完整会话与迁移

`SourceAuthentication` 提供 methods、currentAccount()、begin(methodId,input)、continueAuthentication(challenge,input)、refreshAccount()、logout()、exportSession() 和 importLegacySession(session)。methodId/input/challenge.payload均由包定义；begin 返回 SourceAuthChallenge，continueAuthentication 返回 SourceAuthResult，其 progress 为 WAITING、CONFIRMATION_REQUIRED、VERIFICATION_REQUIRED、SUCCEEDED、EXPIRED。二维码签发、发短信、扫码待确认都不是登录成功。

`SourceAccountState(status,account,revision)` 的status为GUEST/AUTHENTICATED/EXPIRED。SourceAccount含id/displayName/avatarUrl/membership/backgroundUrl/signature/follows/followers摘要。成功持久化、退出或账号变化后更新revision；每个业务请求捕获并在响应提交前核对会话，取消旧账号结果。网络错误不清空原凭据；仅明确失效结果标记EXPIRED。验证码重发/挑战更新也须避免旧轮询覆盖新流程。

宿主在主音源入口选择未登录且 requiresLogin 的音源时先打开认证页，认证页关闭后 currentAccount() 有账号才切换主音源，因此包不必在登录成功时通知宿主，但关闭前必须已持久化账号。宿主前台每 60 秒读取一次 currentAccount()，status、账号或 revision 变化时重读状态；refreshAccount() 成功后间隔 6 小时再调用，失败后 5 分钟可重试，须可重复调用且并发安全。

账号会员、单曲收费与购买权益分别由包核实；登录成功或取得播放地址不能证明会员有效。`SourceAccount.membership` 只用于展示，检测依据见下节。曲目访问状态保留真实收费信息及购买账号归属。

<a id="membership-detection"></a>

### 默认关闭的可选会员检测

入口可额外实现独立 `SourceMembershipProvider`，只有 `membershipDetector` 非 null 才声明检测；默认 null，不联网、不要求 ACCOUNT 能力，也不根据登录状态或 `SourceAccount.membership` 描述文本自动启用。`SourceEntry`、`SourceAuthentication` 与 `SourceAccount` 使用 API 1 声明的签名。使用该接口必须配套包含它的 SDK 与宿主版本。

| 状态 | `SourceEntry.currentMembership()` / `detectMembership()` |
| --- | --- |
| 无 provider 或检测器为 null | `false`，不调用检测器 |
| 已声明，确认当前账号具有真实音乐会员权益 | `true` |
| 已声明，但没有会员、字段不足、尚未检测或查询失败 | `null`，语义为 `unknown` |

这里 `false` 只表示未启用检测。检测器返回的 false/null 均归一化为 unknown，不可将非会员伪装成“未声明”。`currentMembership` 属性必须只读已有结果，不得联网；挂起 `detectMembership()` 才允许查询。挂起扩展 `SourceEntry.detectMembership()` 将普通 Exception 归为未知，CancellationException 原样抛出；同步扩展 `SourceEntry.currentMembership()` 不捕获属性读取或实例租约异常，不能把它当作相同的异常恢复入口。缓存、并发合并、凭证及真实权益由包管理，账号变化或实例移除后拒绝旧结果。当前宿主在在线账号展示及歌曲访问刷新时显式检测；同步账号摘要和音质缓存读取只取当前值。

宿主音质探测缓存及预取身份区分已确认会员 true、未声明 false 与已声明但未知 null，不把 unknown 合并到非会员缓存，也不读取旧 `membership` 摘要猜测缓存身份。读取这些键不会主动检测会员；检测前后的状态变化可能使旧探测结果或旧预取任务不再匹配，但不删除已有离线音频，也不代表已取得新的单曲播放或音质权限。

主音源、补充内容及补充歌单使用同一规则。`playableTrack`、导入和订阅转成 `SourceTrackDetails` 后，仍按每首曲目的原始 `sourceId` 查询当前音源；本地旧曲目也在访问刷新时重新核验。无需向 `SourceRemoteCollection` 增加整单会员开关，不能用一个源的会员结果替代混合歌单中其他源的结果。

会员结果不替代 `TrackAccessInfo` 的单曲会员／购买／试听／可播事实，也不改变播放取址和音质权限。B站不声明检测，宿主返回 false；按真实返回音轨列出音质，失效内容沿用原可用性判断。QQ、酷狗、网易云主动声明；缺字段或无法确认当前音乐权益时均保持 unknown。

`SourceSessionSnapshot(format,version,fields)` 导出完整会话；摘要不是凭据备份。应保留全部Cookie、Token、设备身份、刷新信息和平台需要的其他字段。SourceLegacySession含migrationId/sourceId、preferenceNamespaces和privateFiles；字段字符串格式由迁移双方约定，不把原布尔/整数盲目当字符串写回原偏好。

importLegacySession必须幂等：非空新会话优先、空白旧输入不覆盖有效数据、持久化成功后才返回IMPORTED/ALREADY_IMPORTED/EXISTING_SESSION_PRESERVED收据。主动退出应留下可识别的迁移完成/注销状态，避免下一启动又复活旧账号。SourceSessionSnapshot和LegacySession默认toString会隐藏内容，自建日志同样不得输出原字段。

真实初始化前，运行时在已验证清单和签名的身份下调用 `SourceHost.prepareSourceStorage(sourceId,context)`。生产宿主迁移桥只对固定内置来源ID及可信分发证书开放，不把未知第三方包与任意旧平台凭据关联。隔离验证不运行真实迁移。账号或用户数据保留的判断必须检查完整持久化内容和实际登录业务，不能只看头像仍存在。

<a id="playback"></a>

## 10. 播放、音质、恢复与缓存策略

<a id="playback-session"></a>

### 默认关闭的可选播放会话

需要平台听歌时长同步时，由 `SourceMediaPlayback` 的实现额外实现独立 `SourcePlaybackSessionProvider`，并让 `playbackSessions` 显式返回非 null 的 `SourcePlaybackSessionObserver`。其默认值为 null；未实现接口或未提供观察者时，宿主不启用这条会话事件链路。不增加现有 `SourceEntry`／`SourceMediaPlayback` 的抽象成员，不改变 API 1 和原 `discovery.reportPlayed(track, playedMs)` 的最近播放职责。旧最近播放调用次数及其参数不能用作持续收听时长协议。

```kotlin
class MyPlayback : SourceMediaPlayback, SourcePlaybackSessionProvider {
    override val playbackSessions = SourcePlaybackSessionObserver { event ->
        // 按会话和序号去重，再由包内协议处理累计时长。
        listenReporter.onEvent(event)
    }
    // 其余播放服务按原契约实现。
}
```

`SourcePlaybackSessionEvent` 的公开字段如下：

| 字段 | 约定 |
| --- | --- |
| `sessionId: String` | 本次实际播放会话身份；切歌或重新开始的播放会话独立计数。不能只用歌曲 ID 去重。 |
| `sequence: Long` | 同一会话内递增的事件序号，用于拒绝重复或迟到事件。 |
| `track: SourceTrackDetails` | 本次会话歌曲的完整包内资料，保留稳定身份及不透明载荷。 |
| `type: SourcePlaybackSessionEventType` | `START/PAUSE/RESUME/PROGRESS/END`，由真实播放状态驱动。 |
| `playedMs: Long` | 同一会话内单调累计的真实墙钟收听毫秒数。暂停、缓冲、解析等待及 seek 跳过的进度不计入；不是歌曲总时长，也不是播放位置。 |
| `positionMs: Long` | 该事件的媒体播放位置；拖动可跳变，不能用位置差代替已听时长。 |
| `occurredAtMs: Long` | 事件发生的墙钟时间戳，供包生成平台请求；不是持续计时的差值依据。 |
| `endReason: SourcePlaybackSessionEndReason?` | 结束原因为 `COMPLETED/SWITCHED/STOPPED/ERROR/RELEASED`；非结束事件为空。 |

宿主复用已有一秒播放 ticker 产生累计进度，生命周期状态变化时交付对应事件，通过串行队列调用观察者；不为每个音源另建常驻定时器。事件回调不阻塞音频播放，包仍须响应取消，避免在回调中无限等待认证或网络。会话属于开始时捕获的音源运行实例与账号版本；卸载、实例失效、退出或换号后不把迟到事件交给新实例／新账号。终止或进程退出的最后一次上报属于尽力交付，不承诺远端入账。

`START` 在真正开始推进时交付；暂停和缓冲都会停止累计，并对应 `PAUSE`，再次真实推进时交付 `RESUME`。自然播放完成先交付 `END(COMPLETED)`，随后循环重播使用新会话；seek、同曲取票恢复和音质切换保留既有会话，等待期间不累计。释放播放器时先将 `END(RELEASED)` 入队，再异步排空队列；未发送队列不持久化，进程直接被终止时不能保证末段送达，已经失效的实例或账号租约也不会为了补发而重新放行。

包按 `sessionId` 和 `sequence` 去重，并以 `playedMs` 相对已处理累计值的差额结算，不能将每次累计值全部再次上报。平台要求的歌曲 ID、设备身份、鉴权、签名、开始／结束报文、上报频率及成功读回均由音源包处理；公共层不理解平台协议，也不根据平台名分支。收到暂停、结束或累计值不代表平台已接受请求；网络响应不明时不能自动重发同一新增时长而冒重复入账风险。

当前 QQ 与酷狗参考包均已显式提供观察者，网易云与 B站未接入；这不改变可选接口默认关闭的规则。QQ 在 `PAUSE/END` 按累计差额结算，向下取整到完整秒，毫秒尾数留在同一会话；使用真实歌曲数字 ID、播放票据及统计设备会话生成请求。酷狗同样在 `PAUSE/END` 结算完整秒，每次先读取服务端真实累计基线，再提交本会话新增秒数；另在开始及结束时发送包内播放事件，仅在开始已确认且歌曲身份完整时发送对应结束事件。两包都在发出时长写请求前消费本段差额，响应不明时不重复提交该段；失败不打断播放，也不保证平台已入账。账号或凭据版本变化后丢弃旧会话结果，原最近播放仍独立通过 `discovery.reportPlayed` 处理。

该能力与公共／自建播放器 UI 无关，手机横竖屏和平板使用同一播放会话，不随界面转向或开关播放器页重建。缓存播放也以实际播放状态计时，平台是否接受该曲目和凭据由包判断。使用该接口的包须配套包含它的 SDK AAR 与宿主版本；发布说明应列出已验证的宿主构建标识。

### 音质、资源与行为策略

未实现 `SourceQualityCatalog` 时，`SourceMediaPlayback` 的同步策略声明为defaultQualityId（默认LOSSLESS）、followsMainQualityPreference（默认false）、normalizePreferredQuality(savedQualityId,mainQualityId)与fallbackQualityIds(preferredId)。首选归一化、跟随主音源规则和音质回退顺序由包提供；QQ默认EXHIGH且Dolby回退跳过HI_RES，补充参考包按原主源偏好映射，宿主不按平台名猜测。

以下为旧固定档位的策略，目录路径另见下一节。`followsMainQualityPreference=true` 只让宿主把当前选中来源（且不是本源）的音质偏好作为 mainQualityId 传入，否则传 null。SDK 默认 normalizePreferredQuality 仅返回 `savedQualityId ?: defaultQualityId`，不使用 mainQualityId；需要跟随时必须自行覆写映射，单独开启该标志不会自动跟随。fallbackQualityIds 返回完整的尝试候选链，宿主不自动在前面补 preferredId；希望先试首选时须将它包含在列表中。未知 ID 被过滤，返回空列表或全未知 ID 会使自动选档没有候选，不会补默认回退链；已有音质缓存和设备输出限制还会影响实际尝试顺序。

SDK 默认 fallbackQualityIds 只在 `STANDARD／EXHIGH／LOSSLESS／HI_RES／DOLBY` 中按大小写敏感的名字查找，再返回截至该档的反向前缀；未匹配时仅返回 STANDARD，包括输入 HIGHER 的情况。这与下表宿主转换器接受别名、不区分大小写是两层规则；需要不同策略时由包覆写，不能把宿主接受的全部 ID 当作 SDK 默认函数都认识。


#### 音质目录、身份、标准映射与名称

`mediaPlayback` 可同时实现独立可选 `SourceQualityCatalog`；未实现时使用默认音质策略。`qualityCatalog` 列出 `SourceQualityDefinition(id,label="",compactLabel=label,description="",standardQualityId=null)`；`defaultCatalogQualityId` 指向目录中的默认 ID，`catalogFallbackQualityIds(preferredId)` 给出明确回退顺序。目录必须非空，ID 非空且不重复，默认值必须存在，标准映射必须是下表已知标准 ID；无效声明报错，不静默缩成标准音质。名称可以省略：有标准映射时使用标准通用长／短名称，无映射的单档显示“默认音质”，多档显示“音质 1／音质 2…”；短名省略时继承对应通用短名或完整名称。目录顺序用于展示优先顺序，不代替显式回退链。

自定义 ID 以原始字符串区分身份，和 `sourceId` 共同组成独立音质身份。同源的 `flac-normal` 与 `flac-master` 即使都映射 `LOSSLESS`，仍是两个可独立选择、保存偏好和缓存的档位；其他源复用同名 ID 也不会合并。`standardQualityId` 只说明标准能力，用于迁移旧标准首选、移动网络标准档上限和 Dolby 设备限制，不能拿它替换解析请求、返回资源或缓存的原始 ID。无映射时不会凭名称猜测无损、Hi-Res 或 Dolby；返回资源的实际格式、码率仍须真实。

自定义目录采用目录的 `label/compactLabel/description` 展示，名称不依赖 `SourceAudioQuality.label` 或 `SourcePlayerPresentation` 重新改名。曲目的 `qualities` 仍提供该曲目实际可用档位及不可用原因；`available=false` 不进入可选按钮。默认、偏好归一化、`qualities`、`resolve/recover` 返回的 `qualityId` 应一致使用目录 ID，不塞入宿主编码后的内部偏好键。自定义目录的候选链以选中 ID 开头，再加入明确回退 ID 并去重；已有有效探测缓存可能使本次从缓存档位及其后续候选开始，不保证每次重新请求首选。回退中未声明的 ID 会报错，不按标准映射自动补档。请求、错误恢复、预取和下载都传原始 `qualityId`；实际资源返回未知 ID 时保留该独立身份，不伪装成请求档位，但不把它认定为已声明成功：可用菜单过滤未知 ID，在线解析继续明确回退，恢复和显式缓存遇到未声明返回 ID 报错。偏好持久化以来源和原始 ID 区分，离线／探测缓存同时保存目录名称、说明和标准映射快照，源被移除或目录更新后仍保留真实音质身份；已有快照优先于更新后目录，缺快照的旧记录才读取现目录，避免给旧缓存改写规格。已保存首选仍向 `normalizePreferredQuality` 传本源原始 ID；跟随其他主源时只传该源的标准能力 ID，自定义档使用显式标准映射，未映射传 null，不把其他源同名自定义 ID 当成本源档位。归一化 ID、已保存独立身份、旧标准映射依次匹配，最终采用目录默认，无首选及可跟随标准能力时直接目录默认。不能把旧 SDK 的固定枚举默认回退当自定义回退实现。

未实现 `SourceQualityCatalog` 时，音质 ID 与别名按默认固定转换路径处理，匹配不区分大小写：

| 推荐 ID | 接受的别名 | 播放器展示 |
| --- | --- | --- |
| STANDARD | standard | 标准 |
| HIGHER | higher | 与 EXHIGH 合并展示，非独立按钮 |
| EXHIGH | exhigh | 高品质 |
| LOSSLESS | lossless | 无损 |
| HI_RES | hires | Hi-Res |
| DOLBY | dolby | Dolby，仍受设备输出策略约束 |

未实现 `SourceQualityCatalog` 时，仅识别标准档位：未知可用 ID 被过滤，未知返回 ID 按标准转换规则处理，完整 `SourceAudioQuality` 仍保留供回调；按钮文案可通过 `SourcePlayerPresentation.qualityLabel/qualityCompactLabel` 定制。实现该接口时，除标准 DOLBY 映射外，还根据真实资源 MIME、明确 codecs 参数或格式识别 Dolby，执行设备／USB 限制；无目录时不启用这项资源识别。不能按名称或仅凭 MP4 容器推断 Dolby，音质声明也不能绕过权益和设备限制。

#### 歌曲角标的文字与亮暗配色

`SourceTrackDetails.access.displayBadge` 可选地提供 `SourceTrackBadge(text, lightTextArgb, lightBackgroundArgb, darkTextArgb, darkBackgroundArgb)`。四个颜色均为可空 Long，格式 `0xAARRGGBB`（范围0至0xFFFFFFFF）；0表示透明，null沿用宿主默认配色。文字可以是“VIP”“付费”“试听”或音源自己的简短标签；空白文字明确隐藏该展示角标。不要把角标文字当成鉴权或可播放判据，`badge/trialAvailable/playable/reason` 继续保存真实访问事实。

```kotlin
val badge = SourceTrackBadge(
    "限免试听", lightTextArgb = 0xFF126B52L,
    lightBackgroundArgb = 0x22126B52L,
    darkTextArgb = 0xFF8AE7C8L, darkBackgroundArgb = 0x33126B52L,
)
val access = TrackAccessInfo(TrackAccessBadge.MEMBERSHIP,
    trialAvailable = true, displayBadge = badge)
val detailed = existingDetails.copy(access = access)
```

宿主公共歌曲标题／列表统一消费声明并持久化文字和四个可选颜色；包被删除、暂时不可用或设备离线时仍保留已存资料。未声明 `displayBadge` 时使用默认 VIP／付费／试听样式。首页推荐仅首页隐藏访问角标的默认规则优先于声明，不改变歌曲本身的访问事实；离线缓存标记是独立标记。自定义页面应复用相同声明，并在亮暗背景保证可读，避免长文字挤占歌曲标题。

`TrackAccessInfo` 提供 API 1 的四参数构造、默认参数构造、`component1` 至 `component4` 及对应 `copy` 签名，并提供接收 `displayBadge` 的五参数构造。四参数 `copy` 调用会自动保留展示声明；需要更换声明时构造新的 `TrackAccessInfo`。`SourceTrackDetails` 构造签名不变。使用 `displayBadge` 的包须配套提供该类型的 SDK 与宿主版本，并列明已验证的宿主构建标识。

`SourceMediaPlayback` 提供 enrich(track)、qualities(track)、resolve(request)、recover(request,previous)和availability(tracks)。enrich默认原样返回，应只补缺失资料并保留stableId及用户覆盖；qualities返回实际存在和允许访问的档位，不把请求成功等同真实媒体规格。逐档探测较慢的包可额外覆盖 qualities(track,onProgress)，每确认一档就回报当前已确认的完整列表，宿主据此逐行更新音质菜单；单参数 qualities 仍须实现，可直接委托 `qualities(track) {}`。未覆盖时默认查完后一次性回报；新增重载前编译的旧包由宿主自动回退到单参数版本，不需要提升 apiVersion。availability逐项返回TrackAccessInfo，网络未知与明确失效要区分。

`SourcePlaybackRequest`含track、qualityId、supportedMimeTypes、deviceCapabilities、automatic和purpose；purpose为PLAYBACK/PREFETCH/CACHE。当前宿主常规 resolve 路径填写设备策略、automatic及请求用途，但没有填写 supportedMimeTypes；空集合表示未提供 MIME 限制信息，不能解释为设备不支持任何格式。预取/缓存应保留后台静默或批量节流策略，不弹出前台验证或抢占当前播放。automatic是选档策略，与purpose不同。播放器错误恢复走单独的 recover 路径，其请求字段存在下述限制。

`SourcePlaybackResource`含address、qualityId、format、inlineManifest、segments、recovery、accountId、bitRate、actualFormat、trial、verificationPending。PlaybackAddress提供url、headers、alternatives、mimeType、cacheIdentity、expiresAtEpochMs。每段SourceMediaSegment还有headers/byteRange/alternatives。结果须可直接消费，不得用二维码或未验证接口URL冒充媒体。

稳定cacheIdentity包含来源、账号和真实资源身份/规格，不能只用歌曲标题或短期签名URL。请求头和备用地址完整返回。DASH使用data URI/inlineManifest及实际音轨segments，宿主为相关URL绑定相同稳定键与各自headers；不能把裸CDN地址当DASH清单替代。当前适配层不读取 SourceMediaSegment.byteRange，也不据此生成 HTTP Range 请求；DASH 的初始化／索引等范围应由真实清单表达。address.alternatives 与 segment.alternatives 目前只用于登记请求头和稳定缓存键，不会仅因填写这些列表而自动切换备用 URL；地址选择仍取决于实际清单、播放器处理或包重新取址。当前宿主已接的主要执行路径是单音频资源及音频DASH；SDK字段存在不代表任意多文件/自定义分段清单均已完整缓存验收。

`inlineManifest` 当前专用于音频 DASH：非 null 时编码为 `data:application/dash+xml` 并优先于 address.url 使用，不是任意清单格式入口。资源 accountId 不独立覆盖当前适配器的曲目账号或缓存身份；recovery 不由宿主解析，而是随当前进程保存的完整资源在恢复时通过 previous 交回包。

自定义移动网络音质上限保存来源与原始 ID，读取时按上限所属源的当前目录恢复名称及标准能力；未映射档不猜规格，目录暂不可用时保留原对象，目录明确移除档位时清除旧映射。能力限制可跨源应用，不能按当前播放源的同名 ID 重解释另一源的上限。设置页订阅宿主已有音源发布状态，目录发布后重组恢复文案与选中态，不要求用户重选偏好或重新开页；不额外启动进程常驻观察器。

离线命中同时检查普通媒体与 DASH 索引，先找精确档，再按自定义回退链跨两类选择；仅有旧 DASH 缓存时保留原排序。缓存键枚举和删除覆盖两类，DASH 稳定键不进入普通媒体 URI 解析；两类索引共存不应令普通索引遮住已有 DASH 资源。

recover重新取有效票据并保留稳定身份。当前 SourcePlaybackAdapter.recover 只用 track 和 qualityId 构造请求，supportedMimeTypes／deviceCapabilities 均为空，automatic=false、purpose=PLAYBACK；没有把首次 resolve 的设备能力、自动选质标志和请求用途传回来。previous 是上次实际资源，不包含原始请求。B站参考包的 recover 直接再次 resolve，因此按这些缺省值重新选音轨，可能与首次解析的条件不同；接入者不能假定恢复请求完整继承了首次选质策略。

当前前台起播及播放器错误恢复，在等待音源激活后设置 30 秒的整体解析协程超时；初始化等待不计入，候选音质也不是各有 30 秒。超时显示“音源加载超时，请重试”，本次不自动跳曲。音源挂起操作须响应取消，不能依赖这次调用无限等待人工验证。该限制不代表媒体地址有效期，也不能推广为预取、缓存或全部 Host 操作的统一时限。

expiresAtEpochMs有实际服务依据才填写，不人为统一成30秒。当前参考包由QQ声明50分钟、B站声明90分钟、酷狗和网易声明18分钟。宿主预取优先使用 PlaybackAddress.expiresAtEpochMs，未声明时采用18分钟默认值；这是宿主的票据复用期限，不代表平台保证该URL在此期间始终有效，也不覆盖包给出的真实到期。trial不能作为完整缓存，verificationPending须由真实媒体时长等补验后再标记完整。播放器/队列/Media3/USB/完整性检查和下载执行归宿主，包负责格式/权限/取址与恢复资料。

常规 resolve 传入的设备策略事实包括 `dolby-decoder`、`dolby-unlocked`、`usb-exclusive`。设备解码支持、用户显式解锁、USB开关且实际连接是不同事实；按这些参数选择音轨时，解锁不越过USB限制。当前 recover 请求不携带这些参数，不能把常规 resolve 的参数传递范围延伸到恢复路径。包按自己的真实协议选择格式，宿主保留设备输出策略。模拟器播放不证明物理USB或杜比硬件验收。

可选 `SourcePlaybackBehavior` 由mediaPlayback服务额外实现：renewOnPlayerErrorLimit默认0，skipOnPlayerError默认true，pauseOnFailure默认false。B站声明一次播放器错误重取票，酷狗声明不自动跳过并暂停；宿主保留当前进程实际解析资源，恢复时传给已有recover(request,previous)，纯缓存没有原资源时重新解析。不持久化恢复地址或载荷，也不按平台名判定行为。

`lyricProvider` 可实现 `SourceLyricBehavior`：`waitForSingleSwitchSettlement` 默认 false，QQ 声明 true 用于切歌歌词交接；`preserveVerbatimFallback` 默认 false，网易云／酷狗声明 true，保留逐字后备。逐字更新通过 `parserVersion`／`refreshPlainLyricsForVerbatim` 控制；未实现行为接口时使用默认策略。

可选 `SourcePlayerPresentation` 由mediaPlayback额外实现：qualityLabel／qualityCompactLabel返回音源档位文案或null，pendingAudioDescription可选覆盖读取提示，missingArtworkHoldMs默认0。未实现时沿用宿主通用展示；QQ声明缺封面时保留原封面12000ms，B站声明STANDARD文案及读取提示，宿主不按平台名分支。

collections可额外实现 `SourceCollectionReturnBehavior`，refreshLibraryAfterAnyReturn默认false；网易云／酷狗声明true，保留专辑等集合返回后刷新账号库与提交延迟收藏的原行为。云歌单收藏直接由已有favorites.supportsCollections声明，未声明仍使用宿主本地收藏，不自动变更远端云状态。

collections可额外实现 `SourceCollectionCachePolicy.includesInCacheLibrary(collection)`，包决定哪些远端集合属于账号缓存目录；未实现或缺包不误清已有快照。未实现接口时已取得的目录项默认纳入，策略读取异常也按纳入处理；宿主另有本地缓存占位目录排除规则。该函数只筛选已有元数据，不发网络请求。酷狗在包内筛选cloudlist，宿主不解析平台前缀。

<a id="lyrics"></a>

## 11. 歌词、主题、背景与权限

`SourceLyricProvider.parserVersion`默认0，表示不额外限制歌词缓存版本；声明非0时宿主只复用对应解析器版本，旧缓存仍可作为失败后备。refreshPlainLyricsForVerbatim默认false；QQ设true，在开启逐字效果但缓存只有整行时请求补齐。解析器版本由包声明，宿主不携带平台解析器常量。

`SourceLyricProvider.lyrics(track)` 返回SourceLyrics(lines,plainText)或null。SourceLyricLine为startMs/text/words/translation/transliteration/parserVersion，SourceLyricWord为startMs/durationMs/text。当前宿主转换只读取 lines 中的 startMs、text、words、translation、parserVersion，不读取或往返保留 plainText 和 transliteration；只填 plainText 且 lines 为空时，公共歌词页不会显示这些文本，也不会自动解析它们。需要公共歌词页展示的内容须通过 lines 提供，未提供真实时间的数据不能伪造同步效果。整行和词时间都使用毫秒原始时间线，保留真实逐字数据及解析器版本，不把逐字降成整行后宣称支持逐字。

`SourceLyricWord.startMs` 是歌曲时间线上的绝对毫秒，结束时间为 startMs+durationMs，不是相对所属行的偏移。音源交付已排序的行，宿主转换不会自动排序。可复用的歌词缓存要求行时间非负、文本非空白、行时间非降序，词时间非负且结束不早于开始。点击歌词的 350ms 预卷由宿主处理，音源返回原始时间戳，不预减该值。

timing(track)返回SourceLyricTiming(lineLeadMs,wordLeadMs)，SourcePresentation提供源级默认值。当前逐曲歌词路径优先使用 lyricProvider.timing(track) 返回的整组值；即使返回0/0，也会覆盖 presentation 的非零默认值，不按字段合并或相加。没有逐曲提供者，或当前逐曲读取失败时，回退到 presentation；连 presentation 也没有时才使用0/0。仅按音源查询、没有曲目参数的入口直接使用 presentation，因此 provider 与 descriptor 应给出一致的默认值，逐曲特殊值由 provider 返回。已标记为资料库时间线的手动歌词使用0/0，避免再应用源默认提前量。

`timing(track)` 当前按最近一曲的同一宿主曲目对象与同一源实例缓存结果，不是订阅式状态接口；仅改变包内变量不会立即重算当前曲目的提前量。

lineLeadMs与wordLeadMs分别控制整行和逐字时钟，正数表示提前，逐字时钟不叠加整行提前量；不要既修改返回时间戳又让宿主加同样偏移。用户整行offset正数表示延后，与音源提前量独立，在线与手动歌词的应用方式见下文。

| 现有业务包 | 默认整行 / 逐字提前量 |
| --- | --- |
| QQ | 275 / 105ms |
| 酷狗 | 185 / 115ms |
| 网易云 | 565 / 0ms；565替代原380，不叠加 |
| B站及未声明包 | 0 / 0ms |

`SourcePermissions.editTrackMetadata`、replaceManualLyrics、adjustLineOffset分别控制资料编辑、手动歌词替换、用户整行偏移，默认全false；允许偏移不等于允许替换或编辑。宿主在UI和写入接口再次检查权限，不能只隐藏按钮。歌词服务是否在线可用也不能推断这三项权限。

当前 editTrackMetadata 还有公共收藏的行为影响：来源开启该权限后，宿主内置歌曲收藏及延迟取消收藏动作增删本地“综合歌单”，不调用远端 favorites.setTracksFavorite，也不依赖远端登录。该分支依据权限而非 MAIN／SUPPLEMENTAL 类型选择；公共菜单沿用此动作，包自己传入的 TrackList.onFavorite 等自定义回调仍由包决定。允许资料编辑的源不能期待宿主内置收藏按钮同时同步远端收藏。

允许手动替换且存在有效覆盖时优先装载覆盖；无覆盖仍可读取已声明的在线lyricProvider，不提供在线歌词也不妨碍独立允许手动导入。在线歌词的用户偏移只移动整行时间；资料库手动歌词保持原行词共同偏移语义，装载后的来源标记阻止展示层再次施加偏移或音源默认提前量。

`setManualLyrics` 的非 null URI 会立即读取并复制到宿主资料库的独立歌词文件，校验能解析出有效时间轴；失败时清理新副本。支持 LRC、XLRC、SPL，未知扩展按 LRC 处理；之后编辑原文件不会同步改变已导入副本。传 null 只清除人工覆盖，仍可能回退资料库保存的默认歌词，再按实际能力读取在线歌词，不能当作强制“无歌词”。

SourcePresentation.pageColorArgb用于页面来源色；radioColorArgb/radioLabel用于电台入口，radioLabel默认“猜你喜欢”。可选radioPaletteLight/radioPaletteDark是两套ARGB列表，顺序为background/foreground/wave/badge/badgeInk；按完整五色声明，避免把平台调色规则硬编码到公共模板。SourceTrackTheme.colorArgb(track)独立返回当前曲目有效ARGB或null；null由宿主按背景使用黑/白。页面色不自动充当每首曲目主题色，曲目结果须校验当前请求身份防止迟到覆盖。 宿主按音源及运行实例桥接已解析颜色，缓存曲目的旧账号或空账号不影响平台展示色；换曲和切回平台可沿用已解析色，实例替换或停用后失效。首次尚未取得有效曲目色时仍使用黑/白回退，不借用页面色。

曲目主题色须在 0 至 0xFFFFFFFF 的 ARGB 范围内且 alpha 非零；透明或越界值按未声明处理，使用宿主回退颜色。

可选SourceEntry.visuals提供SourceVisuals：supportsCustomBackground默认false；observe(route)返回StateFlow<SourceVisualState(backgroundUri,revision,foregroundArgb,customBackground)>。home用于全局背景，playlist路由以 arguments["id"] 携带宿主歌单ID，用于歌单背景；它不是包内 SourceCollection.stableId，身份范围见第8章。当前公共歌单页的 observe 路由还附带 owned 字符串，而设置背景回调只携带 id，背景定位应使用 page 和 id，不依赖两个路由的 arguments 完全相等。包管理自己的文件/账号隔离，宿主只消费公开可读URI与视觉声明，不按平台名读包私有路径。setCustomBackground(route,croppedImageUri)接收宿主已裁剪图片，null恢复默认；写入成功后observe必须发布新revision。foregroundArgb可选，用于背景上的自适应前景。视觉能力与曲目主题/资料编辑权限互不推断。

`SourceVisualState.customBackground` 默认 false，表示当前路由是否有自己的自定义背景，不能只根据 backgroundUri 非空省略该标志。home 的全局背景只有该值为 true 才传入公共页面背景作用域；歌单页也用它决定是否显示含恢复操作的“背景设置”。歌单沿用全局背景时可返回继承的 URI 且保持 false，存在歌单自己的覆盖时才返回 true；supportsCustomBackground 则独立表示是否支持用户选择／恢复背景。

公共歌单页的选择／恢复自定义背景入口还要求歌单 `owned=true`，提供 visuals 不代表所有远端收藏歌单都有该操作。

<a id="host-ui"></a>

## 12. Host 能力与公共 UI

通过 `environment.host` 获取接口，可选服务允许 null。提交曲目使用 `playDetailed(tracks,startIndex)` 和 `enqueueDetailed(tracks,playNext)`；false 追加，true 按原顺序插为下一首。简化的 `play/importTracks` 无法完整保留专辑、访问状态、时长及歌词。只提交本 sourceId 曲目，不能跨来源伪造身份。

`playDetailed` 要求列表非空且 startIndex 在有效范围。`enqueueDetailed` 对本次输入按来源与宿主曲目 ID 去重，空输入无操作；playNext=true 保持去重后的原输入顺序插入下一首位置。

<a id="play-recommendation"></a>
`playRecommendation(track)` 用于自定义页面里的首页推荐歌曲点击，由宿主决定是开电台还是插入当前队列，包不要自己判断。使用公共首页（useCustomPages=false）的音源不用调用，公共首页已走同一逻辑。歌单、搜索、歌手和专辑详情继续用 `playDetailed`，不要改用此方法。

用户在“播放与音质”里可开启“取消首页歌曲推荐替换播放队列”，默认关闭。宿主按下表处理：

| 当前状态 | 开关关闭（默认） | 开关开启 |
| --- | --- | --- |
| 没有在播放，队列为空 | 以这首歌为起点开电台 | 同左，没有队列可替换 |
| 普通歌单或队列 | 替换为这首歌开始的电台 | 插到当前歌后面并立即播放，队列其余部分保留 |
| 同音源电台 | 已在队列内则跳到该位置；是当前歌则切换播放／暂停；否则插入后播放，电台继续 | 同左 |
| 其他音源的电台 | 替换为本音源的电台 | 插到当前歌后面并立即播放，队列保留，电台结束且不再补歌 |

- 音源没有真实提供 `radio` 服务时不会开电台，一律按“插入当前队列”处理（同音源电台不存在）。
- 从歌曲开始的电台，第一批 `radio.next` 收到的 previous 为 null；包返回的歌曲里如果包含起点歌曲，宿主会按曲目 ID 去重。
- 只能提交本 sourceId 的曲目，否则抛出异常。与其他播放入口一样受实例租约和账号版本保护，账号变化后已排队的调用不会执行。
- 不展开播放页，也不改变播放模式之外的页面状态；开电台时播放模式由宿主固定为电台顺序。
- 调用此方法的包必须声明 `minHostVersionCode` ≥ 5。SDK 使用 `jvmDefault=DISABLE`，低于 code 5 的宿主里没有这个方法，调用会抛 `AbstractMethodError`，这是 `Error`，普通 `catch (Exception)` 接不住。接口默认实现（只播放这一首）只对实现 `SourceHost` 的测试替身有意义，不是旧宿主的降级路径。

| Host入口 | 用法与边界 |
| --- | --- |
| library.createCollection / destinations | 创建本地歌单；只选择acceptsManualTracks的目标 |
| library.importTracks | 完整曲目导入指定本地目标；公共资料由宿主持有 |
| library.editMetadata | SourceTrackMetadataEdit(title/artists/album/artworkUri)；null保持，空白字符串恢复原资料；需独立权限 |
| library.setManualLyrics | documentUri或null；需replaceManualLyrics权限 |
| library.setLineOffset | 写入用户整行偏移；需adjustLineOffset权限 |
| library.lineOffset | 读取用户整行偏移；不检查adjustLineOffset权限，仍检查实例租约和曲目来源 |
| library.subscriptionRevision / commitSubscription / removeSubscription | 全页收齐后带expectedRevision原子提交；移除关联时明确keepTracks |
| cache.lookup / download / cancel | 查询完整缓存、直接下载真实SourcePlaybackResource、取消该接口发起的曲目下载；音质选择、返回与取消范围见下文 |
| ui.request | SourceUiRequest(kind,arguments)→SourceUiResult(cancelled,values)；支持的 kind、参数和结果见下方协议表 |
| contentUi | 可选公共完整页面与可组合组件；见下文 SourceContentUi，独立于 useCustomPages |
| ui.openDestination | 目的地及参数是宿主接线约定，不是任意字符串都受支持 |
| playerState | 可空 `StateFlow<SourcePlayerState>`，提供当前来源、宿主曲目ID和播放态；stableTrackId的身份范围见第8章 |
| reduceMotion | Boolean只读属性，默认false；当前宿主getter读取降低动效偏好，不是StateFlow，不能调用collectAsState |
| exitSource / reportError / registerTask | 退出当前音源页、展示无凭据错误、登记独立任务取消 |

`editMetadata.artworkUri` 当前仅作为覆盖字符串存储，不自动复制图片或取得持久 URI 权限；调用方应保证图片长期可读。资料字段的空白字符串包含纯空格输入；它与 null 保留当前覆盖的含义不同。

#### 宿主缓存接口的实际返回与取消范围

`cache.lookup(track,qualityId)` 只返回符合当前音质和设备输出策略的完整缓存，没有符合条件的缓存时返回 null。qualityId=null 时以该源当前音质偏好为首选，并允许使用其他可用缓存档位；非 null 时不允许其他档位。未实现 SourceQualityCatalog 时按标准固定档位识别，未知 ID 回退到该源音质偏好。提供目录时按来源及原始 ID 精确查询自定义档位；未知非空 ID 也保留独立身份，没有匹配的完整缓存则返回 null，不回退到首选，也不因此新增声明档位。返回的 SourceCacheEntry.complete 固定为 true，stableTrackId 是传入的包内 stableId；mediaUri／mimeType 来自离线解析，recovery 当前为默认空 SourcePayload，不包含重新取票资料，也不是下载进度接口。

`cache.download(track,resource)` 是挂起的直接下载，完成写入和完整性检查后才正常返回，失败通过异常返回；不是提交后立即返回的后台队列接口。同一来源、账号及 stableId 的再次调用会取消此前由该接口发起的下载，身份键不含音质。`cache.cancel(track)` 只取消上述直接下载中身份匹配的进行中任务，不删除已有缓存，也不取消宿主缓存管理页批量下载或播放／预取写入；包退出页面时仍应按自己的任务生命周期取消调用协程。

#### 宿主 UI 请求与导航

当前宿主明确支持以下 UI 协议，字符串区分大小写：

| 调用 | kind／destination 与参数 | 结果／行为 |
| --- | --- | --- |
| ui.request | document、lyrics、artwork；arguments 可传 mimeType | 使用系统 OpenDocument；artwork 默认 image/*，其余默认 */*；选择成功返回 cancelled=false、values["uri"]，取消返回 cancelled=true、空 values |
| ui.openDestination | source-authentication；arguments["sourceId"] 为本包稳定 ID | 打开包认证页；authenticationPage 缺省时调用 Content(SourceRoute("authentication")) |
| ui.openDestination | source-settings；arguments["sourceId"] 为本包稳定 ID | 打开包设置页；settings 缺省时调用 Content(SourceRoute("settings")) |
| ui.openDestination | source-radio；arguments["sourceId"] 为本包稳定 ID | 启动该源电台，须真实提供 radio 服务；已在播放该源电台时展开播放页，正在播放其他音源的电台时替换为该源电台 |

artwork 请求只选择图片，不包含裁剪；这里没有通用 crop 或外部验证网页请求。未知 request.kind 抛 UnsupportedOperationException，不作为用户取消；未知 destination 没有可用页面处理器，不能视为打开成功。返回的 URI 若需长期使用，由包及时读取到自己的数据目录或按实际权限处理，不假定宿主已复制文件或取得永久授权。包不得回调宿主某个特定平台页面。缺少公共能力时在包内实现或报告缺口；只有用户本轮明确说“允许修改公共框架”后，才可修改公共契约。

```kotlin
val result = environment.host.ui?.request(
    SourceUiRequest("artwork", mapOf("mimeType" to "image/*")),
)
if (result != null && !result.cancelled) {
    val uri = result.values["uri"] // 及时读取图片；此结果尚未裁剪。
}
```

#### 可选的公共页面与组件

`environment.host.contentUi: SourceContentUi?` 提供公共 UI 复用，源包只依赖公开 SDK AAR。主音源与未实现 `SourceSupplementalPageProvider` 的补充入口通过 `useCustomPages` 决定默认页面组织者：false 由宿主公共模板组织，true 由包组织页面与内部导航。实现该接口的补充入口必须将其设为 false，再按各页面策略选择默认、自定义或关闭。两种自定义方式均可调用全部公共模板完整页面、功能组件与基础组件，组合使用或完全自行实现；三层只是能力组织方式，不限制为部分控件或分期开放。公共状态由公共实现内部维护，包通过 SDK 提供数据、配置与事件，不依赖其他音源私有业务。公共功能、动效及手机／平板适配须保留，具体接入仍须按第 14 章验收清单验证。接口与运行时保护的存在不代表具体交付组合的体验验收通过。

##### 完整页面：pages

`contentUi.pages: SourcePageUi?` 可打开公共导航，也可在包布局中嵌入完整页面：

```kotlin
// 普通点击事件调用；关闭后由仍有效的原包实例处理回调。
val pages = environment.host.contentUi?.pages
pages?.open(SourcePageRequest.Search(descriptor.id, query = "演示", submit = true)) {
    refreshOwnLibrary()
}
pages?.open(SourcePageRequest.Collection(collection))
pages?.open(SourcePageRequest.Artist(descriptor.id, artist))
pages?.open(SourcePageRequest.Album(descriptor.id, album))
// 以下只在包的 Composable 中调用，复用同一请求实例，onClose 交回包自己的导航。
val myRequest = remember(descriptor.id) { SourcePageRequest.My(descriptor.id) }
pages?.Content(myRequest, modifier = Modifier,
    bottomInsetDp = 0f, onClose = { closeOwnPage() })
```

配套宿主中的公共整页会自动连接导航胶囊、统一返回、页头和应用全局设置，源包无需导入私有导航类或自行复制动效。预挂载且 `active=false` 的根页不接收交互或接管根控件。公共资料库数据按运行实例与登录边界保留；同账号 revision 续期重新配置请求，保留目录。只有自定义根表面中的嵌入 My 额外保留分类及滚动保存槽，弹出 My、临时搜索和详情按自身页面生命周期管理。换号、登出、会话过期及包实例失效清理对应状态；同账号续期取消旧请求。这不表示永久保留所有详情，也不替代真实收藏变更同步。该行为以发布说明列出的宿主版本及构建标识为准。

| SourcePageRequest | 参数与对应公开服务 |
| --- | --- |
| Home(sourceId)、My(sourceId) | 主首页／我的，按 discovery、authentication、collections 等实际能力取数 |
| Search(sourceId, query="", submit=false) | 公共完整搜索，使用 search；submit=true 主动提交初始查询 |
| Collection(collection) | 完整公共歌单，来源取 collection.identity.sourceId；使用 collections／favorites／collectionEditing 等 |
| Artist(sourceId, artist)、Album(sourceId, album) | artist／album 为 SourceEntity，使用 entities 的详情、歌曲及集合能力 |
| DailyRecommendations(sourceId)、Recent(sourceId)、Similar(track) | 每日、最近及相似页，使用 discovery 及相关可选服务；Similar 来源取 track.track.identity.sourceId |
| Supplemental(sourceId, target=SourceSupplementalTarget.Home) | 完整补充模板，可定向打开下表目标；消费 contents／childItems／remoteCollections／importing／subscriptions 等 |
| Authentication(sourceId)、Settings(sourceId) | 主音源及未实现 `SourceSupplementalPageProvider` 的补充入口：打开 authenticationPage 或 settings.Content，缺失时分别回退 `Content(SourceRoute("authentication"))`／`Content(SourceRoute("settings"))`。实现该接口的补充入口：分别进入 ACCOUNT／SETTINGS，遵循 DEFAULT／CUSTOM／DISABLED 策略及公共服务条件；DEFAULT 账号页不回退 Content。宿主不生成登录协议或平台设置 |

上述新补充逐页分流仅适用于 `pages.open/Content`；`ui.openDestination("source-authentication"/"source-settings", ...)` 仍直接打开独立认证／设置页，按前述导航协议回退到 Content，不通过逐页策略。

`SourceSupplementalTarget` 包括 `Home`、`Account`、`Collections`、`Link(input="")`、`Collection(collection: SourceRemoteCollection)` 和 `Content(content: SourceContent)`。后两项的 identity.sourceId 必须与请求相同；链接解析、内容详情、子条目、导入和订阅沿用完整补充模板业务，不转借 B站私有实现。例如：

```kotlin
pages?.open(SourcePageRequest.Supplemental(descriptor.id,
    SourceSupplementalTarget.Link(input = input)))
pages?.open(SourcePageRequest.Supplemental(descriptor.id,
    SourceSupplementalTarget.Collection(remoteCollection)))
```

嵌入 Content 的请求实例应在进入页面时创建并跨重组保留，避免每次重组重新初始化公共页；关闭时交回包导航并清理该请求。`Content.onClose` 仅请求退出当前嵌入导航，调用方只清理页面请求或导航状态，不在其中提交业务结果、账号结果或资料写入；只要该嵌入仍挂载且包实例有效，账号变化不会吞掉当前页面的退出请求，已卸载嵌入的旧关闭回调失效。`open.onClosed` 则是与打开时账号版本绑定的完成回调，账号或实例失效后不交付；业务事件继续通过各自公开服务与租约边界执行，组件业务事件校验 status／accountId／revision。`SourcePageUi.open(request,onClosed={})` 是普通方法；`Content(request,modifier=Modifier,bottomInsetDp=0f,onClose={})` 才是 Composable。页面归属取请求中的 sourceId，不依赖宿主当前 selectedSource。完整页面仍须由包实现其声明的数据与操作服务；不支持时隐藏入口，缺服务或请求失败不得伪装成功。打开、关闭、返回和嵌入生命周期由公共页面与包导航共同处理，不接管播放器或应用级导航。

`pages.open` 形成公共页面栈，只有顶部且可交互的页面接收返回和触摸；关闭动画结束后才交付仍有效的完成回调。被覆盖的嵌入页生命周期降为 CREATED，保留画面但暂停依赖 STARTED／RESUMED 的观察。`Content(Search)` 在当前自定义首页独立根表面满足条件时可委托宿主已有根搜索，不保证总是新建独立搜索场景。`RootContent.onClose` 与 `Content.onClose` 同为纯导航退出。

##### 可组合功能与基础控件：components

先通过 `contentUi.components: SourceComponentUi?` 的普通方法 `forSource(sourceId,checkAccess={})` 获取 `SourceComponentScope`。所有数据及状态必须属于该 scope 的 sourceId；不要用当前选中音源替代调用包身份。作用域覆盖以下现有公共展示和交互，具体参数以配套 SDK 声明为准：

| 公开接口 | 方法与用途 |
| --- | --- |
| SourceTrackComponents | rememberTrackListState、TrackListHost、TrackList、SelectionRow、SelectionToolbar、SearchTools、SortButton：完整列表、菜单、多选、搜索定位及排序 |
| SourceHomeComponents | SimilarRecommendations、SongCard、SongGroup、SongShelf、DailyCard、RadioCard、PersonalizedCards、RecentPlay：相似推荐、首页歌曲、货架、个性推荐和最近播放 |
| SourceLibraryComponents | CollectionCard、CollectionRow、RecommendationCard、FeaturedCard、CollectionShelf、ProfileHeader、FavoritesRow、CollectionSegment、CollectionButton、EntityRow、EntitySummary、SectionTitle、Empty、InlineMessage、Loading、LoadMore：资料库、实体、账号及加载反馈 |
| SourceBasicComponents | rememberBackdropState、Backdrop、SearchButton、SettingsButton、Artwork、SongTitle、TrackBadges、PlaybackActions、ActionButton、ActionRow、FormField、SettingsGroup：玻璃背景采样、首页搜索／设置入口、封面、标题角标、播放操作与表单基础展示 |
| SourceSupplementalComponents | LinkInput、ContentSummary、ChildSelection、ImportTarget、ImportActions、SupplementalAccount、RemoteCollectionCard、RemoteCollectionRow、RemoteCollectionHeader：补充模板独立片段；解析／分页／导入／订阅由公开数据与事件驱动 |

`SongGroup(title,tracks,...)` 是三歌曲卡位，直接调用组件也只显示 tracks 的前3首，空列表不显示；其余曲目不会自动分页或生成下一组。需要展示更多歌曲时，由包拆成多组或使用 SongShelf／TrackList。

`SimilarRecommendations(pages,baseTrack=null,loading=false,player=SourcePlayerState(),modifier=Modifier,onClick,onRefresh)` 直接复用公共相似推荐区块；pages 为 `List<SourceSimilarRecommendation>`，每项公开数据为 `SourceSimilarRecommendation(baseTrack: SourceTrackDetails,title: String,tracks: List<SourceTrackDetails>)`。包提供推荐数据与 onClick／onRefresh 事件，公共组件保留原展示、交互和动效，不暴露宿主内部推荐缓存或请求模型；这些曲目均须属于当前 source scope。

这五个组件接口中的方法都是 Composable，包括 `rememberTrackListState(key)`。它返回公开 `SourceTrackListState` 句柄，包含 sourceId、query、selectedSortId、selectionActive、selectedTrackIds，以及普通操作 beginSelection(trackId)、toggleSelection(trackId)、clearSelection()。内部选择、菜单及搜索状态由宿主持有，包使用公开状态和回调，不复制宿主私有状态类。

selectedTrackIds 及 beginSelection／toggleSelection 使用当前已注册歌曲的包内 stableId。状态必须由公共组件创建，不能自行实现替代；账号或账号 revision 变化后旧状态失效，应重新取得。`TrackList` 的 query 在已加载歌曲上执行本地搜索；selectedSortId 只识别 ADDED_DESC／ADDED_ASC／NAME，未知值保留输入顺序。平台自己的 sortId 须由包重新取数和排列，不能依赖该字段远端排序。`SortButton` 仅发送选中事件，不自动修改状态或调用服务。`PlaybackActions.selectedSortId` 也只识别上述三个值，未知值显示 ADDED_DESC，回调返回这些名字。

`TrackList`、`TrackListHost` 的输入歌曲，以及 `TrackListHost` 的集合，其 identity.accountId 必须为空或等于当前账号；列表 `loadAll` 返回的歌曲也经过同一校验，不符合时抛出异常。换号后既要重新取得 state，也要同步换入符合当前账号的数据，不能用新状态继续提交旧账号快照。这个限制针对上述列表入口，不应推断为所有展示组件都有相同的账号校验。

`TrackList` 不自动订阅播放态或读取收藏索引。调用方应观察 `host.playerState` 并传入最新 player，同时把自己的收藏快照传入 favoriteIds；省略时分别使用空的 SourcePlayerState() 和空集合，示例中的点击回调能播放，但不会因此自动点亮播放／收藏状态。favoriteIds 接受当前列表的包内 stableId 或对应宿主曲目 ID；player.stableTrackId 则按宿主曲目 ID 匹配，不能替换成包内 ID。

```kotlin
// 位于 Composable 内，collection、tracks 均属于 descriptor.id，账号为空或匹配当前账号。
val components = environment.host.contentUi?.components
val scope = remember(components, descriptor.id) { components?.forSource(descriptor.id) }
if (scope != null) {
    val state = scope.rememberTrackListState("my-list")
    scope.TrackListHost(collection, state, tracks, bottomInsetDp = bottomInsetDp) {
        Column {
            scope.SearchTools(state)
            scope.TrackList(tracks, state,
                loadAll = { loadAllOwnTracks() }, hasMore = hasMore,
                onLoadMore = { loadNextOwnPage() },
                onClick = { track -> environment.host.playDetailed(tracks, tracks.indexOf(track)) },
                onFavorite = { track -> toggleOwnFavorite(track) })
        }
    }
}
```

`TrackListHost` 接收真实集合上下文、公开 state 和已加载完整曲目，装配菜单与批量操作；不得用虚拟集合 ID 冒充用户真实编辑目标。`TrackList` 内置的多选工具栏和单独调用的 `SelectionToolbar` 都需要位于 `TrackListHost` 或已有的公共歌曲菜单容器内，否则不显示工具栏；只提供选择状态不足以显示全选和批量操作。自定义行可组合 `SelectionRow`，并在同一菜单容器内放置 `SelectionToolbar`。

全选优先调用显式提供的 `loadAll` 挂起回调，其次使用已有公共分页上下文，否则选择当前已加载曲目。需要确定全选范围时，由包显式提供取齐全部分页的 `loadAll`，组件不猜测其协议；hasMore／onLoadMore 与 loading／error 分别提供分页和反馈。返回时优先检查 selectionActive 并 clearSelection，其次处理 query，再返回包上级页。详情可查看 [独立主音源示例](../examples/source-main/README.md)，示例仅用虚拟目录演示，不代表真实平台或播放验收。

`TrackListHost.onChanged` 通知操作后的变更，菜单队列操作仅在来源声明 PLAYBACK 时提供。列表点击、收藏、搜索／设置按钮，以及补充组件的解析、选择、导入、播放、订阅事件仍由包连接。`ImportActions.canImport/canPlay`、`RemoteCollectionHeader.canPlay/canSubscribe` 是调用方传入的交互标志，不自动按来源能力推导，与完整补充模板的固定入口限制不同。

`RemoteCollectionCard.artworkRevision` 只有正数才作为封面刷新版本传入；同 URL 的图片内容变化时更新这个正数，null／非正数不启用该版本刷新路径。`RemoteCollectionHeader` 的 playing 表示集合播放正在读取／准备，true 时显示“正在读取…”并禁用播放按钮；即使 canPlay=true，collection.count==0 也会禁用。数量未知应保留 null，不能用 0 代替未知。

##### 带已加载歌曲的每日推荐卡

`forSource` 返回的作用域可额外实现 `SourceLoadedHomeComponents`，提供带 `tracks: List<SourceTrackDetails>` 的 DailyCard／PersonalizedCards。原 `SourceHomeComponents` 中只收集合元数据的方法保留；需要传入真实歌曲及来源卡过渡上下文时使用该扩展。集合与歌曲均须属于当前来源，账号可为空或等于当前账号。组件不会自行加载每日推荐数据；完整来源卡过渡还要求歌曲非空且当前场景实际提供过渡上下文，传 tracks 本身不保证产生动画。

```kotlin
val scope = environment.host.contentUi?.components?.forSource(descriptor.id)
val loadedHome = scope as? SourceLoadedHomeComponents
loadedHome?.DailyCard(collection, tracks, onClick = onOpenDaily)
```

`SearchButton`／`SettingsButton` 是首页公共搜索／设置入口，复用原按钮与交互动效，不是列表内 `SearchTools`。可通过 `rememberBackdropState(key)` 获取仅暴露 sourceId 的 `SourceBackdropState`，用 `Backdrop(state,modifier,content)` 录制指定背景，按钮通过可空 backdrop 参数读取真实玻璃采样；不把按钮自身放入被录制的背景，避免递归采样。未传 backdrop 时使用原控件回退样式。例如在包的 Composable 中：

```kotlin
val contentUi = environment.host.contentUi
val components = contentUi?.components
val scope = remember(components, descriptor.id) { components?.forSource(descriptor.id) }
if (scope != null) {
    val backdrop = scope.rememberBackdropState("header")
    Box {
        scope.Backdrop(backdrop, Modifier.fillMaxSize()) { OwnBackground() }
        scope.SearchButton(backdrop = backdrop) {
            contentUi?.pages?.open(SourcePageRequest.Search(descriptor.id))
        }
    }
}
```

##### SourceContentUi 直接入口

| 保留的 SourceContentUi 方法 | 参数及行为 |
| --- | --- |
| openCollection | `collection: SourceCollection, onClosed: () -> Unit`；从包主动打开完整公共歌单页，关闭动画结束且原实例仍有效时回调，可刷新包自己的资料库；根页面入口同一时间只接受一个未关闭请求，重复打开会抛异常；已在公共整页栈中时进入该公共页栈，避免新歌单被原公共页遮挡；进程重启不恢复内存回调 |
| CollectionCard | `collection, modifier, onClick`；我的歌单卡，保留封面与文字及共享返回动效 |
| RecommendationCollectionCard | `collection, modifier, widthDp=152f, onPlay, onClick`；推荐歌单卡，保留原播放入口 |
| FeaturedCollectionCard | `collection, modifier, heightDp=382f, compact=false, onPlay, onClick`；大封面推荐卡 |
| ProfileHeader | `sourceId, account: SourceAccount?, onLogin`；账号头像与资料入口 |
| FavoritesRow | `collection: SourceCollection?, signedIn, onClick`；我喜欢的音乐入口 |
| CollectionSegment | `created, createdCount, collectedCount, onCreated, onCollected`；创建／收藏分段与切换动效 |
| TrackMenuHost | `sourceId, bottomInsetDp=0f, content`；包页面的公共歌曲菜单容器，与包的歌曲行组合使用 |
| TrackRow | `track: SourceTrackDetails, current, playing, favorite, onClick, onFavorite, showSource=true`；歌曲行，在 TrackMenuHost 内显示公共更多菜单 |

上表中 `openCollection` 是普通方法，其后列出的八个组件方法是 Composable；该规则不适用于 `pages.open`、`components.forSource` 或状态操作。上述方法签名按当前 SDK 声明提供。公共歌单仍复用搜索、排序、播放／随机、批量、分页重试、工具栏、封面过渡及双设备布局；远端能力由对应公开服务决定，不能因有组件就自动获得协议功能。页面排序不表示提交远端顺序；远端改序还须满足 SourceCollectionRemoteOrder 的声明与完整读取要求。

`contentUi`、`pages`、`components` 均须判空；未提供时使用包自己的页面。版本与配套 SDK 要求见第 1 章。

`pages`／`components` 将请求、scope、状态操作及回调绑定到当前实例租约与来源，拒绝跨源和实例失效后的操作。组件业务事件校验组合时的 status／accountId／revision，账号变化后失效。隔离初始化不得打开页面或执行业务写入。关闭回调的导航与账号绑定规则见上文。

`SourceContentUi` 上表中的八个 Composable 直接委托公共组件，不提供 scope 的统一事件保护；音源包须自行处理账号变化和过期事件。`openCollection` 检查实例有效性，完成回调不绑定打开时的账号。调用方须取消自己的任务，不将 Activity、Context、View、状态句柄或回调持久化。

<a id="root-pages-and-shelves"></a>

### 一级根页与横向货架的可选接线

下列根页与货架接口按需使用，版本与配套 SDK 要求见第 1 章。

#### 一级公共页面

`contentUi.pages as? SourceRootPageUi` 提供：

- `RootContent(request, modifier, bottomInsetDp, onClose)`：只接受 `SourcePageRequest.Home/My`，明确把公共整页作为调用方一级页面。复用原页面与导航，省去包装层额外的“返回音源页面”按钮及其 48dp 页头避让。详情、搜索和原独立 `Content/open` 的返回行为保持。
  - 一级公共页的标题、搜索／设置图标由宿主同一个页头绘制，跟随根翻页进度使用原位移和渐变，并共享两页滚动显隐状态；包无需另绘页头。此行为由实现该接线的宿主提供，使用时应核对发布说明中的宿主构建标识。
- `ReportScroll(page, position)`：自定义一级列表向宿主原页头报告滚动。`page` 为小写 `home` 或 `my`；`position` 返回真实 `SourceRootScrollPosition(firstVisibleItemIndex, firstVisibleItemScrollOffset)`，偏移单位为像素。

```kotlin
val rootUi = pages as? SourceRootPageUi
val list = rememberLazyListState()
if (homeActive && !covered) {
    rootUi?.ReportScroll("home") {
        SourceRootScrollPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
    }
}
// 把同一 list 传给自己的 LazyColumn。
val myRequest = remember(sourceId) { SourcePageRequest.My(sourceId) }
rootUi?.RootContent(myRequest, bottomInsetDp = bottomInsetDp) { closeOwnRoot() }
```

仅在对应一级页活跃且没有搜索／详情覆盖时报告滚动；宿主还会检查页面可交互且生命周期为 RESUMED。离屏或后台时停止报告，恢复后提供当前列表位置；标题动画由宿主处理。

只有在明确作为一级页时才调用 `RootContent`；普通导航仍使用 `Content/open`。运行时包装保留可选接口的真实存在性，并沿既有租约与来源校验转发；缺少能力不能假称已支持。

#### 保留内容边距的完整横滑视口

`components.forSource(sourceId) as? SourceHomeShelfComponents` 提供带 `edgeInsetDp` 的 `SongShelf/CollectionShelf`，其余歌曲、播放态、收藏及打开回调沿原接口语义。

```kotlin
val scope = environment.host.contentUi?.components?.forSource(descriptor.id)
val shelves = scope as? SourceHomeShelfComponents
// 列表 item 的外层已经有这个边距；由公共实现扩宽横滑视口并保留内部首尾边距。
val gutterDp = if (wide) 32f else 20f
shelves?.SongShelf(section.id, section.title, section.tracks.chunked(3),
    player, edgeInsetDp = gutterDp, onClick = onTrackClick)
shelves?.CollectionShelf(collections, edgeInsetDp = gutterDp,
    onPlay = onPlayCollection, onOpen = onOpenCollection)
```

`edgeInsetDp` 填入列表已有的外层水平边距，须为有限非负值。公共组件扩宽横滑视口并保留内部首尾边距；调用方保留标题等内容的边距，不重复补偿。手机和平板按各自布局传值。

带 `edgeInsetDp` 的货架使用对称首尾边距，歌单卡宽仍按内容宽度计算；不带该参数的入口保留尾部 20dp 布局。卡片交互、收藏及导航由公共组件处理；歌曲点击仍回调包提供的 `onClick`。首页推荐货架的 `onClick` 应调用 [`playRecommendation`](#play-recommendation)，这样“取消首页歌曲推荐替换播放队列”设置才对该货架生效。


<a id="cached-artwork"></a>

### 冷启动已有封面准备

自定义页面的内容快照属于音源包，图片缓存属于宿主。为在启动恢复内容快照时后台准备已有封面，`contentUi.components.forSource(sourceId)` 可选提供独立接口 `SourceCachedArtworkComponents`：

```kotlin
val scope = environment.host.contentUi?.components?.forSource(descriptor.id)
val cachedArtwork = scope as? SourceCachedArtworkComponents
cachedArtwork?.prepareCachedArtwork(artworks, maxSidePx = 768)
// 在后台准备；恢复后的页面结构立即交付，不等待封面。
```

- 准备时机：自定义首页应在音源启动恢复内容快照时后台发起准备，恢复后立即交付原页面结构，不用封面就绪状态阻挡整个首页，也不显示整页“正在读取音乐快照”。缓存准备慢、失败或取消均不改变已恢复内容。真实网络核对仍由原可见性入口触发。
- 所有包都会初始化；宿主在实例尚未发布时仅为当前主音源准备缓存，避免未选中的包挤占当前首屏封面缓存。实例就绪后的原有调用路径不变；validationOnly 初始化不得调用此入口。
- `artworks` 使用真实封面 URL；`maxSidePx` 与原显示组件的解码尺寸一致，范围1到768，默认768。QQ信息流继续使用其已有卡片/缩略图尺寸。
- 入口只查询既有内存缓存、读取并解码宿主磁盘缓存、准备绘制，不下载图片、不强制刷新，也不返回位图或文件路径。空URL和重复URL会跳过；单张缓存未命中/损坏不阻塞内容，后续由原图片显示入口按原策略处理。
- 宿主在准备前后检查来源作用域、实例租约和账号。调用方仍应取消旧页面任务，并在交付内容前确认账号/会话归属。
- 该扩展为可选能力，版本与配套 SDK 要求见第 1 章。
- 不调用音频缓存接口，不改变播放、卡片布局、封面交接或原动效。缓存封面准备不作为首页内容交付的前置等待条件。


<a id="installation"></a>

## 13. 安装、更新、回退与按类型删除

接入者交付包由宿主 `SourceRepository(context,host)` 管理，不直接写宿主registry。`importPackage(File,allowDowngrade=true)` 先完成新文件签名、清单兼容及已有签名身份检查；同版本且文件内容相同才命中 ALREADY_INSTALLED，详情见第 5 章。其余可接纳的版本继续加载入口、核对 descriptor、隔离初始化并校验能力，成功后只登记 pending 版本。当前进程旧实例不热替换，包括第一次安装也等重启激活。`startAfterProcessRestart` 只在进程启动时调用一次。

更新以同ID同证书导入，更大 versionCode 为升级，相同编号不同内容为覆盖安装；导入页面与仓库接口默认允许较低 versionCode 的同签名包，直接登记为待激活，不额外弹出降级确认；需要限制的集成方可显式传 allowDowngrade=false。允许降级不改变签名、完整清单与兼容检查。requestRollback(id)使用保留的上次可用版本，在下次启动生效；没有备份版本时失败。显式回退保留当前账号/用户数据，不恢复旧用户快照，所以包存储格式必须向后兼容。

用户主动导入的安装决策如下；所有可安装分支均须通过签名、身份、宿主兼容及入口能力检查：

| 与当前选定安装比较 | 结果 |
| --- | --- |
| 没有已安装或待生效包 | 验证后保存，重启首次激活。 |
| 版本与完整文件内容都相同 | 返回 ALREADY_INSTALLED，不重复初始化或登记 pending。 |
| 同版本，内容不同 | 另存独立安装文件，登记 pending，重启覆盖生效；原安装仍可回退。 |
| 更高版本 | 登记 pending，重启升级。 |
| 更低版本 | 默认登记 pending，重启降级；显式 allowDowngrade=false 才拒绝。 |
| 已有另一个 pending，重新选择 active／rollback 包 | 更新待生效选择，不因版本号相同就跳过；仍适用入口降级限制。 |

“当前选定安装”优先指 pending，没有 pending 才指 active。这里只比较包的 versionCode；宿主 minHostVersionCode 是另一套编号，不能拿音源版本或展示名称替代。预装自动更新沿用只接纳更高版本的独立规则，不自动覆盖同版本包或降级。因此内置包内容变了但 versionCode 不变时，已安装旧包的设备不会随宿主升级获得新内容，只有全新安装或用户手动导入才会生效。

待激活安装初始化前，运行时备份稳定源目录，事务同时记录目标版本及安装标识，避免同版本覆盖误认提交完成；使用完成标记区分完整备份与中断备份，恢复可重入。若初始化失败，恢复初始化前数据并尝试当前 active 版本；首次安装没有 active 版本时记录错误。初始化事务只保护 dataDirectory，不包含独立的 cacheDirectory，也不负责包自行写出的任意外部路径／网络副作用。registry损坏时保留原文件、报告错误并限制修改；可读的其他源及本地功能不应被整应用清空或崩溃替代。

主音源确认移除后，宿主调用 `scheduleRemoval(id)`，立即停用并关闭实例和任务租约，在管理页显示“待删除”；包文件与账号暂时保留。下次真正进程启动之前可调用 `undoRemoval(id)` 撤销并重新加载原 activeVersion，原 pendingVersion／rollbackVersion 及待更新、待回退选择保持不变，不热切到待生效版本。待删除期间拒绝导入覆盖及请求回退，预装更新也不覆盖待删除状态。

下次进程启动时，`startAfterProcessRestart()` 在激活前清除包的 active／pending／rollback 文件、包私有数据与登录状态、私有缓存及初始化备份；宿主同时清除旧版会话迁移副本，避免重新导入时恢复已删除账号。删除后保留签名身份及 removed 墓碑，历史已删除主音源的私有账号残留也会清理；清理失败保留待删除或删除记录并报告错误，不激活该源。`installPrebundledOnce` 尊重待删除和删除墓碑；已安装源的同签名更高版本仍遵循正常更新规则。

宿主保留歌曲元数据、资料库、队列和离线缓存；缺少主音源时歌曲名称置灰、该源不可用，撤销后恢复可用性。宿主先处理该来源当前播放的确认与解析、预取、播放停止；撤销或同 ID 同证书重导入均不自动恢复播放。完成删除后主动重导入可恢复曲目关联，但账号需要重新登录。补充音源仍沿用 `remove(id)` 立即关闭租约、删除代码并保留稳定数据及墓碑的原有语义。

### 补充音源立即删除及预装更新的补充规则

remove(id)立即关闭实例和任务租约，删除active/pending/rollback代码文件，保留稳定数据与签名删除墓碑。宿主先处理当前来源播放的提示、确认、停止解析/预取/播放；保留队列和资料，不触发重导入后的自动播放。installPrebundledOnce 会导入从未记录的 ID，也会为已安装且同签名、没有待生效选择、没有待删除或删除标记的源登记更高版本预装更新；随附版本须高于 activeVersion 与 rollbackVersion 两者。已有待更新／待回退选择及更高用户版本不被覆盖，删除墓碑防止启动后预装自动复活。用户主动同ID同证书重导入才恢复关联；删除不等于清空账号/用户数据，清数据须独立明确操作。

<a id="acceptance"></a>

## 14. 接入验收与源码导航

必要验收按证据分开记录：独立SDK-AAR构建；包debug/优化release；共享类未重复打包；真实外部DEX入口；文本/vector资源切换及主题/返回；手机/平板布局；真实账号与全部业务；同ID异证书拒绝；版本重复/更新/降级/初始化失败回退；主源移除立即停用、重启前撤销与版本选择保留、重启清理账号及墓碑防复活；同身份重导入保留宿主曲目资料并重新登录；补充源立即移除取消任务、墓碑阻止预装复活、同身份重导入保留稳定用户数据。只安装到用户平板不代表视觉验收完成，未经同意不操控或截图。

`examples/source-main` 与 `examples/source-supplemental` 支持 `-PsourceVersion=2`，以及故障注入参数 `-PfailInitialization=true`（真实初始化失败）和 `-PfailContent=true`（自定义页首次组合失败）。初始化隔离验证不改真实计数；内容页失败应显示错误并允许返回。正式分发须关闭故障注入。这两个示例也提供同名 preferences 与 vector，用于验证计数及资源隔离。最小补充示例只演示直链解析／播放和 sourceVersion 覆盖。

### 源码导航

| 路径 | 责任 |
| --- | --- |
| source-sdk/src/main/java/com/musicone/source/sdk/SourceContract.kt | 入口、清单、环境与 Host |
| 同目录SourceMedia.kt / SourceAuthentication.kt | 完整曲目/集合/实体/访问模型，认证与完整迁移 |
| 同目录SourceLibraryFeatures.kt | 收藏资格及身份、资料库变化、简介与封面编辑、云端改序、关注和热搜扩展 |
| 同目录SourceCatalogServices.kt / SourceEntityArtwork.kt | 发现／独立每日推荐／货架展示／搜索／轻量实体图片／歌单／收藏／编辑／电台 |
| 同目录SourceContentUi.kt / SourcePageUi.kt | 公共入口、可空扩展、页面请求及补充目标 |
| 同目录SourceComponentUi.kt / SourceBasicComponents.kt / SourceSupplementalComponents.kt | 音源作用域、公开列表状态、功能组件、基础及补充片段 |
| 同目录SourceSupplementalServices.kt | 内容/子条目/远端集合/导入/订阅 |
| 同目录SimpleSupplementalSourceEntry.kt / SimpleSupplementalContents.kt / SourceSupplementalPageProvider.kt | 最小补充基类、内部解析缓存与逐页接管／动作上下文 |
| 同目录SourceQualityCatalog.kt | 自定义音质目录、默认与回退 ID |
| 同目录SourcePlaybackSession.kt | 默认关闭的会话观察者、实际收听累计值、事件及结束原因 |
| 同目录SourceLyricsAndPlayback.kt / SourceHostServices.kt / SourceVisuals.kt | 歌词/主题/播放、源背景声明，以及宿主库/缓存/UI能力 |
| source-runtime/src/main/java/com/musicone/source/runtime/ | ArchiveVerifier/Manifest/Loader/CapabilityValidator/GuardedEntry/Repository/ActivationTransaction/ResourceScope |
| examples/source-main / examples/source-supplemental | 最小独立编译、资源、存储与故障注入验收工程 |
| examples/source-supplemental-simple | 最小直链解析／播放工程，单一 source.properties 生成清单与身份常量 |
| source-packs/qq / kugou / netease / bilibili | 真实业务参考；实现和验收状态分别查对应README与主任务记录 |

接入资料库扩展时，还须验证等价收藏、只读资格、完整远端顺序、循环游标、重复歌曲、账号切换、未声明扩展时的默认行为、详情空值与清空、变化流失效；网络写入与手机／平板交互分别验收。

<a id="host-internals"></a>

## 15. 宿主与音源包的职责边界

音源包通过公开 SDK 提供业务数据、媒体资源和页面；播放执行、队列、宿主缓存及其存储兼容由宿主负责。音源包不依赖宿主内部类、私有存储键或缓存文件名。音质与媒体缓存使用公开 `qualityId`／`cacheIdentity`，歌词解析缓存通过 `parserVersion` 声明。

<a id="documentation-gaps"></a>

## 16. 兼容与交付边界

- 最低宿主通过 `minHostVersionCode` 在导入、启动和请求回退时校验，未满足时在执行包代码前拒绝并显示所需／当前版本；`minHostVersionName` 仅用于展示。已发布旧宿主会忽略未知新字段，新增声明不能使历史宿主自动获得拦截能力。API 1 仍不足以单独确定可选符号与行为，交付须同时记录实际验证组合。
- `radio.feedback` 已在 SDK 声明，但当前宿主没有调用，也没有约定宿主发送的 action 值，不能依赖播放、跳曲或收藏自动触发它。
- 第 17 章给出当前声明、可空性、默认参数和默认实现；前文说明宿主对这些契约的实际使用。SDK 默认返回值与宿主包装器的兼容回退不能混为一谈。
- 正式交付须记录 SDK／宿主／包的实际组合与验证结果；源码核对不能替代优化包、真实账号或手机/平板体验验收。

<a id="sdk-declarations"></a>

## 17. SDK 完整声明与默认实现

本章逐文件收录当前 SDK 契约完整声明和默认实现，保持与链接源码一致。公开接口用于配套 AAR；SimpleSupplementalContents 是简化基类的内部辅助实现，仅供理解行为，不是包可依赖的公开类型。数据字段存在不等于宿主消费全部字段，限制以前文为准。

| 契约文件 | 主要类型 |
| --- | --- |
| [SimpleSupplementalContents.kt](#sdk-simplesupplementalcontents) | SimpleSupplementalContents |
| [SimpleSupplementalSourceEntry.kt](#sdk-simplesupplementalsourceentry) | SimpleSupplementalSourceInfo、SimpleSupplementalSourceEntry |
| [SourceAuthentication.kt](#sdk-sourceauthentication) | SourceAccount、SourceSessionStatus、SourceAccountState、SourceSessionSnapshot、SourceLegacySession、SourceMigrationStatus、SourceMigrationReceipt、SourceLoginMethod、SourceAuthChallenge、SourceAuthProgress、SourceAuthResult、SourceAuthentication |
| [SourceBasicComponents.kt](#sdk-sourcebasiccomponents) | SourceBackdropState、SourceBasicComponents |
| [SourceBehavior.kt](#sdk-sourcebehavior) | SourcePlaybackBehavior、SourceLyricBehavior |
| [SourceCachedArtworkComponents.kt](#sdk-sourcecachedartworkcomponents) | SourceCachedArtworkComponents |
| [SourceCatalogServices.kt](#sdk-sourcecatalogservices) | SourceHomeSection、SourceRecentSnapshot、SourceDailyRecommendations、SourceHomePresentation、SourceHomeSectionPresentation、SourceDiscovery、SourceSearchKind、SourceSearchResult、SourceSearch、SourceEntities、SourceLibrarySection、SourceCollectionSortOption、SourceCollections、SourceFavorites、SourceCollectionEdit、SourceCollectionOperation、SourceCollectionEditing、SourceRadioBatch、SourceRadio |
| [SourceCollectionCachePolicy.kt](#sdk-sourcecollectioncachepolicy) | SourceCollectionCachePolicy |
| [SourceCollectionReturnBehavior.kt](#sdk-sourcecollectionreturnbehavior) | SourceCollectionReturnBehavior |
| [SourceComponentUi.kt](#sdk-sourcecomponentui) | SourceComponentUi、SourceTrackListState、SourceComponentScope、SourceTrackComponents、SourceHomeComponents、SourceSimilarRecommendation、SourceLibraryComponents |
| [SourceContentUi.kt](#sdk-sourcecontentui) | SourceContentUi |
| [SourceContract.kt](#sdk-sourcecontract) | SourceSdk、SourceKind、SourceCapability、SourcePermissions、SourcePresentation、SourceDescriptor、TrackIdentity、SourceTrack、SourcePage、PlaybackAddress、SourcePlayback、SourceCatalog、SourceRoute、SourcePlayerState、SourcePageState、SourceHost、SourceEnvironment、SourceEntry |
| [SourceEntityArtwork.kt](#sdk-sourceentityartwork) | SourceEntityArtwork |
| [SourceHomeContinuation.kt](#sdk-sourcehomecontinuation) | SourceHomeContinuation |
| [SourceHomeShelfComponents.kt](#sdk-sourcehomeshelfcomponents) | SourceHomeShelfComponents |
| [SourceHostServices.kt](#sdk-sourcehostservices) | SourceLocalCollection、SourceTrackMetadataEdit、SourceLibraryHost、SourceCacheEntry、SourceCacheHost、SourceUiRequest、SourceUiResult、SourceUiHost、SourceSettings、SourceAuthenticationPage |
| [SourceLibraryFeatures.kt](#sdk-sourcelibraryfeatures) | SourceCollectionFavoritePolicy、SourceLibraryChange、SourceLibraryChanges、SourceCollectionEditField、SourceCollectionDetailsEdit、SourceCollectionDetailsEditing、SourceCollectionRemoteOrder、SourceEntityFollowing、SourceHotSearch |
| [SourceLoadedHomeComponents.kt](#sdk-sourceloadedhomecomponents) | SourceLoadedHomeComponents |
| [SourceLyricsAndPlayback.kt](#sdk-sourcelyricsandplayback) | SourceLyricWord、SourceLyricLine、SourceLyrics、SourceLyricTiming、SourceLyricProvider、SourceTrackTheme、SourcePlaybackPurpose、SourcePlaybackRequest、SourceMediaSegment、SourcePlaybackResource、SourceMediaPlayback |
| [SourceMedia.kt](#sdk-sourcemedia) | SourcePayload、SourceEntityRef、TrackAccessBadge、TrackAccessInfo、SourceAudioQuality、SourceArtwork、SourceTrackDetails、SourceCollection、SourceEntity、SourceFetchPolicy、SourceCursorRequest、SourceOperationException |
| [SourceMembership.kt](#sdk-sourcemembership) | SourceMembershipProvider、SourceMembershipDetector、membershipDetectorOrNull、currentMembership、detectMembership |
| [SourceMySectionPresentation.kt](#sdk-sourcemysectionpresentation) | SourceMySectionPresentation |
| [SourcePageUi.kt](#sdk-sourcepageui) | SourcePageRequest、SourceSupplementalTarget、SourcePageUi |
| [SourcePlaybackSession.kt](#sdk-sourceplaybacksession) | SourcePlaybackSessionProvider、SourcePlaybackSessionObserver、SourcePlaybackSessionEvent、SourcePlaybackSessionEventType、SourcePlaybackSessionEndReason |
| [SourcePlayerPresentation.kt](#sdk-sourceplayerpresentation) | SourcePlayerPresentation |
| [SourcePlayerUi.kt](#sdk-sourceplayerui) | SourcePlayerUiProviderAccess、playerUiProviderOrNull、SourcePlayerUiProvider、SourcePlayerUiDeclaration、SourcePlayerOpening、SourcePlayerSharedElement、SourcePlayerForm、SourcePlayerQuality、SourcePlayerSnapshot、SourcePlayerTimeline、SourcePlayerTransition、SourcePlayerInsets、SourcePlayerUiContext、SourcePlayerUiActions |
| [SourceQualityCatalog.kt](#sdk-sourcequalitycatalog) | SourceQualityCatalog、SourceQualityDefinition |
| [SourceRootPageUi.kt](#sdk-sourcerootpageui) | SourceRootPageUi、SourceRootScrollPosition |
| [SourceSupplementalComponents.kt](#sdk-sourcesupplementalcomponents) | SourceSupplementalComponents |
| [SourceSupplementalPageProvider.kt](#sdk-sourcesupplementalpageprovider) | SourceSupplementalContract、SourceSupplementalPage、SourceSupplementalPageMode、SourceSupplementalPagePolicy、SourceSupplementalPageProviderAccess、SourceSupplementalPageProvider、SourceSupplementalPageContext、SourceSupplementalPageActions |
| [SourceSupplementalServices.kt](#sdk-sourcesupplementalservices) | SourceContent、SourceChildItem、SourceContents、SourceChildItems、SourceRemoteCollection、SourceRemoteCollections、SourceRemoteCollectionArtwork、SourceImporting、SourceSubscriptionIdentity、SourceSubscriptionItem、SourceSubscriptions |
| [SourceTrackBadge.kt](#sdk-sourcetrackbadge) | SourceTrackBadge |
| [SourceVisuals.kt](#sdk-sourcevisuals) | SourceVisualState、SourceVisuals |

<a id="sdk-simplesupplementalcontents"></a>

### SimpleSupplementalContents.kt

对应源码：[SimpleSupplementalContents.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SimpleSupplementalContents.kt)

```kotlin
package com.musicone.source.sdk

/** 一次解析的歌曲直接用于公共选择和导入；被淘汰的临时结果按原输入重新解析。 */
internal class SimpleSupplementalContents(
    private val sourceId: String,
    private val parse: suspend (String) -> List<SourceTrackDetails>,
) : SourceContents, SourceChildItems, SourceImporting {
    private val snapshots = LinkedHashMap<TrackIdentity, List<SourceTrackDetails>>()

    override suspend fun resolve(input: String): SourceContent {
        require(input.isNotBlank()) { "请输入链接或编号" }
        val tracks = parse(input)
        require(tracks.isNotEmpty()) { "没有解析到可导入的歌曲" }
        require(tracks.all { it.track.identity.sourceId == sourceId && it.stableId.isNotBlank() }) { "解析结果缺少稳定身份或属于其他音源" }
        require(tracks.map { it.track.identity }.distinct().size == tracks.size && tracks.map { it.stableId }.distinct().size == tracks.size) { "解析结果包含重复歌曲身份" }
        val first = tracks.first()
        val identity = TrackIdentity(sourceId, first.track.identity.accountId, "input:$input")
        synchronized(snapshots) {
            snapshots.remove(identity)
            snapshots[identity] = tracks
            while (snapshots.size > 32) snapshots.remove(snapshots.keys.first())
        }
        return SourceContent(identity, first.track.title, artwork = first.artwork,
            durationMs = tracks.sumOf { it.durationMs }, payload = SourcePayload(INPUT_FORMAT, 1, input),
            playableTrack = tracks.singleOrNull())
    }

    override suspend fun detail(content: SourceContent): SourceContent {
        if (content.payload.format != INPUT_FORMAT) return content
        val tracks = snapshot(content)
        // 缓存淘汰后的新解析同时更新内容资料及单曲入口，不能继续沿用旧曲目。
        return content.copy(title = tracks.first().track.title, artwork = tracks.first().artwork,
            durationMs = tracks.sumOf { it.durationMs }, playableTrack = tracks.singleOrNull())
    }

    override suspend fun children(content: SourceContent, request: SourceCursorRequest): SourcePage<SourceChildItem> {
        if (request.cursor != null) return SourcePage(emptyList(), null)
        val tracks = snapshot(content)
        if (tracks.size == 1) return SourcePage(emptyList(), null)
        return SourcePage(tracks.mapIndexed { index, track ->
            SourceChildItem(track.track.identity, index + 1, track.track.title, track.durationMs, initiallySelected = true)
        }, null)
    }

    override suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>): List<SourceTrackDetails> {
        val selected = selectedChildren.map { it.identity }.toSet()
        val tracks = snapshot(content)
        require(selected.all { id -> tracks.any { it.track.identity == id } }) { "所选歌曲已变化，请重新解析" }
        if (selected.isEmpty() && tracks.size == 1) return tracks
        return tracks.filter { it.track.identity in selected }
    }

    private suspend fun snapshot(content: SourceContent): List<SourceTrackDetails> {
        require(content.identity.sourceId == sourceId) { "不能解析其他音源的内容" }
        synchronized(snapshots) { snapshots[content.identity]?.let { return it } }
        require(content.payload.format == INPUT_FORMAT && content.payload.version == 1) { "此内容需要音源提供自定义转换服务" }
        val refreshed = resolve(content.payload.encoded)
        require(refreshed.identity == content.identity) { "内容账号已变化，请重新解析" }
        return synchronized(snapshots) { checkNotNull(snapshots[refreshed.identity]) }
    }

    fun clear() = synchronized(snapshots) { snapshots.clear() }
    private companion object { const val INPUT_FORMAT = "musicone.simple.input" }
}
```

<a id="sdk-simplesupplementalsourceentry"></a>

### SimpleSupplementalSourceEntry.kt

对应源码：[SimpleSupplementalSourceEntry.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SimpleSupplementalSourceEntry.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable

/** 简易入口的静态信息；额外能力仅在确实提供服务时声明。 */
data class SimpleSupplementalSourceInfo(
    val id: String,
    val name: String,
    val versionCode: Long,
    val versionName: String,
    val extraCapabilities: Set<SourceCapability> = emptySet(),
    val requiresLogin: Boolean = false,
    val permissions: SourcePermissions = SourcePermissions(),
    val presentation: SourcePresentation = SourcePresentation(),
) {
    fun descriptor() = SourceDescriptor(id, name, SourceKind.SUPPLEMENTAL, versionCode, versionName,
        setOf(SourceCapability.HOME, SourceCapability.CONTENT, SourceCapability.CHILD_ITEMS,
            SourceCapability.IMPORT, SourceCapability.PLAYBACK) + extraCapabilities,
        useCustomPages = false, requiresLogin = requiresLogin, permissions = permissions, presentation = presentation)
}

/** 只需实现解析和取源；复杂服务、音质及页面仍可逐项替换，不改变旧 SourceEntry。 */
abstract class SimpleSupplementalSourceEntry(info: SimpleSupplementalSourceInfo) : SourceEntry, SourceSupplementalPageProvider {
    final override val descriptor = info.descriptor()
    final override val implementedCapabilities get() = descriptor.capabilities
    protected lateinit var environment: SourceEnvironment
        private set
    private val resolvedInputs = SimpleSupplementalContents(descriptor.id, ::parseInput)

    protected abstract suspend fun parseInput(input: String): List<SourceTrackDetails>
    protected abstract suspend fun resolvePlayback(request: SourcePlaybackRequest): SourcePlaybackResource
    protected open fun onInitialize(environment: SourceEnvironment) {}
    protected open fun onClose() {}

    /** 默认单档不推测编码或无损规格，多档由音源提供稳定标识及明确回退顺序。 */
    protected open val qualityDefinitions: List<SourceQualityDefinition> = listOf(SourceQualityDefinition("default", "默认音质", "默认"))
    protected open val defaultQualityId: String get() = qualityDefinitions.first().id
    protected open fun fallbackQualityIds(preferredId: String): List<String> = listOf(preferredId)
    protected open suspend fun recoverPlayback(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource = resolvePlayback(request)

    override val contents: SourceContents get() = resolvedInputs
    override val childItems: SourceChildItems get() = resolvedInputs
    override val importing: SourceImporting get() = resolvedInputs
    override val mediaPlayback: SourceMediaPlayback = object : SourceMediaPlayback, SourceQualityCatalog {
        override val qualityCatalog get() = qualityDefinitions
        override val defaultCatalogQualityId get() = this@SimpleSupplementalSourceEntry.defaultQualityId
        override val defaultQualityId get() = defaultCatalogQualityId
        override fun catalogFallbackQualityIds(preferredId: String) = this@SimpleSupplementalSourceEntry.fallbackQualityIds(preferredId)
        override fun fallbackQualityIds(preferredId: String) = catalogFallbackQualityIds(preferredId)
        override suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality> =
            track.qualities.ifEmpty { qualityCatalog.map { SourceAudioQuality(it.id, it.label) } }
        override suspend fun resolve(request: SourcePlaybackRequest) = resolvePlayback(request)
        override suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource) = recoverPlayback(request, previous)
        override suspend fun availability(tracks: List<SourceTrackDetails>) = tracks.map { it.access }
    }

    final override fun initialize(environment: SourceEnvironment) {
        check(!::environment.isInitialized) { "音源已经初始化" }
        this.environment = environment
        onInitialize(environment)
    }
    final override fun close() { try { onClose() } finally { resolvedInputs.clear() } }
    @Composable override fun Content(route: SourceRoute) {}
    @Composable override fun SupplementalPage(context: SourceSupplementalPageContext) { context.PublicContent() }
}
```

<a id="sdk-sourceauthentication"></a>

### SourceAuthentication.kt

对应源码：[SourceAuthentication.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceAuthentication.kt)

```kotlin
package com.musicone.source.sdk

data class SourceAccount(
    val id: String, val displayName: String, val avatarUrl: String? = null,
    val membership: String? = null, val backgroundUrl: String? = null,
    val signature: String = "", val follows: Long? = null, val followers: Long? = null
)
enum class SourceSessionStatus { GUEST, AUTHENTICATED, EXPIRED }
data class SourceAccountState(val status: SourceSessionStatus, val account: SourceAccount?, val revision: Long)

// 完整会话是不透明内容，不得将其写入日志，也不能只迁移头像或已登录标记。
class SourceSessionSnapshot(val format: String, val version: Int, val fields: Map<String, String>) {
    override fun toString(): String = "SourceSessionSnapshot(format=$format, version=$version, fields=<已隐藏>)"
}
class SourceLegacySession(
    val migrationId: String, val sourceId: String,
    val preferenceNamespaces: Map<String, Map<String, String>>,
    val privateFiles: Map<String, ByteArray> = emptyMap()
) { override fun toString(): String = "SourceLegacySession(migrationId=$migrationId, sourceId=$sourceId, data=<已隐藏>)" }
enum class SourceMigrationStatus { IMPORTED, ALREADY_IMPORTED, EXISTING_SESSION_PRESERVED }
data class SourceMigrationReceipt(val migrationId: String, val status: SourceMigrationStatus)
data class SourceLoginMethod(val id: String, val label: String)
class SourceAuthChallenge(val id: String, val kind: String, val message: String,
    val displayUrl: String? = null, val imageBase64: String? = null, val payload: SourcePayload = SourcePayload()) {
    override fun toString(): String = "SourceAuthChallenge(kind=$kind, content=<已隐藏>)"
}
enum class SourceAuthProgress { WAITING, CONFIRMATION_REQUIRED, VERIFICATION_REQUIRED, SUCCEEDED, EXPIRED }
data class SourceAuthResult(val progress: SourceAuthProgress, val message: String,
    val challenge: SourceAuthChallenge? = null, val account: SourceAccount? = null)
interface SourceAuthentication {
    val methods: List<SourceLoginMethod>
    fun currentAccount(): SourceAccountState
    suspend fun begin(methodId: String, input: Map<String, String>): SourceAuthChallenge
    suspend fun continueAuthentication(challenge: SourceAuthChallenge, input: Map<String, String>): SourceAuthResult
    suspend fun refreshAccount(): SourceAccountState
    suspend fun logout()
    fun exportSession(): SourceSessionSnapshot?
    // 持久化成功后才返回收据；实现必须幂等，空白输入不得覆盖有效会话。
    fun importLegacySession(session: SourceLegacySession): SourceMigrationReceipt
}
```

<a id="sdk-sourcebasiccomponents"></a>

### SourceBasicComponents.kt

对应源码：[SourceBasicComponents.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceBasicComponents.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可选的页面玻璃采样句柄，内部图层不进入音源接口。 */
interface SourceBackdropState { val sourceId: String }

/** 页面组合用的基础展示控件，保持宿主已有的封面与歌曲角标策略。 */
interface SourceBasicComponents {
    @Composable fun rememberBackdropState(key: String): SourceBackdropState
    @Composable fun Backdrop(state: SourceBackdropState, modifier: Modifier = Modifier, content: @Composable () -> Unit)
    @Composable fun SearchButton(backdrop: SourceBackdropState? = null, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun SettingsButton(backdrop: SourceBackdropState? = null, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun Artwork(artwork: SourceArtwork, modifier: Modifier = Modifier, cornerDp: Float = 16f,
        circular: Boolean = false, markSizeSp: Float = 24f)
    @Composable fun SongTitle(track: SourceTrackDetails, modifier: Modifier = Modifier,
        marquee: Boolean = false, showBadges: Boolean = true, onDark: Boolean = false)
    @Composable fun TrackBadges(track: SourceTrackDetails, onDark: Boolean = false)
    @Composable fun PlaybackActions(collection: SourceCollection, playable: Boolean,
        selectedSortId: String? = null, onPlay: () -> Unit, onShuffle: () -> Unit, onSort: (String) -> Unit = {})
    @Composable fun ActionButton(title: String, enabled: Boolean = true, primary: Boolean = true,
        modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun ActionRow(title: String, subtitle: String? = null, enabled: Boolean = true, onClick: () -> Unit)
    @Composable fun FormField(label: String, value: String, enabled: Boolean = true, onChange: (String) -> Unit)
    @Composable fun SettingsGroup(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit)
}
```

<a id="sdk-sourcebehavior"></a>

### SourceBehavior.kt

对应源码：[SourceBehavior.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceBehavior.kt)

```kotlin
package com.musicone.source.sdk

/** 可选播放策略；未实现此接口时，宿主使用默认值。 */
interface SourcePlaybackBehavior {
    val renewOnPlayerErrorLimit: Int get() = 0
    val skipOnPlayerError: Boolean get() = true
    val pauseOnFailure: Boolean get() = false
}

/** 可选歌词交接和刷新策略，不向已有提供者接口增加抽象成员。 */
interface SourceLyricBehavior {
    val waitForSingleSwitchSettlement: Boolean get() = false
    val preserveVerbatimFallback: Boolean get() = false
}
```

<a id="sdk-sourcecachedartworkcomponents"></a>

### SourceCachedArtworkComponents.kt

对应源码：[SourceCachedArtworkComponents.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceCachedArtworkComponents.kt)

```kotlin
package com.musicone.source.sdk

/** 可选的冷启动封面准备入口，只解码宿主已有缓存，未命中不联网。 */
interface SourceCachedArtworkComponents {
    suspend fun prepareCachedArtwork(artworks: List<SourceArtwork>, maxSidePx: Int = 768)
}
```

<a id="sdk-sourcecatalogservices"></a>

### SourceCatalogServices.kt

对应源码：[SourceCatalogServices.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceCatalogServices.kt)

```kotlin
package com.musicone.source.sdk

data class SourceHomeSection(val id: String, val title: String, val tracks: List<SourceTrackDetails> = emptyList(),
    val collections: List<SourceCollection> = emptyList(), val entities: List<SourceEntity> = emptyList(),
    val payload: SourcePayload = SourcePayload())
data class SourceRecentSnapshot(val tracks: List<SourceTrackDetails> = emptyList(), val totalTracks: Int = tracks.size,
    val collections: List<SourceCollection> = emptyList(), val totalCollections: Int = collections.size,
    val albums: List<SourceCollection> = emptyList(), val totalAlbums: Int = albums.size,
    val radios: List<SourceCollection> = emptyList())

/** 可选的独立每日推荐入口；旧音源不实现此接口时不改变其原有二进制接口。 */
interface SourceDailyRecommendations {
    /** 返回本次完整推荐列表，数量由音源决定；公共模板不额外截断。 */
    suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails>
}

/** 音源声明容器身份和跨页合并规则，宿主不解释平台私有货架编号。 */
data class SourceHomePresentation(val stableId: String, val mergePages: Boolean = false,
    val recommendation: Boolean = false, val groupId: String? = null, val groupLimit: Int? = null)
interface SourceHomeSectionPresentation {
    fun homePresentation(section: SourceHomeSection): SourceHomePresentation
}

interface SourceDiscovery {
    suspend fun home(request: SourceCursorRequest): SourcePage<SourceHomeSection>
    val supportsSimilar: Boolean get() = false
    val supportsRecent: Boolean get() = false
    suspend fun similar(track: SourceTrackDetails, request: SourceCursorRequest): SourcePage<SourceTrackDetails> = throw UnsupportedOperationException("不支持相似推荐")
    suspend fun similarSection(track: SourceTrackDetails, request: SourceCursorRequest): SourceHomeSection =
        SourceHomeSection("similar", "相似推荐", tracks = similar(track, request).items)
    suspend fun recent(request: SourceCursorRequest): SourcePage<SourceTrackDetails> = throw UnsupportedOperationException("不支持最近播放")
    suspend fun recentSnapshot(): SourceRecentSnapshot = SourceRecentSnapshot(tracks = recent(SourceCursorRequest()).items)
    suspend fun reportPlayed(track: SourceTrackDetails, playedMs: Long) {}
}
enum class SourceSearchKind { TRACK, COLLECTION, ARTIST, ALBUM, CONTENT }
data class SourceSearchResult(val tracks: List<SourceTrackDetails> = emptyList(),
    val collections: List<SourceCollection> = emptyList(), val entities: List<SourceEntity> = emptyList(),
    val contents: List<SourceContent> = emptyList(), val nextCursor: String? = null)
interface SourceSearch {
    val kinds: Set<SourceSearchKind>
    suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult
    val supportsSuggestions: Boolean get() = false
    suspend fun suggestions(query: String): List<String> = throw UnsupportedOperationException("不支持搜索建议")
}
interface SourceEntities {
    val kinds: Set<String>
    fun supportsTrackSearch(reference: SourceEntityRef): Boolean = false
    fun trackSortOptions(reference: SourceEntityRef): List<SourceCollectionSortOption> = emptyList()
    suspend fun searchTracks(reference: SourceEntityRef, query: String, sortId: String?, request: SourceCursorRequest): SourcePage<SourceTrackDetails> =
        throw UnsupportedOperationException("不支持实体内搜索")
    suspend fun entity(reference: SourceEntityRef): SourceEntity
    suspend fun tracks(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceTrackDetails>
    suspend fun collections(reference: SourceEntityRef, request: SourceCursorRequest): SourcePage<SourceCollection>
}
enum class SourceLibrarySection { FAVORITE_TRACKS, CREATED_COLLECTIONS, SAVED_COLLECTIONS }
data class SourceCollectionSortOption(val id: String, val label: String)
interface SourceCollections {
    fun sortOptions(collection: SourceCollection): List<SourceCollectionSortOption> = emptyList()
    fun selectedSort(collection: SourceCollection): String? = null
    fun selectSort(collection: SourceCollection, optionId: String) { error("此音源未提供歌单排序") }
    val librarySections: Set<SourceLibrarySection> get() = emptySet()
    suspend fun library(section: SourceLibrarySection, request: SourceCursorRequest): SourcePage<SourceCollection> = throw UnsupportedOperationException("不支持此资料库分区")
    suspend fun detail(collection: SourceCollection): SourceCollection
    suspend fun tracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails>
}
interface SourceFavorites {
    val supportsCollections: Boolean get() = false
    suspend fun favoriteTrackIds(): Set<String>? = null
    suspend fun isTrackFavorite(track: SourceTrackDetails): Boolean
    suspend fun setTrackFavorite(track: SourceTrackDetails, favorite: Boolean)
    suspend fun setTracksFavorite(tracks: List<SourceTrackDetails>, favorite: Boolean) {
        tracks.forEach { setTrackFavorite(it, favorite) }
    }
    suspend fun setCollectionFavorite(collection: SourceCollection, favorite: Boolean): Unit =
        throw UnsupportedOperationException("不支持收藏歌单")
}
data class SourceCollectionEdit(val title: String, val description: String = "", val artworkUri: String? = null)
enum class SourceCollectionOperation { CREATE, EDIT, DELETE, CHANGE_TRACKS, REORDER }
interface SourceCollectionEditing {
    val supportedOperations: Set<SourceCollectionOperation> get() = emptySet()
    suspend fun writableCollections(): List<SourceCollection>
    suspend fun create(edit: SourceCollectionEdit): SourceCollection = throw UnsupportedOperationException("不支持创建歌单")
    suspend fun edit(collection: SourceCollection, edit: SourceCollectionEdit): SourceCollection = throw UnsupportedOperationException("不支持编辑歌单")
    suspend fun delete(collection: SourceCollection): Unit = throw UnsupportedOperationException("不支持删除歌单")
    suspend fun changeTracks(collection: SourceCollection, tracks: List<SourceTrackDetails>, add: Boolean): Unit = throw UnsupportedOperationException("不支持编辑歌单曲目")
    suspend fun reorder(collection: SourceCollection, orderedTrackIds: List<String>): Unit = throw UnsupportedOperationException("不支持远端排序")
}
data class SourceRadioBatch(val tracks: List<SourceTrackDetails>, val continuation: SourcePayload = SourcePayload())
interface SourceRadio {
    suspend fun next(previous: SourcePayload?): SourceRadioBatch
    suspend fun feedback(track: SourceTrackDetails, action: String)
}
```

<a id="sdk-sourcecollectioncachepolicy"></a>

### SourceCollectionCachePolicy.kt

对应源码：[SourceCollectionCachePolicy.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceCollectionCachePolicy.kt)

```kotlin
package com.musicone.source.sdk

/** 可选账号缓存目录规则；由集合服务额外实现，不改变已有集合服务的二进制接口。 */
interface SourceCollectionCachePolicy {
    /** 仅筛选已取得的目录项，不发起网络请求；未实现时保留已有快照。 */
    fun includesInCacheLibrary(collection: SourceCollection): Boolean
}
```

<a id="sdk-sourcecollectionreturnbehavior"></a>

### SourceCollectionReturnBehavior.kt

对应源码：[SourceCollectionReturnBehavior.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceCollectionReturnBehavior.kt)

```kotlin
package com.musicone.source.sdk

/** 可选集合返回策略；部分服务返回专辑页也需要同步账号资料库。 */
interface SourceCollectionReturnBehavior {
    val refreshLibraryAfterAnyReturn: Boolean get() = false
}
```

<a id="sdk-sourcecomponentui"></a>

### SourceComponentUi.kt

对应源码：[SourceComponentUi.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceComponentUi.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可组合公共控件。作用域绑定当前音源，生命周期检查由运行时注入。 */
interface SourceComponentUi {
    fun forSource(sourceId: String, checkAccess: () -> Unit = {}): SourceComponentScope
}

/** 宿主持有内部选择和搜索状态，音源只使用公开状态与操作。 */
interface SourceTrackListState {
    val sourceId: String
    var query: String
    var selectedSortId: String?
    val selectionActive: Boolean
    val selectedTrackIds: Set<String>
    fun beginSelection(trackId: String)
    fun toggleSelection(trackId: String)
    fun clearSelection()
}

/** 所有数据必须属于 sourceId；回调仍由调用页面决定导航、播放和资料刷新。 */
interface SourceComponentScope : SourceTrackComponents, SourceHomeComponents, SourceLibraryComponents,
    SourceBasicComponents, SourceSupplementalComponents

interface SourceTrackComponents {
    val sourceId: String
    @Composable fun rememberTrackListState(key: String): SourceTrackListState
    @Composable fun TrackListHost(collection: SourceCollection, state: SourceTrackListState,
        tracks: List<SourceTrackDetails>, bottomInsetDp: Float = 0f, onChanged: () -> Unit = {}, content: @Composable () -> Unit)
    @Composable fun TrackList(tracks: List<SourceTrackDetails>, state: SourceTrackListState,
        player: SourcePlayerState = SourcePlayerState(), favoriteIds: Set<String> = emptySet(),
        loading: Boolean = false, error: String? = null, modifier: Modifier = Modifier,
        loadAll: (suspend () -> List<SourceTrackDetails>)? = null, hasMore: Boolean = false, onLoadMore: () -> Unit = {},
        onClick: (SourceTrackDetails) -> Unit, onFavorite: (SourceTrackDetails) -> Unit)
    @Composable fun SelectionRow(track: SourceTrackDetails, state: SourceTrackListState,
        current: Boolean = false, playing: Boolean = false, favorite: Boolean = false,
        modifier: Modifier = Modifier, onClick: () -> Unit, onFavorite: () -> Unit)
    @Composable fun SelectionToolbar(state: SourceTrackListState, loadAll: (suspend () -> List<SourceTrackDetails>)? = null)
    @Composable fun SearchTools(state: SourceTrackListState, canLocate: Boolean = false,
        onLocate: () -> Unit = {}, modifier: Modifier = Modifier)
    @Composable fun SortButton(options: List<SourceCollectionSortOption>, selectedId: String?, onSelect: (String) -> Unit)

}

interface SourceHomeComponents {
    @Composable fun SimilarRecommendations(pages: List<SourceSimilarRecommendation>, baseTrack: SourceTrackDetails? = null,
        loading: Boolean = false, player: SourcePlayerState = SourcePlayerState(), modifier: Modifier = Modifier,
        onClick: (SourceTrackDetails) -> Unit, onRefresh: () -> Unit)
    @Composable fun SongCard(track: SourceTrackDetails, title: String = "", current: Boolean = false,
        playing: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun SongGroup(title: String, tracks: List<SourceTrackDetails>,
        player: SourcePlayerState = SourcePlayerState(), modifier: Modifier = Modifier, onClick: (SourceTrackDetails) -> Unit)
    @Composable fun SongShelf(id: String, title: String, pages: List<List<SourceTrackDetails>>,
        player: SourcePlayerState = SourcePlayerState(), showFavorites: Boolean = true,
        modifier: Modifier = Modifier, onClick: (SourceTrackDetails) -> Unit)
    @Composable fun DailyCard(collection: SourceCollection, date: String? = null, loading: Boolean = false,
        message: String? = null, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun RadioCard(active: Boolean, playing: Boolean, loading: Boolean = false,
        visible: Boolean = true, label: String = "猜你喜欢", modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun PersonalizedCards(collection: SourceCollection, date: String? = null,
        loading: Boolean = false, message: String? = null, radioActive: Boolean = false,
        radioPlaying: Boolean = false, radioLoading: Boolean = false, radioVisible: Boolean = true,
        modifier: Modifier = Modifier, onDailyClick: () -> Unit, onRadioClick: () -> Unit)
    @Composable fun RecentPlay(snapshot: SourceRecentSnapshot, signedIn: Boolean, loading: Boolean = false,
        message: String? = null, onOpen: (SourceCollection) -> Unit)

}

/** 相似推荐的单页展示数据，不暴露宿主推荐缓存或请求模型。 */
data class SourceSimilarRecommendation(val baseTrack: SourceTrackDetails, val title: String, val tracks: List<SourceTrackDetails>)

interface SourceLibraryComponents {
    @Composable fun CollectionCard(collection: SourceCollection, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun CollectionRow(collection: SourceCollection, modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun RecommendationCard(collection: SourceCollection, widthDp: Float = 152f,
        modifier: Modifier = Modifier, onPlay: () -> Unit, onClick: () -> Unit)
    @Composable fun FeaturedCard(collection: SourceCollection, heightDp: Float = 382f, compact: Boolean = false,
        modifier: Modifier = Modifier, onPlay: () -> Unit, onClick: () -> Unit)
    @Composable fun CollectionShelf(collections: List<SourceCollection>, onPlay: (SourceCollection) -> Unit, onOpen: (SourceCollection) -> Unit)
    @Composable fun ProfileHeader(account: SourceAccount?, onLogin: () -> Unit)
    @Composable fun FavoritesRow(collection: SourceCollection?, signedIn: Boolean, onClick: () -> Unit)
    @Composable fun CollectionSegment(created: Boolean, createdCount: Int, collectedCount: Int, onCreated: () -> Unit, onCollected: () -> Unit)
    @Composable fun CollectionButton(saved: Boolean, updating: Boolean = false, onClick: () -> Unit)
    @Composable fun EntityRow(entity: SourceEntity, onClick: () -> Unit)
    @Composable fun EntitySummary(entity: SourceEntity, playable: Boolean = false, onPlay: () -> Unit = {})
    @Composable fun SectionTitle(title: String)
    @Composable fun Empty(message: String, modifier: Modifier = Modifier)
    @Composable fun InlineMessage(message: String, modifier: Modifier = Modifier)
    @Composable fun Loading()
    @Composable fun LoadMore(loading: Boolean, error: String? = null, hasMore: Boolean = false, onLoad: () -> Unit)
}
```

<a id="sdk-sourcecontentui"></a>

### SourceContentUi.kt

对应源码：[SourceContentUi.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceContentUi.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可选的公共页面与展示组件。专属页面可按需调用，不改变包的页面声明。 */
interface SourceContentUi {
    /** 完整公共页面；默认页面选择不限制包主动打开或嵌入公共页面。 */
    val pages: SourcePageUi? get() = null
    /** 可组合公共组件；宿主未提供此能力时返回空，调用方应提供兼容页面或标明所需宿主版本。 */
    val components: SourceComponentUi? get() = null

    /** 使用公共歌单完整体验，关闭后回到调用包的原页面。 */
    fun openCollection(collection: SourceCollection, onClosed: () -> Unit = {})

    @Composable fun CollectionCard(collection: SourceCollection, modifier: Modifier = Modifier,
        onClick: () -> Unit)
    @Composable fun RecommendationCollectionCard(collection: SourceCollection, modifier: Modifier = Modifier,
        widthDp: Float = 152f, onPlay: () -> Unit, onClick: () -> Unit)
    @Composable fun FeaturedCollectionCard(collection: SourceCollection, modifier: Modifier = Modifier,
        heightDp: Float = 382f, compact: Boolean = false, onPlay: () -> Unit, onClick: () -> Unit)
    @Composable fun ProfileHeader(sourceId: String, account: SourceAccount?, onLogin: () -> Unit)
    @Composable fun FavoritesRow(collection: SourceCollection?, signedIn: Boolean, onClick: () -> Unit)
    @Composable fun CollectionSegment(created: Boolean, createdCount: Int, collectedCount: Int,
        onCreated: () -> Unit, onCollected: () -> Unit)
    @Composable fun TrackMenuHost(sourceId: String, bottomInsetDp: Float = 0f, content: @Composable () -> Unit)
    @Composable fun TrackRow(track: SourceTrackDetails, current: Boolean, playing: Boolean,
        favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, showSource: Boolean = true)
}
```

<a id="sdk-sourcecontract"></a>

### SourceContract.kt

对应源码：[SourceContract.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceContract.kt)

```kotlin
package com.musicone.source.sdk

import android.content.Context
import androidx.compose.runtime.Composable
import java.io.File
import kotlinx.coroutines.flow.StateFlow

object SourceSdk {
    const val API_VERSION = 1
    const val KOTLIN_VERSION = "2.3.21"
    const val COMPOSE_VERSION = "1.11.4"
}

enum class SourceKind(val extension: String) { MAIN("mscm"), SUPPLEMENTAL("mscp") }
enum class SourceCapability {
    PLAYBACK, HOME, SEARCH, ACCOUNT, PLAYLIST, ARTIST, ALBUM, RADIO,
    CONTENT, CHILD_ITEMS, REMOTE_COLLECTIONS, IMPORT, SUBSCRIPTION, LYRICS, SETTINGS
}

data class SourcePermissions(
    val editTrackMetadata: Boolean = false,
    val replaceManualLyrics: Boolean = false,
    val adjustLineOffset: Boolean = false
)

data class SourcePresentation(
    val pageColorArgb: Long? = null,
    val radioColorArgb: Long? = null,
    val radioLabel: String = "猜你喜欢",
    val lineLeadMs: Long = 0,
    val wordLeadMs: Long = 0,
    val radioPaletteLight: List<Long> = emptyList(),
    val radioPaletteDark: List<Long> = emptyList()
)

data class SourceDescriptor(
    val id: String,
    val name: String,
    val kind: SourceKind,
    val versionCode: Long,
    val versionName: String,
    val capabilities: Set<SourceCapability>,
    // 仅决定默认内容页面由谁组织，不限制公开页面和组件的按需复用。
    val useCustomPages: Boolean = false,
    val requiresLogin: Boolean = false,
    val permissions: SourcePermissions = SourcePermissions(),
    val presentation: SourcePresentation = SourcePresentation()
)

data class TrackIdentity(val sourceId: String, val accountId: String?, val remoteId: String)
data class SourceTrack(
    val identity: TrackIdentity,
    val title: String,
    val artists: List<String> = emptyList(),
    val artworkUrl: String? = null,
    val opaqueData: Map<String, String> = emptyMap()
)
data class SourcePage<T>(val items: List<T>, val nextCursor: String?)
data class PlaybackAddress(
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val alternatives: List<String> = emptyList(),
    val mimeType: String? = null,
    val cacheIdentity: String,
    val expiresAtEpochMs: Long? = null
)

// 失败通过异常返回；取消不得转换成空页或分页结束。
interface SourcePlayback { suspend fun resolve(track: SourceTrack, quality: String?): PlaybackAddress }
interface SourceCatalog {
    suspend fun tracks(page: SourceRoute, cursor: String?): SourcePage<SourceTrack>
}
data class SourceRoute(val page: String = "home", val arguments: Map<String, String> = emptyMap())
data class SourcePlayerState(val sourceId: String? = null, val stableTrackId: String? = null, val isPlaying: Boolean = false)
data class SourcePageState(val page: String = "home", val title: String? = null, val atRoot: Boolean = true)
interface SourceHost {
    fun play(tracks: List<SourceTrack>, startIndex: Int = 0)
    fun importTracks(tracks: List<SourceTrack>, destinationId: String)
    fun exitSource()
    fun reportError(message: String)
    val library: SourceLibraryHost? get() = null
    val cache: SourceCacheHost? get() = null
    val ui: SourceUiHost? get() = null
    // 宿主未提供此能力时，包可使用自己的页面；此能力与 useCustomPages 相互独立。
    val contentUi: SourceContentUi? get() = null
    val playerState: StateFlow<SourcePlayerState>? get() = null
    val reduceMotion: Boolean get() = false
    fun playDetailed(tracks: List<SourceTrackDetails>, startIndex: Int = 0) { play(tracks.map { it.track }, startIndex) }
    fun enqueueDetailed(tracks: List<SourceTrackDetails>, playNext: Boolean = false) { error("宿主未提供队列操作") }
    // 首页推荐歌曲点击：由宿主按用户设置决定开电台或插入当前队列。调用方须声明 minHostVersionCode ≥ 5。
    fun playRecommendation(track: SourceTrackDetails) { playDetailed(listOf(track)) }
    fun prepareSourceStorage(sourceId: String, context: Context) {}
    // 包启动的独立任务须登记取消回调；移除后不能继续提交账号、分页或资料结果。
    fun registerTask(cancel: () -> Unit): AutoCloseable = AutoCloseable { }
}
// 包私有持久化必须向后兼容；更新、降级及手动回退共享稳定目录，不能重置账号或用户资料。
// 宿主公共资料通过 SourceHost 写入；初始化验证使用独立临时目录且拒绝宿主业务操作。
data class SourceEnvironment(
    val context: Context,
    val dataDirectory: File,
    val cacheDirectory: File,
    val host: SourceHost,
    val validationOnly: Boolean
)

// 包只能处理内部导航；返回 false 时将返回交给宿主的应用级导航。
interface SourceEntry {
    val descriptor: SourceDescriptor
    val visuals: SourceVisuals? get() = null
    val pageState: StateFlow<SourcePageState>? get() = null
    val playback: SourcePlayback? get() = null
    val catalog: SourceCatalog? get() = null
    val authentication: SourceAuthentication? get() = null
    val authenticationPage: SourceAuthenticationPage? get() = null
    val discovery: SourceDiscovery? get() = null
    val search: SourceSearch? get() = null
    val entities: SourceEntities? get() = null
    val collections: SourceCollections? get() = null
    val favorites: SourceFavorites? get() = null
    val collectionEditing: SourceCollectionEditing? get() = null
    val radio: SourceRadio? get() = null
    val contents: SourceContents? get() = null
    val childItems: SourceChildItems? get() = null
    val remoteCollections: SourceRemoteCollections? get() = null
    val importing: SourceImporting? get() = null
    val subscriptions: SourceSubscriptions? get() = null
    val lyricProvider: SourceLyricProvider? get() = null
    val trackTheme: SourceTrackTheme? get() = null
    val mediaPlayback: SourceMediaPlayback? get() = null
    val settings: SourceSettings? get() = null
    val implementedCapabilities: Set<SourceCapability>
    fun initialize(environment: SourceEnvironment)
    fun onBack(): Boolean = false
    fun close()
    @Composable fun Content(route: SourceRoute)
}
```

<a id="sdk-sourceentityartwork"></a>

### SourceEntityArtwork.kt

对应源码：[SourceEntityArtwork.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceEntityArtwork.kt)

```kotlin
package com.musicone.source.sdk

/** 可选的轻量图片查询能力，避免为了菜单头像加载完整歌手页。 */
interface SourceEntityArtwork {
    suspend fun artwork(reference: SourceEntityRef): SourceArtwork
}

/** 实体图片由此可选接口提供，不向 SourceEntry 增加抽象成员。 */
suspend fun SourceEntities.artwork(reference: SourceEntityRef): SourceArtwork =
    (this as? SourceEntityArtwork)?.artwork(reference) ?: entity(reference).artwork
```

<a id="sdk-sourcehomecontinuation"></a>

### SourceHomeContinuation.kt

对应源码：[SourceHomeContinuation.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceHomeContinuation.kt)

```kotlin
package com.musicone.source.sdk

/** 可选的首页暂存提示；只读取已获取的卡池，不发起请求或准备卡片。 */
interface SourceHomeContinuation {
    /** 游标对应的下一批已在包内暂存时返回 true；未知、过期或仅有远端下一页时返回 false。 */
    fun hasBufferedHomeItems(cursor: String): Boolean
}
```

<a id="sdk-sourcehomeshelfcomponents"></a>

### SourceHomeShelfComponents.kt

对应源码：[SourceHomeShelfComponents.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceHomeShelfComponents.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可选横向货架能力：视口延伸到页面边缘，标题与首尾内容保留指定边距。 */
interface SourceHomeShelfComponents {
    @Composable fun SongShelf(id: String, title: String, pages: List<List<SourceTrackDetails>>,
        player: SourcePlayerState = SourcePlayerState(), showFavorites: Boolean = true,
        edgeInsetDp: Float = 0f, modifier: Modifier = Modifier, onClick: (SourceTrackDetails) -> Unit)

    @Composable fun CollectionShelf(collections: List<SourceCollection>, edgeInsetDp: Float = 0f,
        onPlay: (SourceCollection) -> Unit, onOpen: (SourceCollection) -> Unit)
}
```

<a id="sdk-sourcehostservices"></a>

### SourceHostServices.kt

对应源码：[SourceHostServices.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceHostServices.kt)

```kotlin
package com.musicone.source.sdk

data class SourceLocalCollection(val id: String, val title: String, val acceptsManualTracks: Boolean)
// null 保持现有覆盖，空字符串恢复原资料。
data class SourceTrackMetadataEdit(val title: String? = null, val artists: String? = null, val album: String? = null,
    val artworkUri: String? = null)
interface SourceLibraryHost {
    suspend fun createCollection(title: String): SourceLocalCollection = throw UnsupportedOperationException("宿主未提供新建歌单")
    suspend fun destinations(): List<SourceLocalCollection>
    suspend fun importTracks(tracks: List<SourceTrackDetails>, destinationId: String)
    suspend fun editMetadata(track: SourceTrackDetails, edit: SourceTrackMetadataEdit)
    suspend fun setManualLyrics(track: SourceTrackDetails, documentUri: String?)
    // 用户输入正数表示延后，不改变包的默认整行／逐字提前量。
    suspend fun setLineOffset(track: SourceTrackDetails, offsetMs: Long)
    suspend fun lineOffset(track: SourceTrackDetails): Long = 0
    suspend fun subscriptionRevision(identity: SourceSubscriptionIdentity): Long
    suspend fun commitSubscription(identity: SourceSubscriptionIdentity, expectedRevision: Long, items: List<SourceSubscriptionItem>)
    suspend fun removeSubscription(identity: SourceSubscriptionIdentity, keepTracks: Boolean)
}
data class SourceCacheEntry(val stableTrackId: String, val complete: Boolean, val mediaUri: String?,
    val mimeType: String?, val recovery: SourcePayload = SourcePayload())
interface SourceCacheHost {
    suspend fun lookup(track: SourceTrackDetails, qualityId: String?): SourceCacheEntry?
    suspend fun download(track: SourceTrackDetails, resource: SourcePlaybackResource)
    suspend fun cancel(track: SourceTrackDetails)
}
data class SourceUiRequest(val kind: String, val arguments: Map<String, String> = emptyMap())
data class SourceUiResult(val cancelled: Boolean, val values: Map<String, String> = emptyMap())
interface SourceUiHost {
    // 文档选择、封面选择／裁剪、外部验证页等由宿主实现；取消应返回 cancelled=true。
    suspend fun request(request: SourceUiRequest): SourceUiResult
    fun openDestination(destination: String, arguments: Map<String, String> = emptyMap())
}
interface SourceSettings { @androidx.compose.runtime.Composable fun Content() }
interface SourceAuthenticationPage { @androidx.compose.runtime.Composable fun Content() }
```

<a id="sdk-sourcelibraryfeatures"></a>

### SourceLibraryFeatures.kt

对应源码：[SourceLibraryFeatures.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceLibraryFeatures.kt)

```kotlin
package com.musicone.source.sdk

import kotlinx.coroutines.flow.Flow

/** 可选能力，不改变旧音源接口、集合身份或临时集合的分页语义。 */
interface SourceCollectionFavoritePolicy {
    fun canFavorite(collection: SourceCollection): Boolean = true
    /** 同一账号内的等价收藏键；宿主只比较交集，不解释平台编号。 */
    fun favoriteIdentityKeys(collection: SourceCollection): Set<String> = setOf(collection.stableId)
}

data class SourceLibraryChange(val accountId: String, val sections: Set<SourceLibrarySection>)
interface SourceLibraryChanges { val libraryChanges: Flow<SourceLibraryChange> }

enum class SourceCollectionEditField { DESCRIPTION, ARTWORK }
/** description=null 表示保留，空字符串明确表示清空；artworkUri=null 表示保留封面。 */
data class SourceCollectionDetailsEdit(val title: String, val description: String? = null, val artworkUri: String? = null)
interface SourceCollectionDetailsEditing {
    val editableFields: Set<SourceCollectionEditField>
    suspend fun createDetails(edit: SourceCollectionDetailsEdit): SourceCollection
    suspend fun editDetails(collection: SourceCollection, edit: SourceCollectionDetailsEdit): SourceCollection
}

/** 云端顺序编辑独立于本地显示排序和播放队列；读取必须完整分页并保持服务器顺序。 */
interface SourceCollectionRemoteOrder {
    fun supportsRemoteOrder(collection: SourceCollection): Boolean
    suspend fun orderedTracks(collection: SourceCollection, request: SourceCursorRequest): SourcePage<SourceTrackDetails>
}

interface SourceEntityFollowing {
    val supportsFollowing: Boolean get() = true
    suspend fun isFollowing(reference: SourceEntityRef): Boolean
    suspend fun setFollowing(reference: SourceEntityRef, following: Boolean)
    suspend fun followed(request: SourceCursorRequest): SourcePage<SourceEntity>
}

interface SourceHotSearch {
    val supportsHotSearch: Boolean get() = true
    suspend fun hotSearches(): List<String>
}
```

<a id="sdk-sourceloadedhomecomponents"></a>

### SourceLoadedHomeComponents.kt

对应源码：[SourceLoadedHomeComponents.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceLoadedHomeComponents.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 可选扩展：传入已加载的真实歌曲，原日推卡才能沿用完整的来源卡过渡。 */
interface SourceLoadedHomeComponents {
    @Composable fun DailyCard(collection: SourceCollection, tracks: List<SourceTrackDetails>,
        date: String? = null, loading: Boolean = false, message: String? = null,
        modifier: Modifier = Modifier, onClick: () -> Unit)
    @Composable fun PersonalizedCards(collection: SourceCollection, tracks: List<SourceTrackDetails>,
        date: String? = null, loading: Boolean = false, message: String? = null,
        radioActive: Boolean = false, radioPlaying: Boolean = false, radioLoading: Boolean = false,
        radioVisible: Boolean = true, modifier: Modifier = Modifier,
        onDailyClick: () -> Unit, onRadioClick: () -> Unit)
}
```

<a id="sdk-sourcelyricsandplayback"></a>

### SourceLyricsAndPlayback.kt

对应源码：[SourceLyricsAndPlayback.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceLyricsAndPlayback.kt)

```kotlin
package com.musicone.source.sdk

data class SourceLyricWord(val startMs: Long, val durationMs: Long, val text: String)
data class SourceLyricLine(val startMs: Long, val text: String, val words: List<SourceLyricWord> = emptyList(),
    val translation: String? = null, val transliteration: String? = null, val parserVersion: Int = 0)
data class SourceLyrics(val lines: List<SourceLyricLine>, val plainText: String? = null)
data class SourceLyricTiming(val lineLeadMs: Long = 0, val wordLeadMs: Long = 0)
interface SourceLyricProvider {
    val parserVersion: Int get() = 0
    val refreshPlainLyricsForVerbatim: Boolean get() = false
    suspend fun lyrics(track: SourceTrackDetails): SourceLyrics?
    fun timing(track: SourceTrackDetails): SourceLyricTiming
}
interface SourceTrackTheme { suspend fun colorArgb(track: SourceTrackDetails): Long? }
enum class SourcePlaybackPurpose { PLAYBACK, PREFETCH, CACHE }
data class SourcePlaybackRequest(val track: SourceTrackDetails, val qualityId: String? = null,
    val supportedMimeTypes: Set<String> = emptySet(), val deviceCapabilities: Set<String> = emptySet(),
    val automatic: Boolean = false, val purpose: SourcePlaybackPurpose = SourcePlaybackPurpose.PLAYBACK)
data class SourceMediaSegment(val url: String, val headers: Map<String, String> = emptyMap(),
    val byteRange: String? = null, val alternatives: List<String> = emptyList())
data class SourcePlaybackResource(
    val address: PlaybackAddress, val qualityId: String, val format: String,
    val inlineManifest: String? = null, val segments: List<SourceMediaSegment> = emptyList(),
    val recovery: SourcePayload = SourcePayload(), val accountId: String? = null,
    val bitRate: Int = 0, val actualFormat: String? = null, val trial: Boolean = false,
    val verificationPending: Boolean = false
)
interface SourceMediaPlayback {
    val defaultQualityId: String get() = "LOSSLESS"
    val followsMainQualityPreference: Boolean get() = false
    fun normalizePreferredQuality(savedQualityId: String?, mainQualityId: String?): String = savedQualityId ?: defaultQualityId
    fun fallbackQualityIds(preferredId: String): List<String> {
        val order = listOf("STANDARD", "EXHIGH", "LOSSLESS", "HI_RES", "DOLBY")
        val index = order.indexOf(preferredId).takeIf { it >= 0 } ?: 0
        return order.take(index + 1).asReversed()
    }
    suspend fun enrich(track: SourceTrackDetails): SourceTrackDetails = track
    suspend fun qualities(track: SourceTrackDetails): List<SourceAudioQuality>
    /** 逐档探测的包可在每确认一档后回报当前已确认的完整列表；默认查完后一次性回报。 */
    suspend fun qualities(track: SourceTrackDetails, onProgress: (List<SourceAudioQuality>) -> Unit): List<SourceAudioQuality> =
        qualities(track).also(onProgress)
    suspend fun resolve(request: SourcePlaybackRequest): SourcePlaybackResource
    suspend fun recover(request: SourcePlaybackRequest, previous: SourcePlaybackResource): SourcePlaybackResource
    suspend fun availability(tracks: List<SourceTrackDetails>): List<TrackAccessInfo>
}
```

<a id="sdk-sourcemedia"></a>

### SourceMedia.kt

对应源码：[SourceMedia.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceMedia.kt)

```kotlin
package com.musicone.source.sdk

data class SourcePayload(val format: String = "", val version: Int = 1, val encoded: String = "")
data class SourceEntityRef(val id: String, val name: String, val kind: String, val payload: SourcePayload = SourcePayload())
enum class TrackAccessBadge { MEMBERSHIP, PURCHASE }
class TrackAccessInfo(
    val badge: TrackAccessBadge? = null, val trialAvailable: Boolean = false,
    val playable: Boolean = true, val reason: String? = null
) {
    var displayBadge: SourceTrackBadge? = null
        private set

    // 保留 API 1 四参数构造及默认参数入口，新增展示声明不改变原调用签名。
    constructor(badge: TrackAccessBadge? = null, trialAvailable: Boolean = false,
        playable: Boolean = true, reason: String? = null, displayBadge: SourceTrackBadge?) :
        this(badge, trialAvailable, playable, reason) {
        this.displayBadge = displayBadge
    }

    // 原 data class 的解构与 copy 二进制入口保持原签名，并保留新增展示声明。
    operator fun component1(): TrackAccessBadge? = badge
    operator fun component2(): Boolean = trialAvailable
    operator fun component3(): Boolean = playable
    operator fun component4(): String? = reason
    fun copy(badge: TrackAccessBadge? = this.badge, trialAvailable: Boolean = this.trialAvailable,
        playable: Boolean = this.playable, reason: String? = this.reason): TrackAccessInfo =
        TrackAccessInfo(badge, trialAvailable, playable, reason, displayBadge)

    override fun equals(other: Any?): Boolean = other is TrackAccessInfo && badge == other.badge &&
        trialAvailable == other.trialAvailable && playable == other.playable &&
        reason == other.reason && displayBadge == other.displayBadge
    override fun hashCode(): Int {
        var result = badge?.hashCode() ?: 0
        result = 31 * result + trialAvailable.hashCode()
        result = 31 * result + playable.hashCode()
        result = 31 * result + (reason?.hashCode() ?: 0)
        return 31 * result + (displayBadge?.hashCode() ?: 0)
    }
    override fun toString(): String = "TrackAccessInfo(badge=$badge, trialAvailable=$trialAvailable, playable=$playable, reason=$reason, displayBadge=$displayBadge)"
}
data class SourceAudioQuality(val id: String, val label: String, val mimeType: String? = null,
    val available: Boolean = true, val reason: String? = null, val payload: SourcePayload = SourcePayload())
data class SourceArtwork(val url: String? = null, val startArgb: Long = 0, val endArgb: Long = 0, val mark: String = "")

// stableId 保留既有本地资料库 ID，不由包版本或 remoteId 重新生成。
data class SourceTrackDetails(
    val track: SourceTrack,
    val stableId: String,
    val album: String = "",
    val durationMs: Long = 0,
    val artwork: SourceArtwork = SourceArtwork(url = track.artworkUrl),
    val artists: List<SourceEntityRef> = emptyList(),
    val albumRef: SourceEntityRef? = null,
    val access: TrackAccessInfo = TrackAccessInfo(),
    val qualities: List<SourceAudioQuality> = emptyList(),
    val lyrics: SourceLyrics? = null,
    val localUri: String = "",
    val payload: SourcePayload = SourcePayload(),
    val artistsText: String = track.artists.joinToString(" / "),
    val previewUrl: String = "",
    val identityAliases: List<String> = emptyList()
)
data class SourceCollection(
    val identity: TrackIdentity, val stableId: String, val title: String,
    val subtitle: String = "", val description: String = "", val count: Int? = null,
    val artwork: SourceArtwork = SourceArtwork(), val owned: Boolean = false,
    val accountFavorites: Boolean = false, val payload: SourcePayload = SourcePayload()
)
data class SourceEntity(
    val reference: SourceEntityRef, val description: String = "", val artwork: SourceArtwork = SourceArtwork(),
    val attributes: Map<String, String> = emptyMap()
)
enum class SourceFetchPolicy { CACHE_FIRST, REFRESH }

// nextCursor=null 表示正常结束，异常表示失败；取消必须继续抛出，不能返回空页冒充成功。
data class SourceCursorRequest(val cursor: String? = null, val limit: Int = 50, val policy: SourceFetchPolicy = SourceFetchPolicy.CACHE_FIRST)
class SourceOperationException(val code: String, message: String, val retryable: Boolean = false) : Exception(message)
```

<a id="sdk-sourcemembership"></a>

### SourceMembership.kt

对应源码：[SourceMembership.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceMembership.kt)

```kotlin
package com.musicone.source.sdk

import java.util.concurrent.CancellationException

/** 由音源主动声明的可选会员检测；默认空值，不启用检测或联网。 */
interface SourceMembershipProvider {
    val membershipDetector: SourceMembershipDetector? get() = null
}

interface SourceMembershipDetector {
    /** 只读本地已知结果，不得在读取属性时联网；只有 true 表示已确认会员。 */
    val currentMembership: Boolean? get() = null

    /** 按当前账号检测，只有 true 表示已确认会员；无会员或未知返回 false 或 null。 */
    suspend fun detectMembership(): Boolean?
}

fun SourceEntry.membershipDetectorOrNull(): SourceMembershipDetector? =
    (this as? SourceMembershipProvider)?.membershipDetector

/** 未声明为 false；已声明但未确认会员为未知，不从旧账号摘要推断结果。 */
fun SourceEntry.currentMembership(): Boolean? {
    val detector = membershipDetectorOrNull() ?: return false
    return detector.currentMembership.takeIf { it == true }
}

/** 仅显式调用时检测；普通失败保持未知，取消不能转成会员结果。 */
suspend fun SourceEntry.detectMembership(): Boolean? = try {
    val detector = membershipDetectorOrNull()
    if (detector == null) false else detector.detectMembership().takeIf { it == true }
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    null
}
```

<a id="sdk-sourcemysectionpresentation"></a>

### SourceMySectionPresentation.kt

对应源码：[SourceMySectionPresentation.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceMySectionPresentation.kt)

```kotlin
package com.musicone.source.sdk

/** discovery 可选实现的我的页分区声明；未实现时保留公共模板原有分区。 */
interface SourceMySectionPresentation {
    val showRecentInMy: Boolean
}
```

<a id="sdk-sourcepageui"></a>

### SourcePageUi.kt

对应源码：[SourcePageUi.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourcePageUi.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 公共完整页面的公开请求。页面归属始终由请求决定，与宿主当前选择的音源无关。 */
sealed interface SourcePageRequest {
    val sourceId: String
    data class Home(override val sourceId: String) : SourcePageRequest
    data class My(override val sourceId: String) : SourcePageRequest
    data class Search(override val sourceId: String, val query: String = "", val submit: Boolean = false) : SourcePageRequest
    data class Collection(val collection: SourceCollection) : SourcePageRequest {
        override val sourceId get() = collection.identity.sourceId
    }
    data class Artist(override val sourceId: String, val artist: SourceEntity) : SourcePageRequest
    data class Album(override val sourceId: String, val album: SourceEntity) : SourcePageRequest
    data class DailyRecommendations(override val sourceId: String) : SourcePageRequest
    data class Recent(override val sourceId: String) : SourcePageRequest
    data class Similar(val track: SourceTrackDetails) : SourcePageRequest {
        override val sourceId get() = track.track.identity.sourceId
    }
    data class Supplemental(override val sourceId: String,
        val target: SourceSupplementalTarget = SourceSupplementalTarget.Home) : SourcePageRequest {
        init {
            val targetSource = when (target) {
                is SourceSupplementalTarget.Collection -> target.collection.identity.sourceId
                is SourceSupplementalTarget.Content -> target.content.identity.sourceId
                else -> sourceId
            }
            require(targetSource == sourceId) { "补充页面目标必须属于请求音源" }
        }
    }
    data class Authentication(override val sourceId: String) : SourcePageRequest
    data class Settings(override val sourceId: String) : SourcePageRequest
}

/** 补充模板可以直接进入既有目录、链接导入或内容子项目选择流程。 */
sealed interface SourceSupplementalTarget {
    data object Home : SourceSupplementalTarget
    data object Account : SourceSupplementalTarget
    data object Collections : SourceSupplementalTarget
    data class Link(val input: String = "") : SourceSupplementalTarget
    data class Collection(val collection: SourceRemoteCollection) : SourceSupplementalTarget
    data class Content(val content: SourceContent) : SourceSupplementalTarget
}

/** 自定义页面可以打开公共导航，也可以把完整公共页面嵌入自己的布局。 */
interface SourcePageUi {
    fun open(request: SourcePageRequest, onClosed: () -> Unit = {})
    /** onClose 仅请求调用方退出当前嵌入导航；业务完成与账号结果不得放在这个回调中提交。 */
    @Composable fun Content(request: SourcePageRequest, modifier: Modifier = Modifier,
        bottomInsetDp: Float = 0f, onClose: () -> Unit = {})
}
```

<a id="sdk-sourceplaybacksession"></a>

### SourcePlaybackSession.kt

对应源码：[SourcePlaybackSession.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourcePlaybackSession.kt)

```kotlin
package com.musicone.source.sdk

/** 由播放服务主动提供的可选能力；默认空值，不启用会话记录或回调。 */
interface SourcePlaybackSessionProvider {
    val playbackSessions: SourcePlaybackSessionObserver? get() = null
}

fun interface SourcePlaybackSessionObserver {
    /** 同一播放器内串行交付；网络、平台协议和结算频率由音源负责。 */
    suspend fun onPlaybackSession(event: SourcePlaybackSessionEvent)
}

enum class SourcePlaybackSessionEventType { START, PAUSE, RESUME, PROGRESS, END }
enum class SourcePlaybackSessionEndReason { COMPLETED, SWITCHED, STOPPED, ERROR, RELEASED }

/** playedMs 是本次会话累计的真实墙钟收听毫秒，不能当作每次新增量。 */
data class SourcePlaybackSessionEvent(
    val sessionId: String,
    val sequence: Long,
    val track: SourceTrackDetails,
    val type: SourcePlaybackSessionEventType,
    val playedMs: Long,
    val positionMs: Long,
    val occurredAtMs: Long,
    val endReason: SourcePlaybackSessionEndReason? = null,
)
```

<a id="sdk-sourceplayerpresentation"></a>

### SourcePlayerPresentation.kt

对应源码：[SourcePlayerPresentation.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourcePlayerPresentation.kt)

```kotlin
package com.musicone.source.sdk

/** 播放服务可选的展示策略；未实现时沿用宿主通用文案和封面行为。 */
interface SourcePlayerPresentation {
    fun qualityLabel(qualityId: String): String? = null
    fun qualityCompactLabel(qualityId: String): String? = null
    val pendingAudioDescription: String? get() = null
    val missingArtworkHoldMs: Long get() = 0
}
```

<a id="sdk-sourceplayerui"></a>

### SourcePlayerUi.kt

对应源码：[SourcePlayerUi.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourcePlayerUi.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/** 主音源可选的完整播放器页面；未提供该接口时由宿主显示播放器页面。 */
interface SourcePlayerUiProviderAccess {
    val playerUiProvider: SourcePlayerUiProvider?
}

fun SourceEntry.playerUiProviderOrNull(): SourcePlayerUiProvider? =
    (this as? SourcePlayerUiProviderAccess)?.playerUiProvider ?: this as? SourcePlayerUiProvider

/** 由主音源提供完整播放器界面，宿主负责挂载页面并提供受控能力。 */
interface SourcePlayerUiProvider {
    val playerUiDeclaration: SourcePlayerUiDeclaration
    @Composable fun Player(context: SourcePlayerUiContext)
}

data class SourcePlayerUiDeclaration(
    val supportsPhone: Boolean,
    val supportsTablet: Boolean,
    val supportsPhoneLandscape: Boolean,
    val opening: SourcePlayerOpening,
)

data class SourcePlayerOpening(
    val durationMillis: Int,
    val barParticipates: Boolean,
    val sharedElements: Set<SourcePlayerSharedElement>,
)

enum class SourcePlayerSharedElement { ARTWORK, TITLE, ARTIST, PLAY_PAUSE }
enum class SourcePlayerForm { PHONE_PORTRAIT, PHONE_LANDSCAPE, TABLET }

/** 已转换到页面朝向的绝对安全边距；内容按四边避让，背景仍可铺满页面。 */
data class SourcePlayerInsets(val left: Dp, val top: Dp, val right: Dp, val bottom: Dp)

data class SourcePlayerQuality(val id: String, val label: String, val compactLabel: String? = null)

data class SourcePlayerSnapshot(
    val track: SourceTrackDetails?,
    val isPlaying: Boolean,
    val shuffle: Boolean,
    val repeatMode: String,
    val qualities: List<SourcePlayerQuality>,
    val activeQualityId: String?,
    val qualityLoading: Boolean,
    val qualityChanging: Boolean,
    val errorMessage: String?,
    val favorite: Boolean = false,
)

/** 位置携带歌曲与播放代际；无歌曲时 trackId 为空字符串。 */
data class SourcePlayerTimeline(
    val trackId: String,
    val positionMs: Long,
    val durationMs: Long,
    val generation: Long,
    val advancing: Boolean,
)

data class SourcePlayerTransition(
    val progress: Float,
    val opening: Boolean,
    val interactive: Boolean,
    val reduceMotion: Boolean,
)

/** 仅提供状态与受宿主管理的动作，不暴露核心播放器或底部栏组件。 */
interface SourcePlayerUiContext {
    val snapshot: SourcePlayerSnapshot
    val timeline: SourcePlayerTimeline
    val form: SourcePlayerForm
    val safeDrawingInsets: SourcePlayerInsets
    val transition: SourcePlayerTransition
    val actions: SourcePlayerUiActions
    fun dismiss()
    fun onFailure(message: String)
    @Composable fun Lyrics(modifier: Modifier = Modifier)
    @Composable fun SharedElement(element: SourcePlayerSharedElement, modifier: Modifier = Modifier,
        content: @Composable () -> Unit)
}

interface SourcePlayerUiActions {
    fun togglePlay()
    fun next()
    fun previous()
    fun seekTo(trackId: String, generation: Long, positionMs: Long)
    fun cyclePlaybackMode()
    fun selectQuality(id: String)
    fun refreshQualities()
    fun openQueue()
    fun toggleFavorite()
    fun retryLyrics()
}
```

<a id="sdk-sourcequalitycatalog"></a>

### SourceQualityCatalog.kt

对应源码：[SourceQualityCatalog.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceQualityCatalog.kt)

```kotlin
package com.musicone.source.sdk

/** 可选音质目录，由 SourceMediaPlayback 的实现同时实现；未声明时使用宿主默认音质策略。 */
interface SourceQualityCatalog {
    val qualityCatalog: List<SourceQualityDefinition>
    val defaultCatalogQualityId: String
    /** 提供明确回退顺序；宿主先尝试首选并去重，未列出的其他档位不会自动加入。 */
    fun catalogFallbackQualityIds(preferredId: String): List<String>
}

/** id 在同一音源内保持稳定；多个 id 可以对应同一个标准能力档。 */
data class SourceQualityDefinition(
    val id: String,
    val label: String = "",
    val compactLabel: String = label,
    val description: String = "",
    val standardQualityId: String? = null,
)
```

<a id="sdk-sourcerootpageui"></a>

### SourceRootPageUi.kt

对应源码：[SourceRootPageUi.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceRootPageUi.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 自绘一级页可选接入宿主页头；不改变原公共页面及独立打开的导航契约。 */
interface SourceRootPageUi {
    /** 仅接受 Home／My；详情和搜索仍使用原 Content 或 open 入口。 */
    @Composable fun RootContent(request: SourcePageRequest, modifier: Modifier = Modifier,
        bottomInsetDp: Float = 0f, onClose: () -> Unit = {})

    /** 上报当前一级页的列表位置，不接管列表状态、回顶动作或滚动动效。 */
    @Composable fun ReportScroll(page: String, position: () -> SourceRootScrollPosition)
}

data class SourceRootScrollPosition(val firstVisibleItemIndex: Int, val firstVisibleItemScrollOffset: Int)
```

<a id="sdk-sourcesupplementalcomponents"></a>

### SourceSupplementalComponents.kt

对应源码：[SourceSupplementalComponents.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceSupplementalComponents.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 补充模板的独立控件。解析、分页、导入和订阅由音源通过公开事件处理。 */
interface SourceSupplementalComponents {
    @Composable fun LinkInput(value: String, resolving: Boolean = false, onChange: (String) -> Unit, onResolve: () -> Unit)
    @Composable fun ContentSummary(content: SourceContent)
    @Composable fun ChildSelection(children: List<SourceChildItem>, selected: Set<TrackIdentity>, enabled: Boolean = true,
        onToggle: (TrackIdentity) -> Unit, onSelectAll: () -> Unit, onClear: () -> Unit)
    @Composable fun ImportTarget(destinations: List<SourceLocalCollection>, selectedId: String?, onSelect: (SourceLocalCollection) -> Unit)
    @Composable fun ImportActions(selectedCount: Int, hasChildren: Boolean, busy: Boolean = false,
        canImport: Boolean = true, canPlay: Boolean = true, onImport: () -> Unit, onPlay: () -> Unit)
    @Composable fun SupplementalAccount(account: SourceAccount?, sourceName: String, onManage: () -> Unit)
    @Composable fun RemoteCollectionCard(collection: SourceRemoteCollection, loading: Boolean = false,
        artworkRevision: Long? = null, modifier: Modifier = Modifier, onOpen: () -> Unit, onPlay: () -> Unit)
    @Composable fun RemoteCollectionRow(collection: SourceRemoteCollection, onOpen: () -> Unit)
    @Composable fun RemoteCollectionHeader(collection: SourceRemoteCollection, sourceName: String,
        playing: Boolean = false, subscribing: Boolean = false, subscribed: Boolean = false,
        canPlay: Boolean = true, canSubscribe: Boolean = false, onPlay: () -> Unit,
        onSubscribe: () -> Unit = {}, onRefreshSubscription: () -> Unit = {}, onRemoveSubscription: () -> Unit = {})
}
```

<a id="sdk-sourcesupplementalpageprovider"></a>

### SourceSupplementalPageProvider.kt

对应源码：[SourceSupplementalPageProvider.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceSupplementalPageProvider.kt)

```kotlin
package com.musicone.source.sdk

import androidx.compose.runtime.Composable

/** API 1 补充音源按可选页面接口声明逐页页面策略。 */
object SourceSupplementalContract { const val API_VERSION = 1 }
enum class SourceSupplementalPage { HOME, LINK, CONTENT, COLLECTIONS, COLLECTION, ACCOUNT, SETTINGS }
enum class SourceSupplementalPageMode { DEFAULT, CUSTOM, DISABLED }
data class SourceSupplementalPagePolicy(
    val mode: SourceSupplementalPageMode = SourceSupplementalPageMode.DEFAULT,
    val allowPublicFallback: Boolean = false,
)

/** 守卫入口透明转发可选能力；空值表示未提供该能力。 */
interface SourceSupplementalPageProviderAccess {
    val supplementalPageProvider: SourceSupplementalPageProvider?
}
fun SourceEntry.supplementalPageProviderOrNull(): SourceSupplementalPageProvider? =
    (this as? SourceSupplementalPageProviderAccess)?.supplementalPageProvider ?: this as? SourceSupplementalPageProvider

interface SourceSupplementalPageProvider {
    fun supplementalPagePolicy(page: SourceSupplementalPage): SourceSupplementalPagePolicy = SourceSupplementalPagePolicy()
    /** 仅无登录账号的公开订阅使用。必须稳定、非空且与该源其他身份隔离；不会创建假账号。 */
    fun publicSubscriptionIdentity(): String? = null
    @Composable fun SupplementalPage(context: SourceSupplementalPageContext)
}

/** 自定义整页与公共整页共用业务规则；展示失败只由显式回调报告。 */
interface SourceSupplementalPageContext {
    val page: SourceSupplementalPage
    val target: SourceSupplementalTarget
    val bottomInsetDp: Float
    val actions: SourceSupplementalPageActions
    fun onBack()
    fun onFailure(message: String)
    fun openPublic(page: SourceSupplementalPage, target: SourceSupplementalTarget = SourceSupplementalTarget.Home)
    @Composable fun PublicContent(page: SourceSupplementalPage = this.page,
        target: SourceSupplementalTarget = this.target)
}

/** 动作沿用公共请求、选择、导入及订阅规则，异步结果通过当前页面状态显示。 */
interface SourceSupplementalPageActions {
    fun navigate(page: SourceSupplementalPage, target: SourceSupplementalTarget = SourceSupplementalTarget.Home)
    fun resolve(input: String)
    fun openContent(content: SourceContent)
    fun play(contents: List<SourceContent>)
    fun importContents(contents: List<SourceContent>, destinationId: String, destinationTitle: String)
    fun subscribe(collection: SourceRemoteCollection)
    fun refreshSubscription(collection: SourceRemoteCollection)
    fun removeSubscription(collection: SourceRemoteCollection)
}
```

<a id="sdk-sourcesupplementalservices"></a>

### SourceSupplementalServices.kt

对应源码：[SourceSupplementalServices.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceSupplementalServices.kt)

```kotlin
package com.musicone.source.sdk

data class SourceContent(val identity: TrackIdentity, val title: String, val description: String = "",
    val author: SourceEntityRef? = null, val artwork: SourceArtwork = SourceArtwork(),
    val durationMs: Long = 0, val access: TrackAccessInfo = TrackAccessInfo(), val payload: SourcePayload = SourcePayload(),
    val playableTrack: SourceTrackDetails? = null)
data class SourceChildItem(val identity: TrackIdentity, val index: Int, val title: String,
    val durationMs: Long = 0, val payload: SourcePayload = SourcePayload(), val initiallySelected: Boolean = false)
interface SourceContents {
    suspend fun resolve(input: String): SourceContent
    suspend fun detail(content: SourceContent): SourceContent
}
interface SourceChildItems {
    suspend fun children(content: SourceContent, request: SourceCursorRequest): SourcePage<SourceChildItem>
}
data class SourceRemoteCollection(val identity: TrackIdentity, val title: String, val count: Int? = null,
    val artwork: SourceArtwork = SourceArtwork(), val payload: SourcePayload = SourcePayload())
interface SourceRemoteCollections {
    suspend fun collections(request: SourceCursorRequest): SourcePage<SourceRemoteCollection>
    suspend fun contents(collection: SourceRemoteCollection, query: String?, request: SourceCursorRequest): SourcePage<SourceContent>
}
/** 可选集合封面能力；宿主只为可见卡片请求，失败或空图时保留已显示封面。 */
interface SourceRemoteCollectionArtwork {
    suspend fun artwork(collection: SourceRemoteCollection, policy: SourceFetchPolicy): SourceArtwork
}
interface SourceImporting {
    suspend fun tracks(content: SourceContent, selectedChildren: List<SourceChildItem>): List<SourceTrackDetails>
}
data class SourceSubscriptionIdentity(val sourceId: String, val accountId: String,
    val remoteCollectionId: String, val localCollectionId: String)
data class SourceSubscriptionItem(val remoteMemberId: String, val tracks: List<SourceTrackDetails>, val position: Long)
interface SourceSubscriptions {
    // 宿主收齐全部成功分页后原子提交；失败或取消保留旧内容、手动成员和资料覆盖。
    suspend fun page(collection: SourceRemoteCollection, request: SourceCursorRequest): SourcePage<SourceSubscriptionItem>
}
```

<a id="sdk-sourcetrackbadge"></a>

### SourceTrackBadge.kt

对应源码：[SourceTrackBadge.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceTrackBadge.kt)

```kotlin
package com.musicone.source.sdk

/** 歌曲名称旁的展示角标。颜色为可空的 0xAARRGGBB；空值沿用宿主默认配色。 */
data class SourceTrackBadge(
    val text: String,
    val lightTextArgb: Long? = null,
    val lightBackgroundArgb: Long? = null,
    val darkTextArgb: Long? = null,
    val darkBackgroundArgb: Long? = null,
)
```

<a id="sdk-sourcevisuals"></a>

### SourceVisuals.kt

对应源码：[SourceVisuals.kt](../source-sdk/src/main/java/com/musicone/source/sdk/SourceVisuals.kt)

```kotlin
package com.musicone.source.sdk

import kotlinx.coroutines.flow.StateFlow

/** 背景由音源声明，宿主只消费可读 URI 与公开视觉参数，不探查包私有路径。 */
data class SourceVisualState(val backgroundUri: String? = null, val revision: Long = 0,
    val foregroundArgb: Long? = null, val customBackground: Boolean = false)

interface SourceVisuals {
    val supportsCustomBackground: Boolean get() = false
    // 全局使用 home，歌单使用 playlist 与稳定 id 参数；状态必须在背景更新后发布。
    fun observe(route: SourceRoute = SourceRoute()): StateFlow<SourceVisualState>
    // 输入为宿主已裁剪图片的可读 URI；null 恢复默认，成功返回后 observe 必须反映新状态。
    suspend fun setCustomBackground(route: SourceRoute, croppedImageUri: String?) {
        throw UnsupportedOperationException("此音源未开放背景设置")
    }
}
```
