package com.musicone.source.sdk

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** 自绘一级页可选接入宿主页头；不改变原公共页面及独立打开的导航契约。 */
interface SourceRootPageUi {
    /** 仅接受 Home／My；详情和搜索仍使用原 Content 或 open 入口。 */
    @Composable fun RootContent(request: SourcePageRequest, modifier: Modifier = Modifier,
        bottomInsetDp: Float = 0f, onClose: () -> Unit = {})

    /** 上报当前一级页的列表位置，不接管列表状态、回顶动作或滚动动效。 */
    @Composable fun ReportScroll(page: String, position: () -> SourceRootScrollPosition)
}

data class SourceRootScrollPosition(val firstVisibleItemIndex: Int, val firstVisibleItemScrollOffset: Int)
