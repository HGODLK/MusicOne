package com.musicone.demo

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput

/** 与歌单浮动搜索共用玻璃组件，位于背景采样树之外。 */
@Composable
internal fun QqSearchMenu(
    state: QqSearchState, model: QqSearchViewModel, motion: QqSearchMotion,
    backdrop: GraphicsLayer, bounds: Rect, obscured: Boolean,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    BackHandler(state.opened && !obscured && state.singer == null &&
        LocalEntityNavigation.current?.rootMenu?.expanded != true) { model.back() }
    LaunchedEffect(state.opened, state.full, obscured) {
        if (state.opened && !state.full && !obscured) {
            withFrameNanos { }
            focus.requestFocus()
            keyboard?.show()
        } else keyboard?.hide()
    }
    val visibility by androidx.compose.animation.core.animateFloatAsState(
        if (obscured || state.singer != null) 0f else 1f, musicMotion(260), label = "搜索菜单详情避让")
    if (!state.opened && !motion.mounted) return
    val buttonBorder = MaterialTheme.colorScheme.surface.copy(alpha = .68f)
    QqSearchTheme {
    BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding()) {
        val glassSurface = MaterialTheme.colorScheme.surface
        val viewportWidth = maxWidth
        val viewportHeight = maxHeight
        val geometry = searchGeometry(maxWidth, maxHeight, state, motion)
        val darkTheme = MaterialTheme.colorScheme.background == MusicOneDarkColors.background
        val dim by androidx.compose.animation.core.animateFloatAsState(
            if (!darkTheme && state.query.isNotBlank() && !state.full) .16f else 0f,
            musicMotion(300), label = "搜索玻璃压暗",
        )
        val textColor = MaterialTheme.colorScheme.onSurface
        if (!obscured && state.singer == null && !state.full) {
            Box(Modifier.fillMaxSize().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null) { model.back() })
        }
        // 全屏内容由根背景层绘制，玻璃随展开末段交接，输入框始终只有一个实例。
        Box(Modifier.fillMaxSize()
            .graphicsLayer {
                val frame = geometry()
                alpha = visibility; shape = QqSearchRevealShape { frame }; clip = true
            }.qqSearchBorder(geometry, { motion.menu.value }, buttonBorder)) {
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 1f - playerSurfaceReveal(motion.full.value) }) {
                QqSearchGlass(backdrop, bounds, motion, dim = { dim * motion.menu.value }) {
                    glassSurface.copy(alpha = .28f + .38f * motion.menu.value)
                }
            }
            if (!obscured && (state.full || motion.fullMoving)) {
                // 全屏搜索的空白区域也要消费触摸，不能穿透到首页卡片。
                Box(Modifier.fillMaxWidth().height(56.dp).pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) awaitPointerEvent().changes.forEach { it.consume() }
                    }
                })
            }
            if (!obscured && visibility > .001f) CompositionLocalProvider(LocalContentColor provides textColor) {
                Column(Modifier.layout { measurable, constraints ->
                    // 全屏转场只使用进入前的固定布局；进度不进入测量阶段。
                    val fixed = if (state.full || motion.fullMoving) motion.frozenMenuSize else
                        geometry().let { it.contentWidth to it.contentHeight }
                    val (contentWidth, contentHeight) = searchContentSize(
                        fixed ?: (minOf(480.dp, viewportWidth - 40.dp) to 204.dp),
                        viewportWidth to viewportHeight, motion.resultsReady)
                    val child = measurable.measure(Constraints.fixed(contentWidth.roundToPx().coerceAtLeast(1),
                        contentHeight.roundToPx().coerceAtLeast(1)))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        child.place(0, 0)
                    }
                }.graphicsLayer {
                    val open = motion.menu.value
                    val frame = geometry()
                    alpha = ((open - .18f) / .82f).coerceIn(0f, 1f)
                    // 图层自身只有菜单宽，横向定位必须使用外层视口宽度。
                    translationX = searchContentOffset(viewportWidth, frame, open).toPx()
                    translationY = frame.top.toPx()
                }) {
                    val unifiedBack = LocalUnifiedBack.current
                    // 输入行始终按视口测量，文字只做绘制位移，端点不改变光标滚动宽度。
                    Row(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredWidth(viewportWidth).height(56.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = model::back, enabled = !state.full,
                            modifier = Modifier.size(48.dp).graphicsLayer {
                                alpha = if (unifiedBack) 1f - motion.full.value else 1f
                            }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回", tint = textColor) }
                        BasicTextField(state.query, model::setQuery, Modifier.weight(1f).focusRequester(focus)
                            .graphicsLayer { translationX = if (unifiedBack) 28.dp.toPx() * motion.full.value else 0f }
                            .drawWithContent {
                                val reserved = if (state.query.isEmpty()) 96.dp else 144.dp
                                val extraStart = if (unifiedBack) 28.dp * motion.full.value else 0.dp
                                val visibleWidth = (geometry().width - reserved - extraStart).toPx().coerceIn(0f, size.width)
                                clipRect(right = visibleWidth) { this@drawWithContent.drawContent() }
                            }
                            .semantics { contentDescription = "搜索歌曲、歌手、专辑或歌单" },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(color = textColor), singleLine = true,
                            cursorBrush = SolidColor(QqMusicThemeColor), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { model.submit(); keyboard?.hide() }),
                            decorationBox = { field -> Box {
                                if (state.query.isEmpty()) Text("搜索音乐", color = textColor.copy(alpha = .7f))
                                field()
                            } })
                        if (state.query.isNotEmpty()) Spacer(Modifier.width(48.dp))
                        Spacer(Modifier.width(48.dp))
                    }
                    if (!state.full || !motion.resultsReady) Box(Modifier.weight(1f).graphicsLayer {
                        val full = motion.full.value
                        alpha = 1f - full; translationY = -24.dp.toPx() * full
                    }) {
                        AnimatedContent(state.query.isNotBlank(), transitionSpec = {
                            (fadeIn(musicMotion(280)) + slideInVertically(musicMotion(320)) { it / 5 }) togetherWith
                                (fadeOut(musicMotion(200)) + slideOutVertically(musicMotion(280)) { -it / 5 })
                        }, label = "历史联想切换") { hasQuery ->
                            if (hasQuery) SearchSuggestions(state, model) else SearchHistory(state.history, model)
                        }
                    }
                }
            }
            if (!obscured && state.query.isNotEmpty()) IconButton(onClick = { model.setQuery("") },
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp).graphicsLayer {
                    val frame = geometry()
                    translationX = -frame.right.toPx() - 48.dp.toPx()
                    translationY = frame.top.toPx() + 4.dp.toPx() * motion.menu.value
                    alpha = motion.menu.value
                }) { Icon(Icons.Rounded.Close, "清空输入", tint = textColor) }
            // 独立于文字入退场，连续连接首页 48dp 按钮与输入栏末端的同一个放大镜。
            IconButton(onClick = { model.submit(); keyboard?.hide() },
                enabled = state.query.isNotBlank() && !obscured && state.singer == null,
                modifier = Modifier.align(Alignment.TopEnd).size(48.dp).graphicsLayer {
                    val frame = geometry()
                    translationX = -frame.right.toPx()
                    translationY = frame.top.toPx() + 4.dp.toPx() * motion.menu.value
                }) {
                Icon(Icons.Default.Search, "搜索", Modifier.size(24.dp), tint = androidx.compose.ui.graphics.lerp(
                    textColor, QqMusicThemeColor.copy(alpha = if (state.query.isBlank()) .4f else 1f), motion.menu.value))
            }
        }
    }
    }
}

