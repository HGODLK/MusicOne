package com.musicone.demo

import androidx.compose.animation.core.animate
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 普通返回先恢复头图，使头像锚点重新可见；拖动返回已在顶部，直接衔接。 */
@Composable
internal fun rememberArtistReturnAction(menu: QqPlaylistSongMenuState?, search: PlaylistSearchState,
    motion: PageMotion?, wide: Boolean, collapsed: () -> Float, setCollapsed: (Float) -> Unit,
    onBack: () -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val currentCollapsed by rememberUpdatedState(collapsed)
    val updateCollapsed by rememberUpdatedState(setCollapsed)
    val close by rememberUpdatedState(onBack)
    return {
        when {
            menu?.expanded == true -> menu.back()
            search.expanded -> search.close()
            job?.isActive == true -> Unit
            else -> {
                job = scope.launch {
                    if (!wide && motion?.moving != true && currentCollapsed() > 0f) {
                        animate(currentCollapsed(), 0f, animationSpec = musicMotion(240)) { value, _ -> updateCollapsed(value) }
                        repeat(2) { withFrameNanos { } }
                    }
                    close()
                }
            }
        }
    }
}
