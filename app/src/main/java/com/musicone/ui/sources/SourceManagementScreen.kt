package com.musicone.ui.sources

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.musicone.SettingsHeader
import com.musicone.source.runtime.InstalledSource
import com.musicone.source.sdk.SourceKind
import com.musicone.sourceintegration.SourcePackageController
import com.musicone.sourceintegration.importDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SourceManagementScreen(
    controller: SourcePackageController,
    onBack: () -> Unit,
    onOpenSource: (String) -> Unit,
    bottomInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed by controller.installed.collectAsState()
    val message by controller.message.collectAsState()
    val startup by controller.startup.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var removing by remember { mutableStateOf<InstalledSource?>(null) }
    var importing by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<String?>(null) }
    fun importSource(uri: Uri) {
        scope.launch {
            importing = true
            try { withContext(Dispatchers.IO) { controller.importDocument(context, uri, allowDowngrade = true) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                error = failure.message ?: "音源导入失败"
            } finally { importing = false }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importSource(it) } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 20.dp + bottomInset),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { SettingsHeader("音源管理", onBack) }
            item { Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !importing && !startup.initializing) { Text(if (importing) "正在导入" else "导入音源") } }
            message?.let { item { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            if (startup.initializing) item { Text("正在准备音源，完成后自动更新列表") }
            startup.error?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
            listOf(SourceKind.MAIN, SourceKind.SUPPLEMENTAL, null).forEach { kind ->
                val group = installed.filter { it.kind == kind && visibleInSourceManagement(it) }
                if (group.isNotEmpty()) item(key = "group:${kind?.name}") {
                    Text(when (kind) { SourceKind.MAIN -> "主音源"; SourceKind.SUPPLEMENTAL -> "补充音源"; null -> "待识别音源" },
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                }
                items(group, key = { it.id }) { source ->
                    val instance = controller.running(source.id)
                    val title = source.name ?: source.id
                    Card(Modifier.fillMaxWidth().animateItem()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(title, style = MaterialTheme.typography.titleMedium)
                            Text(when {
                                source.pendingRemoval -> "已停用 · 下次启动时删除，可撤销"
                                source.removed -> "已移除 · 账号与资料保留"
                                instance == null -> source.error ?: "重启后生效"
                                else -> "已启用"
                            }, style = MaterialTheme.typography.bodySmall)
                            source.activeVersion?.let { version ->
                                Text("当前版本 ${source.versionName ?: version.toString()}", style = MaterialTheme.typography.bodySmall)
                            }
                            source.pendingVersion?.let { Text("待生效版本 ${source.pendingVersionName ?: it.toString()} · 重启应用后生效") }
                            source.error?.takeIf { instance != null }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (instance != null) Button(onClick = { onOpenSource(source.id) }) { Text("打开") }
                                if (source.pendingRemoval) Button(onClick = {
                                    scope.launch {
                                        restoring = source.id
                                        try { withContext(Dispatchers.IO) { controller.undoRemoval(source.id) } }
                                        catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                                        catch (failure: Exception) { error = failure.message ?: "撤销失败，请重试" }
                                        finally { restoring = null }
                                    }
                                }, enabled = restoring == null && !startup.initializing) {
                                    Text(if (restoring == source.id) "正在恢复" else "撤销移除")
                                }
                                if (!source.removed && !source.pendingRemoval) OutlinedButton(onClick = { removing = source }, enabled = !startup.initializing) { Text("移除") }
                                if (!source.removed && !source.pendingRemoval && source.rollbackVersion != null) TextButton(onClick = {
                                    runCatching { controller.rollback(source.id) }.onFailure { error = it.message }
                                }) { Text("回退") }
                            }
                        }
                    }
                }
            }
        }
    }
    removing?.let { source -> AlertDialog(
        onDismissRequest = { removing = null }, title = { Text("移除${source.name ?: source.id}？") },
        text = { Text((if (controller.isPlayingSource(source.id)) "正在播放的歌曲会停止。" else "") +
            if (source.kind == SourceKind.MAIN) "确认后立即停用，歌曲置灰；本次运行期间可撤销。下次启动时删除音源包和登录数据，保留歌曲资料及队列。"
            else "账号、资料和队列会保留，重新导入同一音源可恢复使用。") },
        confirmButton = { TextButton(onClick = {
            runCatching { controller.remove(source.id, confirmedPlayingRemoval = true) }.onFailure { error = it.message }
            removing = null
        }) { Text("移除") } }, dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
    ) }
    error?.let { text -> AlertDialog(onDismissRequest = { error = null }, title = { Text("音源操作失败") },
        text = { Text(text) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } }) }
}

internal fun visibleInSourceManagement(source: InstalledSource): Boolean =
    source.kind != SourceKind.MAIN || !source.removed
