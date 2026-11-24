package com.musicone

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * “添加到歌单”的目标列表：先合并本地歌单与音源缓存立即显示，再逐个音源联网刷新后整体替换。
 * 刷新失败保留已显示的缓存；换歌或重新打开时取消上一轮，避免旧平台的歌单混进新菜单。
 */
internal class PlaylistAdditionTargetLoader(
    private val scope: CoroutineScope,
    private val preferences: PlatformPreferences,
    private val repository: PlatformPlaylistSongRepository,
) {
    var loading by mutableStateOf(false); private set
    private var job: Job? = null
    private var generation = 0

    fun cancel() { generation++; job?.cancel(); job = null; loading = false }

    fun load(local: List<MusicPlaylist>, sources: List<MusicSource>,
        onTargets: (List<MusicPlaylist>) -> Unit, onMessage: (String) -> Unit) {
        cancel()
        val online = sources.filter { it != MusicSource.LOCAL }
        val token = ++generation
        loading = true
        job = scope.launch {
            try {
                val cached = online.associateWith { source ->
                    try { withContext(Dispatchers.IO) { repository.cachedTargets(preferences.readSession(source)) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                }
                if (cached.values.any { it != null }) onTargets(merge(local, online.map { cached[it].orEmpty() }))
                var failed = false
                val fresh = online.map { source ->
                    try { withContext(Dispatchers.IO) { repository.additionTargets(preferences.readSession(source)) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { failed = true; cached[source].orEmpty() }
                }
                val merged = merge(local, fresh)
                onTargets(merged)
                if (failed) onMessage(if (cached.values.any { it != null }) "歌单状态刷新失败，显示的是上次的歌单"
                    else "部分平台歌单读取失败，仍可选择已加载的歌单")
                else if (merged.isEmpty()) onMessage("暂无可添加的自建歌单")
            } finally {
                if (token == generation) loading = false
            }
        }
    }

    private fun merge(local: List<MusicPlaylist>, remote: List<List<MusicPlaylist>>) =
        (local + remote.flatten()).distinctBy { it.id }
}
