package com.musicone

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** 复用 LibraryFormsOverlay 同一套玻璃采样、曲线与返回规则，不引入系统样式弹窗。 */
@Composable
internal fun CustomFontOverlays(backdrop: GraphicsLayer, bounds: Rect) {
    val context = LocalContext.current
    val visible = CustomFontImportSession.hasOverlay
    var retainedGithub by remember { mutableStateOf<CustomFontGitHubForm?>(null) }
    var retainedRelease by remember { mutableStateOf<CustomFontReleasePicker?>(null) }
    var retainedZip by remember { mutableStateOf<CustomFontZipPicker?>(null) }
    var retainedDelete by remember { mutableStateOf<List<CustomFontRecord>>(emptyList()) }
    var retainedReset by remember { mutableStateOf(false) }

    SideEffect {
        if (visible) {
            retainedGithub = CustomFontImportSession.githubForm
            retainedRelease = CustomFontImportSession.releasePicker
            retainedZip = CustomFontImportSession.zipPicker
            retainedDelete = CustomFontImportSession.confirmDeleteRecords
            retainedReset = CustomFontImportSession.confirmReset
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(musicMotion(220)) + scaleIn(musicMotion(280), initialScale = .84f),
        exit = fadeOut(musicMotion(180)) + scaleOut(musicMotion(220), targetScale = .92f),
    ) {
        Box(
            modifier = Modifier.fillMaxSize().imePadding()
                .clickable(remember { MutableInteractionSource() }, null) { CustomFontImportSession.dismissOverlay() },
            contentAlignment = Alignment.Center,
        ) {
            MusicOneBackdropGlass(
                backdrop,
                bounds,
                Modifier.padding(24.dp).widthIn(max = 440.dp).fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, null) {},
                RoundedCornerShape(28.dp),
                containerColor = playlistMenuContainer,
            ) {
                Column(
                    Modifier.heightIn(max = 620.dp).verticalScroll(rememberScrollState()).padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when {
                        retainedGithub != null -> GitHubImportOverlayBody(context, retainedGithub!!)
                        retainedRelease != null -> ReleasePickerOverlayBody(context, retainedRelease!!)
                        retainedZip != null -> ZipPickerOverlayBody(context, retainedZip!!)
                        retainedDelete.isNotEmpty() -> DeleteConfirmOverlayBody(context, retainedDelete)
                        retainedReset -> ResetConfirmOverlayBody(context)
                    }
                }
            }
        }
    }

    if (visible) {
        BackHandler {
            if (CustomFontImportSession.busy) CustomFontImportSession.cancelActive()
            else CustomFontImportSession.dismissOverlay()
        }
    }
}