@Composable
private fun SearchHistory(history: List<String>, model: QqSearchViewModel) {
    Column {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("搜索历史", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = { model.removeHistory(null) }, enabled = history.isNotEmpty()) { Text("清空") }
        }
        if (history.isEmpty()) Text("搜索过的关键词会保存在这里", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodyMedium)
        else LazyHorizontalGrid(rows = GridCells.Fixed(2), modifier = Modifier.fillMaxWidth().height(96.dp),
            contentPadding = PaddingValues(horizontal = 12.dp)) {
            items(history, key = { it }) { word ->
                Row(Modifier.height(48.dp).animateItem(fadeInSpec = musicMotion(240), fadeOutSpec = musicMotion(200)), verticalAlignment = Alignment.CenterVertically) {
                    Text(word, Modifier.widthIn(max = 160.dp).heightIn(min = 48.dp).clickable { model.submit(word) }.padding(12.dp),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { model.removeHistory(word) }) { Icon(Icons.Rounded.Close, "删除历史：$word", Modifier.size(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SearchSuggestions(state: QqSearchState, model: QqSearchViewModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 12.dp)) {
        if (state.suggesting && state.suggestions.isEmpty()) item { Text("…", Modifier.padding(20.dp)) }
        state.suggestionError?.let { message -> item {
            Text(message, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { model.setQuery(state.query) }) { Text("重试联想", color = LocalContentColor.current) }
        } }
        items(state.suggestions, key = { it }) { word ->
            Text(word, Modifier.fillMaxWidth().animateItem(fadeInSpec = musicMotion(260), fadeOutSpec = musicMotion(220))
                .clickable { model.submit(word) }.heightIn(min = 48.dp).padding(horizontal = 20.dp, vertical = 14.dp),
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 20.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (!state.suggesting && state.suggestionError == null && state.suggestions.isEmpty()) item {
            Text("点击搜索查找“${state.query}”", Modifier.fillMaxWidth().clickable { model.submit() }.padding(20.dp))
        }
    }
}
