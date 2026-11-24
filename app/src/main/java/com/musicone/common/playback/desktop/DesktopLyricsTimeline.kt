package com.musicone

internal enum class DesktopLyricKind { TITLE, LINE, EMPTY }

/** 切句动画的身份：同一首歌的同一句不重复入场。 */
internal data class DesktopLyricKey(val trackId: String, val kind: DesktopLyricKind, val index: Int = -1)

internal data class DesktopLyricContent(
    val key: DesktopLyricKey,
    val text: String,
    val line: TimedLyric? = null,
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    // 长句横向滚动的结束时间，不把句后的长间奏算进去。
    val scrollEndMs: Long = endMs,
)

internal const val DESKTOP_NO_LYRICS = "暂无歌词"
private const val DESKTOP_LAST_LINE_MS = 6_000L
private const val DESKTOP_CLOCK_JITTER_MS = 400L
private const val DESKTOP_UNIT_MS = 350L
private const val DESKTOP_MIN_SCROLL_MS = 3_000L
private const val DESKTOP_TITLE_HOLD_MS = 2_000L
private const val DESKTOP_TITLE_DP_PER_SECOND = 30f

/**
 * 把播放快照换成当前该显示的内容。前奏和加载中显示歌名；空白间奏沿用上一句；
 * 确认没有歌词、失败或加载超过约 3 秒后才落到“暂无歌词”，同一首歌重试期间不再闪回歌名。
 */
internal class DesktopLyricResolver(private val loadingTimeoutMs: Long = PLAYBACK_LYRICS_TIMEOUT_MS) {
    private var trackId: String? = null
    private var loadingSince = Long.MIN_VALUE
    private var settledEmpty = false
    private var checkedLines: List<SystemLyricLine>? = null
    private var hasText = false
    private var unitMs = DESKTOP_UNIT_MS
    private var titleStart = Long.MIN_VALUE

    /** 加载提示的落定时刻；没有在等待时为空。 */
    var loadingDeadlineMs: Long? = null
        private set

    fun resolve(snapshot: SystemPlaybackPresentation, positionMs: Long, nowMs: Long): DesktopLyricContent {
        val id = snapshot.key.trackId
        if (id != trackId) {
            trackId = id
            loadingSince = Long.MIN_VALUE
            settledEmpty = false
            titleStart = Long.MIN_VALUE
        }
        if (checkedLines !== snapshot.lyrics) {
            checkedLines = snapshot.lyrics
            hasText = snapshot.lyrics.any { it.text.isNotBlank() }
            unitMs = desktopLyricUnitMs(snapshot.lyrics)
        }
        loadingDeadlineMs = null
        // 新曲尚未就绪时位置还不属于它，只显示歌名。
        if (!snapshot.readyForPresentation) return title(snapshot, positionMs)
        if (hasText) {
            loadingSince = Long.MIN_VALUE
            settledEmpty = false
            return lineAt(snapshot, positionMs) ?: title(snapshot, positionMs)
        }
        if (snapshot.lyricState != LyricLoadState.LOADING) settledEmpty = true
        else if (!settledEmpty) {
            if (loadingSince == Long.MIN_VALUE) loadingSince = nowMs
            if (nowMs - loadingSince >= loadingTimeoutMs) settledEmpty = true
            else loadingDeadlineMs = loadingSince + loadingTimeoutMs
        }
        return if (settledEmpty) DesktopLyricContent(DesktopLyricKey(id, DesktopLyricKind.EMPTY), DESKTOP_NO_LYRICS)
        else title(snapshot, positionMs)
    }

    /** 歌名的滚动从它开始显示时算起，回拖到更早的位置时重新开始。 */
    private fun title(snapshot: SystemPlaybackPresentation, positionMs: Long): DesktopLyricContent {
        if (titleStart == Long.MIN_VALUE || positionMs < titleStart) titleStart = positionMs
        return DesktopLyricContent(
            DesktopLyricKey(snapshot.key.trackId, DesktopLyricKind.TITLE),
            listOf(snapshot.title, snapshot.artist).filter { it.isNotBlank() }.joinToString(" - "),
            startMs = titleStart, endMs = titleStart,
        )
    }

    private fun lineAt(snapshot: SystemPlaybackPresentation, positionMs: Long): DesktopLyricContent? {
        val lines = snapshot.lyrics
        var index = desktopLineIndex(lines, positionMs)
        while (index >= 0 && lines[index].text.isBlank()) index--
        if (index < 0) return null
        val line = lines[index]
        val source = line.source
        val sungEnd = source?.words?.lastOrNull()?.let { line.timeMs + it.endMs - source.timeMs }
        val end = lines.getOrNull(index + 1)?.timeMs ?: sungEnd ?: (line.timeMs + DESKTOP_LAST_LINE_MS)
        val scrollEnd = sungEnd ?: (line.timeMs + (desktopLyricUnits(line.text) * unitMs).coerceAtLeast(DESKTOP_MIN_SCROLL_MS))
        return DesktopLyricContent(DesktopLyricKey(snapshot.key.trackId, DesktopLyricKind.LINE, index),
            line.text, source, line.timeMs, end, scrollEnd.coerceAtMost(end).coerceAtLeast(line.timeMs + 1L))
    }
}

