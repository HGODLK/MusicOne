package com.musicone

/**
 * 自然过渡的歌词交接判定：交接点之后歌词仍停在上一首，等下一首真正开唱（第一句减去提前量）再按正常切歌切换；
 * 下一首没有歌词时在上一首收干净（fromEndMs）时切换。播放页歌词与桌面歌词共用这一份判定，释放后同一次过渡不再保持。
 */
internal object AutoMixLyricHold {
    @Volatile private var released: AutoMixPlan? = null
    // 下一首的切入位置，用来跳过切入点之前（不会被听到）的歌词。
    @Volatile private var entry: Pair<AutoMixPlan, Long>? = null
    @Volatile private var lastPosition: Pair<AutoMixPlan, Long>? = null
    @Volatile private var fromPosition: Pair<AutoMixPlan, Long>? = null

    /**
     * 返回仍需保持上一首歌词的过渡；null 表示按当前歌曲正常显示。
     * @param positionMs 当前歌曲的连续播放位置；过渡进行中改用 toPositionMs()。
     */
    fun heldPlan(
        current: MusicTrack?,
        lyricState: LyricLoadState,
        positionMs: Long?,
        seeking: Boolean = false,
        lineLeadMs: (MusicTrack) -> Long = { sourceLyricTiming(it).lineLeadMs },
    ): AutoMixPlan? {
        val active = AutoMixSession.active.value
        val plan = active?.plan
            ?: AutoMixSession.lastEnd.value?.takeIf { it.second == AutoMixEnd.COMPLETED }?.first
            ?: return null
        if (plan === released) return null
        if (active != null) {
            if (entry?.first !== plan) {
                entry = plan to (active.toPositionMs() - (active.clockMs() - plan.enterMs).coerceAtLeast(0L))
            }
            fromPosition = plan to active.fromPositionMs()
        }
        // 交接点之前歌词本来就是上一首，不需要介入。
        if (active != null && current?.sameAutoMixEntry(plan.from) == true) return null
        if (current == null || !current.sameAutoMixEntry(plan.to) || seeking) return release(plan)
        val position = active?.toPositionMs() ?: positionMs ?: return plan
        // 过渡结束后往回拖动属于用户操作，直接交给正常歌词。
        val previous = lastPosition?.takeIf { it.first === plan }?.second
        if (active == null && previous != null && position < previous - AUTOMIX_LYRIC_SEEK_BACK_MS) return release(plan)
        lastPosition = plan to position
        if (lyricState == LyricLoadState.LOADING) return plan
        val firstLine = autoMixFirstSungLineMs(current.lyrics, entry?.takeIf { it.first === plan }?.second ?: position)
        return when {
            firstLine == null -> if (active != null && active.clockMs() < plan.fromEndMs) plan else release(plan)
            position + lineLeadMs(current) + AUTOMIX_LYRIC_HANDOFF_LEAD_MS >= firstLine -> release(plan)
            else -> plan
        }
    }

    /** 保持期间上一首歌词停留的位置；过渡途中才挂载的歌词窗口用它定位。 */
    fun heldFromPositionMs(plan: AutoMixPlan): Long =
        fromPosition?.takeIf { it.first === plan }?.second ?: plan.from.durationMs

    /** 最近一次已经释放的过渡，供歌词交接沿“下一首”方向入场。 */
    fun releasedPlan(): AutoMixPlan? = released

    private fun release(plan: AutoMixPlan): AutoMixPlan? {
        released = plan
        return null
    }

    internal fun resetForTest() {
        released = null
        entry = null
        lastPosition = null
        fromPosition = null
    }
}

/** 与歌词切句的预滚量一致，让下一首的窗口在第一句开唱前入场完毕。 */
internal const val AUTOMIX_LYRIC_HANDOFF_LEAD_MS = LYRIC_SEEK_PREROLL_MS
private const val AUTOMIX_LYRIC_SEEK_BACK_MS = 1_500L

/** 切入点之后的第一句有字歌词；切入点之前的部分不会被听到。 */
internal fun autoMixFirstSungLineMs(lyrics: List<TimedLyric>, entryMs: Long): Long? =
    lyrics.firstOrNull { it.text.isNotBlank() && it.timeMs >= entryMs }?.timeMs

/** 根据当前播放进度，判断此刻是否还需要先留着上一首的歌词。 */
internal fun MusicPlaybackRuntime.autoMixHeldLyricPlan(nowMs: Long = android.os.SystemClock.elapsedRealtime()): AutoMixPlan? {
    if (AutoMixSession.active.value == null && AutoMixSession.lastEnd.value == null) return null
    val playback = state.value
    val track = playback.currentTrack
    val progress = playbackProgress.value
    val activity = playbackActivity.value
    val position = progress.takeIf { track != null && it.trackId == track.id }?.let {
        continuousLyricPosition(it.positionMs, it.sampledAtMs, nowMs,
            activity.advancing && activity.trackId == track?.id, seeking = false, track?.durationMs ?: 0L)
    }
    return AutoMixLyricHold.heldPlan(track, playback.lyricLoadState, position,
        seeking = seekPreview.position.value != null)
}
