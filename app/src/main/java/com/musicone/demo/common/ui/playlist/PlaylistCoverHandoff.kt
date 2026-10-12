package com.musicone.demo

import androidx.compose.animation.core.Animatable

/** 实际目标发生变化时只调整共享封面，不重启整页进度；一致时立即交接。 */
internal suspend fun settlePlaylistCoverHandoff(motion: PageMotion) {
    while (motion.wantsOpen) {
        val start = motion.targetSnapshot[motion.coverKey] ?: return
        val target = motion.targets[motion.coverKey]?.takeIf { anchorFits(it.bounds, motion.hostBounds) } ?: return
        if (start == target) return
        if (ExperiencePreferences.options.reduceMotion) {
            motion.replaceCapturedCoverTarget(target)
            return
        }
        Animatable(0f).animateTo(1f, musicSpring(stiffness = 320f)) {
            motion.replaceCapturedCoverTarget(interpolatePlaylistCoverAnchor(start, target, value))
        }
        motion.replaceCapturedCoverTarget(target)
    }
}

internal fun interpolatePlaylistCoverAnchor(start: MotionAnchor, target: MotionAnchor, progress: Float) =
    MotionAnchor(motionRect(start.bounds, target.bounds, progress),
        motionLerp(start.corner, target.corner, progress),
        motionLerp(start.markSize, target.markSize, progress),
        motionLerp(start.markX, target.markX, progress),
        motionLerp(start.markY, target.markY, progress),
        motionLerp(start.bottomFade, target.bottomFade, progress),
        motionLerp(start.contentScale, target.contentScale, progress),
        motionLerp(start.textSizeSp, target.textSizeSp, progress))
