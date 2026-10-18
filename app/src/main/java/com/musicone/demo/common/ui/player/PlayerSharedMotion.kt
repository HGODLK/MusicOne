package com.musicone.demo

/** 播放页的共享内容必须一同完成布局，不能只凭封面就开始交接。 */
internal val PlayerSharedAnchorKeys = setOf("surface", "cover", "title", "subtitle", "play")

/** 接近迷你播放条时，所有飞行内容共同收敛到当前真实布局，避免落地后再次缩放。 */
internal fun playerSourceAnchor(
    motion: PageMotion,
    key: String,
    captured: MotionAnchor? = motion.sourceSnapshot[key],
): MotionAnchor? {
    captured ?: return null
    val current = motion.sources[key]?.takeIf { anchorFits(it.bounds, motion.hostBounds) } ?: return captured
    return playerSourceAnchor(captured, current, motion.value)
}

internal fun playerSourceAnchor(captured: MotionAnchor, current: MotionAnchor, progress: Float): MotionAnchor {
    val handoff = playerGlassSourceHandoffAlpha(progress)
    if (captured == current || handoff == 0f) return captured
    if (handoff == 1f) return current
    return captured.copy(
        bounds = motionRect(captured.bounds, current.bounds, handoff),
        corner = motionLerp(captured.corner, current.corner, handoff),
        markSize = motionLerp(captured.markSize, current.markSize, handoff),
        markX = motionLerp(captured.markX, current.markX, handoff),
        markY = motionLerp(captured.markY, current.markY, handoff),
        bottomFade = motionLerp(captured.bottomFade, current.bottomFade, handoff),
        contentScale = motionLerp(captured.contentScale, current.contentScale, handoff),
        textSizeSp = motionLerp(captured.textSizeSp, current.textSizeSp, handoff),
    )
}
