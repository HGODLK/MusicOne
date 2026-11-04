package com.musicone.source.kugou

import com.musicone.source.sdk.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 账号与取消检查沿用包内请求边界，不缓存或串用其他账号的首页内容。 */
internal class KugouSourceDiscovery(
    private val state: () -> KugouPackageState,
    private val remember: (MusicPlaylist, KugouAccountRequest) -> MusicPlaylist = { playlist, _ -> playlist },
    private val client: KugouHomeClient = KugouHomeClient(),
    private val dailyClient: KugouDailyClient = KugouDailyClient(),
    private val recentClient: KugouRecentClient = KugouRecentClient(),
    private val reporter: KugouRecentReporter = KugouRecentReporter(),
    private val recentChanged: (String) -> Unit = {},
) : SourceDiscovery, SourceDailyRecommendations, SourceHomeSectionPresentation, SourceMySectionPresentation {
    override val showRecentInMy = false
    override val supportsRecent = true

    override suspend fun home(request: SourceCursorRequest): SourcePage<SourceHomeSection> {
        val owner = state()
        return owner.request { session ->
            val cursor = KugouHomeCursor.read(request, session.cookie)
            // 兼容旧首页快照留下的下一页游标；新首页的推荐歌单固定为一批，不再追加更多卡片。
            if (cursor.page != 1) return@request SourcePage(emptyList(), null)
            val account = owner.account()
            val vip = owner.verifiedVipAccess(session)
            val playlists = client.playlists(session.cookie, 1, KUGOU_HOME_PLAYLIST_LIMIT,
                vip).asKugouHomeBatch()
            currentCoroutineContext().ensureActive()
            owner.check(session)
            val recommendations = if (cursor.page == 1) client.recommendations(session.cookie, vip) else null
            currentCoroutineContext().ensureActive()
            owner.check(session)
            SourcePage(playlists.homeSections(account?.userId, { remember(it, session) }, recommendations), null)
        }
    }

    override suspend fun dailyRecommendations(policy: SourceFetchPolicy): List<SourceTrackDetails> {
        val owner = state()
        return owner.request { session ->
            val account = owner.account()
            // 宿主首次网络读取也会传 REFRESH，它不等于用户要求重生成推荐，先读取账号真实当日日推。
            dailyClient.daily(session.cookie, owner.verifiedVipAccess(session)).tracks
                .map { KugouSourceMapping.track(it, account?.userId) }
        }
    }

    override suspend fun reportPlayed(track: SourceTrackDetails, playedMs: Long) {
        if (playedMs <= 0) return
        val owner = state()
        owner.request { session ->
            owner.check(session)
            reporter.report(track, session.cookie)
            owner.check(session)
            recentChanged(session.cookie.requiredKugouSession().userId)
        }
    }

    override suspend fun recentSnapshot(): SourceRecentSnapshot {
        val owner = state()
        return owner.request { session ->
            recentClient.snapshot(session.cookie, owner.verifiedVipAccess(session)) { owner.check(session) }
        }
    }

    override suspend fun recent(request: SourceCursorRequest): SourcePage<SourceTrackDetails> {
        require(request.cursor == null) { "酷狗云端最近播放已完整读取，请刷新记录" }
        return SourcePage(recentSnapshot().tracks, null)
    }

    override fun homePresentation(section: SourceHomeSection) = SourceHomePresentation(section.id,
        recommendation = section.id == "kugou.personal-recommendations")
}

internal data class KugouHomeCursor(val page: Int, val pageSize: Int, val account: String) {
    fun encode(): String = "kgh1:$page:$pageSize:$account"

    companion object {
        fun read(request: SourceCursorRequest, cookie: String): KugouHomeCursor {
            require(request.limit > 0) { "推荐分页数量必须大于零" }
            val owner = KugouProtocol.md5(cookie)
            val encoded = request.cursor ?: return KugouHomeCursor(1, request.limit, owner)
            val parts = encoded.split(':')
            require(parts.size == 4 && parts[0] == "kgh1" && parts[3] == owner) { "酷狗首页游标已失效，请刷新" }
            val page = parts[1].toIntOrNull()
            val size = parts[2].toIntOrNull()
            require(page != null && page > 1 && size != null && size > 0) { "酷狗首页游标格式不正确" }
            return KugouHomeCursor(page, size, owner)
        }
    }
}
