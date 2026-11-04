package des.c5inco.torph.core

/** A run of digits and attached symbols found in a string, e.g. `$1,234.50` or `-12%`. */
public data class NumericWord(
    val start: Int,
    val end: Int,
    val text: String,
    /** Place value per char offset within [text]; symbols inherit a place from a neighbouring digit. */
    val places: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        other is NumericWord && other.start == start && other.end == end && other.text == text

    override fun hashCode(): Int = 31 * start + text.hashCode()
}

/**
 * Finds numeric words in [text].
 *
 * A numeric word is a maximal run containing at least one digit made of:
 * digits (any script), grouping/decimal separators when between two digits, a leading sign,
 * currency symbols directly before or after the digits, and a trailing `%`/`‰`.
 */
public fun findNumericWords(text: String, symbols: NumberSymbols): List<NumericWord> {
    val out = ArrayList<NumericWord>()
    val n = text.length
    var i = 0
    var lastEnd = 0
    while (i < n) {
        if (!Character.isDigit(text[i])) { i++; continue }
        var start = i
        var end = i
        // Extend right over digits and inter-digit separators.
        while (end < n) {
            val ch = text[end]
            when {
                Character.isDigit(ch) -> end++
                isSeparator(ch, symbols) && end + 1 < n && Character.isDigit(text[end + 1]) && end > start -> end++
                else -> break
            }
        }
        // Suffixes: currency, percent, one trailing currency after an optional (non-breaking) space.
        var s = end
        if (s < n && isSpace(text[s]) && s + 1 < n && isCurrency(text[s + 1])) s++
        while (s < n && (isCurrency(text[s]) || text[s] == '%' || text[s] == '\u2030')) s++
        end = s
        // Prefixes: currency, then optional sign, then optional currency again ("-$5", "$-5", "\u20ac 5").
        // Never reach back into the previous numeric word.
        var p = start
        if (p > lastEnd && isCurrency(text[p - 1])) p--
        else if (p - 1 > lastEnd && isSpace(text[p - 1]) && isCurrency(text[p - 2])) p -= 2
        if (p > lastEnd && isSign(text[p - 1], symbols)) p--
        if (p > lastEnd && isCurrency(text[p - 1])) p--
        start = p
        lastEnd = end
        val word = text.substring(start, end)
        out.add(NumericWord(start, end, word, assignPlaces(word, symbols)))
        i = end
    }
    return out
}

private fun isSeparator(ch: Char, s: NumberSymbols): Boolean =
    ch == s.decimalSeparator || ch == s.groupingSeparator || ch == '.' || ch == ',' ||
        ch == '\u00A0' || ch == '\u202F' || ch == '\'' || ch == '٫' || ch == '٬'

private fun isSpace(ch: Char): Boolean = ch == ' ' || ch == '\u00A0' || ch == '\u202F'

private fun isSign(ch: Char, s: NumberSymbols): Boolean =
    ch == s.minusSign || ch == '-' || ch == '−' || ch == '+'

private fun isCurrency(ch: Char): Boolean = Character.getType(ch) == Character.CURRENCY_SYMBOL.toInt()

/**
 * Place values for each char in a numeric word. The rightmost integer digit is place 0;
 * fraction digits are -1, -2, ...; a symbol takes the place of the nearest digit to its left,
 * or to its right when it precedes every digit.
 */
internal fun assignPlaces(word: String, symbols: NumberSymbols): IntArray {
    val places = IntArray(word.length)
    val digitIdx = word.indices.filter { Character.isDigit(word[it]) }
    if (digitIdx.isEmpty()) return places
    val decimal = decimalIndex(word, symbols)
    val intDigits = digitIdx.filter { decimal < 0 || it < decimal }
    val fracDigits = digitIdx.filter { decimal >= 0 && it > decimal }
    intDigits.forEachIndexed { k, idx -> places[idx] = intDigits.size - 1 - k }
    fracDigits.forEachIndexed { k, idx -> places[idx] = -(k + 1) }
    var last: Int? = null
    for (idx in word.indices) {
        if (Character.isDigit(word[idx])) { last = places[idx]; continue }
        places[idx] = last ?: places[digitIdx.first()]
    }
    return places
}

/**
 * Index of the decimal separator in [word], or -1. Only the locale's decimal char counts, and only
 * its last occurrence. When that char is '.' and appears more than once with exactly three digits
 * after the last one ("1.234.567"), it is read as grouping and there is no decimal.
 */
internal fun decimalIndex(word: String, symbols: NumberSymbols): Int {
    val loc = word.lastIndexOf(symbols.decimalSeparator)
    if (loc >= 0 && symbols.decimalSeparator != symbols.groupingSeparator) {
        // Only a decimal if it's not followed by exactly 3 digits then end/another separator (ambiguous "1.234").
        val trailing = word.substring(loc + 1).takeWhile { Character.isDigit(it) }
        val after = loc + 1 + trailing.length
        val ambiguous = trailing.length == 3 && (after == word.length || !Character.isDigit(word[after])) &&
            symbols.decimalSeparator == '.' && word.count { it == '.' } > 1
        if (!ambiguous) return loc
    }
    return -1
}
