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
    // 只决定默认内容页面由谁渲染，不影响按需复用公共页面和组件。
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

// 失败直接抛异常；协程取消时照常抛出，不要转成空页或当成最后一页。
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
    // 首页货架点歌（带上同一页的歌曲）：开启或已在猜你喜欢时从 startIndex 起按顺序入队，上面的不入队；
    // 用户开着“保持队列”时只插入点击的一首。调用方须声明 minHostVersionCode ≥ 6。
    fun playRecommendations(tracks: List<SourceTrackDetails>, startIndex: Int) { playRecommendation(tracks[startIndex]) }
    fun prepareSourceStorage(sourceId: String, context: Context) {}
    // 包启动的独立任务须登记取消回调；移除后不能继续提交账号、分页或资料结果。
    fun registerTask(cancel: () -> Unit): AutoCloseable = AutoCloseable { }
}
// 音源私有存储需保持向后兼容；升级、降级或回退都共用同一目录，不能清空账号或用户数据。
// 宿主公共数据通过 SourceHost 写入；校验初始化时会用独立临时目录，且禁止宿主业务操作。
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
