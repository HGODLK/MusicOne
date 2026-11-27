package com.musicone

import androidx.compose.animation.core.animate
import androidx.compose.runtime.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 点返回键时如果头图被滚上去了就先滚回顶部露出头像锚点；顶部下拉返回直接衔接动画。 */
@Composable
internal fun rememberArtistReturnAction(menu: MainSourcePlaylistSongMenuState?, search: PlaylistSearchState,
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
