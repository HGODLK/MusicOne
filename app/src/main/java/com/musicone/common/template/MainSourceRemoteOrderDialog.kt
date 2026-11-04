package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*
import com.musicone.sourceintegration.*
import kotlinx.coroutines.*

/** 只编辑服务器歌曲顺序，不访问播放器或正在播放的队列。 */
@Composable
internal fun MainSourceRemoteOrderDialog(editor: MainSourcePlaylistEditorState, onChanged: (PlaylistEditMode, MusicPlaylist?) -> Unit) {
    val target = editor.playlist ?: return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entry = remember(target.id) { SourcePackageController.get(context).running(target.source.id)?.entry }
    val owner = editor.presentationAccountId
    val collection = remember(target, owner) { target.toSourceCollection(owner) }
    var rows by remember { mutableStateOf<List<SourceTrackDetails>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var revision by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf<Int?>(null) }
    var destination by remember { mutableStateOf("") }
    fun checkAccount() { check(owner != null && entry?.authentication?.currentAccount()?.account?.id == owner) { "账号已变化，请重新打开" } }
    LaunchedEffect(entry, target.id, revision) {
        loading = true; loaded = false; error = null
        try {
            rows = withContext(Dispatchers.IO) {
                checkAccount()
                val ordering = requireNotNull(entry?.collectionEditing as? SourceCollectionRemoteOrder)
                loadRemoteCollectionOrder(ordering, collection, ::checkAccount)
            }
            loaded = true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.asUserMessage() }
        finally { loading = false }
    }
    AlertDialog(onDismissRequest = { if (!saving) editor.remoteOrderOpen = false }, title = { Text("云端歌曲顺序") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("保存到云端歌单；本地显示排序保持原设置。", style = MaterialTheme.typography.bodySmall)
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 12.dp))
                error?.let { Text(it, color = MaterialTheme.colorScheme.error); if (!loaded) TextButton(onClick = { revision++ }) { Text("重试") } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
                    itemsIndexed(rows, key = { _, row -> row.stableId }) { index, row ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${row.track.title}", Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            IconButton(onClick = { moving = index; destination = (index + 1).toString() }, enabled = !saving) {
                                Icon(Icons.Rounded.SwapVert, "移动${row.track.title}")
                            }
                        }
                    }
                }
            }
        }, confirmButton = { TextButton(enabled = loaded && !loading && !saving, onClick = {
            saving = true; error = null
            val ordered = rows.map { it.stableId }
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { checkAccount(); requireNotNull(entry?.collectionEditing).reorder(collection, ordered); checkAccount() }
                    editor.remoteOrderOpen = false; editor.close(); onChanged(PlaylistEditMode.Rename, target)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { error = failure.asUserMessage() }
                finally { saving = false }
            }
        }) { Text(if (saving) "保存中…" else "保存") } },
        dismissButton = { TextButton(onClick = { editor.remoteOrderOpen = false }, enabled = !saving) { Text("取消") } })
    moving?.let { from ->
        val to = destination.toIntOrNull()?.minus(1)
        AlertDialog(onDismissRequest = { moving = null }, title = { Text("移动到第几首") }, text = {
            OutlinedTextField(destination, { destination = it }, label = { Text("位置（1–${rows.size}）") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
        }, confirmButton = { TextButton(enabled = to != null && to in rows.indices, onClick = {
            rows = rows.toMutableList().apply { add(requireNotNull(to), removeAt(from)) }; moving = null
        }) { Text("移动") } }, dismissButton = { TextButton(onClick = { moving = null }) { Text("取消") } })
    }
}

internal suspend fun loadRemoteCollectionOrder(service: SourceCollectionRemoteOrder, collection: SourceCollection,
    checkAccount: () -> Unit): List<SourceTrackDetails> {
    val result = mutableListOf<SourceTrackDetails>()
    val cursors = mutableSetOf<String>()
    var cursor: String? = null
    do {
        currentCoroutineContext().ensureActive(); checkAccount()
        val page = service.orderedTracks(collection, SourceCursorRequest(cursor, policy = if (cursor == null) SourceFetchPolicy.REFRESH else SourceFetchPolicy.CACHE_FIRST))
        result += page.items
        cursor = page.nextCursor
        check(cursor == null || cursors.add(cursor)) { "云端顺序分页未前进，请重试" }
    } while (cursor != null)
    checkAccount()
    require(result.map { it.stableId }.distinct().size == result.size) { "歌单包含重复歌曲，暂不能调整云端顺序" }
    return result
}
