package com.musicone

/** 切歌或拖动进度后，等播放器真正报出目标附近的新位置后再接管进度，防止旧位置闪回覆盖。 */
internal class PlaybackPositionOwner(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private var generation = -1L
    private var trackId: String? = null
    private data class ExpectedPosition(val positionMs: Long, val requestedAtMs: Long)
    private var expectedPosition: ExpectedPosition? = null

    fun attach(generation: Long, trackId: String, startingPositionMs: Long? = null) {
        this.generation = generation
        this.trackId = trackId
        expectedPosition = startingPositionMs?.let { ExpectedPosition(it.coerceAtLeast(0L), nowMs()) }
    }

    fun awaitPosition(generation: Long, trackId: String, positionMs: Long) {
        if (this.generation == generation && this.trackId == trackId) {
            expectedPosition = ExpectedPosition(positionMs.coerceAtLeast(0L), nowMs())
        }
    }

    fun matches(generation: Long, currentId: String?, playerId: String?): Boolean =
        currentId != null && this.generation == generation && trackId == currentId && playerId == currentId

    fun position(generation: Long, currentId: String?, playerId: String?, playerPosition: Long?, saved: Long,
                 playerReady: Boolean = true, durationMs: Long? = null, speed: Float = 1f): Long {
        if (!matches(generation, currentId, playerId)) return saved
        val position = playerPosition?.coerceAtLeast(0L) ?: return saved
        expectedPosition?.let { expected ->
            if (!playerReady) return saved
            val target = durationMs?.takeIf { it >= 0L }?.let { expected.positionMs.coerceAtMost(it) }
                ?: expected.positionMs
            // 播放器有时媒体项已经切了，但进度还停在旧位置，这里只接收落在目标合理范围内的位置。
            val elapsed = (nowMs() - expected.requestedAtMs).coerceAtLeast(0L)
            val advance = (elapsed * speed.coerceAtLeast(0f).toDouble()).toLong()
            if (position < (target - POSITION_TOLERANCE_MS).coerceAtLeast(0L) ||
                position > target + advance + POSITION_TOLERANCE_MS) return saved
            expectedPosition = null
        }
        return position
    }
}

private const val POSITION_TOLERANCE_MS = 1_000L
