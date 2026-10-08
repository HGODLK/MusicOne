package com.musicone.demo

/** 装载或跳转后的首个位置还须确认，媒体身份更新不能让旧位置覆盖目标。 */
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
            // 控制器可能已显示新媒体，却仍返回旧位置估计；只接管目标附近实际可达的位置。
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
