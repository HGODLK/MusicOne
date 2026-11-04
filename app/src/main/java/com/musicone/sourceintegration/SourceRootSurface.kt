package com.musicone.sourceintegration

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import com.musicone.MusicOnePage

/** 由宿主固定一级页身份，包内重组与活跃状态回报不能改变所属页面。 */
internal data class SourceRootIdentity(val sourceId: String, val page: MusicOnePage)

/** 稳定引用跨越独立重组器，只有真正读取坐标的锚点订阅移动。 */
internal class SourceRootCoordinates {
    var offset by mutableStateOf(Offset.Zero)
    val read: () -> Offset = { offset }
}

internal fun sourcePrimaryHeaderVisible(custom: Boolean, atRoot: Boolean, rootSearch: Boolean,
    hostRoot: Boolean, scene: SourcePublicScene?): Boolean = when {
    scene != null -> scene.rootHeader != null && scene.entities.pages.isEmpty()
    else -> !custom || hostRoot || atRoot || rootSearch
}
