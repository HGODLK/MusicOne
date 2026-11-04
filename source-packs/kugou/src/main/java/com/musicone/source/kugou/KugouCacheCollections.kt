package com.musicone.source.kugou

import com.musicone.source.sdk.SourceCollection
import com.musicone.source.sdk.SourceLibrarySection

/** 账号目录只提供云歌单，首页推荐与搜索结果不能成为缓存管理目录。 */
internal fun isKugouAccountCollection(collection: SourceCollection): Boolean =
    collection.identity.sourceId == KUGOU_SOURCE_ID &&
        collection.stableId.startsWith("kugou-cloudlist:")

/** 在音源输出边界筛选，而不是让宿主识别酷狗私有歌单编号。 */
internal fun KugouLibrary.cacheCollections(section: SourceLibrarySection): List<MusicPlaylist> =
    when (section) {
        SourceLibrarySection.FAVORITE_TRACKS -> listOfNotNull(favorite)
        SourceLibrarySection.CREATED_COLLECTIONS -> created
        SourceLibrarySection.SAVED_COLLECTIONS -> collected
    }.filter { it.id.startsWith("kugou-cloudlist:") }.distinctBy(MusicPlaylist::id)
