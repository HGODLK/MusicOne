package com.musicone.source.kugou

import com.musicone.source.sdk.*

/** 启动恢复缓存时顺手预热首页封面，后台静默跑，不阻塞页面展示。 */
internal suspend fun prepareKugouHomeCachedArtwork(environment: SourceEnvironment, state: KugouHomeState) {
    if (environment.validationOnly) return
    val artworks = kugouHomeCachedArtworks(state)
    if (artworks.isEmpty()) return
    val components = environment.host.contentUi?.components?.forSource(KUGOU_SOURCE_ID)
    (components as? SourceCachedArtworkComponents)?.prepareCachedArtwork(artworks)
}

internal fun kugouHomeCachedArtworks(state: KugouHomeState): List<SourceArtwork> = buildList {
    // 按首页展示顺序预热，日推和最近播放卡片只预热第一首歌的封面
    state.daily.firstOrNull()?.let { add(it.artwork) }
    state.recent.tracks.firstOrNull()?.let { add(it.artwork) }
    state.homeSections.forEach { section ->
        addAll(section.tracks.map { it.artwork })
        addAll(section.collections.map { it.artwork })
    }
}.filter { !it.url.isNullOrBlank() }.distinctBy { it.url }
