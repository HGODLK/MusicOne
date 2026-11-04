package com.musicone.sourceintegration

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicone.*
import com.musicone.source.sdk.*

internal class SourceCollectionUiRequest(val collection: SourceCollection, val onClosed: () -> Unit)

/** 只桥接通用展示和页面请求，平台协议与账号逻辑仍由包提供。 */
internal class SourceContentUiBridge(private val context: Context) : SourceContentUi {
    override val pages = SourcePublicPageUiBridge(context)
    override val components = SourcePublicComponentUiBridge(context)
    var request by mutableStateOf<SourceCollectionUiRequest?>(null)
        private set

    override fun openCollection(collection: SourceCollection, onClosed: () -> Unit) {
        if (pages.mounted) {
            pages.open(SourcePageRequest.Collection(collection), onClosed)
            return
        }
        check(request == null) { "请先返回当前歌单" }
        request = SourceCollectionUiRequest(collection, onClosed)
    }

    fun closed(expected: SourceCollectionUiRequest) {
        if (request !== expected) return
        request = null
        expected.onClosed()
    }

    @Composable private fun HostContent(content: @Composable () -> Unit) {
        // 包资源上下文不能用于读取宿主图片、偏好或字体资源。
        SourceHostUiResources(context, content)
    }

    @Composable override fun CollectionCard(collection: SourceCollection, modifier: Modifier, onClick: () -> Unit) {
        HostContent { Box(modifier) { MainSourceLibraryPlaylistCard(collection.toMusicPlaylist(), 0, onClick = onClick) } }
    }

    @Composable override fun FeaturedCollectionCard(collection: SourceCollection, modifier: Modifier,
        heightDp: Float, compact: Boolean, onPlay: () -> Unit, onClick: () -> Unit) {
        HostContent { FeaturePlaylistCard(collection.toMusicPlaylist(), modifier, heightDp.dp, compact, onPlay, onClick) }
    }

    @Composable override fun RecommendationCollectionCard(collection: SourceCollection, modifier: Modifier,
        widthDp: Float, onPlay: () -> Unit, onClick: () -> Unit) {
        HostContent { MainSourcePlaylistCard(collection.toMusicPlaylist(), onClick, onPlay, modifier, widthDp.dp) }
    }

    @Composable override fun ProfileHeader(sourceId: String, account: SourceAccount?, onLogin: () -> Unit) {
        val member = rememberSourceMembership(SourcePackageController.get(context).running(sourceId)?.entry)
        HostContent { MainSourceProfileHeader(account?.let { MusicAccount(MusicSource.fromId(sourceId), it.id,
            it.displayName, it.avatarUrl, member == true, it.backgroundUrl, it.signature,
            it.follows?.toInt() ?: 0, it.followers?.toInt() ?: 0) }, onLogin) }
    }

    @Composable override fun FavoritesRow(collection: SourceCollection?, signedIn: Boolean, onClick: () -> Unit) {
        HostContent { MainSourceFavoritesRow(collection?.toMusicPlaylist(), 0, signedIn, onClick) }
    }

    @Composable override fun CollectionSegment(created: Boolean, createdCount: Int, collectedCount: Int,
        onCreated: () -> Unit, onCollected: () -> Unit) {
        HostContent { MainSourcePlaylistSegment(created, createdCount, collectedCount, onCreated, onCollected) }
    }

    @Composable override fun TrackRow(track: SourceTrackDetails, current: Boolean, playing: Boolean,
        favorite: Boolean, onClick: () -> Unit, onFavorite: () -> Unit, showSource: Boolean) {
        HostContent { MusicTrackRow(track.toMusicTrack(), current, playing, favorite, onClick, onFavorite, showSource = showSource) }
    }

    @Composable override fun TrackMenuHost(sourceId: String, bottomInsetDp: Float, content: @Composable () -> Unit) {
        val packageContext = LocalContext.current
        HostContent {
            val favorites = LocalMusicFavorites.current
            MainSourcePlaylistSongMenuHost(
                MusicPlaylist("source-ui:$sourceId", MusicSource.fromId(sourceId), "歌曲操作", "", "", 0,
                    0L, 0L, "", emptyList()),
                emptyList(), bottomInsetDp.dp, {}, {}, favorites.toggle,
                content = { SourceHostUiResources(packageContext, content) })
        }
    }
}

/** 在已有根歌单目的地打开，保留共享封面、返回手势和根层玻璃采样。 */
@Composable
internal fun SourceCollectionUiHost(navigation: RecommendationNavigation) {
    val context = LocalContext.current
    val bridge = remember(context) { SourceApplicationHost.get(context).contentUi }
    val controller = remember(context) { SourcePackageController.get(context) }
    val installed by controller.installed.collectAsState()
    val request = bridge.request
    LaunchedEffect(request, installed) {
        if (request != null) {
            if (controller.running(request.collection.identity.sourceId) == null) {
                if (navigation.playlist?.source?.id == request.collection.identity.sourceId) navigation.close()
                else bridge.closed(request)
            } else if (navigation.playlist == null) navigation.open(request.collection.toMusicPlaylist())
        }
    }
    LaunchedEffect(request, navigation.motion.mounted, navigation.playlist) {
        if (request != null && navigation.playlist == null && !navigation.motion.mounted) {
            // 首次打开的 effect 必须先设置目标，不能在同一帧误报关闭。
            kotlinx.coroutines.yield()
            if (navigation.playlist == null && !navigation.motion.mounted) bridge.closed(request)
        }
    }
}
