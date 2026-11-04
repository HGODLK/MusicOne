package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

/** 设置与缓存列表不继承根歌单的关闭进度，复用歌曲行始终正常显示。 */
@Composable
internal fun SourceLibraryContentScope(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPlaylistMotion provides null, LocalPlaylistContentEntrance provides { 1f }, content = content)
}
