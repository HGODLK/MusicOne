package com.musicone.source.webdav

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.SourceComponentScope
import com.musicone.source.sdk.SourceLocalCollection
import com.musicone.source.webdav.internal.WebDavPaths
import com.musicone.source.webdav.internal.webDavArtwork

private data class WebDavCrumb(val key: String, val label: String, val onClick: () -> Unit)

/** 固定在列表上方的路径条；切目录时只有新增或移除的层级参与动画，并自动滚到当前层。 */
@Composable
internal fun WebDavPathBar(
    controller: WebDavBrowserController,
    rootLabel: String,
    gutter: Dp,
    showShortcuts: Boolean,
    onOpenServers: () -> Unit,
) {
    val motion = LocalWebDavMotion.current
    val path = controller.view.path
    val bookmarks = controller.showBookmarks
    val crumbs = remember(path, rootLabel, bookmarks) {
        val folders = WebDavPaths.breadcrumbs(path, rootLabel).map { (label, target) ->
            WebDavCrumb("dir:$target", label) { controller.open(target) }
        }
        // 进书签页时保留当前目录的面包屑，点它直接返回原目录不重新拉取
        if (bookmarks) listOf(folders.last().copy(onClick = controller::closeBookmarks), WebDavCrumb("bookmarks", "书签与订阅") {})
        else folders
    }
    val scroll = rememberLazyListState()
    LaunchedEffect(crumbs) {
        if (motion.reduce) scroll.scrollToItem(crumbs.lastIndex) else scroll.animateScrollToItem(crumbs.lastIndex)
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = (gutter - 8.dp).coerceAtLeast(0.dp), end = (gutter - 12.dp).coerceAtLeast(0.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LazyRow(Modifier.weight(1f), state = scroll, verticalAlignment = Alignment.CenterVertically) {
            itemsIndexed(crumbs, key = { _, crumb -> crumb.key }) { index, crumb ->
                val last = index == crumbs.lastIndex
                val color by animateColorAsState(
                    if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    motion.spec(220), label = "路径颜色",
                )
                Row(
                    Modifier.animateItem(motion.spec(220), motion.spec(260), motion.spec(160)),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (index > 0) Icon(Icons.Rounded.ChevronRight, null, Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Box(
                        Modifier.heightIn(min = 48.dp).clip(CircleShape)
                            .clickable(enabled = !last, role = Role.Button, onClick = crumb.onClick)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(crumb.label, Modifier.widthIn(max = 220.dp), color = color, fontSize = 14.sp,
                            fontWeight = if (last) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (showShortcuts) {
            IconButton(onClick = { if (bookmarks) controller.closeBookmarks() else controller.openBookmarks() }) {
                WebDavSwapIcon(if (bookmarks) Icons.Rounded.Close else Icons.Rounded.Bookmarks, if (bookmarks) "返回文件夹" else "书签与订阅")
            }
            IconButton(onClick = onOpenServers) { Icon(Icons.Rounded.Dns, "服务器") }
        }
    }
}

/** 当前目录标题：有目录封面时从左侧展开，其余时候不占位置。 */
@Composable
internal fun WebDavFolderTitle(
    ui: SourceComponentScope,
    view: WebDavDirectoryView,
    title: String,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onRefresh: () -> Unit,
) {
    val motion = LocalWebDavMotion.current
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
        AnimatedVisibility(
            !view.coverUri.isNullOrBlank(),
            enter = expandHorizontally(motion.spec(320)) + fadeIn(motion.spec(300)),
            exit = shrinkHorizontally(motion.spec(260)) + fadeOut(motion.spec(180)),
        ) {
            Box(Modifier.padding(end = 16.dp)) {
                ui.Artwork(webDavArtwork(view.coverUri, title), Modifier.size(72.dp), cornerDp = 16f)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                when {
                    view.loaded -> webDavCountText(view.directories.size, view.tracks.size)
                    view.loading -> "正在读取…"
                    else -> "未能读取"
                },
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onToggleBookmark, enabled = view.loaded) {
            WebDavSwapIcon(if (bookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder, if (bookmarked) "取消书签" else "加入书签")
        }
        WebDavRefreshButton(view.loading, onRefresh)
    }
}

/** 按钮尺寸和宿主歌单页保持一致；排序放到歌曲标题栏了，这里第三个按钮换成“加入歌单”。 */
@Composable
internal fun WebDavPlayActions(
    enabled: Boolean,
    importOpen: Boolean,
    showIncludeToggle: Boolean,
    includeSubfolders: Boolean,
    onIncludeChange: (Boolean) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onImport: () -> Unit,
) {
    val motion = LocalWebDavMotion.current
    val soft = IconButtonDefaults.filledIconButtonColors(
        containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = .06f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    )
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.widthIn(max = 380.dp).fillMaxWidth().padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(onShuffle, Modifier.size(48.dp), enabled, CircleShape, soft) { Icon(Icons.Rounded.Shuffle, "随机播放") }
            Button(
                onClick = onPlay, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp), shape = CircleShape,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.onSurface,
                    contentColor = MaterialTheme.colorScheme.surface,
                ),
            ) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("播放", fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            FilledIconButton(onImport, Modifier.size(48.dp), enabled, CircleShape, soft) {
                WebDavSwapIcon(if (importOpen) Icons.Rounded.Close else Icons.AutoMirrored.Rounded.PlaylistAdd, if (importOpen) "收起" else "加入歌单")
            }
        }
        AnimatedVisibility(
            showIncludeToggle,
            enter = expandVertically(motion.spec(220)) + fadeIn(motion.spec(180)),
            exit = shrinkVertically(motion.spec(180)) + fadeOut(motion.spec(140)),
        ) {
            WebDavTogglePill("包含子文件夹", includeSubfolders, onChange = onIncludeChange)
        }
    }
}

/** 就地展开的导入面板：目标选择沿用宿主“添加到”菜单，不再弹原生对话框。 */
@Composable
internal fun WebDavImportPanel(
    ui: SourceComponentScope,
    controller: WebDavBrowserController,
    folderTitle: String,
    onImport: (SourceLocalCollection?, String) -> Unit,
) {
    val motion = LocalWebDavMotion.current
    var destinations by remember { mutableStateOf<List<SourceLocalCollection>>(emptyList()) }
    var failure by remember { mutableStateOf<String?>(null) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var newTitle by remember(folderTitle) { mutableStateOf(folderTitle) }
    LaunchedEffect(Unit) {
        try {
            destinations = controller.importDestinations()
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            failure = error.message ?: "读取本地歌单失败"
        }
    }
    val selected = destinations.firstOrNull { it.id == selectedId }
    val idle = controller.busyMessage == null
    ui.SettingsGroup("加入歌单", Modifier.padding(bottom = 12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (controller.effectiveIncludeSubfolders) "将加入当前文件夹及全部子文件夹的歌曲" else "将加入当前文件夹的歌曲",
                fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            failure?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error) }
            AnimatedVisibility(
                destinations.isNotEmpty(),
                enter = expandVertically(motion.spec(220)) + fadeIn(motion.spec(180)),
            ) {
                ui.ImportTarget(destinations, selectedId) { selectedId = it.id }
            }
            AnimatedVisibility(
                creating,
                enter = expandVertically(motion.spec(260)) + fadeIn(motion.spec(220)),
                exit = shrinkVertically(motion.spec(220)) + fadeOut(motion.spec(160)),
            ) {
                ui.FormField("新歌单名称", newTitle, idle) { newTitle = it }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.ActionButton(
                    if (creating) "新建并加入" else "加入",
                    enabled = idle && if (creating) newTitle.isNotBlank() else selected != null,
                ) { onImport(selected.takeUnless { creating }, newTitle) }
                ui.ActionButton(if (creating) "选择已有歌单" else "新建歌单", enabled = idle, primary = false) { creating = !creating }
            }
        }
    }
}
