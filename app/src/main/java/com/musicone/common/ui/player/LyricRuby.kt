package com.musicone

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.sp
import kotlin.math.ceil

/** 字组进度由所含词按时长加权；rangeIndex 为 -1 时该词不参与明暗，视为已亮。 */
internal data class LyricRubyWordWeight(val rangeIndex: Int, val weight: Float)

internal data class LyricRubyGroup(
    val textStart: Int,
    val textEnd: Int,
    val moras: List<String>,
    val words: List<LyricRubyWordWeight>,
) {
    inline fun progress(read: (Int) -> Float): Float {
        var value = 0f
        for (word in words) value += word.weight * (if (word.rangeIndex >= 0) read(word.rangeIndex) else 1f)
        return value
    }
}


/**
 * 解析 transliteration 注音，支持两种格式：
 * 1. 按原文位置标注 {原文|读音}，不依赖逐字，见 LyricRubyAnchors.kt；
 * 2. 音源包的逐词格式：制表符分隔且段数等于 words。空段无注音，"+" 与前词合标，
 *    其他段为空格分隔的音，可带 "@相对字组起点偏移+时长"。
 * 格式不符时整句不显示注音，不让错位内容上屏。
 */
internal fun lyricRubyGroups(line: TimedLyric, ranges: List<LyricHighlightRange>): List<LyricRubyGroup>? {
    lyricRubyAnchors(line.text, line.transliteration)?.let { return lyricAnchoredRubyGroups(line, it, ranges) }
    val segments = lyricRubySegments(line) ?: return null
    val positions = lyricWordRanges(line)
    val groups = ArrayList<LyricRubyGroup>()
    var index = 0
    while (index < segments.size) {
        val segment = segments[index]
        if (segment == "+") return null
        if (segment.isEmpty()) { index++; continue }
        var last = index
        while (segments.getOrNull(last + 1) == "+") last++
        val words = line.words.subList(index, last + 1)
        lyricRubyGroup(line.text, segment, words, positions, ranges)?.let(groups::add) ?: return null
        index = last + 1
    }
    return groups.takeIf { it.isNotEmpty() }
}

internal fun lyricRubySegments(line: TimedLyric): List<String>? {
    val raw = line.transliteration?.takeIf { it.isNotBlank() && line.words.isNotEmpty() } ?: return null
    return raw.split('\t').takeIf { it.size == line.words.size }
}

private fun lyricRubyGroup(text: String, segment: String, words: List<LyricWord>,
    positions: List<LyricWordRange>, ranges: List<LyricHighlightRange>): LyricRubyGroup? {
    val first = positions.firstOrNull { it.word === words.first() } ?: return null
    val last = positions.firstOrNull { it.word === words.last() } ?: return null
    var start = first.start
    var end = last.end
    while (start < end && text[start].isWhitespace()) start++
    while (end > start && text[end - 1].isWhitespace()) end--
    if (start >= end) return null
    val moras = lyricRubyMoras(segment) ?: return null
    val total = words.sumOf { (it.endMs - it.startMs).coerceAtLeast(1L) }.toFloat()
    return LyricRubyGroup(start, end, moras, words.map { word ->
        LyricRubyWordWeight(ranges.indexOfFirst { it.word === word },
            (word.endMs - word.startMs).coerceAtLeast(1L) / total)
    })
}

/** 位置标注的字组按重叠的词加权点亮；没有逐字时整组随整行一起亮。 */
private fun lyricAnchoredRubyGroups(line: TimedLyric, anchors: List<LyricRubyAnchor>,
    ranges: List<LyricHighlightRange>): List<LyricRubyGroup>? {
    val positions = lyricWordRanges(line)
    return anchors.map { anchor ->
        var start = anchor.start
        var end = anchor.end
        while (start < end && line.text[start].isWhitespace()) start++
        while (end > start && line.text[end - 1].isWhitespace()) end--
        if (start >= end) return null
        val moras = lyricRubyMoras(anchor.reading) ?: return null
        val words = positions.filter { it.start < end && it.end > start }.map { it.word }
        val total = words.sumOf { (it.endMs - it.startMs).coerceAtLeast(1L) }.toFloat()
        LyricRubyGroup(start, end, moras, if (words.isEmpty()) listOf(LyricRubyWordWeight(-1, 1f)) else words.map { word ->
            LyricRubyWordWeight(ranges.indexOfFirst { it.word === word }, (word.endMs - word.startMs).coerceAtLeast(1L) / total)
        })
    }.takeIf { it.isNotEmpty() }
}

