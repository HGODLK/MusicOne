package com.musicone.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.SortByAlpha
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 沿用已有排序控件的方向翻转与字母图标过渡，并遵守全局动画倍率。 */
@Composable
internal fun QqPlaylistSortButton(sort: PlaylistSort, onSort: (PlaylistSort) -> Unit) {
    val next = PlaylistSort.entries[(sort.ordinal + 1) % PlaylistSort.entries.size]
    val rotation by animateFloatAsState(
        if (sort == PlaylistSort.ADDED_ASC) 180f else 0f, musicMotion(280), label = "排序方向翻转",
    )
    FilledIconButton(
        onClick = { onSort(next) },
        modifier = Modifier.size(48.dp).semantics {
            contentDescription = "当前${sort.label}，点击切换为${next.label}"
        },
        shape = CircleShape,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        AnimatedContent(sort == PlaylistSort.NAME, transitionSpec = {
            (fadeIn(musicMotion(220)) + scaleIn(musicMotion(280), initialScale = .8f)) togetherWith
                (fadeOut(musicMotion(160)) + scaleOut(musicMotion(220), targetScale = .8f))
        }, label = "排序图标切换") { alphabetical ->
            Icon(
                if (alphabetical) Icons.Default.SortByAlpha else Icons.Default.ArrowDownward,
                contentDescription = null,
                modifier = Modifier.graphicsLayer { rotationZ = if (alphabetical) 0f else rotation },
            )
        }
    }
}
