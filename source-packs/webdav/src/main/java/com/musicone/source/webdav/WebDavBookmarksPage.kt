package com.musicone.source.webdav

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.SourceComponentScope
import com.musicone.source.webdav.internal.WebDavBookmark
import com.musicone.source.webdav.internal.webDavArtwork

/** 书签列表；点按进入宿主收藏详情页，在那里播放或订阅为本地歌单。 */
@Composable
internal fun WebDavBookmarksPage(
    ui: SourceComponentScope,
    controller: WebDavBrowserController,
    gutter: Dp,
    bottomInset: Dp,
    onOpenCollection: (WebDavBookmark) -> Unit,
) {
    val motion = LocalWebDavMotion.current
    var removing by remember { mutableStateOf<WebDavBookmark?>(null) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = gutter, end = gutter, bottom = bottomInset + 96.dp),
    ) {
        item("title") { WebDavSectionTitle("书签与订阅", Modifier.padding(bottom = 4.dp)) }
        items(controller.bookmarks, key = { it.id }) { bookmark ->
            WebDavFolderRow(
                title = bookmark.title,
                subtitle = listOfNotNull(bookmark.path.ifEmpty { "根目录" }, bookmark.trackCount?.let { "$it 首" }).joinToString(" · "),
                modifier = Modifier.animateItem(motion.spec(300), motion.spec(280), motion.spec(180)),
                leading = { ui.Artwork(webDavArtwork(bookmark.coverFileUri, bookmark.title), Modifier.size(52.dp), cornerDp = 16f) },
                trailing = {
                    BookmarkMenu(
                        onBrowse = { controller.open(bookmark.path) },
                        onRemove = { removing = bookmark },
                    )
                },
            ) { onOpenCollection(bookmark) }
        }
        if (controller.bookmarks.isEmpty()) item("empty") {
            WebDavEmpty(
                icon = Icons.Rounded.BookmarkBorder,
                title = "还没有书签",
                message = "在文件夹标题右侧点按书签图标，就能在这里订阅为本地歌单",
                modifier = Modifier.animateItem(motion.spec(300), motion.spec(260), motion.spec(180)),
            )
        }
    }
    removing?.let { bookmark ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("移除「${bookmark.title}」？") },
            text = { Text("已订阅的本地歌单和歌曲会保留，但不能再刷新。") },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    controller.removeBookmark(bookmark)
                }) { Text("移除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun BookmarkMenu(onBrowse: () -> Unit, onRemove: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, "书签操作", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("在文件夹中打开") }, onClick = { expanded = false; onBrowse() })
            DropdownMenuItem(text = { Text("移除书签") }, onClick = { expanded = false; onRemove() })
        }
    }
}
