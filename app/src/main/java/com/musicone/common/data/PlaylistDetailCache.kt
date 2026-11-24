package com.musicone

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * 账号关联歌单（创建、收藏、我喜欢）的完整歌曲列表缓存，每个歌单一个文件。
 * 进入歌单先显示缓存，后台核对后再一次性替换；不放进目录用的 SharedPreferences，避免启动时整体解析。
 */
internal class PlaylistDetailCache(context: Context) {
    private val directory = File(context.applicationContext.filesDir, "playlist-detail")

    private fun file(playlist: MusicPlaylist, accountId: String?): File {
        val key = "${playlist.source.id}\n${accountId.orEmpty()}\n${playlist.id}"
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun read(playlist: MusicPlaylist, accountId: String?): MusicPlaylist? = runCatching {
        val file = file(playlist, accountId)
        if (!file.isFile) null
        else JSONObject(file.readText()).toStoredPlaylist().takeIf { it.id == playlist.id && it.tracks.isNotEmpty() }
    }.getOrNull()

    fun save(playlist: MusicPlaylist, accountId: String?) {
        runCatching {
            directory.mkdirs()
            val target = file(playlist, accountId)
            // 先写临时文件再改名，写到一半被杀也不会留下读不出来的缓存。
            val temporary = File(directory, target.name + ".tmp")
            temporary.writeText(playlist.toStoredPlaylistJson().toString())
            if (!temporary.renameTo(target)) {
                target.delete()
                temporary.renameTo(target)
            }
        }
    }
}

/** 第一页与缓存开头逐首一致且总数相同，视为歌单没有变化，不必把剩余分页全部重拉。 */
internal fun playlistCacheStillFresh(cached: MusicPlaylist, firstPage: MusicPlaylist): Boolean {
    val head = firstPage.tracks
    return head.isNotEmpty() && firstPage.count == cached.count && head.size <= cached.tracks.size &&
        head.indices.all { head[it].id == cached.tracks[it].id }
}

/** 传入的歌单本身已带完整歌曲（如“我喜欢”的存档）时可直接当作缓存。 */
internal fun MusicPlaylist.completeTracksOrNull(): MusicPlaylist? =
    takeIf { tracks.isNotEmpty() && tracks.size >= count }

/** 歌曲、标题、数量和封面都没变就不重写缓存文件。 */
internal fun playlistCacheNeedsSave(cached: MusicPlaylist?, latest: MusicPlaylist): Boolean =
    latest.tracks.isNotEmpty() && (cached == null || cached.tracks != latest.tracks || cached.title != latest.title ||
        cached.count != latest.count || cached.artworkUrl != latest.artworkUrl)