internal fun desktopLineIndex(lines: List<SystemLyricLine>, positionMs: Long): Int {
    var low = 0
    var high = lines.size
    while (low < high) {
        val middle = (low + high) ushr 1
        if (lines[middle].timeMs <= positionMs) low = middle + 1 else high = middle
    }
    return low - 1
}

/**
 * 按帧补间的播放位置。同一播放代次内的小幅回退视为采样抖动，高亮不倒退；
 * 往回拖动、切歌和重新播放照常跳转，暂停时停在采样点。
 */
internal class DesktopLyricClock {
    private var run: SystemPlaybackKey? = null
    private var basePosition = 0L
    private var baseTime = 0L
    private var advancing = false

    fun rebase(key: SystemPlaybackKey, positionMs: Long, advancing: Boolean, nowMs: Long) {
        val predicted = position(nowMs)
        val jitter = run == key && advancing && this.advancing &&
            positionMs < predicted && predicted - positionMs < DESKTOP_CLOCK_JITTER_MS
        run = key
        basePosition = if (jitter) predicted else positionMs
        baseTime = nowMs
        this.advancing = advancing
    }

    fun freeze(nowMs: Long) {
        basePosition = position(nowMs)
        baseTime = nowMs
        advancing = false
    }

    fun position(nowMs: Long): Long = basePosition + if (advancing) (nowMs - baseTime).coerceAtLeast(0L) else 0L
}

/**
 * 超长单句随进度横向滚动的距离。逐字歌词让已唱边缘停在可视宽度约 60% 处；
 * 整行歌词在本句 15%～85% 时间内匀速从头滚到尾。不超长时为 0。
 */
internal fun desktopLyricScroll(textWidth: Float, viewport: Float, edge: Float?, startMs: Long, endMs: Long,
    positionMs: Long): Float {
    val overflow = textWidth - viewport
    if (viewport <= 0f || overflow <= 0f) return 0f
    val target = if (edge != null) edge - viewport * .6f else {
        val span = (endMs - startMs).coerceAtLeast(1L)
        (((positionMs - startMs).toFloat() / span - .15f) / .7f).coerceIn(0f, 1f) * overflow
    }
    return target.coerceIn(0f, overflow)
}

/** 中日韩按字、其他语言按单词计数；标点和空白不算，撇号留在单词内。 */
internal fun desktopLyricUnits(text: String): Int {
    var count = 0
    var inWord = false
    for (char in text) {
        if (!char.isLetterOrDigit()) {
            if (char != '\'' && char != '’') inWord = false
        } else if (char.code in 0x2E80..0xFFEF) {
            count++
            inWord = false
        } else if (!inWord) {
            count++
            inWord = true
        }
    }
    return count
}

/** 本曲一个字的常见时长，取各句中位数并留余量，慢歌不会提前滚完；句后间奏只影响少数句，不会带偏。 */
internal fun desktopLyricUnitMs(lines: List<SystemLyricLine>): Long {
    val values = ArrayList<Long>()
    for (index in 0 until lines.size - 1) {
        val units = desktopLyricUnits(lines[index].text)
        val duration = lines[index + 1].timeMs - lines[index].timeMs
        if (units > 0 && duration > 0L) values += duration / units
    }
    if (values.size < 4) return DESKTOP_UNIT_MS
    values.sort()
    return (values[values.size / 2] * 5 / 4).coerceAtLeast(DESKTOP_UNIT_MS)
}

/** 过长歌名来回滚动的位置；stillMs 为两端停留的剩余时间，移动中为 0。 */
internal data class DesktopTitleScroll(val offset: Float, val stillMs: Long)

internal fun desktopTitleScroll(overflow: Float, elapsedMs: Long, density: Float): DesktopTitleScroll {
    val travel = (overflow / (DESKTOP_TITLE_DP_PER_SECOND * density) * 1_000f).toLong().coerceAtLeast(1L)
    val half = DESKTOP_TITLE_HOLD_MS + travel
    val time = elapsedMs.coerceAtLeast(0L) % (half * 2)
    val back = time >= half
    val local = if (back) time - half else time
    if (local < DESKTOP_TITLE_HOLD_MS) return DesktopTitleScroll(if (back) overflow else 0f, DESKTOP_TITLE_HOLD_MS - local)
    val moved = (local - DESKTOP_TITLE_HOLD_MS).toFloat() / travel * overflow
    return DesktopTitleScroll(if (back) overflow - moved else moved, 0L)
}

