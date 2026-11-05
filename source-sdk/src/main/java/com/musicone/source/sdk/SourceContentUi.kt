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
