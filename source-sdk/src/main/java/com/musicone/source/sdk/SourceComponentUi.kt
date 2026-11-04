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
