package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** 歌词窗口逐首调用，复用原歌词缓存，不向当前播放状态发布预取歌词。 */
internal class QqNextLyricsPreloader(
    private val session: () -> PlatformSession,
    private val readCached: suspend (MusicTrack, String) -> List<TimedLyric>,
    private val fetch: suspend (MusicTrack, String) -> List<TimedLyric>,
    private val save: suspend (MusicTrack, List<TimedLyric>, String) -> Unit,
) {
    constructor(context: Context) : this(
        session = { PlatformPreferences(context).readSession(MusicSource.QQ) },
        readCached = { track, namespace -> CachedLyricsStore(context).read(track, namespace) },
        fetch = { track, cookie ->
            QqSessionRequestCoordinator.beforeTicketRequest(cookie, showVerification = false)
            QqApiClient().lyrics(track, cookie, origin = QqRequestOrigin.PRELOAD)
        },
        save = { track, lyrics, namespace -> CachedLyricsStore(context).save(track, lyrics, namespace) },
    )

    suspend fun preload(track: MusicTrack, namespace: String) {
        currentCoroutineContext().ensureActive()
        val requestedSession = session()
        if (track.source != MusicSource.QQ || requestedSession.cacheNamespace() != namespace) return
        if (readCached(track, namespace).isNotEmpty()) return
        currentCoroutineContext().ensureActive()
        if (session().cacheNamespace() != namespace) return
        val lyrics = track.lyrics.ifEmpty { fetch(track, requestedSession.credential) }
        currentCoroutineContext().ensureActive()
        // 阻塞网络请求返回后再次核对账号，并始终用开始时的命名空间写入。
        if (lyrics.isNotEmpty() && session().cacheNamespace() == namespace) save(track, lyrics, namespace)
    }
}
