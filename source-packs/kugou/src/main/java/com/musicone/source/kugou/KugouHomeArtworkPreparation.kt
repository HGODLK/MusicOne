package com.musicone.source.kugou

import com.musicone.source.sdk.*

/** 由启动及快照恢复任务调用，页面不观察准备状态，也不等待此任务。 */
internal suspend fun prepareKugouHomeCachedArtwork(environment: SourceEnvironment, state: KugouHomeState) {
    if (environment.validationOnly) return
    val artworks = kugouHomeCachedArtworks(state)
    if (artworks.isEmpty()) return
    val components = environment.host.contentUi?.components?.forSource(KUGOU_SOURCE_ID)
    (components as? SourceCachedArtworkComponents)?.prepareCachedArtwork(artworks)
}

internal fun kugouHomeCachedArtworks(state: KugouHomeState): List<SourceArtwork> = buildList {
    // 顺序与首页相同；日推和最近播放卡只使用各自实际显示的第一首封面。
    state.daily.firstOrNull()?.let { add(it.artwork) }
    state.recent.tracks.firstOrNull()?.let { add(it.artwork) }
    state.homeSections.forEach { section ->
        addAll(section.tracks.map { it.artwork })
        addAll(section.collections.map { it.artwork })
    }
}.filter { !it.url.isNullOrBlank() }.distinctBy { it.url }
