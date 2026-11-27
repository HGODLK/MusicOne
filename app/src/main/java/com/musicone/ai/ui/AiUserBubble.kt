package com.musicone

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

internal fun aiUserBubbleMaxWidth(readingWidth: Dp): Dp = minOf(600.dp, readingWidth * .88f)

/** 欢迎页灵感气泡和正式用户气泡共用同一套宽度、字号和内边距，飞过去交接时换行不会跳。 */
@Composable
internal fun AiUserBubble(text: String, maximumWidth: Dp, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(22.dp, 6.dp, 22.dp, 22.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier.widthIn(max = maximumWidth)) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}
