package des.c5inco.torph.core

import java.util.Locale

/** Options for [segmentText]. */
public data class SegmentOptions(
    val segmentation: Segmentation = Segmentation.AUTO,
    /** Detect numeric words and tag digits with place values. */
    val numbers: Boolean = true,
    /** Caret position in the string; the numeric word containing it is matched positionally, not by place. */
    val cursorIndex: Int? = null,
    /** Above this many segments the text is re-segmented by word regardless of [segmentation]. */
    val maxSegments: Int = 300,
)

/** Scripts whose glyphs depend on their neighbours; morphing individual graphemes would break shaping. */
private val complexScripts: Set<Character.UnicodeScript> = setOf(
    Character.UnicodeScript.ARABIC, Character.UnicodeScript.SYRIAC, Character.UnicodeScript.NKO,
    Character.UnicodeScript.DEVANAGARI, Character.UnicodeScript.BENGALI, Character.UnicodeScript.GURMUKHI,
    Character.UnicodeScript.GUJARATI, Character.UnicodeScript.ORIYA, Character.UnicodeScript.TAMIL,
    Character.UnicodeScript.TELUGU, Character.UnicodeScript.KANNADA, Character.UnicodeScript.MALAYALAM,
    Character.UnicodeScript.SINHALA, Character.UnicodeScript.THAI, Character.UnicodeScript.LAO,
    Character.UnicodeScript.KHMER, Character.UnicodeScript.MYANMAR, Character.UnicodeScript.TIBETAN,
    Character.UnicodeScript.MONGOLIAN, Character.UnicodeScript.HEBREW,
)

/** True when [text] contains a letter whose script needs contextual shaping. */
public fun needsWordSegmentation(text: String): Boolean {
    var i = 0
    while (i < text.length) {
        val cp = text.codePointAt(i)
        if (Character.isLetter(cp)) {
            return try { Character.UnicodeScript.of(cp) in complexScripts } catch (_: IllegalArgumentException) { false }
        }
        i += Character.charCount(cp)
    }
    return false
}

/**
 * Splits [text] into [Segment]s with ids `firstId, firstId+1, ...`.
 *
 * Numeric words (when [SegmentOptions.numbers]) become one [SegmentKind.DIGIT] per digit and one
 * [SegmentKind.NUMBER_SYMBOL] per attached symbol, all sharing a `group`. Line breaks become
 * [SegmentKind.NEWLINE]. Everything else is split per [SegmentOptions.segmentation].
 */
public fun segmentText(
    text: String,
    locale: Locale = Locale.getDefault(),
    segmenter: Segmenter = SimpleSegmenter,
    options: SegmentOptions = SegmentOptions(),
    firstId: Long = 0L,
): List<Segment> {
    // AUTO follows torph: anything with a space or line break goes word by word.
    if (options.segmentation == Segmentation.WORD ||
        (options.segmentation == Segmentation.AUTO && (text.indexOf(' ') >= 0 || text.indexOf('\n') >= 0))
    ) {
        return segmentInternal(text, locale, segmenter, options.copy(segmentation = Segmentation.WORD), firstId)
    }
    // Stops as soon as it passes maxSegments, so long text doesn't pay for a full pass it throws away.
    val result = segmentInternal(text, locale, segmenter, options, firstId, limit = options.maxSegments)
    if (result.size > options.maxSegments) {
        return segmentInternal(text, locale, segmenter, options.copy(segmentation = Segmentation.WORD), firstId)
    }
    return result
}

private fun segmentInternal(
    text: String,
    locale: Locale,
    segmenter: Segmenter,
    options: SegmentOptions,
    firstId: Long,
    /** Return early (with more than [limit] segments) once the result is known to exceed it. */
    limit: Int = Int.MAX_VALUE,
): List<Segment> {
    val out = ArrayList<Segment>(text.length)
    var id = firstId
    val symbols = NumberSymbols.forLocale(locale)
    val numeric = if (options.numbers) findNumericWords(text, symbols) else emptyList()
    val cursor = options.cursorIndex

    var pos = 0
    var group = 0
    fun emitPlain(start: Int, end: Int) {
        if (end <= start) return
        var i = start
        while (i < end && out.size <= limit) {
            val nl = text.indexOf('\n', i).let { if (it < 0 || it >= end) end else it }
            if (nl > i) {
                for (r in splitPlain(text, i, nl, locale, segmenter, options.segmentation, limit - out.size)) {
                    out.add(Segment(id++, text.substring(r.first, r.last + 1), r.first, SegmentKind.TEXT))
                }
            }
            if (nl < end) {
                out.add(Segment(id++, "\n", nl, SegmentKind.NEWLINE))
                i = nl + 1
            } else {
                i = end
            }
        }
    }

    for (word in numeric) {
        emitPlain(pos, word.start)
        if (out.size > limit) return out
        // The numeric word holding the caret is matched positionally: emit as plain graphemes.
        val holdsCursor = cursor != null && cursor >= word.start && cursor <= word.end
        if (holdsCursor) {
            for (r in segmenter.graphemes(word.text, locale)) {
                out.add(Segment(id++, word.text.substring(r.first, r.last + 1), word.start + r.first, SegmentKind.TEXT, group = null))
            }
        } else {
            var k = 0
            while (k < word.text.length) {
                val ch = word.text[k]
                val len = if (Character.isHighSurrogate(ch) && k + 1 < word.text.length) 2 else 1
                val piece = word.text.substring(k, k + len)
                val kind = if (Character.isDigit(ch)) SegmentKind.DIGIT else SegmentKind.NUMBER_SYMBOL
                out.add(Segment(id++, piece, word.start + k, kind, place = word.places[k], group = group))
                k += len
            }
        }
        group++
        pos = word.end
    }
    emitPlain(pos, text.length)
    return out
}

private fun splitPlain(
    text: String,
    start: Int,
    end: Int,
    locale: Locale,
    segmenter: Segmenter,
    mode: Segmentation,
    /** AUTO stops once it has more ranges than this; the caller then discards the result. */
    limit: Int,
): List<IntRange> {
    val sub = text.substring(start, end)
    fun shift(ranges: List<IntRange>, base: Int) = ranges.map { (it.first + base)..(it.last + base) }
    return when (mode) {
        Segmentation.GRAPHEME -> shift(segmenter.graphemes(sub, locale), start)
        Segmentation.WORD -> shift(segmenter.words(sub, locale), start)
        Segmentation.AUTO -> {
            val out = ArrayList<IntRange>()
            for (w in segmenter.words(sub, locale)) {
                if (out.size > limit) break
                val wordText = sub.substring(w.first, w.last + 1)
                if (needsWordSegmentation(wordText)) {
                    out.add((w.first + start)..(w.last + start))
                } else {
                    out.addAll(shift(segmenter.graphemes(wordText, locale), start + w.first))
                }
            }
            out
        }
    }
}
