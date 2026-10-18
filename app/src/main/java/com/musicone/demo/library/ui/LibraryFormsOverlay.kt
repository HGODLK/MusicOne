package com.musicone.demo

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.unit.dp

/** 编辑、导入确认沿用已有玻璃菜单的采样、曲线与返回规则。 */
@Composable
internal fun LibraryFormsOverlay(ui: MusicLibraryUiController, backdrop: GraphicsLayer, bounds: Rect) {
    var retainedForm by remember { mutableStateOf<LibraryEditForm?>(null) }
    var retainedPreview by remember { mutableStateOf<MusicArchivePreview?>(null) }
    val visible = ui.form != null || ui.preview != null
    SideEffect { if (visible) { retainedForm = ui.form; retainedPreview = ui.preview } }
    fun dismiss() { if (!ui.busy) { ui.form = null; ui.preview?.folder?.deleteRecursively(); ui.preview = null } }
    AnimatedVisibility(visible, enter = fadeIn(musicMotion(220)) + scaleIn(musicMotion(280), initialScale = .84f),
        exit = fadeOut(musicMotion(180)) + scaleOut(musicMotion(220), targetScale = .92f)) {
        Box(Modifier.fillMaxSize().imePadding().clickable(remember { MutableInteractionSource() }, null) { dismiss() }, contentAlignment = Alignment.Center) {
            MusicOneBackdropGlass(backdrop, bounds, Modifier.padding(24.dp).widthIn(max = 420.dp).fillMaxWidth()
                .clickable(remember { MutableInteractionSource() }, null) {}, RoundedCornerShape(28.dp), containerColor = playlistMenuContainer) {
                Column(Modifier.heightIn(max = 600.dp).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    retainedForm?.let { form ->
                        Text(form.title, style = MaterialTheme.typography.titleLarge)
                        form.labels.forEachIndexed { index, label -> LibraryFormField(label, form.values[index].value, { form.values[index].value = it }, !ui.busy) }
                        LibraryActionRow(if (ui.busy) "正在保存…" else if (form.labels.isEmpty()) "确认" else "保存", enabled = !ui.busy, onClick = ui::submit)
                    }
                    retainedPreview?.let { preview ->
                        Text("导入音乐库", style = MaterialTheme.typography.titleLarge)
                        Text("${preview.songs} 首歌曲，${preview.playlists} 个歌单${if (preview.containsAudio) "，含本地音乐文件" else "，仅资料"}")
                        val incomingSongs = preview.data.optJSONArray("songs")
                        val incomingLists = preview.data.optJSONArray("playlists")
                        val songs = ui.snapshot.tracks.mapTo(hashSetOf()) { it.id }
                        val playlists = ui.snapshot.playlists.mapTo(hashSetOf()) { it.id }
                        val songConflicts = (0 until (incomingSongs?.length() ?: 0)).count { incomingSongs?.optJSONObject(it)?.optString("id") in songs }
                        val listConflicts = (0 until (incomingLists?.length() ?: 0)).count { incomingLists?.optJSONObject(it)?.optString("id") in playlists }
                        Text("合并预览：$songConflicts 首已有歌曲、$listConflicts 个已有歌单；其余作为新增内容。", style = MaterialTheme.typography.bodySmall)
                        Text("同身份歌曲与歌单合并，保留已有自定义资料；综合歌单合并到现有唯一歌单。", style = MaterialTheme.typography.bodySmall)
                        if (!preview.containsAudio) Text("本地文件需在此设备重新关联目录，订阅需登录对应账号后刷新。", style = MaterialTheme.typography.bodySmall)
                        LibraryActionRow("确认合并", if (preview.containsAudio) "音频保存到应用音乐库" else null, !ui.busy) { ui.mergeArchive() }
                        if (preview.containsAudio) LibraryActionRow("选择音频保存文件夹", enabled = !ui.busy, onClick = ui.chooseArchiveDirectory)
                    }
                    ui.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    LibraryActionRow(if (ui.busy) "取消当前任务" else "取消") { if (ui.busy) ui.cancel() else dismiss() }
                }
            }
        }
    }
    if (visible) BackHandler { if (ui.busy) ui.cancel() else dismiss() }
}

@Composable
internal fun LibraryArtworkCrop(uri: Uri, backdrop: GraphicsLayer, bounds: Rect, onDismiss: () -> Unit, onConfirm: (Bitmap, NeteaseCropSelection) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val bitmap by produceState<Bitmap?>(null, uri) { value = NeteaseProfileBackgroundStore.loadPreview(context, uri) }
    val selection = remember(uri) { mutableStateOf(NeteaseCropSelection(aspectRatio = 1f)) }
    Box(Modifier.fillMaxSize().imePadding().clickable(remember { MutableInteractionSource() }, null) {}, contentAlignment = Alignment.Center) {
        MusicOneBackdropGlass(backdrop, bounds, Modifier.padding(24.dp).widthIn(max = 460.dp).fillMaxWidth(), RoundedCornerShape(28.dp), containerColor = playlistMenuContainer) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("裁剪封面", style = MaterialTheme.typography.titleLarge)
                Text("拖动和双指缩放选择方形区域", style = MaterialTheme.typography.bodySmall)
                bitmap?.let { NeteaseCropViewport(it, selection, Modifier.fillMaxWidth().aspectRatio(1f)) }
                    ?: Text("正在读取图片…")
                LibraryActionRow("使用此区域", enabled = bitmap != null) { bitmap?.let { onConfirm(it, selection.value) } }
                LibraryActionRow("取消", onClick = onDismiss)
            }
        }
    }
    BackHandler(onBack = onDismiss)
}
