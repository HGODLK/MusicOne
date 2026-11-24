package com.musicone

import android.content.Context
import com.musicone.source.sdk.SourceCollectionOperation
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.toMusicPlaylist
import com.musicone.sourceintegration.toSourceCollection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一次事务删掉多个本地歌单，只发布一次快照；固定歌单不在此列。 */
internal suspend fun MusicLibraryStore.deletePlaylists(ids: Collection<String>): Int {
    val targets = ids.filter { it != UNIFIED_PLAYLIST_ID && it != PHONE_STORAGE_PLAYLIST_ID }
    if (targets.isEmpty()) return 0
    return aiTransaction(publishChanges = true) { db ->
        targets.sumOf { db.delete("playlists", "id=?", arrayOf(it)) }
    }
}

internal class CollectionBatchResult(val done: List<MusicPlaylist>, val failed: List<MusicPlaylist>, val error: String?) {
    fun summary(verb: String): String = when {
        failed.isEmpty() -> "已$verb ${done.size} 个歌单"
        done.isEmpty() -> error ?: "${verb}失败，请重试"
        else -> "已$verb ${done.size} 个，${failed.size} 个失败" + (error?.let { "：$it" } ?: "")
    }
}

/**
 * 批量删除当前账号自建的云端歌单。核对与单个删除一致：音源声明了删除、目标在可写歌单清单里、
 * 每删一个都确认账号没有变化；可写清单一批只取一次。单个失败不中断，账号变化则立即停止。
 */
internal suspend fun deleteSourceCollections(context: Context, source: MusicSource, playlists: List<MusicPlaylist>,
    onProgress: (Int, Int) -> Unit): CollectionBatchResult = withContext(Dispatchers.IO) {
    val entry = requireNotNull(SourcePackageController.get(context.applicationContext).running(source.id)?.entry) { "音源未启用" }
    val account = requireNotNull(entry.authentication?.currentAccount()?.account) { "请先登录${source.label}" }
    val editing = requireNotNull(entry.collectionEditing) { "此音源不支持编辑歌单" }
    require(SourceCollectionOperation.DELETE in editing.supportedOperations) { "此音源不支持删除歌单" }
    val writable = editing.writableCollections().filterNot { it.accountFavorites }.associateBy { it.toMusicPlaylist().id }
    val done = mutableListOf<MusicPlaylist>()
    val failed = mutableListOf<MusicPlaylist>()
    var error: String? = null
    playlists.forEachIndexed { index, playlist ->
        onProgress(index, playlists.size)
        val owned = writable[playlist.id]
        if (owned == null) {
            failed += playlist
            error = "只能删除当前账号自建的歌单"
            return@forEachIndexed
        }
        try {
            editing.delete(owned)
            done += playlist
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed += playlist
            error = failure.asUserMessage()
        }
        check(entry.authentication?.currentAccount()?.account?.id == account.id) { "账号已变化，已停止删除" }
    }
    CollectionBatchResult(done, failed, error)
}

/**
 * 逐个执行批量动作：操作条上显示进度，单个失败不中断。
 * 全部成功则退出多选，否则只保留失败项方便重试；返回给用户看的结果文字。
 */
internal suspend fun <T> runPlaylistCardBatch(selection: PlaylistCardSelection, items: List<T>, id: (T) -> String,
    verb: String, each: suspend (T) -> Unit): String {
    val failed = mutableListOf<T>()
    var error: String? = null
    try {
        items.forEachIndexed { index, item ->
            selection.busyText = "正在$verb ${index + 1}/${items.size}"
            try { each(item) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed += item; error = failure.asUserMessage() }
        }
    } finally {
        selection.busyText = null
    }
    if (failed.isEmpty()) selection.close() else selection.keepOnly(failed.map(id))
    val done = items.size - failed.size
    return when {
        failed.isEmpty() -> "已$verb $done 个歌单"
        done == 0 -> error ?: "${verb}失败，请重试"
        else -> "已$verb $done 个，${failed.size} 个失败" + (error?.let { "：$it" } ?: "")
    }
}

/** 批量取消收藏；走音源声明的收藏歌单接口，单个失败不中断。 */
internal suspend fun unfavoriteSourceCollections(context: Context, source: MusicSource, accountId: String?,
    playlists: List<MusicPlaylist>, onProgress: (Int, Int) -> Unit): CollectionBatchResult = withContext(Dispatchers.IO) {
    val entry = requireNotNull(SourcePackageController.get(context.applicationContext).running(source.id)?.entry) { "音源未启用" }
    val favorites = requireNotNull(entry.favorites?.takeIf { it.supportsCollections }) { "此音源不支持收藏歌单" }
    val done = mutableListOf<MusicPlaylist>()
    val failed = mutableListOf<MusicPlaylist>()
    var error: String? = null
    playlists.forEachIndexed { index, playlist ->
        onProgress(index, playlists.size)
        try {
            favorites.setCollectionFavorite(playlist.toSourceCollection(accountId), false)
            done += playlist
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failed += playlist
            error = failure.asUserMessage()
        }
    }
    CollectionBatchResult(done, failed, error)
}
