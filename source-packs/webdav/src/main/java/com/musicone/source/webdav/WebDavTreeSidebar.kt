package com.musicone.source.webdav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.webdav.internal.WebDavServer

/** 平板左栏：服务器、书签与目录树；展开、收起和当前位置变化都从现有位置续接。 */
@Composable
internal fun WebDavTreeSidebar(
    server: WebDavServer,
    controller: WebDavBrowserController,
    modifier: Modifier = Modifier,
    onOpenServers: () -> Unit,
) {
    val motion = LocalWebDavMotion.current
    val nodes by remember(controller, server.displayName) {
        derivedStateOf { buildWebDavTree(server.displayName, controller.treeChildren, controller.expandedPaths) }
    }
    val currentPath = controller.view.path
    val bookmarks = controller.showBookmarks
    val scroll = rememberLazyListState()
    // 当前目录不在可见范围时才滚动，正常点按不移动列表。
    LaunchedEffect(currentPath, nodes.size) {
        val index = nodes.indexOfFirst { it.path == currentPath }
        val layout = scroll.layoutInfo
        if (index < 0 || layout.visibleItemsInfo.isEmpty()) return@LaunchedEffect
        val visible = layout.visibleItemsInfo.any {
            it.key == "node:$currentPath" && it.offset >= layout.viewportStartOffset &&
                it.offset + it.size <= layout.viewportEndOffset
        }
        if (visible) return@LaunchedEffect
        val lead = -(layout.viewportSize.height / 3)
        val target = index + HEADER_ITEMS
        if (motion.reduce) scroll.scrollToItem(target, lead) else scroll.animateScrollToItem(target, lead)
    }
    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .65f)) {
        LazyColumn(Modifier.fillMaxSize(), state = scroll, contentPadding = PaddingValues(12.dp)) {
            item("server") {
                SidebarEntry(Icons.Rounded.Dns, server.displayName, "管理服务器", selected = false, onClick = onOpenServers)
            }
            item("bookmarks") {
                SidebarEntry(Icons.Rounded.Bookmarks, "书签与订阅", "${controller.bookmarks.size} 个书签", bookmarks) {
                    if (bookmarks) controller.closeBookmarks() else controller.openBookmarks()
                }
            }
            item("divider") {
                HorizontalDivider(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .1f))
            }
            items(nodes, key = { "node:${it.path}" }) { node ->
                TreeRow(
                    node = node,
                    selected = !bookmarks && currentPath == node.path,
                    loading = controller.treeLoading[node.path] == true,
                    modifier = Modifier.animateItem(motion.spec(220), motion.spec(280), motion.spec(140)),
                    onToggle = { controller.toggleTree(node.path) },
                    onOpen = {
                        if (currentPath == node.path) controller.closeBookmarks() else controller.open(node.path)
                    },
                )
            }
        }
    }
}

private const val HEADER_ITEMS = 3

@Composable
private fun rowBackground(selected: Boolean): State<androidx.compose.ui.graphics.Color> = animateColorAsState(
    MaterialTheme.colorScheme.onSurface.copy(alpha = if (selected) .1f else 0f),
    LocalWebDavMotion.current.spec(220), label = "选中底色",
)

@Composable
private fun SidebarEntry(icon: ImageVector, title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    val background by rowBackground(selected)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp)).background(background)
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun TreeRow(
    node: WebDavTreeNode,
    selected: Boolean,
    loading: Boolean,
    modifier: Modifier,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
) {
    val motion = LocalWebDavMotion.current
    val background by rowBackground(selected)
    val rotation = animateFloatAsState(if (node.expanded) 90f else 0f, motion.spec(220), label = "展开箭头")
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp)).background(background)
            .selectable(selected, role = Role.Tab, onClick = onOpen)
            // 层级很深时缩进封顶，给名称留出空间。
            .padding(start = (node.depth.coerceAtMost(6) * 14).dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp, 48.dp), contentAlignment = Alignment.Center) {
            when {
                loading -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                !node.leaf -> IconButton(onClick = onToggle, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Rounded.ChevronRight, if (node.expanded) "收起${node.label}" else "展开${node.label}",
                        Modifier.size(20.dp).graphicsLayer { rotationZ = rotation.value },
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        Icon(if (selected) Icons.Rounded.FolderOpen else Icons.Rounded.Folder, null, Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(node.label, Modifier.weight(1f).padding(start = 10.dp), fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
