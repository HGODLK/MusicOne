package com.musicone.source.webdav

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.CloudQueue
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*
import com.musicone.source.webdav.internal.*

/** 按页面实例注册返回键拦截，避免旧页面退出动画结束时误清掉新页面的返回处理。 */
internal typealias WebDavBackBinder = (owner: Any, handler: (() -> Boolean)?) -> Unit

/** 切换动画只根据目录路径触发，刷新当前目录内容不播转场，退场中的页面保留各自列表状态。 */
private data class WebDavScreen(val bookmarks: Boolean, val path: String, val trackState: SourceTrackListState) {
    val key: String get() = if (bookmarks) "bookmarks" else "dir:$path"
    val rank: Int get() = if (bookmarks) Int.MAX_VALUE else webDavDepth(path)
}

/**
 * WebDAV 文件浏览首页（HOME=CUSTOM）。标题栏、返回箭头和层级入场由宿主管理。
 * 手机和横屏单栏；宽高都足够时（平板）左侧目录树、右侧列表。
 */
@Composable
internal fun WebDavBrowserPage(
    context: SourceSupplementalPageContext,
    host: SourceHost,
    auth: WebDavAuthentication,
    controller: WebDavBrowserController,
    bindBack: WebDavBackBinder,
) {
    val ui = remember(host) { host.contentUi?.components?.forSource(WEBDAV_SOURCE_ID) }
    val reduceMotion = host.reduceMotion
    val revision = auth.observedRevision
    LaunchedEffect(controller, revision) { controller.sync() }
    DisposableEffect(controller) {
        controller.onShown()
        onDispose { controller.onHidden() }
    }
    val server = remember(revision) { auth.currentServer() }
    val bottomInset = context.bottomInsetDp.dp
    CompositionLocalProvider(LocalWebDavMotion provides remember(reduceMotion) { WebDavMotion(reduceMotion) }) {
        when {
            ui == null -> Box(Modifier.fillMaxSize().padding(24.dp)) { Text("当前版本不支持此页面，请更新 MusicOne。") }
            server == null || server.expired -> WebDavConnectGuide(ui, server, bottomInset) {
                context.actions.navigate(SourceSupplementalPage.ACCOUNT)
            }
            // 换账号时整页重建：旧列表状态已失效，不能留在退场动画里继续使用。
            controller.view.serverKey == server.key -> key(server.key, revision) {
                WebDavBrowserContent(context, host, controller, server, ui, bindBack)
            }
        }
    }
}