internal fun desktopWordProgress(word: LyricWord, positionMs: Long): Float = when {
    positionMs >= word.endMs -> 1f
    positionMs <= word.startMs -> 0f
    else -> (positionMs - word.startMs).toFloat() / (word.endMs - word.startMs).coerceAtLeast(1L)
}

/** 已唱边缘的横向位置；标点等没有时间的片段随前一个词一起越过。 */
internal fun desktopSungEdge(ranges: List<LyricHighlightRange>, positionMs: Long, x: (Int) -> Float): Float {
    var edge = 0f
    for (range in ranges) {
        val word = range.word ?: continue
        val progress = desktopWordProgress(word, positionMs)
        if (progress <= 0f) return edge
        val start = x(range.start)
        edge = start + (x(range.end) - start) * progress
        if (progress < 1f) return edge
    }
    return if (ranges.isEmpty()) 0f else x(ranges.last().end)
}

internal enum class DesktopLyricTransition { FADE, FORWARD, BACKWARD }

/** 往回一句反向移动；跨越多句的跳转和降低动效只做淡变。 */
internal fun desktopLyricTransition(from: DesktopLyricKey?, to: DesktopLyricKey, reduceMotion: Boolean): DesktopLyricTransition {
    if (from == null || reduceMotion) return DesktopLyricTransition.FADE
    if (from.trackId != to.trackId || from.kind != DesktopLyricKind.LINE || to.kind != DesktopLyricKind.LINE)
        return DesktopLyricTransition.FORWARD
    return when (to.index - from.index) {
        in 1..2 -> DesktopLyricTransition.FORWARD
        in -2..-1 -> DesktopLyricTransition.BACKWARD
        else -> DesktopLyricTransition.FADE
    }
}

/** 极短句按句长缩短切换时间，避免还没进完就该离开。 */
internal fun desktopTransitionScale(content: DesktopLyricContent): Float =
    if (content.key.kind != DesktopLyricKind.LINE) 1f
    else ((content.endMs - content.startMs) / 600f).coerceIn(.35f, 1f)

/** 比例位置换算为坐标，始终落在可用区域内。 */
internal fun desktopLyricsOrigin(fraction: Float, safeStart: Int, safeEnd: Int, size: Int): Int {
    val room = (safeEnd - safeStart - size).coerceAtLeast(0)
    return safeStart + (room * fraction.coerceIn(0f, 1f)).toInt()
}

/** 该方向没有移动余地时保留原比例，竖屏拖动不会抹掉横屏的位置。 */
internal fun desktopLyricsFraction(origin: Int, safeStart: Int, safeEnd: Int, size: Int, fallback: Float): Float {
    val room = safeEnd - safeStart - size
    return if (room <= 0) fallback else ((origin - safeStart).toFloat() / room).coerceIn(0f, 1f)
}

internal const val DESKTOP_MIN_WIDTH_DP = 160f

internal fun desktopLyricsWide(availableDp: Float): Boolean = availableDp >= 600f

/** 默认宽度给手机留出两侧边距，平板和横屏限制行宽；用户调过的宽度只受可用宽度约束。 */
internal fun desktopLyricsWidthDp(availableDp: Float, savedDp: Float = 0f): Float {
    val wanted = if (savedDp > 0f) savedDp else minOf(availableDp - 24f, if (desktopLyricsWide(availableDp)) 520f else 480f)
    return wanted.coerceAtMost(availableDp).coerceAtLeast(minOf(DESKTOP_MIN_WIDTH_DP, availableDp))
}

internal enum class DesktopLyricsGesture { MOVE, RESIZE_LEFT, RESIZE_RIGHT }

/** 两侧各三成区域内横向起手是调整该侧宽度；中间起手或竖向起手都是移动位置。 */
internal fun desktopLyricsGesture(startX: Float, width: Float, dx: Float, dy: Float): DesktopLyricsGesture = when {
    kotlin.math.abs(dx) <= kotlin.math.abs(dy) -> DesktopLyricsGesture.MOVE
    startX < width * .3f -> DesktopLyricsGesture.RESIZE_LEFT
    startX > width * .7f -> DesktopLyricsGesture.RESIZE_RIGHT
    else -> DesktopLyricsGesture.MOVE
}

/** 调整一侧时另一侧不动；返回新的左边与右边。 */
internal fun desktopLyricsResize(gesture: DesktopLyricsGesture, left: Float, right: Float, dx: Float,
    safeLeft: Float, safeRight: Float, minWidth: Float): Pair<Float, Float> = when (gesture) {
    DesktopLyricsGesture.RESIZE_LEFT -> (left + dx).coerceAtMost(right - minWidth).coerceAtLeast(safeLeft) to right
    DesktopLyricsGesture.RESIZE_RIGHT -> left to (right + dx).coerceAtLeast(left + minWidth).coerceAtMost(safeRight)
    DesktopLyricsGesture.MOVE -> left to right
}
