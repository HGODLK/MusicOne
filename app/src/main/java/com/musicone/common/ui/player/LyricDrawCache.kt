package com.musicone

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.CacheDrawModifierNode
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo

/** 歌词节点可随常驻内容迁移；脱离时同时作废录制，不能重用已释放的图层。 */
internal fun Modifier.lyricDrawWithCache(block: CacheDrawScope.() -> DrawResult): Modifier =
    then(LyricDrawCacheElement(block))

private data class LyricDrawCacheElement(val block: CacheDrawScope.() -> DrawResult) :
    ModifierNodeElement<LyricDrawCacheNode>() {
    override fun create() = LyricDrawCacheNode(block)
    override fun update(node: LyricDrawCacheNode) = node.update(block)
    override fun InspectorInfo.inspectableProperties() { name = "lyricDrawWithCache" }
}

private class LyricDrawCacheNode(private var block: CacheDrawScope.() -> DrawResult) : DelegatingNode() {
    private val cache = delegate(CacheDrawModifierNode { block() })
    fun update(next: CacheDrawScope.() -> DrawResult) {
        block = next
        cache.invalidateDrawCache()
    }
    override fun onDetach() { cache.invalidateDrawCache() }
    override fun onReset() { cache.invalidateDrawCache() }
}
