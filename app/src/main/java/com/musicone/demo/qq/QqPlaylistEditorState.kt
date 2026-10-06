package com.musicone.demo

import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.*
import org.json.JSONObject

internal enum class PlaylistEditMode { Actions, Create, Rename, Delete }

/** 编辑窗口独立持有状态，写入前重新核对当前账号的目录归属。 */
internal class QqPlaylistEditorState(private val preferences: PlatformPreferences, private val scope: CoroutineScope) {
    var open by mutableStateOf(false)
    var playlist by mutableStateOf<MusicPlaylist?>(null)
    var anchor by mutableStateOf(Rect.Zero)
    var artworkVersion: Long? = null
    var mode by mutableStateOf(PlaylistEditMode.Actions)
    var name by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    fun show(target: MusicPlaylist?, bounds: Rect, version: Long? = null) {
        if (busy) return
        playlist = target; anchor = bounds; artworkVersion = version; name = target?.title.orEmpty(); error = null
        mode = if (target == null) PlaylistEditMode.Create else PlaylistEditMode.Actions
        open = true
    }
    fun close() { if (!busy) open = false }
    fun back() {
        if (busy) return
        if (playlist != null && mode != PlaylistEditMode.Actions) mode = PlaylistEditMode.Actions else close()
    }
    fun submit(onChanged: (PlaylistEditMode, MusicPlaylist?) -> Unit) {
        if (busy) return
        val action = mode
        val target = playlist
        val title = name.trim()
        if (action != PlaylistEditMode.Delete && title.isBlank()) { error = "请输入歌单名称"; return }
        val session = preferences.readSession(MusicSource.QQ)
        busy = true; error = null
        scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val account = session.account ?: throw PlatformApiException("请先登录 QQ 音乐")
                    val owned = target?.let { selected ->
                        QqLibraryClient().createdPlaylists(session.credential, account.userId)
                            .firstOrNull { it.id == selected.id && !it.isQqFavoritesShortcut() }
                            ?: throw PlatformApiException("只能修改当前账号自建的歌单")
                    }
                    val body = qqPlaylistEditRequest(session.credential, action, owned, title)
                    val response = JSONObject(PlatformHttp.postJson("https://u.y.qq.com/cgi-bin/musicu.fcg", body.toString(),
                        session.credential, mapOf("Referer" to "https://y.qq.com/")).text)
                    val result = response.optJSONObject("req_0") ?: throw PlatformApiException("歌单操作返回异常")
                    val code = result.optInt("code", -1)
                    val data = result.optJSONObject("data")
                    val ret = data?.optInt("retCode", -1) ?: -1
                    if (code != 0 || ret != 0) throw PlatformApiException("歌单操作失败（${if (code != 0) code else ret}）")
                }
                open = false
                if (preferences.readSession(MusicSource.QQ).account?.userId == session.account?.userId) {
                    onChanged(action, target)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.asUserMessage() }
            finally { busy = false }
        }
    }
}

internal fun qqPlaylistEditRequest(cookie: String, action: PlaylistEditMode, playlist: MusicPlaylist?, name: String): JSONObject {
    val comm = qqPlaybackComm(cookie)
    require(comm.optString("authst").isNotBlank()) { "请先登录 QQ 音乐" }
    val param = JSONObject().put("bFmtUtf8", true)
    val method = when (action) {
        PlaylistEditMode.Create -> { param.put("dirName", name).put("dirShow", 1); "AddPlaylist" }
        PlaylistEditMode.Rename, PlaylistEditMode.Delete -> {
            val directory = playlist?.qqDirectoryId ?: throw PlatformApiException("缺少歌单目录信息，请刷新后重试")
            require(directory > 0 && !playlist.isQqFavoritesShortcut()) { "不能编辑这个歌单" }
            param.put("dirId", directory)
            if (action == PlaylistEditMode.Rename) { param.put("mask", 1).put("dirNewName", name); "EditPlaylist" }
            else { param.put("dirName", playlist.title); "DelPlaylist" }
        }
        PlaylistEditMode.Actions -> error("请选择操作")
    }
    return JSONObject().put("comm", comm).put("req_0", JSONObject().put("module", "music.musicasset.PlaylistBaseWrite")
        .put("method", method).put("param", param))
}
