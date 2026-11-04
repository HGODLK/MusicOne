package com.musicone.source.sdk

/** 可选的轻量图片查询能力，避免为了菜单头像加载完整歌手页。 */
interface SourceEntityArtwork {
    suspend fun artwork(reference: SourceEntityRef): SourceArtwork
}

/** 旧音源仍通过既有实体接口读取图片，不向旧实现添加抽象方法。 */
suspend fun SourceEntities.artwork(reference: SourceEntityRef): SourceArtwork =
    (this as? SourceEntityArtwork)?.artwork(reference) ?: entity(reference).artwork
