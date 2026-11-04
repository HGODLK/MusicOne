package des.c5inco.torph.compose

import androidx.compose.animation.core.FloatAnimationSpec
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.FloatTweenSpec
import kotlin.time.Duration

/**
 * One animated float with plain fields, driven by [TextMorphState]'s single frame loop.
 *
 * Unlike `Animatable`, reading or advancing it allocates nothing: values come straight from
 * [FloatAnimationSpec.getValueFromNanos]. Retargeting captures the current value and velocity, so
 * interrupted animations continue smoothly for both tweens and springs.
 */
internal class Channel(initial: Float) {
    var value: Float = initial
        private set
    var target: Float = initial
        private set
    var active: Boolean = false
        private set

    private var from = initial
    private var v0 = 0f
    private var start = UNSET
    private var duration = 0L
    private var spec: FloatAnimationSpec? = null
    /** Velocity handed over by [snapTo] for the next [animateTo] to start with. */
    private var carried = 0f

    fun snapTo(v: Float, velocity: Float = 0f) {
        value = v; target = v; active = false; v0 = 0f; carried = velocity
    }

    /** Retarget to [t]. [now] is the last frame time, or [UNSET] when no frame loop is running. */
    fun animateTo(t: Float, s: FloatAnimationSpec, now: Long) {
        val vel = if (active) velocityAt(now) else carried
        carried = 0f
        from = value
        v0 = vel
        target = t
        spec = s
        start = now
        if (from == target && vel == 0f) { active = false; return }
        duration = s.getDurationNanos(from, target, v0)
        active = duration > 0L
        if (!active) value = target
    }

    fun velocityAt(now: Long): Float {
        val s = spec
        if (!active || s == null || start == UNSET || now == UNSET) return 0f
        return s.getVelocityFromNanos((now - start).coerceIn(0L, duration), from, target, v0)
    }

    /** Advance to [now]; returns true while still moving. */
    fun tick(now: Long): Boolean {
        if (!active) return false
        val s = spec ?: run { snapTo(target); return false }
        if (start == UNSET) start = now
        val t = now - start
        if (t >= duration) {
            value = target
            active = false
        } else {
            value = s.getValueFromNanos(t, from, target, v0)
        }
        return active
    }

    companion object {
        const val UNSET = Long.MIN_VALUE
    }
}

/** Float spec for one channel: tween for curves, physical spring for springs. */
internal fun MorphEase.toFloatSpec(duration: Duration, threshold: Float): FloatAnimationSpec = when (this) {
    is MorphEase.Curve -> FloatTweenSpec(duration.inWholeMilliseconds.toInt().coerceAtLeast(0), 0, easing)
    is MorphEase.Spring -> FloatSpringSpec(
        dampingRatio = dampingRatio.coerceAtLeast(0.01f),
        stiffness = (stiffness / mass).coerceAtLeast(1f),
        visibilityThreshold = threshold,
    )
}
