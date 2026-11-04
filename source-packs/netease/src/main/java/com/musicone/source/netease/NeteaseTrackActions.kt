package com.musicone.source.netease

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private data class NeteaseDestination(val id: String, val title: String, val playlist: NeteasePlaylist? = null)
internal class NeteaseTrackActions(private val owner: NeteasePackageState, private val services: NeteaseSourceServices) {
    private var favorites by mutableStateOf<Set<String>>(emptySet())
    private var favoriteAccount by mutableStateOf<String?>(null)
    val favoriteIds: Set<String> get() = if (favoriteAccount == owner.account?.userId) favorites else emptySet()
    private var targets by mutableStateOf<List<NeteaseDestination>>(emptyList())
    private var importing by mutableStateOf<List<NeteaseTrack>>(emptyList())
    private var offsetTrack by mutableStateOf<NeteaseTrack?>(null)
    private var offsetText by mutableStateOf("0")
    fun refreshFavorites() = owner.action {
        val revision = owner.revision.get(); val cookie = owner.credential(); val user = owner.account?.userId.orEmpty()
        if (cookie.isBlank()) { favorites = emptySet(); favoriteAccount = null; return@action }
        val ids = withContext(Dispatchers.IO) { owner.api.likedTrackIds(cookie, user) }
        owner.check(revision); favorites = ids; favoriteAccount = user
    }
    fun favorite(track: NeteaseTrack) = owner.action {
        val next = track.id !in favoriteIds
        services.setTrackFavorite(track.toDetails(owner.account?.userId), next)
        favorites = if (next) favoriteIds + track.id else favoriteIds - track.id; favoriteAccount = owner.account?.userId
        owner.message = if (next) "已加入我喜欢" else "已取消喜欢"
    }
    fun enqueue(tracks: List<NeteaseTrack>, next: Boolean) = owner.action {
        owner.environment.host.enqueueDetailed(tracks.map { it.toDetails(owner.account?.userId) }, next)
        owner.message = if (next) "已设为下一首播放" else "已加入播放队列"
    }
    fun chooseDestination(tracks: List<NeteaseTrack>) = owner.action {
        val revision = owner.revision.get(); val cookie = owner.credential(); val user = owner.account?.userId.orEmpty()
        val remote = withContext(Dispatchers.IO) { owner.api.userPlaylists(cookie, user).filter { it.isOwned && !it.isPlatformFavorites } }
        val local = owner.environment.host.library?.destinations().orEmpty()
        owner.check(revision)
        targets = remote.map { NeteaseDestination(it.id, "网易云 · ${it.title}", it) } + local.map { NeteaseDestination(it.id, "本地 · ${it.title}") }
        importing = tracks
    }
    fun download(track: NeteaseTrack) = owner.action {
        val details = track.toDetails(owner.account?.userId)
        val resource = services.resolve(SourcePlaybackRequest(details))
        require(!resource.trial) { "试听音源不能当作完整缓存" }
        requireNotNull(owner.environment.host.cache) { "宿主未提供缓存能力" }.download(details, resource)
        owner.message = "已开始缓存"
    }
    fun offset(track: NeteaseTrack) = owner.action {
        offsetText = requireNotNull(owner.environment.host.library).lineOffset(track.toDetails(owner.account?.userId)).toString()
        offsetTrack = track
    }
    fun remove(track: NeteaseTrack) = owner.action {
        val playlist = requireNotNull(owner.selectedPlaylist)
        services.changeTracks(playlist.toCollection(owner.account?.userId), listOf(track.toDetails(owner.account?.userId)), false)
        owner.playlistPage = owner.playlistPage?.let { page -> page.copy(playlist = page.playlist.copy(tracks = page.playlist.tracks.filterNot { it.id == track.id }, count = (page.playlist.count - 1).coerceAtLeast(0))) }
        owner.selectedPlaylist = owner.playlistPage?.playlist ?: playlist
        owner.message = "已从歌单移除"
    }
    fun back(): Boolean {
        if (importing.isNotEmpty()) { importing = emptyList(); return true }
        if (offsetTrack != null) { offsetTrack = null; return true }
        return false
    }
    @Composable fun Dialogs() {
        if (importing.isNotEmpty()) AlertDialog(onDismissRequest = { importing = emptyList() }, title = { Text("添加 ${importing.size} 首歌曲") },
            text = { LazyColumn { if (targets.isEmpty()) item { Text("没有可添加的歌单") }
                items(targets, key = { it.id }) { target -> TextButton(onClick = {
                    val tracks = importing; importing = emptyList()
                    owner.action {
                        if (target.playlist != null) services.changeTracks(target.playlist.toCollection(owner.account?.userId), tracks.map { it.toDetails(owner.account?.userId) }, true)
                        else requireNotNull(owner.environment.host.library).importTracks(tracks.map { it.toDetails(owner.account?.userId) }, target.id)
                        owner.message = "已添加到${target.title}"
                    }
                }) { Text(target.title) } }
            } }, confirmButton = { TextButton(onClick = { importing = emptyList() }) { Text("取消") } })
        offsetTrack?.let { track -> AlertDialog(onDismissRequest = { offsetTrack = null }, title = { Text("歌词整行偏移") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("正数延后，负数提前；不改变逐字时间线")
                OutlinedTextField(offsetText, { offsetText = it }, label = { Text("毫秒") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row { TextButton(onClick = { offsetText = ((offsetText.toLongOrNull() ?: 0) - 100).toString() }) { Text("提前100") }
                    TextButton(onClick = { offsetText = "0" }) { Text("归零") }
                    TextButton(onClick = { offsetText = ((offsetText.toLongOrNull() ?: 0) + 100).toString() }) { Text("延后100") } }
            } }, confirmButton = { TextButton(enabled = offsetText.toLongOrNull() != null, onClick = {
                val amount = requireNotNull(offsetText.toLongOrNull()); offsetTrack = null
                owner.action { requireNotNull(owner.environment.host.library).setLineOffset(track.toDetails(owner.account?.userId), amount) }
            }) { Text("保存") } }, dismissButton = { TextButton(onClick = { offsetTrack = null }) { Text("取消") } }) }
    }
}
