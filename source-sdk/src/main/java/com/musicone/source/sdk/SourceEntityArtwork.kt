package com.musicone.source.sdk

/** 可选的轻量图片查询能力，避免为了菜单头像加载完整歌手页。 */
interface SourceEntityArtwork {
    suspend fun artwork(reference: SourceEntityRef): SourceArtwork
}

/** 通过此可选接口提供实体图片，避免给 SourceEntry 新增抽象方法。 */
suspend fun SourceEntities.artwork(reference: SourceEntityRef): SourceArtwork =
    (this as? SourceEntityArtwork)?.artwork(reference) ?: entity(reference).artwork
