package com.musicone.demo

internal enum class PlayerProgressHandoffAction { NONE, SYNC, RESET, RETURN_TO_ORIGIN }

/** 换曲只消费一次归零，音频状态或周期采样更新不会重新发起归零。 */
internal class PlayerProgressHandoff(initialTrackId: String, initiallyActive: Boolean) {
    private var trackId = initialTrackId
    private var active = initiallyActive
    private var switching = false
    private var origin: String? = null

    fun update(nextTrackId: String, nextSwitching: Boolean, nextActive: Boolean): PlayerProgressHandoffAction {
        val resuming = nextActive && !active
        active = nextActive
        if (!nextActive || resuming) {
            trackId = nextTrackId
            switching = false
            origin = null
            return PlayerProgressHandoffAction.SYNC
        }
        val changed = nextTrackId != trackId
        trackId = nextTrackId
        if (nextSwitching) {
            val beginning = !switching
            if (beginning) origin = nextTrackId
            switching = true
            return if (beginning) PlayerProgressHandoffAction.RESET else PlayerProgressHandoffAction.NONE
        }
        val returning = switching && nextTrackId == origin
        val alreadyReset = switching
        switching = false
        origin = null
        return when {
            returning -> PlayerProgressHandoffAction.RETURN_TO_ORIGIN
            changed && !alreadyReset -> PlayerProgressHandoffAction.RESET
            else -> PlayerProgressHandoffAction.NONE
        }
    }
}
