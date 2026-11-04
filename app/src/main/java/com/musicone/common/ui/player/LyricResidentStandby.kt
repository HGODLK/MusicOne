package com.musicone

internal data class LyricStandbyFrame(val stamp: Any, val line: Int)

/**
 * 常驻歌词的直开资格：本轮转向后已完整走过一次展开准备，关闭态也已按当前句绘制到阅读锚点。
 * 任何一项不满足都回到原准备流程，转屏后的首次打开不走这里。
 */
internal class LyricResidentStandby {
    private var owner: Any? = null
    private var live: (() -> LyricStandbyFrame?)? = null
    private var drawn: LyricStandbyFrame? = null
    private var confirmedGeneration: Int? = null

    fun bind(owner: Any, live: () -> LyricStandbyFrame?) {
        if (this.owner !== owner) drawn = null
        this.owner = owner
        this.live = live
    }

    fun release(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        live = null
        drawn = null
    }

    fun drawn(owner: Any, frame: LyricStandbyFrame?) {
        if (this.owner === owner) drawn = frame
    }

    fun clearDrawn() { drawn = null }

    fun confirmOpening(generation: Int) { confirmedGeneration = generation }

    fun ready(generation: Int): Boolean {
        if (confirmedGeneration != generation) return false
        val recorded = drawn ?: return false
        return recorded == live?.invoke()
    }
}
