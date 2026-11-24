package com.musicone.source.qq.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * “这首歌在不在歌单里”：本地索引直接回答；核对时先取一次全部自建歌单的版本，只重拉版本变了的歌单（只要曲目身份，不解析权益）。
 * 参照官方 App：弹窗只查本地库，歌单按更新时间增量同步，本机写入直接改本地库。
 */
internal class QqPlaylistMembership(
    val store: QqPlaylistMembershipStore,
    private val loadVersions: (String) -> Map<Long, QqDirectoryVersion>? = ::fetchQqDirectoryVersions,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val versionTtlMs: Long = 30_000L,
) {
    private val versionLock = Mutex()
    private var versions: Triple<String, Long, Map<Long, QqDirectoryVersion>?>? = null

    /** 只读本地索引；没有该歌单的索引返回 null。 */
    fun cached(owner: String, playlistId: String, tracks: Map<String, Set<String>>): Set<String>? =
        store.entry(owner, playlistId)?.let { contained(it.ids, tracks) }

    /** 版本没变直接用索引；本机刚写过的接受服务端新版本；其余整单读取曲目身份后更新索引。 */
    suspend fun refresh(owner: String, credential: String, playlist: MusicPlaylist, tracks: Map<String, Set<String>>,
        loadIdentities: () -> Set<String>): Set<String> {
        val version = playlist.qqDirectoryId?.let { versionsFor(owner, credential)?.get(it) }?.key
        val entry = store.entry(owner, playlist.id)
        if (version != null && entry != null) {
            if (entry.version == version) return contained(entry.ids, tracks)
            if (entry.version == QqPlaylistMembershipStore.PENDING_VERSION) {
                store.saveEntry(owner, playlist.id, entry.copy(version = version))
                return contained(entry.ids, tracks)
            }
        }
        val started = nowMs()
        val identities = loadIdentities()
        QqSessionLog.record("歌单归属整单读取：曲目身份${identities.size}个，耗时=${nowMs() - started}ms，原因=${
            if (entry == null) "无索引" else if (version == null) "版本未知" else "版本变化"}")
        // 版本未知时记空版本：本地仍可先显示，下次核对仍会整单重拉。
        store.saveEntry(owner, playlist.id, QqPlaylistMembershipStore.Entry(version.orEmpty(), identities))
        return contained(identities, tracks)
    }

    fun applyWrite(owner: String, playlistId: String, identities: Set<String>, add: Boolean) {
        store.applyWrite(owner, playlistId, identities, add)
        // 写入后服务端版本已变，丢掉内存里的旧版本，下次核对重新取。
        versions = null
    }

    private suspend fun versionsFor(owner: String, credential: String): Map<Long, QqDirectoryVersion>? = versionLock.withLock {
        versions?.takeIf { it.first == owner && nowMs() - it.second < versionTtlMs }?.let { return@withLock it.third }
        val loaded = loadVersions(credential)
        versions = Triple(owner, nowMs(), loaded)
        loaded
    }

    private fun contained(ids: Set<String>, tracks: Map<String, Set<String>>): Set<String> =
        tracks.filterValues { identities -> identities.any(ids::contains) }.keys
}

private fun fetchQqDirectoryVersions(credential: String): Map<Long, QqDirectoryVersion>? = runCatchingCancellable {
    parseQqDirectoryVersions(JSONObject(PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg",
        qqDirectoryVersionsRequest(credential).toString(), credential,
        mapOf("Referer" to "https://y.qq.com/", "User-Agent" to QQ_ANDROID_USER_AGENT),
        connectTimeoutMs = 5_000, readTimeoutMs = 8_000).text))
}.getOrNull().also { if (it == null) QqSessionLog.record("歌单版本读取失败，按版本未知处理") }
