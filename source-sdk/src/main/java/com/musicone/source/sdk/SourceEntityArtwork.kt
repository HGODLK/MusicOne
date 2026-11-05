package com.musicone.source.sdk

/** 可选的轻量图片查询能力，避免为了菜单头像加载完整歌手页。 */
interface SourceEntityArtwork {
    suspend fun artwork(reference: SourceEntityRef): SourceArtwork
}

/** 实体图片由此可选接口提供，不向 SourceEntry 增加抽象成员。 */
suspend fun SourceEntities.artwork(reference: SourceEntityRef): SourceArtwork =
    (this as? SourceEntityArtwork)?.artwork(reference) ?: entity(reference).artwork