/** 两种注音格式任一有效即视为带注音，列表行高按此预留。 */
internal fun lyricHasRuby(line: TimedLyric): Boolean =
    lyricRubySegments(line) != null || lyricRubyAnchors(line.text, line.transliteration) != null

/** 逐音时间可以写但不参与点亮：整组一道光跟着词的进度走，这里只取读音。 */
private fun lyricRubyMoras(segment: String): List<String>? {
    val moras = segment.split(' ').filter { it.isNotEmpty() }.map { it.substringBefore('@') }
    return moras.takeIf { it.isNotEmpty() && it.none(String::isEmpty) }
}

/** 注音占用的额外行高；保留区都放在正文上方，正文之间的距离不变。 */
internal val LyricTypography.rubyLineSp: Float get() = translationSp * 1.2f

internal fun lyricRubyTextStyle(base: TextStyle, typography: LyricTypography): TextStyle = base.copy(
    lineHeight = (typography.activeLineSp + typography.rubyLineSp).sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
)

/** 列表项类型同时区分翻译和注音，缓存高度估算才不会把带注音的短句当成折行。 */
internal enum class LyricRowContent(val translated: Boolean, val ruby: Boolean) {
    PLAIN(false, false), TRANSLATED(true, false), RUBY(false, true), RUBY_TRANSLATED(true, true);
}

internal fun lyricRowContent(line: TimedLyric): LyricRowContent {
    val ruby = lyricHasRuby(line)
    return when {
        ruby && line.translation != null -> LyricRowContent.RUBY_TRANSLATED
        ruby -> LyricRowContent.RUBY
        line.translation != null -> LyricRowContent.TRANSLATED
        else -> LyricRowContent.PLAIN
    }
}

/** 一句短歌词的基准高度（不含折行），供转场和行程测量判断这一行是否折行。 */
internal fun lyricShortRowLineSp(typography: LyricTypography, content: Any?): Float =
    typography.activeLineSp + if ((content as? LyricRowContent)?.ruby == true) typography.rubyLineSp else 0f

internal fun lyricRowTranslated(content: Any?): Boolean = (content as? LyricRowContent)?.translated == true

/** 音的位图范围（相对排版原点）；四周留出边距，j 的钩部等外伸笔画不会被切掉。 */
internal fun lyricRubyInkBounds(width: Int, height: Int, fontPx: Float): IntRect {
    val padding = ceil(fontPx * .2f).toInt().coerceAtLeast(1)
    return IntRect(-padding, -padding, width + padding, height + padding)
}

internal data class LyricRubySpan(val anchorLeft: Float, val anchorRight: Float, val width: Float)
internal data class LyricRubyPlacement(val left: Float, val scale: Float)

/**
 * 同一视觉行内的注音定位：优先居中于字组；放不下时伸向相邻字上方；
 * 伸出太多才缩小这一组（不低于 0.75），绝不撑开正文。
 */
internal fun placeLyricRubyLine(spans: List<LyricRubySpan>, maxRight: Float, overhang: Float, gap: Float): List<LyricRubyPlacement> {
    val scales = FloatArray(spans.size) { index ->
        val span = spans[index]
        val allowed = span.anchorRight - span.anchorLeft + overhang * 2f
        if (span.width <= allowed) 1f else (allowed / span.width).coerceAtLeast(.75f)
    }
    val widths = FloatArray(spans.size) { spans[it].width * scales[it] }
    val lefts = FloatArray(spans.size) { (spans[it].anchorLeft + spans[it].anchorRight - widths[it]) / 2f }
    for (index in lefts.indices) {
        val floor = if (index == 0) 0f else lefts[index - 1] + widths[index - 1] + gap
        lefts[index] = maxOf(lefts[index], floor)
    }
    for (index in lefts.indices.reversed()) {
        val ceiling = if (index == lefts.lastIndex) maxRight else lefts[index + 1] - gap
        lefts[index] = minOf(lefts[index], ceiling - widths[index])
    }
    if (lefts.isNotEmpty() && lefts[0] < 0f) lefts[0] = 0f
    return spans.indices.map { LyricRubyPlacement(lefts[it], scales[it]) }
}
