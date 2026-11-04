package com.musicone

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
import androidx.compose.material.icons.filled.Sort
import com.musicone.source.sdk.SourceCollectionSortOption
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp

@Composable
internal fun ArtistSongSortButton(sort: SourceCollectionSortOption?, onToggle: () -> Unit) {
    if (sort == null) return
    TextButton(onToggle, Modifier.heightIn(min = 48.dp).semantics {
        stateDescription = sort.label
        contentDescription = "切换歌曲排序"
    }) {
        AnimatedContent(sort, transitionSpec = {
            (fadeIn(musicMotion(220)) + slideInVertically(musicMotion(280)) { it / 3 }) togetherWith
                (fadeOut(musicMotion(160)) + slideOutVertically(musicMotion(220)) { -it / 3 })
        }, label = "歌曲排序") { value ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Sort,
                    null, Modifier.size(20.dp))
                Text(value.label)
            }
        }
    }
}
