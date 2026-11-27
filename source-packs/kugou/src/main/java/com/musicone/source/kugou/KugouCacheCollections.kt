package com.musicone.source.kugou

import com.musicone.source.sdk.SourceCollection
import com.musicone.source.sdk.SourceLibrarySection

/** 只有账号自己的云歌单能进缓存管理目录，首页推荐和搜索出来的歌单不算。 */
internal fun isKugouAccountCollection(collection: SourceCollection): Boolean =
    collection.identity.sourceId == KUGOU_SOURCE_ID &&
        collection.stableId.startsWith("kugou-cloudlist:")

/** 在酷狗包内先过滤出云歌单，避免让宿主硬编码识别酷狗的歌单 ID 格式。 */
internal fun KugouLibrary.cacheCollections(section: SourceLibrarySection): List<MusicPlaylist> =
    when (section) {
        SourceLibrarySection.FAVORITE_TRACKS -> listOfNotNull(favorite)
        SourceLibrarySection.CREATED_COLLECTIONS -> created
        SourceLibrarySection.SAVED_COLLECTIONS -> collected
    }.filter { it.id.startsWith("kugou-cloudlist:") }.distinctBy(MusicPlaylist::id)
