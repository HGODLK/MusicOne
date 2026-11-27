package com.musicone

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.delay

/** 背景九色场的错开量占过渡窗口的比例；原切歌是 137ms / 900ms，长窗口按比例放大。 */
internal const val AUTOMIX_ATMOSPHERE_STAGGER_FRACTION = .15f

private const val AUTOMIX_PAUSED_POLL_MS = 100L

internal fun MusicTrack.sameAutoMixEntry(other: MusicTrack): Boolean = id == other.id && source == other.source

/**
 * 封面与背景要提前朝下一首渐变：过渡进行中，上一首或下一首都换成下一首作为目标；
 * 标题、歌词等仍按 currentTrack 在交接点切换，不经过这里。
 */
@Composable
internal fun autoMixArtworkTrack(track: MusicTrack): MusicTrack {
    val active by AutoMixSession.active.collectAsState()
    val plan = active?.plan ?: return track
    return if (track.sameAutoMixEntry(plan.from)) plan.to else track
}

/** 正在进行、且目标正是这张封面的过渡；两首同封面时不需要驱动。 */
internal fun autoMixVisualDriveFor(identity: ArtworkIdentity): AutoMixVisualDrive? {
    val transition = AutoMixSession.active.value ?: return null
    val plan = transition.plan
    if (plan.to.artworkIdentity() != identity || plan.from.artworkIdentity() == identity) return null
    val stagger = if (ExperiencePreferences.options.reduceMotion) 0f else AUTOMIX_ATMOSPHERE_STAGGER_FRACTION
    return AutoMixVisualDrive(transition, stagger)
}

/** 途中才挂载的封面直接按当前进度画出上一首与下一首的混合。 */
internal fun autoMixInitialArtworkBlend(frame: PlayerArtworkFrame): List<ArtworkBlendSnapshot>? {
    val drive = autoMixVisualDriveFor(frame.identity) ?: return null
    val from = drive.transition.plan.from
    val progress = drive.progress(drive.transition.clockMs())
    if (progress >= 1f) return null
    val fromFrame = PlayerArtworkFrame(from.artworkIdentity(), ArtworkRepository.peek(from.artworkUrl))
    return listOf(ArtworkBlendSnapshot(fromFrame, 1f - progress), ArtworkBlendSnapshot(frame, progress))
}

/** 途中才挂载的背景从上一首的九色场出发，按当前进度直接画出。 */
internal fun autoMixInitialAtmosphereSweep(identity: ArtworkIdentity, colors: IntArray): AtmosphereSweep? {
    val drive = autoMixVisualDriveFor(identity) ?: return null
    val fromColors = ArtworkColorFieldRepository.peek(drive.transition.plan.from.artworkIdentity()) ?: return null
    val base = averageArtworkColor(fromColors)
    return AtmosphereSweep(fromColors, base, base, colors, TrackTransitionDirection.NEXT, settled = false,
        drive = drive, resumeFromStart = false)
}

/**
 * 把过渡时钟换算成封面与背景的渐变进度。visualProgress 已是听感占比，这里只做区间归一化，不再叠加缓动曲线。
 * 背景按视觉纵向错开：下一首下方先变、上方后变，各位置都完整走一遍听感曲线并落在 [enterMs, fromEndMs] 内。
 */
internal class AutoMixVisualDrive(
    val transition: AutoMixTransition,
    private val staggerFraction: Float,
) {
    private val plan = transition.plan
    private val window = (plan.fromEndMs - plan.enterMs).coerceAtLeast(1L)
    private val stagger = window * staggerFraction.coerceIn(0f, .5f)
    private val startValue = transition.visualProgress(plan.enterMs)
    private val endValue = transition.visualProgress(plan.fromEndMs)

    /** 开始驱动时的过渡时钟；中途接手时从这里把剩余部分铺满，避免跳色。 */
    val startClockMs: Long = transition.clockMs()

    val finishedAt: Long get() = plan.fromEndMs

    fun progress(clockMs: Long): Float {
        val clock = clockMs.coerceIn(plan.enterMs, plan.fromEndMs)
        val span = endValue - startValue
        return if (span > .001f) ((transition.visualProgress(clock) - startValue) / span).coerceIn(0f, 1f)
        else (clock - plan.enterMs).toFloat() / window
    }

    /** depth 为屏幕视觉纵向位置（0 上、1 下），方向映射已由调用方按横竖屏换算。 */
    fun sweepProgress(clockMs: Long, depth: Float, direction: TrackTransitionDirection): Float {
        val lead = if (direction == TrackTransitionDirection.NEXT) 1f - depth else depth
        val local = if (stagger <= 0f) clockMs.toFloat() else
            plan.enterMs + (clockMs - plan.enterMs - lead * stagger) * window / (window - stagger)
        return progress(local.toLong())
    }

    /**
     * 每帧把过渡时钟交给 [onClock]，直到 A 收干净（返回 true）或过渡提前结束／被打断（返回 false）。
     * 暂停时时钟不动，只低频复查，不持续请求绘制帧。
     */
    suspend fun follow(onClock: (Long) -> Unit): Boolean {
        var last = Long.MIN_VALUE
        while (true) {
            if (AutoMixSession.active.value !== transition) return last >= plan.fromEndMs
            val clock = transition.clockMs()
            if (clock != last) onClock(clock)
            if (clock >= plan.fromEndMs) return true
            if (clock == last) delay(AUTOMIX_PAUSED_POLL_MS) else withFrameNanos { }
            last = clock
        }
    }
}

/** 中途接手的渐变：可见权重或接手时的进度作为起点，余下部分按过渡进度铺满。 */
internal fun autoMixResumedProgress(progress: Float, base: Float): Float =
    if (base >= 1f) 1f else ((progress - base) / (1f - base)).coerceIn(0f, 1f)

/** 进度条在过渡期间的位置：交接前取上一首、交接后取下一首各自的媒体位置；与过渡无关时为 null。 */
internal fun autoMixTimelinePosition(track: MusicTrack): Long? {
    val transition = AutoMixSession.active.value ?: return null
    val plan = transition.plan
    return when {
        track.sameAutoMixEntry(plan.from) -> transition.fromPositionMs()
        track.sameAutoMixEntry(plan.to) -> transition.toPositionMs()
        else -> null
    }
}

/** 本次换曲是否为自然过渡的交接（含交接与过渡结束同时发生的情况）。 */
internal fun autoMixProgressHandoff(previousTrackId: String, trackId: String): Boolean {
    val plan = AutoMixSession.active.value?.plan
        ?: AutoMixSession.lastEnd.value?.takeIf { it.second == AutoMixEnd.COMPLETED }?.first
        ?: return false
    return plan.from.id == previousTrackId && plan.to.id == trackId
}
