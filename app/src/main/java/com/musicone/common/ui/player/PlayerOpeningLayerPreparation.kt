package com.musicone

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.IntSize

internal enum class PlayerOpeningLayer { BODY, FLYING, GLASS }

/** 追踪展开前各飞行图层是否已完成首次录制，不持有像素，也不额外增加等待超时。 */
internal class PlayerOpeningLayerPreparation {
    var generation by mutableIntStateOf(0)
        private set
    private var owner: Any? = null
    private data class Entry(val generation: Int, val size: IntSize)
    private val recorded = mutableMapOf<PlayerOpeningLayer, Entry>()

    fun begin() { generation++; recorded.clear() }
    fun attach(owner: Any) {
        if (this.owner !== owner) recorded.clear()
        this.owner = owner
    }
    fun detach(owner: Any) { if (this.owner === owner) { this.owner = null; recorded.clear() } }

    fun record(layer: PlayerOpeningLayer, size: IntSize) {
        if (owner != null && size.width > 0 && size.height > 0) recorded[layer] = Entry(generation, size)
    }

    fun ready(size: IntSize): Boolean = owner == null ||
        size.width > 0 && size.height > 0 && PlayerOpeningLayer.entries.all {
            recorded[it]?.let { entry -> entry.generation == generation && entry.size == size } == true
        }
}

internal val LocalPlayerOpeningLayers = staticCompositionLocalOf<PlayerOpeningLayerPreparation?> { null }
