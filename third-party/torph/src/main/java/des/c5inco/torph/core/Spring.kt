package des.c5inco.torph.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Physical spring parameters in the same units torph (and CSS `linear()` spring generators) use.
 *
 * @property stiffness Spring constant k.
 * @property damping Damping coefficient c (NOT a ratio).
 * @property mass Mass m.
 * @property precision Distance from the target below which the spring is considered settled.
 */
internal data class SpringParams(
    val stiffness: Float = 100f,
    val damping: Float = 10f,
    val mass: Float = 1f,
    val precision: Float = 0.001f,
) {
    init {
        require(stiffness > 0f) { "stiffness must be > 0" }
        require(damping >= 0f) { "damping must be >= 0" }
        require(mass > 0f) { "mass must be > 0" }
        require(precision > 0f) { "precision must be > 0" }
    }

    /** Undamped angular frequency ω₀ = √(k/m). */
    val omega: Float get() = sqrt(stiffness / mass)

    /** Damping ratio ζ = c / (2√(km)). 1 = critically damped. */
    val dampingRatio: Float get() = damping / (2f * sqrt(stiffness * mass))
}

/**
 * Estimated time in milliseconds for a unit-displacement spring to stay within [SpringParams.precision]
 * of its target, or [Long.MAX_VALUE] for an undamped spring. An estimate for callers planning around
 * a spring ease; the Compose renderer runs the spring itself and does not use it.
 */
internal fun settleTime(params: SpringParams): Long = settleTime(
    params.stiffness, params.damping, params.mass, params.precision,
)

/** [settleTime] for loose spring parameters. */
internal fun settleTime(stiffness: Float, damping: Float, mass: Float, precision: Float = 0.001f): Long {
    val p = SpringParams(stiffness, damping, mass, precision)
    val zeta = p.dampingRatio
    val omega = p.omega
    val target = -ln(precision.toDouble())
    val seconds = when {
        zeta <= 0f -> return Long.MAX_VALUE
        // Envelope of a unit-displacement underdamped spring is e^{-ζωt}/√(1-ζ²).
        zeta < 1f -> (target - ln(sqrt(1.0 - zeta * zeta))) / (zeta * omega)
        zeta == 1f -> {
            // x(t) = (1 + ω t) e^{-ω t}; solve numerically.
            var t = 0.0
            val dt = 1.0 / (omega * 50.0)
            while ((1 + omega * t) * exp(-omega * t) > precision && t < 60.0) t += dt
            t
        }
        else -> {
            val slowRoot = omega * (zeta - sqrt(zeta * zeta - 1.0))
            target / slowRoot
        }
    }
    return (seconds * 1000).toLong().coerceAtLeast(0L)
}

/**
 * Position of a damped spring at [timeMs] starting at displacement 1 with [initialVelocity], settling to 0.
 * Handy for tests and for generating easing tables; the Compose layer uses its own solver at runtime.
 */
internal fun springPosition(params: SpringParams, timeMs: Long, initialVelocity: Float = 0f): Float {
    val t = timeMs / 1000.0
    val zeta = params.dampingRatio.toDouble()
    val w0 = params.omega.toDouble()
    val x0 = 1.0
    val v0 = initialVelocity.toDouble()
    return when {
        zeta < 1.0 -> {
            val wd = w0 * sqrt(1 - zeta * zeta)
            val a = x0
            val b = (v0 + zeta * w0 * x0) / wd
            (exp(-zeta * w0 * t) * (a * cos(wd * t) + b * sin(wd * t)))
        }
        abs(zeta - 1.0) < 1e-6 -> {
            (x0 + (v0 + w0 * x0) * t) * exp(-w0 * t)
        }
        else -> {
            val s = w0 * sqrt(zeta * zeta - 1)
            val r1 = -zeta * w0 + s
            val r2 = -zeta * w0 - s
            val c2 = (v0 - r1 * x0) / (r2 - r1)
            val c1 = x0 - c2
            c1 * exp(r1 * t) + c2 * exp(r2 * t)
        }
    }.toFloat()
}
