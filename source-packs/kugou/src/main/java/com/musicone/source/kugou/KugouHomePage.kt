package com.musicone.source.kugou

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musicone.source.sdk.*

/** 只组织酷狗首页；歌曲货架、歌单卡与后续页面均使用现有公共实现。 */
@Composable
internal fun KugouHomePage(
    state: KugouHomeState, components: SourceComponentScope, shelves: SourceHomeShelfComponents,
    rootUi: SourceRootPageUi, reportScroll: Boolean, player: SourcePlayerState,
    dailyCollection: SourceCollection, recentCollection: SourceCollection,
    bottomInsetDp: Float, onRadio: () -> Unit, onDaily: () -> Unit,
    onRecent: () -> Unit,
    onRefreshRecommendations: () -> Unit,
    onOpenCollection: (SourceCollection) -> Unit, onPlayCollection: (SourceCollection) -> Unit,
    onPlayTrack: (SourceTrackDetails) -> Unit,
) {
    val list = rememberLazyListState()
    if (reportScroll) rootUi.ReportScroll("home") {
        SourceRootScrollPosition(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset)
    }
    val recommendations = remember(state.homeSections) {
        state.homeSections.filter { it.id == "kugou.personal-recommendations" }
    }
    val playlists = remember(state.homeSections) {
        state.homeSections.flatMap { it.collections }.distinctBy { it.stableId }.take(KUGOU_HOME_PLAYLIST_LIMIT)
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val gutter = if (wide) 32.dp else 20.dp
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxHeight().widthIn(max = 1120.dp).fillMaxWidth().align(Alignment.TopCenter),
            contentPadding = PaddingValues(start = gutter, end = gutter, top = 112.dp,
                bottom = bottomInsetDp.coerceAtLeast(0f).dp + 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            item("radio") {
                KugouBlueRadioCard(if (wide) 208 else 192, onRadio)
            }
            item("personal") {
                KugouHomePersonalCards(state, components, dailyCollection, recentCollection,
                    if (wide) 240f else 216f, !wide, onDaily, onRecent, { onPlayCollection(recentCollection) })
                if (state.dailyError != null || state.recentError != null) {
                    components.InlineMessage(listOfNotNull(state.dailyError, state.recentError).joinToString("\n"))
                }
            }
            recommendations.forEach { section ->
                item(section.id) {
                    Box(Modifier.fillMaxWidth()) {
                        shelves.SongShelf(section.id, section.title, section.tracks.chunked(3),
                            player, showFavorites = true, edgeInsetDp = gutter.value, onClick = onPlayTrack)
                        KugouHomeRefresh(state.loadingHome, "刷新推荐", onRefreshRecommendations,
                            Modifier.align(Alignment.TopEnd).offset(y = (-10).dp))
                    }
                }
            }
            item("playlists") {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    // 两个按钮刷新同一份首页数据；没有歌曲货架时这里是唯一的手动重试入口。
                    KugouHomeRefreshHeading(KUGOU_HOME_PLAYLIST_TITLE, state.loadingHome,
                        "刷新推荐", onRefreshRecommendations, showRefresh = recommendations.isEmpty())
                    if (playlists.isNotEmpty()) shelves.CollectionShelf(playlists, gutter.value, onPlayCollection, onOpenCollection)
                    else if (state.loadingHome) components.InlineMessage("正在加载推荐歌单…")
                    else components.InlineMessage(state.homeError ?: "暂时没有推荐歌单")
                    if (playlists.isNotEmpty() && state.homeError != null) components.InlineMessage(state.homeError)
                }
            }
        }
    }
}

/** 蓝卡沿用原酷狗渐变和圆角，点击只进入现有私人电台，不另拉一批歌曲作假预览。 */
@Composable
private fun KugouBlueRadioCard(heightDp: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = Color.Transparent,
        modifier = Modifier.fillMaxWidth().height(heightDp.dp)) {
        Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF00A8FF), Color(0xFF0874D1))))) {
            Box(Modifier.size(182.dp).align(Alignment.CenterEnd).offset(x = 24.dp)
                .clip(CircleShape).background(Color.White.copy(alpha = .08f)))
            Icon(Icons.Rounded.Favorite, null, tint = Color.White.copy(alpha = .12f),
                modifier = Modifier.size(112.dp).align(Alignment.CenterEnd).padding(end = 28.dp))
            Column(Modifier.fillMaxSize().padding(22.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("猜你喜欢", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text("为你发现喜欢的好音乐", color = Color.White.copy(alpha = .88f), fontSize = 13.sp)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("开启你的私人电台", Modifier.weight(1f), color = Color.White.copy(alpha = .9f), fontSize = 13.sp)
                    Surface(shape = CircleShape, color = Color.White, modifier = Modifier.size(48.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(Icons.Rounded.PlayArrow, "打开猜你喜欢电台", tint = Color(0xFF0874D1), modifier = Modifier.size(28.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KugouHomeRefreshHeading(title: String, loading: Boolean, label: String, onRefresh: () -> Unit, showRefresh: Boolean) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
        if (showRefresh) KugouHomeRefresh(loading, label, onRefresh)
    }
}

@Composable
internal fun KugouHomeRefresh(loading: Boolean, label: String, onRefresh: () -> Unit, modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    IconButton(onClick = onRefresh, enabled = !loading, modifier = modifier.size(48.dp),
        colors = IconButtonDefaults.iconButtonColors(contentColor = color, disabledContentColor = color.copy(alpha = .4f))) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = color, strokeWidth = 2.dp)
        else Icon(Icons.Rounded.Refresh, label, modifier = Modifier.size(20.dp))
    }
}
