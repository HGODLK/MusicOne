package com.musicone.demo

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

internal class LibraryEditForm(val title: String, val labels: List<String>, initial: List<String>, val save: suspend (List<String>) -> Unit) {
    val values = initial.map { mutableStateOf(it) }
}

/** 文件选择、编辑与后台任务集中在音乐库自己的控制器，根节点只挂载宿主。 */
@Stable
internal class MusicLibraryUiController(val context: Context, private val scope: CoroutineScope) {
    val store = MusicLibraryStore.get(context)
    val importer = LocalMusicImporter(context, store)
    val archive = MusicLibraryArchive(context, store)
    var snapshot by mutableStateOf(LibrarySnapshot())
    var localMode by mutableStateOf(false)
    var form by mutableStateOf<LibraryEditForm?>(null)
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
    var progress by mutableStateOf("")
    var copyFiles by mutableStateOf(false)
    var preview by mutableStateOf<MusicArchivePreview?>(null)
    private var job: Job? = null
    var chooseFiles: () -> Unit = {}
    var chooseDirectory: (LibraryFolder?) -> Unit = {}
    var chooseLyrics: (MusicTrack) -> Unit = {}
    var chooseCover: (MusicTrack) -> Unit = {}
    var chooseRelinkFile: (MusicTrack) -> Unit = {}
    var chooseArchive: () -> Unit = {}
    var exportArchive: (Boolean) -> Unit = {}
    var chooseArchiveDirectory: () -> Unit = {}

    fun work(label: String, action: suspend () -> Unit) {
        if (busy) return
        busy = true; progress = label; message = null
        job = scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { message = "已取消，已完成的导入内容保留"; throw cancelled }
            catch (error: Exception) {
                android.util.Log.e("MusicLibrary", "操作失败: $label", error)
                val msg = error.message.orEmpty()
                message = when {
                    msg.contains("目录访问已失效") -> "目录访问已失效，请重新关联"
                    msg.contains("未找到有效歌词时间轴") -> "未找到有效歌词时间轴"
                    msg.contains("请输入有效秒数") -> "请输入有效秒数"
                    label.contains("导入") -> "导入失败，请重试"
                    label.contains("刷新") -> "刷新失败，请重试"
                    else -> "操作失败，请重试"
                }
            }
            finally { busy = false; progress = ""; job = null }
        }
    }
    fun cancel() { job?.cancel() }
    fun submit() {
        val current = form ?: return
        work("正在保存…") { current.save(current.values.map { it.value }); form = null }
    }
    fun createPlaylist() {
        form = LibraryEditForm("新建本地歌单", listOf("歌单名称"), listOf("")) { store.createPlaylist(it.first()); message = "歌单已创建" }
    }
    fun renamePlaylist(playlist: MusicPlaylist) {
        form = LibraryEditForm("重命名歌单", listOf("歌单名称"), listOf(playlist.title)) { store.renamePlaylist(playlist.id, it.first()) }
    }
    fun deletePlaylist(playlist: MusicPlaylist) {
        form = LibraryEditForm("删除「${playlist.title}」？原音乐文件会保留。", emptyList(), emptyList()) { store.deletePlaylist(playlist.id) }
    }
    fun addTo(playlistId: String, track: MusicTrack) = work("正在添加…") {
        store.addTracks(playlistId, listOf(track))
    }
    fun editTrack(track: MusicTrack) = work("正在读取资料…") {
        val overrides = withContext(Dispatchers.IO) { store.overrides(track.id) }
        form = LibraryEditForm("编辑歌曲资料", listOf("曲名（留空恢复默认）", "创作者（留空恢复默认）"),
            listOf(overrides.first.orEmpty(), overrides.second.orEmpty())) { values ->
            store.updateOverrides(track.id, values[0].takeIf(String::isNotBlank), values[1].takeIf(String::isNotBlank), overrides.third)
        }
    }
    fun offset(track: MusicTrack) = work("正在读取歌词设置…") {
        if (track.source.supportsOnlineLyricOffset) store.saveTrack(track)
        val offset = withContext(Dispatchers.IO) { store.lyricAsset(track.id).second }
        form = LibraryEditForm("歌词时间偏移", listOf("秒：正数延后，负数提前；留空恢复为零"), listOf((offset / 1000.0).toString())) {
            val value = it.first().takeIf(String::isNotBlank)?.toDoubleOrNull() ?: if (it.first().isBlank()) 0.0 else error("请输入有效秒数")
            require(value.isFinite()) { "请输入有效秒数" }
            store.setLyricOffset(track.id, (value * 1000).toLong())
        }
    }
    fun shift(track: MusicTrack, delta: Long) = work("正在调整歌词…") {
        if (track.source.supportsOnlineLyricOffset) store.saveTrack(track)
        val offset = withContext(Dispatchers.IO) { store.lyricAsset(track.id).second }
        store.setLyricOffset(track.id, offset + delta)
    }
    fun restoreCover(track: MusicTrack) = work("正在恢复封面…") {
        val old = withContext(Dispatchers.IO) { store.overrides(track.id) }
        store.updateOverrides(track.id, old.first, old.second, null)
    }
    fun importLyrics(track: MusicTrack, uri: Uri) = work("正在导入歌词…") {
        val file = File(store.assets, "lyrics/${track.id}-${UUID.randomUUID()}.lrc")
        var committed = false
        try {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: error("无法读取歌词")
                require(parseLibraryLyrics(file.readText()).isNotEmpty()) { "未找到有效歌词时间轴" }
                store.setLyrics(track.id, file.absolutePath, onCommitted = { committed = true })
            }
            message = "歌词已保存"
        } finally {
            if (!committed) file.delete()
        }
    }
    fun imported(result: LocalImportResult) {
        if (result.failed.isNotEmpty()) {
            android.util.Log.w("MusicLibrary", "导入完成，未导入 ${result.failed.size} 项:\n${result.failed.joinToString("\n")}")
        }
        message = result.displaySummary()
    }
    fun refresh(folder: LibraryFolder) = work("正在刷新目录…") { imported(importer.refresh(folder) { progress = it }) }
    fun mergeArchive(directory: Uri? = null) {
        val pending = preview ?: return
        work("正在合并归档…") { try { archive.merge(pending, directory); message = "音乐库已合并" } finally { preview = null } }
    }
}

internal val LocalMusicLibraryUi = staticCompositionLocalOf<MusicLibraryUiController> { error("音乐库宿主尚未挂载") }
