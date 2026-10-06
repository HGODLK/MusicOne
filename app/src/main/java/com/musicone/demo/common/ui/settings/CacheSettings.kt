package com.musicone.demo

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CacheSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cache by remember { mutableStateOf<MusicDiskCache?>(null) }
    var usage by remember { mutableStateOf(CacheUsage()) }
    var limit by remember { mutableIntStateOf(5) }
    var selecting by remember { mutableStateOf(false) }
    var confirming by remember { mutableStateOf(false) }
    var clearAll by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        cache = withContext(Dispatchers.IO) { MusicDiskCache.get(context) }
        limit = cache!!.limitGb
        usage = withContext(Dispatchers.IO) { cache!!.usage() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("缓存", style = MaterialTheme.typography.titleLarge)
        Text("普通缓存 ${cacheSizeLabel(usage.ordinary)} · 保留音频 ${cacheSizeLabel(usage.protected)}")
        Text("我喜欢与主动缓存的歌单不计入普通上限。进入“缓存音乐”选择歌单下载或删除。",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { selecting = true }, enabled = cache != null) {
            Text("缓存上限：${if (limit == 0) "无限制" else "${limit} GB"}")
        }
        TextButton(onClick = { clearAll = false; confirming = true }, enabled = cache != null && !clearing) {
            Text(if (clearing) "正在清理…" else "清除普通缓存")
        }
        TextButton(onClick = { clearAll = true; confirming = true }, enabled = cache != null && !clearing) {
            Text("清除所有缓存")
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    if (selecting) AlertDialog(onDismissRequest = { selecting = false }, title = { Text("缓存大小") }, text = {
        Column {
            listOf(1, 2, 5, 10, 20, 0).forEach { gb ->
                TextButton(onClick = {
                    limit = gb; selecting = false
                    scope.launch {
                        cache?.setLimit(gb)?.join()
                        usage = withContext(Dispatchers.IO) { cache?.usage() ?: CacheUsage() }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (gb == 0) "无限制" else "$gb GB") }
            }
            Text("无限制仍受设备剩余空间限制。", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = { selecting = false }) { Text("取消") } })
    if (confirming) AlertDialog(onDismissRequest = { confirming = false }, title = { Text(if (clearAll) "清除所有缓存？" else "清除普通缓存？") },
        text = { Text(if (clearAll) "包括我喜欢的已缓存音频、封面和推荐占位，不会删除收藏或退出账号。正在播放的音频会在停止使用后清理。"
            else "清除普通音频、封面和推荐占位。保留我喜欢的音频及正在播放的文件。") },
        confirmButton = { TextButton(onClick = {
            confirming = false; clearing = true; message = null
            scope.launch {
                try { withContext(Dispatchers.IO) { if (clearAll) cache?.clearAll() else cache?.clearOrdinary(); usage = cache?.usage() ?: CacheUsage() } }
                catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                catch (_: Exception) { message = "部分缓存暂时无法清理，请稍后重试" }
                finally { clearing = false }
            }
        }) { Text("清除") } }, dismissButton = { TextButton(onClick = { confirming = false }) { Text("取消") } })
}

private fun cacheSizeLabel(bytes: Long): String = if (bytes >= 1_073_741_824L)
    "%.2f GB".format(bytes / 1_073_741_824.0) else "%.1f MB".format(bytes / 1_048_576.0)
