package des.c5inco.torph.core

/** What a [Segment] represents. Drives matching in [diffSegments] and rendering. */
public enum class SegmentKind {
    /** One grapheme cluster or one word of ordinary text. */
    TEXT,
    /** A single digit inside a numeric word. Carries a [Segment.place]. */
    DIGIT,
    /** A symbol attached to a numeric word: currency, grouping/decimal separator, sign, percent. */
    NUMBER_SYMBOL,
    /** A line break. Never drawn; participates in matching so line structure persists. */
    NEWLINE,
}

/**
 * One unit of morphing text.
 *
 * @property id Stable identity. A segment that persists across a diff keeps the id it had before.
 * @property text The characters this segment covers.
 * @property index Char offset of [text] in the source string.
 * @property kind See [SegmentKind].
 * @property place Place value for [SegmentKind.DIGIT] and [SegmentKind.NUMBER_SYMBOL]:
 *   0 = ones, 1 = tens, ..., -1 = tenths. `null` for other kinds.
 * @property group Ordinal of the numeric word this segment belongs to (0-based, left to right),
 *   or `null` when the segment is not part of a numeric word.
 * @property roll Vertical roll direction assigned by the diff for digits that changed:
 *   `+1` when the number grew, `-1` when it shrank, `0` for no roll.
 */
public data class Segment(
    val id: Long,
    val text: String,
    val index: Int,
    val kind: SegmentKind = SegmentKind.TEXT,
    val place: Int? = null,
    val group: Int? = null,
    val roll: Int = 0,
) {
    /** Char range covered by this segment in its source string. */
    public val range: IntRange get() = index until index + text.length

    /** Exclusive end offset in the source string. */
    public val end: Int get() = index + text.length
}

/** How ordinary (non-numeric) text is split into segments. */
public enum class Segmentation {
    /** One segment per grapheme cluster. */
    GRAPHEME,
    /** One segment per word (spaces and punctuation become their own segments). */
    WORD,
    /**
     * torph's rule: text with a space or line break morphs word by word, and a single word per
     * grapheme. Unlike torph, a single word in a script that depends on contextual shaping (Arabic,
     * Indic scripts, Thai, ...) stays whole, since splitting it would break its shaping.
     */
    AUTO,
}