@Composable
private fun GitHubImportOverlayBody(context: Context, form: CustomFontGitHubForm) {
    Text("从 GitHub 链接导入", style = MaterialTheme.typography.titleLarge)
    Text(
        "支持仓库主页、Release 页、blob/raw 文件或 ZIP 直链。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LibraryFormField(
        label = "下载链接",
        value = form.url,
        onChange = { next -> CustomFontImportSession.updateGitHubForm { it.copy(url = next, error = null) } },
        enabled = !form.busy,
    )
    LibraryActionRow("从剪贴板粘贴链接", enabled = !form.busy) {
        val text = readClipboardText(context)
        if (text.isNullOrBlank()) {
            CustomFontImportSession.updateGitHubForm { it.copy(error = "剪贴板为空") }
        } else {
            CustomFontImportSession.updateGitHubForm { it.copy(url = text, error = null) }
        }
    }
    Text("下载线路", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val channelIndex = if (form.channel == CustomFontDownloadChannel.GHPROXY) 0 else 1
    LibrarySectionTabs(channelIndex, listOf(CustomFontDownloadChannel.GHPROXY.label, CustomFontDownloadChannel.DIRECT.label)) { idx ->
        if (!form.busy) {
            val next = if (idx == 0) CustomFontDownloadChannel.GHPROXY else CustomFontDownloadChannel.DIRECT
            CustomFontImportSession.updateGitHubForm { it.copy(channel = next, error = null) }
        }
    }
    Text(
        if (form.channel == CustomFontDownloadChannel.GHPROXY) {
            "ghproxy 公共代理可能随时失效，不可用时可修改前缀或改用直连。"
        } else {
            "直连 GitHub 可能需要自行配置网络环境。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (form.channel == CustomFontDownloadChannel.GHPROXY) {
        LibraryFormField(
            label = "ghproxy 前缀（默认 $CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX）",
            value = form.ghProxyPrefix,
            onChange = { next -> CustomFontImportSession.updateGitHubForm { it.copy(ghProxyPrefix = next, error = null) } },
            enabled = !form.busy,
        )
        if (form.ghProxyPrefix.trim() != CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX) {
            LibraryActionRow("恢复默认前缀", CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX, enabled = !form.busy) {
                CustomFontImportSession.updateGitHubForm { it.copy(ghProxyPrefix = CUSTOM_FONT_DEFAULT_GHPROXY_PREFIX, error = null) }
            }
        }
    }
    form.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    LibraryActionRow(if (form.busy) "正在获取发布列表…" else "下载并导入", enabled = !form.busy) {
        CustomFontImportSession.submitGitHubForm(context)
    }
    LibraryActionRow(if (form.busy) "取消当前任务" else "取消") {
        if (form.busy) CustomFontImportSession.cancelActive() else CustomFontImportSession.dismissOverlay()
    }
}

@Composable
private fun ReleasePickerOverlayBody(context: Context, picker: CustomFontReleasePicker) {
    Text("选择 Release 附件", style = MaterialTheme.typography.titleLarge)
    Text(picker.repoLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    picker.assets.forEach { asset ->
        LibraryActionRow(asset.name, formatFontFileSize(asset.sizeBytes)) {
            CustomFontImportSession.selectReleaseAsset(context, asset)
        }
    }
    LibraryActionRow("取消") { CustomFontImportSession.dismissOverlay() }
}

@Composable
private fun ZipPickerOverlayBody(context: Context, picker: CustomFontZipPicker) {
    val selected = remember(picker) { mutableStateListOf<String>() }
    var filter by rememberSaveable(picker.archiveName) { mutableStateOf("") }
    val shown = remember(picker.entries, filter) {
        val q = filter.trim()
        if (q.isEmpty()) picker.entries else picker.entries.filter {
            it.displayName.contains(q, ignoreCase = true) ||
                it.entryPath.contains(q, ignoreCase = true) ||
                (it.hint?.contains(q, ignoreCase = true) == true)
        }
    }
    Text("选择压缩包内的字体", style = MaterialTheme.typography.titleLarge)
    Text(
        "${picker.archiveName} · 共 ${picker.entries.size} 个字体",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (picker.entries.size > 8) {
        LibraryFormField("按名称或字重筛选", filter, { filter = it })
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LibraryActionButton("全选当前", {
            shown.forEach { if (it.entryPath !in selected) selected.add(it.entryPath) }
        }, Modifier.weight(1f), primary = false)
        LibraryActionButton("清空已选", { selected.clear() }, Modifier.weight(1f), enabled = selected.isNotEmpty(), primary = false)
    }
    shown.forEach { entry ->
        val checked = entry.entryPath in selected
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                .toggleable(checked, role = Role.Checkbox) { next ->
                    if (next) { if (entry.entryPath !in selected) selected.add(entry.entryPath) }
                    else selected.remove(entry.entryPath)
                }
                .padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(entry.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val meta = listOfNotNull(entry.hint, entry.sizeBytes.takeIf { it > 0L }?.let(::formatFontFileSize)).joinToString(" · ")
                if (meta.isNotEmpty()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    LibraryActionRow("导入已选（${selected.size}）", enabled = selected.isNotEmpty()) {
        CustomFontImportSession.confirmZipSelection(context, selected.toSet())
    }
    LibraryActionRow("取消") { CustomFontImportSession.dismissOverlay() }
}

@Composable
private fun DeleteConfirmOverlayBody(context: Context, records: List<CustomFontRecord>) {
    val cfg = CustomFontStore.config
    if (records.size == 1) {
        val record = records[0]
        val bound = cfg.boundLabels(record.id)
        Text("移除字体「${record.displayName}」？", style = MaterialTheme.typography.titleLarge)
        Text(
            "当前已绑定到：${bound.joinToString("、")}。移除后这些槽位会自动恢复回退风格。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LibraryActionRow("确认移除") { CustomFontImportSession.confirmDeletePending(context) }
    } else {
        val boundSlots = records.flatMap { cfg.boundLabels(it.id) }.distinct()
        val message = if (boundSlots.isNotEmpty()) {
            "其中包含已绑定槽位（${boundSlots.joinToString("、")}）。移除后这些槽位会自动恢复回退风格，本地字体文件也会一并删除。"
        } else {
            "将从本地字体库移除这 ${records.size} 个字体文件，可随时重新导入。"
        }
        Text("移除已选的 ${records.size} 个字体？", style = MaterialTheme.typography.titleLarge)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LibraryActionRow("确认移除（${records.size}）") { CustomFontImportSession.confirmDeletePending(context) }
    }
    LibraryActionRow("取消") { CustomFontImportSession.dismissOverlay() }
}

@Composable
private fun ResetConfirmOverlayBody(context: Context) {
    Text("重置自定义字体？", style = MaterialTheme.typography.titleLarge)
    Text(
        "将删除所有已导入的本地字体文件，并把界面、歌词与横屏时钟全部恢复为默认。",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LibraryActionRow("确认重置") { CustomFontImportSession.confirmResetAll(context) }
    LibraryActionRow("取消") { CustomFontImportSession.dismissOverlay() }
}

private fun readClipboardText(context: Context): String? {
    val manager = context.getSystemService(ClipboardManager::class.java) ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount <= 0) return null
    return clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim()
}
