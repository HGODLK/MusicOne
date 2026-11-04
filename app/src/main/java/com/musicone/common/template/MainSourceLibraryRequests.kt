package com.musicone

import com.musicone.source.sdk.*

/** 同一次目录读取的所有分页都保留调用方的刷新意图。 */
internal suspend fun SourceCollections.loadMainSourceLibrary(
    section: SourceLibrarySection,
    policy: SourceFetchPolicy,
): List<SourceCollection> {
    if (section !in librarySections) return emptyList()
    val result = mutableListOf<SourceCollection>()
    var cursor: String? = null
    do {
        val page = library(section, SourceCursorRequest(cursor, policy = policy))
        result += page.items
        check(page.nextCursor == null || page.nextCursor != cursor) { "音源目录分页没有前进" }
        cursor = page.nextCursor
    } while (cursor != null)
    return result
}

internal suspend fun SourceCollections.loadMainSourceFavorites(
    policy: SourceFetchPolicy,
): Pair<SourceCollection, List<SourceTrackDetails>> {
    val collection = library(SourceLibrarySection.FAVORITE_TRACKS, SourceCursorRequest(policy = policy))
        .items.firstOrNull() ?: error("音源没有收藏歌曲目录")
    val detail = detail(collection)
    return detail to tracks(detail, SourceCursorRequest(limit = PLAYLIST_TRACK_PAGE_SIZE, policy = policy)).items
}
