package com.musicone

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal val AiReadingWidth = 800.dp
internal val AiReadingMargin = 16.dp

/** 输入框未输入多行文本时的基准占位高度，首帧渲染时直接预留，避免滑入时挤压信息流产生瞬移。 */
internal val AiChatDefaultComposerHeight = 128.dp

/**
 * 聊天正文的左右留白：等价于原来的 `widthIn(max = 800.dp)` 再内缩 16dp 的阅读列。
 * 列表本身保持整屏以便顶栏玻璃整屏采样，正文靠这段留白回到原宽度。
 */
internal fun aiChatReadingInset(width: Dp): Dp =
    maxOf(AiReadingMargin, (width - (AiReadingWidth - AiReadingMargin * 2)) / 2)

/** 原始阅读列的内容宽度，用于校验正文边距没有改变。 */
internal fun aiChatReadingContentWidth(width: Dp): Dp = width - aiChatReadingInset(width) * 2
