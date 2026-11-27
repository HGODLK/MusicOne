package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 自绘一级页可选接入宿主页头，不影响原有公共页面和独立页面的导航方式。 */
interface SourceRootPageUi {
    /** 仅接受 Home／My；详情和搜索仍使用原 Content 或 open 入口。 */
    @Composable fun RootContent(request: SourcePageRequest, modifier: Modifier = Modifier,
        bottomInsetDp: Float = 0f, onClose: () -> Unit = {})

    /** 只上报当前一级页列表的滚动位置，不接管列表状态、回顶或滚动动效。 */
    @Composable fun ReportScroll(page: String, position: () -> SourceRootScrollPosition)
}

data class SourceRootScrollPosition(val firstVisibleItemIndex: Int, val firstVisibleItemScrollOffset: Int)
