package com.musicone.demo

/** 只有本次播放请求已装载的媒体才拥有真实进度，旧播放器不能覆盖恢复快照。 */
internal class PlaybackPositionOwner {
    private var generation = -1L
    private var trackId: String? = null

    fun attach(generation: Long, trackId: String) {
        this.generation = generation
        this.trackId = trackId
    }

    fun matches(generation: Long, currentId: String?, playerId: String?): Boolean =
        currentId != null && this.generation == generation && trackId == currentId && playerId == currentId

    fun position(generation: Long, currentId: String?, playerId: String?, playerPosition: Long?, saved: Long): Long =
        if (matches(generation, currentId, playerId)) playerPosition?.coerceAtLeast(0L) ?: saved else saved
}
