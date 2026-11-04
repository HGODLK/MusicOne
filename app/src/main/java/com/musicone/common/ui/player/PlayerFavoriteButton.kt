package com.musicone

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** 红心可见边缘到 48dp 按钮边缘的距离：图标四周留白加上 24 栅格图形自带的 2 格留白。 */
internal val PLAYER_FAVORITE_EDGE_INSET = (48.dp - 27.dp) / 2 + 27.dp * 2 / 24

/** 进度条线帽超出轨道的半径，竖屏对齐它的可见端点。 */
private val PLAYER_PROGRESS_CAP_RADIUS = 2.5.dp
internal val PLAYER_FAVORITE_PROGRESS_SHIFT = PLAYER_FAVORITE_EDGE_INSET + PLAYER_PROGRESS_CAP_RADIUS

/** 播放器共用长按入口，添加目标和写入仍交给已有歌曲菜单。 */
@Composable
internal fun PlayerFavoriteButton(track: MusicTrack, favorite: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val menu = LocalMainSourcePlaylistSongMenu.current
    val view = LocalView.current
    var bounds by remember { mutableStateOf(Rect.Zero) }
    AnimatedFavoriteButton(favorite, onClick,
        modifier.onGloballyPositioned { bounds = it.boundsInRoot() }, enabled,
        onLongClick = if (menu == null) null else ({
            if (menu.openAddition(track, bounds)) playerFavoriteAdditionFeedback(view)
        }))
}
