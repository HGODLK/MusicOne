package org.example.source

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.musicone.source.sdk.*

/** 只保存示例自身的导航，列表选择和搜索状态仍由公共实现维护。 */
internal class ExamplePublicUiDemoState {
    var showList by mutableStateOf(false)
    var embeddedRequest by mutableStateOf<SourcePageRequest?>(null)
    var listState: SourceTrackListState? = null

    fun onBack(): Boolean {
        val state = listState
        return when {
            embeddedRequest != null -> { embeddedRequest = null; true }
            state?.selectionActive == true -> { state.clearSelection(); true }
            !state?.query.isNullOrEmpty() -> { state.query = ""; true }
            showList -> { showList = false; true }
            else -> false
        }
    }

    fun reset() { embeddedRequest = null; showList = false; listState = null }
}

@Composable
internal fun ExamplePublicUiDemo(host: SourceHost, demo: ExampleCatalog,
    navigation: ExamplePublicUiDemoState, bottomInsetDp: Float, onClose: () -> Unit) {
    val sourceId = demo.collection.identity.sourceId
    val pages = host.contentUi?.pages
    val components = host.contentUi?.components
    val scope = remember(components, sourceId) { components?.forSource(sourceId) }
    var closedPages by remember { mutableIntStateOf(0) }
    val request = navigation.embeddedRequest
    if (request != null && pages != null) {
        // 请求实例在进入时创建，重组时复用，避免公共页面状态反复初始化。
        pages.Content(request, modifier = Modifier.fillMaxSize(), bottomInsetDp = bottomInsetDp, onClose = {
            if (navigation.embeddedRequest === request) {
                navigation.embeddedRequest = null
                closedPages++
            }
        })
        return
    }
    if (navigation.showList && scope != null) {
        val state = scope.rememberTrackListState("example-full-list")
        DisposableEffect(state) {
            navigation.listState = state
            onDispose { navigation.listState = null }
        }
        scope.TrackListHost(demo.collection, state, demo.demoTracks, bottomInsetDp = bottomInsetDp) {
            Column(Modifier.fillMaxSize()
                .padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = bottomInsetDp.dp + 16.dp)) {
                Button(onClick = { if (!navigation.onBack()) onClose() }) { Text("返回（优先取消选择或搜索）") }
                Text("长按歌曲进入公共多选；示例不提供音频")
                scope.SearchTools(state)
                scope.TrackList(demo.demoTracks, state, modifier = Modifier.weight(1f),
                    onClick = { host.reportError("这是虚拟歌曲，示例未提供音频播放") },
                    onFavorite = { host.reportError("示例未声明远端收藏能力") })
            }
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
        .padding(start = 24.dp, top = 24.dp, end = 24.dp, bottom = bottomInsetDp.dp + 24.dp)) {
        Text("自定义页面复用公共 UI")
        Text("以下数据全部来自包内虚拟目录，不连接商业平台，也不提供音频。")
        Text("已返回示例页 $closedPages 次")
        fun open(request: SourcePageRequest) {
            pages?.open(request, onClosed = { closedPages++ })
        }
        Button(enabled = pages != null, onClick = { open(SourcePageRequest.Search(sourceId, "演示", submit = true)) }) { Text("打开公共搜索") }
        Button(enabled = pages != null, onClick = {
            navigation.embeddedRequest = SourcePageRequest.Search(sourceId, "演示", submit = true)
        }) { Text("嵌入公共搜索完整页面") }
        Button(enabled = pages != null, onClick = { open(SourcePageRequest.Collection(demo.collection)) }) { Text("打开公共歌单") }
        Button(enabled = pages != null, onClick = { open(SourcePageRequest.Artist(sourceId, demo.artist)) }) { Text("打开公共歌手") }
        Button(enabled = pages != null, onClick = { open(SourcePageRequest.Album(sourceId, demo.album)) }) { Text("打开公共专辑") }
        Button(enabled = scope != null, onClick = { navigation.showList = true }) { Text("嵌入公共完整列表与多选") }
        if (pages == null || scope == null) Text("当前宿主未提供相应公共 UI 扩展，请使用包含新 SDK 实现的宿主。")
        Button(onClick = onClose) { Text("返回示例首页") }
    }
}
