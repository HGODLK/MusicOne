package com.musicone.demo

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

internal enum class ArtistSongSort(val order: Int, val label: String) {
    Popular(1, "热门歌曲"), Latest(0, "最新歌曲")
}

@Composable
internal fun ArtistSongSortButton(sort: ArtistSongSort, onToggle: () -> Unit) {
    TextButton(onToggle, Modifier.heightIn(min = 48.dp).semantics {
        stateDescription = sort.label
        contentDescription = "切换热门歌曲与最新歌曲"
    }) {
        AnimatedContent(sort, transitionSpec = {
            (fadeIn(musicMotion(220)) + slideInVertically(musicMotion(280)) { it / 3 }) togetherWith
                (fadeOut(musicMotion(160)) + slideOutVertically(musicMotion(220)) { -it / 3 })
        }, label = "歌曲排序") { value ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (value == ArtistSongSort.Popular) Icons.Default.Whatshot else Icons.Default.Schedule,
                    null, Modifier.size(20.dp))
                Text(value.label)
            }
        }
    }
}
