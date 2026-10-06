package com.musicone.demo

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal class QqRecentPlayRepository(context: Context) {
    private val preferences = PlatformPreferences(context)
    private val artworkAliases = QqArtworkAliasStore(context)

    suspend fun load(): QqRecentPlaySnapshot = withContext(Dispatchers.IO) {
        val session = preferences.readSession(MusicSource.QQ)
        if (qqCredentialAccountId(session.credential).isBlank() ||
            qqCredentialMusicKey(session.credential).isBlank()
        ) throw PlatformApiException("登录 QQ 音乐后查看最近播放", 301)
        val modules = qqRecentPlayRequest()
        val body = JSONObject().put("comm", qqPlaybackComm(session.credential))
        modules.keys().forEach { key -> body.put(key, modules.getJSONObject(key)) }
        val response = JSONObject(PlatformHttp.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body.toString(),
            session.credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 14090008(android 14)"),
        ).text)
        val parsed = parseQqRecentPlay(response, session.account?.hasVipAccess == true)
        parsed.copy(songs = artworkAliases.remember(QqTrackAccessResolver().resolve(parsed.songs, session.credential).map(artworkAliases::apply)))
    }

    suspend fun report(track: MusicTrack) = withContext(Dispatchers.IO) {
        val session = preferences.readSession(MusicSource.QQ)
        if (qqCredentialAccountId(session.credential).isBlank() ||
            qqCredentialMusicKey(session.credential).isBlank()
        ) return@withContext
        val modules = qqRecentPlayReportRequest(track, System.currentTimeMillis() / 1_000L)
        val body = JSONObject().put("comm", qqPlaybackComm(session.credential))
        modules.keys().forEach { key -> body.put(key, modules.getJSONObject(key)) }
        val response = JSONObject(PlatformHttp.postJson(
            "https://u.y.qq.com/cgi-bin/musicu.fcg",
            body.toString(),
            session.credential,
            mapOf("Referer" to "https://y.qq.com/", "User-Agent" to "QQMusic 14090008(android 14)"),
        ).text)
        requireQqRecentPlayReportSuccess(response)
        QqRecentPlayChanges.publish()
    }
}

internal object QqRecentPlayChanges {
    private val mutableRevision = MutableStateFlow(0L)
    val revision = mutableRevision.asStateFlow()

    fun publish() {
        mutableRevision.value += 1L
    }
}