@Composable
private fun WebDavBrowserContent(
    context: SourceSupplementalPageContext,
    host: SourceHost,
    controller: WebDavBrowserController,
    server: WebDavServer,
    ui: SourceComponentScope,
    bindBack: WebDavBackBinder,
) {
    val motion = LocalWebDavMotion.current
    val bottomInset = context.bottomInsetDp.dp
    val view = controller.view
    val player by (host.playerState?.collectAsState() ?: remember { mutableStateOf(SourcePlayerState()) })
    val trackState = ui.rememberTrackListState("webdav:${server.key}:${view.path}")
    var importOpen by remember(view.path) { mutableStateOf(false) }

    val latestTrackState by rememberUpdatedState(trackState)
    val latestImportOpen = rememberUpdatedState(importOpen)
    val closeImport by rememberUpdatedState { importOpen = false }
    val backOwner = remember { Any() }
    DisposableEffect(backOwner) {
        bindBack(backOwner) {
            when {
                controller.showBookmarks -> { controller.closeBookmarks(); true }
                latestTrackState.selectionActive -> { latestTrackState.clearSelection(); true }
                latestTrackState.query.isNotEmpty() -> { latestTrackState.query = ""; true }
                latestImportOpen.value -> { closeImport(); true }
                else -> controller.navigateUp()
            }
        }
        onDispose { bindBack(backOwner, null) }
    }
    val leave: (SourceSupplementalPage, SourceSupplementalTarget) -> Unit = { page, target ->
        // 离开动画期间返回键属于新页面，不能继续退目录。
        bindBack(backOwner, null)
        context.actions.navigate(page, target)
    }
    val openServers = { leave(SourceSupplementalPage.ACCOUNT, SourceSupplementalTarget.Account) }

    val collection = remember(server.key, view.path, view.coverUri, view.tracks.size) {
        val title = WebDavPaths.fileNameOf(view.path).ifBlank { server.displayName }
        SourceCollection(
            identity = TrackIdentity(WEBDAV_SOURCE_ID, server.accountId, "dir:${view.path}"),
            stableId = "webdav:dir:${server.key}:${view.path}",
            title = title,
            subtitle = server.subtitleUrl,
            count = view.tracks.size,
            artwork = webDavArtwork(view.coverUri, title),
            owned = false,
        )
    }

    // 歌曲菜单须覆盖整页定位，因此容器包在双栏之外。
    ui.TrackListHost(collection, trackState, view.tracks, context.bottomInsetDp) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 手机横屏虽宽但高度不足，仍用单栏。
            val twoPane = maxWidth >= 720.dp && maxHeight >= 480.dp
            val wide = maxWidth >= 600.dp
            val paneGutter = if (twoPane) 8.dp else maxOf(if (wide) 32.dp else 20.dp, (maxWidth - 1120.dp) / 2)
            Row(Modifier.fillMaxSize().padding(horizontal = if (twoPane) 24.dp else 0.dp)) {
                if (twoPane) WebDavTreeSidebar(
                    server, controller,
                    Modifier.width(288.dp).fillMaxHeight().padding(bottom = bottomInset + 16.dp),
                    onOpenServers = openServers,
                )
                Box(Modifier.weight(1f).fillMaxHeight().padding(start = if (twoPane) 16.dp else 0.dp)) {
                    Column(Modifier.fillMaxSize()) {
                        WebDavPathBar(controller, server.displayName, paneGutter, showShortcuts = !twoPane, onOpenServers = openServers)
                        AnimatedContent(
                            WebDavScreen(controller.showBookmarks, view.path, trackState),
                            Modifier.weight(1f).fillMaxWidth(),
                            transitionSpec = { motion.pageTransform(targetState.rank >= initialState.rank) },
                            contentKey = { it.key },
                            label = "文件夹层级衔接",
                        ) { screen ->
                            if (screen.bookmarks) {
                                WebDavBookmarksPage(ui, controller, paneGutter, bottomInset) { bookmark ->
                                    leave(SourceSupplementalPage.COLLECTION,
                                        SourceSupplementalTarget.Collection(controller.collectionOf(bookmark)))
                                }
                            } else {
                                WebDavDirectoryPage(
                                    ui = ui,
                                    controller = controller,
                                    view = rememberScreenView(controller, screen.path),
                                    trackState = screen.trackState,
                                    player = player,
                                    serverName = server.displayName,
                                    gutter = paneGutter,
                                    bottomInset = bottomInset,
                                    importOpen = importOpen && screen.path == view.path,
                                    onImportOpenChange = { importOpen = it },
                                )
                            }
                        }
                    }
                    WebDavStatusPill(
                        controller.busyMessage, controller.feedback,
                        // 右侧留给搜索按钮。
                        Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 88.dp, bottom = bottomInset + 22.dp),
                        controller::dismissFeedback,
                    )
                }
            }
        }
    }
}

/** 渲染退场动画里的目录页，保持它最后一份数据，不被新目录覆盖。 */
@Composable
private fun rememberScreenView(controller: WebDavBrowserController, path: String): WebDavDirectoryView {
    val live = controller.view
    val holder = remember(path) { arrayOf(live) }
    if (live.path == path) holder[0] = live
    return holder[0]
}

@Composable
private fun WebDavConnectGuide(ui: SourceComponentScope, server: WebDavServer?, bottomInset: Dp, onOpen: () -> Unit) {
    val expired = server?.expired == true
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = bottomInset + 24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        WebDavEmpty(
            icon = if (expired) Icons.Rounded.CloudOff else Icons.Rounded.CloudQueue,
            title = if (expired) "服务器登录已失效" else "连接 WebDAV 服务器",
            message = if (expired) "「${server.displayName}」拒绝了当前账号，请重新输入密码或切换服务器。"
            else "支持 OpenList、AList、NAS、坚果云等 WebDAV 服务，连接后可直接浏览文件夹、播放并订阅为本地歌单。",
        ) {
            ui.ActionButton(if (expired) "重新登录" else "添加服务器", onClick = onOpen)
        }
    }
}
