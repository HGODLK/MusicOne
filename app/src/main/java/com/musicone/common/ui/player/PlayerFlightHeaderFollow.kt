package com.musicone

internal val PLAYER_HEADER_FLIGHT_KEYS = setOf("cover", "title", "subtitle")

/** 记录上一次放置时的页眉进度；只有整页飞行途中进度确实变化才需要更新飞行端点。 */
internal class PlayerFlightHeaderFollow {
    private var last = Float.NaN

    fun changed(moving: Boolean, header: Float): Boolean {
        val moved = moving && header != last
        last = header
        return moved
    }
}
