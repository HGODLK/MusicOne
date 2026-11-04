package com.musicone.source.sdk

/** 可选的冷启动封面准备入口，只解码宿主已有缓存，未命中不联网。 */
interface SourceCachedArtworkComponents {
    suspend fun prepareCachedArtwork(artworks: List<SourceArtwork>, maxSidePx: Int = 768)
}
