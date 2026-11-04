package com.musicone.source.kugou

import com.musicone.source.sdk.*

internal class KugouSourceSearch(
    private val state: () -> KugouPackageState,
    private val client: KugouContentClient = KugouContentClient(),
) : SourceSearch {
    override val kinds = setOf(SourceSearchKind.TRACK, SourceSearchKind.ARTIST, SourceSearchKind.ALBUM, SourceSearchKind.COLLECTION)
    override val supportsSuggestions = true
    override suspend fun search(query: String, kind: SourceSearchKind, request: SourceCursorRequest): SourceSearchResult {
        require(kind in kinds) { "酷狗未接入此搜索类型" }
        val keyword = query.trim()
        if (keyword.isEmpty()) return SourceSearchResult()
        val current = state()
        return current.request { session ->
            val page = KugouContentPage.from(request, "search:${kind.name}:$keyword", session.contentOwner())
            val result = client.load(client.searchRequest(keyword, kind, page.page, page.size), session.cookie)
            current.check(session)
            val rows = result.contentRows("lists", "info", "list")
            val next = page.next(result, rows)
            val account = current.account()?.userId
            val vip = current.verifiedVipAccess(session)
            when (kind) {
                SourceSearchKind.TRACK -> SourceSearchResult(tracks = rows.map {
                    KugouSourceMapping.track(it.toKugouContentTrack(vip), account)
                }, nextCursor = next)
                SourceSearchKind.ARTIST -> SourceSearchResult(entities = rows.map { it.toKugouContentEntity("artist") }, nextCursor = next)
                SourceSearchKind.ALBUM -> SourceSearchResult(collections = rows.map { it.toKugouSearchAlbumCollection(account) }, nextCursor = next)
                SourceSearchKind.COLLECTION -> SourceSearchResult(collections = rows.map { it.toKugouContentCollection(account, false) }, nextCursor = next)
                else -> error("酷狗未接入此搜索类型")
            }
        }
    }

    override suspend fun suggestions(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val current = state()
        return current.request { session ->
            val result = client.load(KugouContentRequest("/v2/getSearchTip", "searchtip.kugou.com", mapOf(
                "keyword" to query.trim(), "AlbumTipCount" to "10", "CorrectTipCount" to "10",
                "MVTipCount" to "10", "MusicTipCount" to "10", "radiotip" to "1",
            )), session.cookie)
            current.check(session)
            result.kugouContentSuggestions()
        }
    }
}
