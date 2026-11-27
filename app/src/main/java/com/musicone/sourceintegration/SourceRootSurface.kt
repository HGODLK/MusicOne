package com.musicone.sourceintegration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.musicone.MusicOnePage

/** 标识某个音源的一级页（首页或我的），由宿主固定分配。 */
internal data class SourceRootIdentity(val sourceId: String, val page: MusicOnePage)

/** 跨独立 ComposeView 传递根坐标偏移的容器，只有读它的锚点才会随位置变化重算。 */
internal class SourceRootCoordinates {
    var offset by mutableStateOf(Offset.Zero)
    val read: () -> Offset = { offset }
}

internal fun sourcePrimaryHeaderVisible(custom: Boolean, atRoot: Boolean, rootSearch: Boolean,
    hostRoot: Boolean, scene: SourcePublicScene?): Boolean = when {
    scene != null -> scene.rootHeader != null && scene.entities.pages.isEmpty()
    else -> !custom || hostRoot || atRoot || rootSearch
}
