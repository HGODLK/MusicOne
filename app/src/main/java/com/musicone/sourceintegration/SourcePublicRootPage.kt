package com.musicone.sourceintegration

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.musicone.MusicOnePage
import com.musicone.ReportPrimaryHeaderScroll
import com.musicone.source.sdk.SourcePageRequest
import com.musicone.source.sdk.SourceRootScrollPosition

/** 嵌入一级页只把按钮回调交给宿主页头，标题和图标动画继续由宿主统一控制。 */
internal class SourcePublicRootHeader(
    val onSearch: () -> Unit,
    val onSettings: () -> Unit,
    val onSettingsBounds: (Rect) -> Unit,
)

internal fun isSourceRootPageRequest(request: SourcePageRequest): Boolean =
    request is SourcePageRequest.Home || request is SourcePageRequest.My

internal fun sourceRootScrollPage(page: String): MusicOnePage = when (page) {
    "home" -> MusicOnePage.HOME
    "my" -> MusicOnePage.MY
    else -> throw IllegalArgumentException("一级滚动位置仅支持首页和我的")
}

/** 一级页仍使用原宿主页头状态，只在当前表面可交互且前台恢复时观察位置。 */
@Composable
internal fun SourcePublicRootScrollReport(page: String, position: () -> SourceRootScrollPosition) {
    val root = sourceRootScrollPage(page)
    val surface = LocalSourcePublicSurface.current
    val state by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    if (surface == null || !surface.active || !surface.interactive || !state.isAtLeast(Lifecycle.State.RESUMED)) return
    ReportPrimaryHeaderScroll(root, position) {
        val value = position()
        value.firstVisibleItemIndex to value.firstVisibleItemScrollOffset
    }
}
