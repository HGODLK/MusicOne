package des.c5inco.torph.core

import java.util.Locale

/**
 * Locale-aware text boundary provider. The Compose module supplies an ICU-backed
 * implementation; tests can use [SimpleSegmenter].
 */
public interface Segmenter {
    /** Grapheme cluster ranges covering [text] in order, each `start until end`. */
    public fun graphemes(text: String, locale: Locale): List<IntRange>

    /** Word ranges covering [text] in order. Whitespace and punctuation are returned as their own ranges. */
    public fun words(text: String, locale: Locale): List<IntRange>
}

/**
 * Dependency-free [Segmenter]: graphemes are code points plus trailing combining marks / variation
 * selectors / ZWJ sequences; words are runs of letters+digits, runs of whitespace, or single other chars.
 * Adequate for tests and for Latin text; use the ICU segmenter in production.
 */
public object SimpleSegmenter : Segmenter {
    override fun graphemes(text: String, locale: Locale): List<IntRange> {
        val out = ArrayList<IntRange>(text.length)
        var i = 0
        while (i < text.length) {
            val start = i
            var cp = text.codePointAt(i)
            i += Character.charCount(cp)
            // Absorb extenders: combining marks, variation selectors, ZWJ + next, emoji modifiers.
            while (i < text.length) {
                cp = text.codePointAt(i)
                val type = Character.getType(cp)
                val extend = type == Character.NON_SPACING_MARK.toInt() ||
                    type == Character.ENCLOSING_MARK.toInt() ||
                    type == Character.COMBINING_SPACING_MARK.toInt() ||
                    cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF || cp == 0x200D
                if (!extend) break
                i += Character.charCount(cp)
                if (cp == 0x200D && i < text.length) {
                    i += Character.charCount(text.codePointAt(i))
                }
            }
            out.add(start until i)
        }
        return out
    }

    override fun words(text: String, locale: Locale): List<IntRange> {
        val out = ArrayList<IntRange>()
        var i = 0
        while (i < text.length) {
            val start = i
            val cp = text.codePointAt(i)
            val cls = classify(cp)
            i += Character.charCount(cp)
            if (cls != 2) {
                while (i < text.length) {
                    val next = text.codePointAt(i)
                    if (classify(next) != cls) break
                    i += Character.charCount(next)
                }
            }
            out.add(start until i)
        }
        return out
    }

    private fun classify(cp: Int): Int = when {
        Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.NON_SPACING_MARK.toInt() ||
            cp == '\''.code || cp == 0x2019 -> 0
        Character.isWhitespace(cp) -> 1
        else -> 2
    }
}
