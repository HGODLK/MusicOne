package com.musicone.source.kugou

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*

internal val LocalKugouSharedUi = staticCompositionLocalOf<SourceContentUi?> { null }
internal val LocalKugouAccountId = staticCompositionLocalOf<String?> { null }

@Composable internal fun KugouPackageContent(controller: KugouPageController, authentication: KugouSourceAuthenticationPage, route: SourceRoute) {
    val embedded = route.arguments["embedded"] == "true"
    val active = route.arguments["active"] != "false"
    val controllerPage by controller.page.collectAsState()
    val visiblePage = kugouVisiblePage(route.page, active, controllerPage.page, controller.rootPage)
    val page = if (visiblePage == controllerPage.page) controllerPage else KugouPageState(page = visiblePage)
    val homeScroll = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val myScroll = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val atRoot = page.page in setOf(MusicOnePage.HOME, MusicOnePage.MY)
    val bottomInset = if (embedded) route.arguments["bottomInsetDp"]?.toFloatOrNull()?.coerceAtLeast(0f)?.dp ?: 0.dp else 0.dp
    val catalog by controller.catalog.collectAsState()
    val library by controller.library.collectAsState()
    val favorites by controller.favorites.collectAsState()
    val playerFlow = controller.owner.environment.host.playerState
    val player = if (playerFlow == null) null else playerFlow.collectAsState().value
    val current = if (player?.sourceId == KUGOU_SOURCE_ID) (catalog.recommendedTracks + page.playlist?.tracks.orEmpty() + page.searchTracks + library.favoritePlaylist?.tracks.orEmpty())
        .firstOrNull { it.id == player.stableTrackId } else null
    val ui = MusicOneUiState(page.page, current, player?.isPlaying == true)
    val searchOpened = page.page == MusicOnePage.SEARCH
    val searchMotion = rememberKugouSearchMotion(searchOpened)
    var info by remember { mutableStateOf<MusicTrack?>(null) }
    LaunchedEffect(route.page, active, route.arguments["query"], route.arguments["id"], route.arguments["collectionId"]) {
        if (!active) return@LaunchedEffect
        when (route.page.lowercase()) {
            "home" -> controller.tab(MusicOnePage.HOME)
            "my", "mine" -> controller.tab(MusicOnePage.MY)
            "search" -> { controller.navigate(MusicOnePage.SEARCH); route.arguments["query"]?.let { controller.search(it) } }
            "playlist", "collection", "album" -> {
                val id = route.arguments["id"] ?: route.arguments["collectionId"] ?: error("缺少歌单身份")
                controller.open(MusicPlaylist(id, title = route.arguments["title"].orEmpty().ifBlank { "酷狗歌单" }))
            }
            "authentication", "settings", "account" -> controller.navigate(MusicOnePage.LOGIN)
            else -> error("酷狗未声明此内容页面：${route.page}")
        }
    }
    CompositionLocalProvider(LocalMusicFavorites provides KugouFavoriteActions(favorites, controller::favorite),
        LocalKugouTrackMenu provides controller::showMenu,
        LocalKugouSharedUi provides controller.owner.environment.host.contentUi,
        LocalKugouAccountId provides controller.owner.account()?.userId) {
        MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = Color(KUGOU_PAGE_COLOR))) {
            KugouSharedTrackMenu(controller.owner.environment.host.contentUi, bottomInset.value) {
            Box(Modifier.fillMaxSize().padding(bottom = bottomInset).background(MaterialTheme.colorScheme.background)) {
                when (page.page) {
                    MusicOnePage.HOME, MusicOnePage.SEARCH -> KugouHomeScreen(ui.copy(page = MusicOnePage.HOME), catalog,
                        MusicPlaybackRuntime(controller::play, controller::play), 0.dp,
                        PlatformHomeActions(controller::refreshTracks, { controller.open(it, true) }, { controller.open(it) }, controller::play),
                        topInset = 82.dp, scroll = homeScroll)
                    MusicOnePage.MY -> KugouMyScreen(controller.owner.account(), library, 0.dp,
                        { controller.navigate(MusicOnePage.LOGIN) }, { controller.open(it, it.tracks.isNotEmpty()) }, controller::refreshLibrary,
                        topInset = 88.dp, scroll = myScroll)
                    MusicOnePage.PLAYLIST -> KugouCollectionPage(controller, ui, favorites)
                    MusicOnePage.LOGIN -> Box(Modifier.padding(top = 72.dp)) { authentication.Content() }
                }
                if (page.page != MusicOnePage.SEARCH && !(embedded && atRoot)) Row(Modifier.fillMaxWidth().height(72.dp).background(MaterialTheme.colorScheme.background)
                    .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (page.page in setOf(MusicOnePage.PLAYLIST, MusicOnePage.LOGIN)) IconButton({ if (!controller.back()) controller.owner.environment.host.exitSource() }) { Icon(Icons.Default.ArrowBack, "返回") }
                    else {
                        TextButton({ controller.tab(MusicOnePage.HOME) }) { Text("首页") }
                        TextButton({ controller.tab(MusicOnePage.MY) }) { Text("我的") }
                    }
                    Spacer(Modifier.weight(1f))
                    if (page.page != MusicOnePage.LOGIN) IconButton({ controller.navigate(MusicOnePage.SEARCH) }) { Icon(Icons.Default.Search, "搜索酷狗音乐") }
                    if (page.page == MusicOnePage.HOME) IconButton(controller::refreshHome) { Icon(Icons.Default.Refresh, "刷新首页") }
                    if (page.page == MusicOnePage.MY) IconButton(controller::refreshLibrary) { Icon(Icons.Default.Refresh, "刷新我的歌单") }
                }
                if (searchOpened || searchMotion.mounted) Box(Modifier.fillMaxSize().clip(KugouSearchRevealShape { searchMotion.reveal.value })
                    .background(MaterialTheme.colorScheme.background)) { KugouSearchPage(controller, ui, favorites) }
                page.message?.let { message ->
                    Snackbar(Modifier.align(Alignment.BottomCenter).padding(16.dp), action = { TextButton({ controller.page.value = controller.page.value.copy(message = null) }) { Text("关闭") } }) { Text(message) }
                }
            }
            }
            page.menuTrack?.let { track ->
                val selection = page.menuSelection.ifEmpty { listOf(track) }
                AlertDialog(onDismissRequest = controller::dismissMenu, title = { Text(if (selection.size == 1) track.title else "已选择 ${selection.size} 首歌曲") },
                    text = { Column {
                        TextButton({ controller.playTracks(selection); controller.dismissMenu() }) { Text("播放") }
                        TextButton({ val liked = !selection.all { it.id in favorites.ids }; selection.forEach { controller.setFavorite(it, liked) }; controller.dismissMenu() }) { Text(if (selection.all { it.id in favorites.ids }) "取消喜欢" else "加入我喜欢") }
                        TextButton(controller::chooseOnlineTargets) { Text("添加到酷狗歌单") }
                        TextButton(controller::chooseLocalTargets) { Text("导入本地歌单") }
                        TextButton({ controller.cache(selection) }) { Text("缓存") }
                        if (selection.size == 1) TextButton({ info = track; controller.dismissMenu() }) { Text("歌曲资料") }
                        page.playlist?.takeIf { it.isOwned && !it.isPlatformFavorites }?.let { collection ->
                            TextButton({ controller.removeOnline(selection, collection); controller.dismissMenu() }) { Text("从此歌单移除") }
                        }
                    } }, confirmButton = { TextButton(controller::dismissMenu) { Text("关闭") } })
            }
            page.destinations?.let { targets -> AlertDialog(onDismissRequest = controller::dismissMenu, title = { Text("选择本地导入目标") },
                text = { Column { if (targets.isEmpty()) Text("没有允许手动添加的本地歌单") else targets.forEach { target -> TextButton({ controller.importLocal(target) }) { Text(target.title) } } } },
                confirmButton = { TextButton(controller::dismissMenu) { Text("取消") } }) }
            page.onlineTargets?.let { targets -> AlertDialog(onDismissRequest = controller::dismissMenu, title = { Text("选择酷狗歌单") },
                text = { Column { if (targets.isEmpty()) Text("请先在酷狗音乐创建歌单") else targets.forEach { target -> TextButton({ controller.importOnline(target) }) { Text(target.title) } } } },
                confirmButton = { TextButton(controller::dismissMenu) { Text("取消") } }) }
            info?.let { track -> AlertDialog(onDismissRequest = { info = null }, title = { Text(track.title) },
                text = { Text("${track.artists}\n${track.album}\n时长 ${track.durationMs / 1000} 秒\n音质 ${track.qualityIds.keys.joinToString { it.label }}") },
                confirmButton = { TextButton({ info = null }) { Text("关闭") } }) }
        }
    }
}

@Composable private fun KugouSharedTrackMenu(shared: SourceContentUi?, bottomInset: Float, content: @Composable () -> Unit) {
    if (shared == null) content() else shared.TrackMenuHost(KUGOU_SOURCE_ID, bottomInset, content)
}
@Composable private fun isSystemInDarkThemeCompat(): Boolean = MaterialTheme.colorScheme.background.luminance() < .5f
